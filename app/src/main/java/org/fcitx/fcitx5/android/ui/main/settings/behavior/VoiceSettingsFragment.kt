/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.voice.SenseVoiceModelManager
import org.fcitx.fcitx5.android.input.voice.VoiceInputAudioCapture

class VoiceSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().voice) {

    private var micPermissionPreference: Preference? = null

    private val micPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshMicPermissionSummary()
        }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = screen.context
        // 常驻说明：以下配置项仅在内置引擎被选为首选语音输入时可用
        screen.addPreference(Preference(ctx).apply {
            key = "voice_settings_hint"
            order = -2
            isIconSpaceReserved = false
            isSelectable = false
            setTitle(R.string.voice_settings_hint)
        })
        // 麦克风权限：显示授予状态；未授予时点击发起运行时申请，已授予时跳应用详情页
        micPermissionPreference = Preference(ctx).apply {
            key = "voice_mic_permission"
            order = -1
            isIconSpaceReserved = false
            setTitle(R.string.voice_mic_permission)
            setOnPreferenceClickListener {
                if (VoiceInputAudioCapture.hasPermission(ctx)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", ctx.packageName, null)
                        )
                    )
                } else {
                    micPermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
                }
                true
            }
        }.also { screen.addPreference(it) }
        refreshMicPermissionSummary()

        // SenseVoice 模型下载 Preference：summary 由 downloadState StateFlow 驱动
        // （collect 挂在 fragment lifecycleScope，随 fragment 销毁自动取消）。
        // Idle 时按磁盘上模型文件存在性区分"未下载/已就绪"——下载完成过一轮
        // 后 Ready 状态已在 flow 内；冷启动进入设置页时 flow 仍是 Idle。
        screen.findPreference<Preference>(SV_DOWNLOAD_PREF_KEY)?.let { pref ->
            lifecycleScope.launch {
                SenseVoiceModelManager.downloadState.collect { state ->
                    pref.summary = when (state) {
                        is SenseVoiceModelManager.DownloadState.Downloading ->
                            getString(R.string.voice_sv_model_downloading, state.progressPercent)
                        SenseVoiceModelManager.DownloadState.Verifying ->
                            getString(R.string.voice_sv_model_verifying)
                        SenseVoiceModelManager.DownloadState.Ready ->
                            getString(R.string.voice_sv_model_status_ready)
                        is SenseVoiceModelManager.DownloadState.Error ->
                            getString(R.string.voice_sv_model_error, state.message)
                        SenseVoiceModelManager.DownloadState.Idle ->
                            getString(
                                if (SenseVoiceModelManager.modelFilesExist(pref.context)) {
                                    R.string.voice_sv_model_status_ready
                                } else {
                                    R.string.voice_sv_model_status_missing
                                }
                            )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 权限申请弹窗/应用详情页返回后刷新状态
        refreshMicPermissionSummary()
    }

    private fun refreshMicPermissionSummary() {
        val ctx = context ?: return
        micPermissionPreference?.setSummary(
            if (VoiceInputAudioCapture.hasPermission(ctx)) {
                R.string.voice_mic_permission_granted
            } else {
                R.string.voice_mic_permission_denied
            }
        )
    }

    companion object {
        /** 与 AppPrefs.Voice 内 SenseVoiceModelDownload 的 key 对齐。 */
        private const val SV_DOWNLOAD_PREF_KEY = "voice_input_sv_download_model"
    }
}

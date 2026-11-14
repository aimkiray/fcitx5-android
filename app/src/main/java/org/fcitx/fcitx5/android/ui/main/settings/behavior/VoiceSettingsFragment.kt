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
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
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
            order = 0
            isIconSpaceReserved = false
            isSelectable = false
            setTitle(R.string.voice_settings_hint)
        })
        // 麦克风权限：显示授予状态；未授予时点击发起运行时申请，已授予时跳应用详情页
        micPermissionPreference = Preference(ctx).apply {
            key = "voice_mic_permission"
            order = 1
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
}

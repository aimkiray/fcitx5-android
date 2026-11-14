/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.os.Build
import android.view.View
import androidx.annotation.StringRes
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.voice.VoiceInputManager
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.ui.main.settings.DialogSeekBarPreference
import org.fcitx.fcitx5.android.ui.main.settings.EditTextIntPreference
import org.fcitx.fcitx5.android.ui.main.settings.TwinSeekBarPreference
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.includes

abstract class ManagedPreferenceUi<T : Preference>(
    val key: String,
    private val enableUiOn: (() -> Boolean)? = null
) {

    abstract fun createUi(context: Context): T

    fun isEnabled() = enableUiOn?.invoke() ?: true

    class Switch(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Boolean,
        @StringRes
        val summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<MySwitchPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = MySwitchPreference(context).apply {
            key = this@Switch.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setDefaultValue(defaultValue)
            if (this@Switch.summary != null)
                setSummary(this@Switch.summary)
            setTitle(this@Switch.title)
        }
    }

    class StringList<T : Any>(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: T,
        val codec: ManagedPreference.StringLikeCodec<T>,
        val entryValues: List<T>,
        @StringRes
        val entryLabels: List<Int>,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<ListPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = ListPreference(context).apply {
            key = this@StringList.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            entryValues = this@StringList.entryValues.map { codec.encode(it) }.toTypedArray()
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setDefaultValue(codec.encode(defaultValue))
            setTitle(this@StringList.title)
            entries = this@StringList.entryLabels.map { context.getString(it) }.toTypedArray()
            setDialogTitle(this@StringList.title)
        }
    }

    class VoiceInputList(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: String,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<ListPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = ListPreference(context).apply {
            key = this@VoiceInputList.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setDefaultValue(defaultValue)
            setTitle(this@VoiceInputList.title)
            setDialogTitle(this@VoiceInputList.title)
            val voiceInputMethods = InputMethodUtil.listVoiceInputMethods()
            // system voice input methods + the built-in ASR engines
            val systemIds = voiceInputMethods.map { it.first.id }
            val systemLabels =
                voiceInputMethods.map { it.first.loadLabel(context.packageManager) }
            entryValues = arrayOf(
                "",
                *systemIds.toTypedArray(),
                VoiceInputManager.BUILTIN_PROVIDER_ID,
                VoiceInputManager.BUILTIN_SF_PROVIDER_ID
            )
            entries = arrayOf(
                context.getString(R.string.system_default),
                *systemLabels.toTypedArray(),
                context.getString(R.string.voice_provider_builtin),
                context.getString(R.string.voice_provider_siliconflow)
            )
            // shows "(Not Available)" if selected id is not present
            summaryProvider = Preference.SummaryProvider<ListPreference> { preference ->
                if (preference.entryValues.includes(preference.value)) {
                    preference.entry
                } else {
                    context.getString(R.string._not_available_)
                }
            }
        }
    }

    class EditTextPassword(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: String,
        @StringRes
        val summary: Int? = null,
        val dataStore: PreferenceDataStore? = null,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<EditTextPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = EditTextPreference(context).apply {
            key = this@EditTextPassword.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setDefaultValue(defaultValue)
            setTitle(this@EditTextPassword.title)
            setDialogTitle(this@EditTextPassword.title)
            // 非空时把读写路由到自定义存储（如独立 SP 文件），绕过默认 SP 与云备份
            this@EditTextPassword.dataStore?.let { preferenceDataStore = it }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                if (pref.text.isNullOrBlank()) context.getString(R.string._not_available_)
                // 掩码显示，不显示真实密钥（规格 §5.5：summaryProvider 不得输出明文）；
                // 固定 8 个掩码符，不随密钥长度变化，避免泄露长度信息
                else "●".repeat(8)
            }
            setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                // 密钥字段禁止 autofill 框架存取（API 26+）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    editText.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                }
            }
        }
    }

    class EditTextString(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: String,
        @StringRes
        val hint: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<EditTextPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = EditTextPreference(context).apply {
            key = this@EditTextString.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setDefaultValue(defaultValue)
            setTitle(this@EditTextString.title)
            setDialogTitle(this@EditTextString.title)
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            this@EditTextString.hint?.let { hintRes ->
                setOnBindEditTextListener { editText ->
                    editText.hint = context.getString(hintRes)
                }
            }
        }
    }

    class EditTextInt(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<EditTextPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = EditTextIntPreference(context).apply {
            key = this@EditTextInt.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = EditTextIntPreference.SimpleSummaryProvider
            setDefaultValue(this@EditTextInt.defaultValue)
            setTitle(this@EditTextInt.title)
            setDialogTitle(this@EditTextInt.title)
            min = this@EditTextInt.min
            max = this@EditTextInt.max
            unit = this@EditTextInt.unit
        }
    }

    class SeekBarInt(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        val step: Int = 1,
        @StringRes
        val defaultLabel: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<DialogSeekBarPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = DialogSeekBarPreference(context).apply {
            key = this@SeekBarInt.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
            this@SeekBarInt.defaultLabel?.let { defaultLabel = context.getString(it) }
            setDefaultValue(this@SeekBarInt.defaultValue)
            setTitle(this@SeekBarInt.title)
            setDialogTitle(this@SeekBarInt.title)
            min = this@SeekBarInt.min
            max = this@SeekBarInt.max
            unit = this@SeekBarInt.unit
            step = this@SeekBarInt.step
        }
    }

    class TwinSeekBarInt(
        @StringRes
        val title: Int,
        @StringRes
        val label: Int,
        key: String,
        val defaultValue: Int,
        @StringRes
        val secondaryLabel: Int,
        val secondaryKey: String,
        val secondaryDefaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        val step: Int = 1,
        @StringRes
        val defaultLabel: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<TwinSeekBarPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = TwinSeekBarPreference(context).apply {
            setTitle(this@TwinSeekBarInt.title)
            setDialogTitle(this@TwinSeekBarInt.title)
            label = context.getString(this@TwinSeekBarInt.label)
            key = this@TwinSeekBarInt.key
            secondaryLabel = context.getString(this@TwinSeekBarInt.secondaryLabel)
            secondaryKey = this@TwinSeekBarInt.secondaryKey
            this@TwinSeekBarInt.defaultLabel?.let { defaultLabel = context.getString(it) }
            setDefaultValue(this@TwinSeekBarInt.defaultValue to this@TwinSeekBarInt.secondaryDefaultValue)
            min = this@TwinSeekBarInt.min
            max = this@TwinSeekBarInt.max
            unit = this@TwinSeekBarInt.unit
            step = this@TwinSeekBarInt.step
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = TwinSeekBarPreference.SimpleSummaryProvider
        }
    }
}
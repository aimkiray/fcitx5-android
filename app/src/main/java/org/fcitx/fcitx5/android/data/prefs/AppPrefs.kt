/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.InputFeedbacks.InputFeedbackMode
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesOrientation
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase
import org.fcitx.fcitx5.android.input.keyboard.LangSwitchBehavior
import org.fcitx.fcitx5.android.input.keyboard.SpaceLongPressBehavior
import org.fcitx.fcitx5.android.input.keyboard.SwipeSymbolDirection
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.input.voice.VoiceInputManager
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.vibrator

class AppPrefs(private val sharedPreferences: SharedPreferences) {

    inner class Internal : ManagedPreferenceInternal(sharedPreferences) {
        val firstRun = bool("first_run", true)
        val lastSymbolLayout = string("last_symbol_layout", PickerWindow.Key.Symbol.name)
        val lastPickerType = string("last_picker_type", PickerWindow.Key.Emoji.name)
        val verboseLog = bool("verbose_log", false)
        val pid = int("pid", 0)
        val editorInfoInspector = bool("editor_info_inspector", false)
        val needNotifications = bool("need_notifications", true)
    }

    inner class Advanced : ManagedPreferenceCategory(R.string.advanced, sharedPreferences) {
        val ignoreSystemCursor = switch(R.string.ignore_sys_cursor, "ignore_system_cursor", false)
        val hideKeyConfig = switch(R.string.hide_key_config, "hide_key_config", true)
        val disableAnimation = switch(R.string.disable_animation, "disable_animation", false)
        val vivoKeypressWorkaround = switch(
            R.string.vivo_keypress_workaround,
            "vivo_keypress_workaround",
            // there's some feedback that this workaround is no longer necessary on Origin OS 4, which based on Android 14
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && DeviceUtil.isVivoOriginOS
        )
        val ignoreSystemWindowInsets = switch(
            R.string.ignore_system_window_insets, "ignore_system_window_insets", false
        )
        val keyboardHeightPercentBase = enumList(
            R.string.keyboard_height_percent_base,
            "keyboard_height_percent_base",
            KeyboardHeightPercentBase.DisplayMetrics
        )
    }

    inner class Keyboard : ManagedPreferenceCategory(R.string.virtual_keyboard, sharedPreferences) {
        val hapticOnKeyPress =
            enumList(
                R.string.button_haptic_feedback,
                "haptic_on_keypress",
                InputFeedbackMode.FollowingSystem
            )
        val hapticOnKeyUp = switch(
            R.string.button_up_haptic_feedback,
            "haptic_on_keyup",
            false
        ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
        val hapticOnRepeat = switch(R.string.haptic_on_repeat, "haptic_on_repeat", false)

        val buttonPressVibrationMilliseconds: ManagedPreference.PInt
        val buttonLongPressVibrationMilliseconds: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_milliseconds,
                R.string.button_press,
                "button_vibration_press_milliseconds",
                0,
                R.string.button_long_press,
                "button_vibration_long_press_milliseconds",
                0,
                0,
                100,
                "ms",
                defaultLabel = R.string.system_default
            ) { hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled }
            buttonPressVibrationMilliseconds = primary
            buttonLongPressVibrationMilliseconds = secondary
        }

        val buttonPressVibrationAmplitude: ManagedPreference.PInt
        val buttonLongPressVibrationAmplitude: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.button_vibration_amplitude,
                R.string.button_press,
                "button_vibration_press_amplitude",
                0,
                R.string.button_long_press,
                "button_vibration_long_press_amplitude",
                0,
                0,
                255,
                defaultLabel = R.string.system_default
            ) {
                (hapticOnKeyPress.getValue() != InputFeedbackMode.Disabled)
                        // hide this if using default duration
                        && (buttonPressVibrationMilliseconds.getValue() != 0 || buttonLongPressVibrationMilliseconds.getValue() != 0)
                        && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appContext.vibrator.hasAmplitudeControl())
            }
            buttonPressVibrationAmplitude = primary
            buttonLongPressVibrationAmplitude = secondary
        }

        val soundOnKeyPress = enumList(
            R.string.button_sound,
            "sound_on_keypress",
            InputFeedbackMode.FollowingSystem
        )
        val soundOnKeyPressVolume = int(
            R.string.button_sound_volume,
            "button_sound_volume",
            0,
            0,
            100,
            "%",
            defaultLabel = R.string.system_default
        ) {
            soundOnKeyPress.getValue() != InputFeedbackMode.Disabled
        }
        val focusChangeResetKeyboard =
            switch(R.string.reset_keyboard_on_focus_change, "reset_keyboard_on_focus_change", true)
        val expandToolbarByDefault =
            switch(R.string.expand_toolbar_by_default, "expand_toolbar_by_default", false)
        val inlineSuggestions = switch(R.string.inline_suggestions, "inline_suggestions", true)
        val toolbarNumRowOnPassword =
            switch(R.string.toolbar_num_row_on_password, "toolbar_num_row_on_password", true)
        val popupOnKeyPress = switch(R.string.popup_on_key_press, "popup_on_key_press", true)
        val keepLettersUppercase = switch(
            R.string.keep_keyboard_letters_uppercase,
            "keep_keyboard_letters_uppercase",
            false
        )

        val showVoiceInputButton =
            switch(R.string.show_voice_input_button, "show_voice_input_button", false)
        // 默认内置 DashScope 引擎（仍需用户填 API key 才真正可用；
        // 未填时语音入口走 voice_error_no_api_key 提示）
        val preferredVoiceInput = voiceInputPreference(
            R.string.preferred_voice_input, "preferred_voice_input",
            VoiceInputManager.BUILTIN_PROVIDER_ID
        ) {
            showVoiceInputButton.getValue() ||
                spaceKeyLongPressBehavior.getValue() in setOf(
                    SpaceLongPressBehavior.VoiceInput,
                    SpaceLongPressBehavior.VoiceInputHold
                )
        }

        val expandKeypressArea =
            switch(R.string.expand_keypress_area, "expand_keypress_area", false)
        val swipeSymbolDirection = enumList(
            R.string.swipe_symbol_behavior,
            "swipe_symbol_behavior",
            SwipeSymbolDirection.Down
        )
        val longPressDelay = int(
            R.string.keyboard_long_press_delay,
            "keyboard_long_press_delay",
            300,
            100,
            700,
            "ms",
            10
        )
        // 默认长按空格 = 按住说话（松开结束识别），配合 preferredVoiceInput
        // 默认内置 DashScope，开箱即得按住空格语音输入
        val spaceKeyLongPressBehavior = enumList(
            R.string.space_long_press_behavior,
            "space_long_press_behavior",
            SpaceLongPressBehavior.VoiceInputHold
        )
        val spaceSwipeMoveCursor =
            switch(R.string.space_swipe_move_cursor, "space_swipe_move_cursor", true)
        val showLangSwitchKey =
            switch(R.string.show_lang_switch_key, "show_lang_switch_key", true)
        val langSwitchKeyBehavior = enumList(
            R.string.lang_switch_key_behavior,
            "lang_switch_key_behavior",
            LangSwitchBehavior.Enumerate
        ) { showLangSwitchKey.getValue() }

        val keyboardHeightPercent: ManagedPreference.PInt
        val keyboardHeightPercentLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_height,
                R.string.portrait,
                "keyboard_height_percent",
                30,
                R.string.landscape,
                "keyboard_height_percent_landscape",
                49,
                10,
                90,
                "%"
            )
            keyboardHeightPercent = primary
            keyboardHeightPercentLandscape = secondary
        }

        val keyboardSidePadding: ManagedPreference.PInt
        val keyboardSidePaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_side_padding,
                R.string.portrait,
                "keyboard_side_padding",
                0,
                R.string.landscape,
                "keyboard_side_padding_landscape",
                0,
                0,
                300,
                "dp"
            )
            keyboardSidePadding = primary
            keyboardSidePaddingLandscape = secondary
        }

        val keyboardBottomPadding: ManagedPreference.PInt
        val keyboardBottomPaddingLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.keyboard_bottom_padding,
                R.string.portrait,
                "keyboard_bottom_padding",
                0,
                R.string.landscape,
                "keyboard_bottom_padding_landscape",
                0,
                0,
                100,
                "dp"
            )
            keyboardBottomPadding = primary
            keyboardBottomPaddingLandscape = secondary
        }

        val horizontalCandidateStyle = enumList(
            R.string.horizontal_candidate_style,
            "horizontal_candidate_style",
            HorizontalCandidateMode.AutoFillWidth
        )
        val expandedCandidateStyle = enumList(
            R.string.expanded_candidate_style,
            "expanded_candidate_style",
            ExpandedCandidateStyle.Grid
        )

        val expandedCandidateGridSpanCount: ManagedPreference.PInt
        val expandedCandidateGridSpanCountLandscape: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.expanded_candidate_grid_span_count,
                R.string.portrait,
                "expanded_candidate_grid_span_count_portrait",
                6,
                R.string.landscape,
                "expanded_candidate_grid_span_count_landscape",
                8,
                4,
                12,
            )
            expandedCandidateGridSpanCount = primary
            expandedCandidateGridSpanCountLandscape = secondary
        }

    }

    inner class Candidates :
        ManagedPreferenceCategory(R.string.candidates_window, sharedPreferences) {
        val mode = enumList(
            R.string.show_candidates_window,
            "show_candidates_window",
            FloatingCandidatesMode.InputDevice
        )

        val orientation = enumList(
            R.string.candidates_orientation,
            "candidates_window_orientation",
            FloatingCandidatesOrientation.Automatic
        )

        val windowMinWidth = int(
            R.string.candidates_window_min_width,
            "candidates_window_min_width",
            0,
            0,
            640,
            "dp",
            10
        )

        val windowPadding =
            int(R.string.candidates_window_padding, "candidates_window_padding", 4, 0, 32, "dp")

        val fontSize =
            int(R.string.candidates_font_size, "candidates_window_font_size", 20, 4, 64, "sp")

        val windowRadius =
            int(R.string.candidates_window_radius, "candidates_window_radius", 0, 0, 48, "dp")

        val itemPaddingVertical: ManagedPreference.PInt
        val itemPaddingHorizontal: ManagedPreference.PInt

        init {
            val (primary, secondary) = twinInt(
                R.string.candidates_padding,
                R.string.vertical,
                "candidates_item_padding_vertical",
                2,
                R.string.horizontal,
                "candidates_item_padding_horizontal",
                4,
                0,
                64,
                "dp"
            )
            itemPaddingVertical = primary
            itemPaddingHorizontal = secondary
        }
    }

    inner class Clipboard : ManagedPreferenceCategory(R.string.clipboard, sharedPreferences) {
        val clipboardListening = switch(R.string.clipboard_listening, "clipboard_enable", true)
        val clipboardHistoryLimit = int(
            R.string.clipboard_limit,
            "clipboard_limit",
            10,
        ) { clipboardListening.getValue() }
        val clipboardSuggestion = switch(
            R.string.clipboard_suggestion, "clipboard_suggestion", true
        ) { clipboardListening.getValue() }
        val clipboardItemTimeout = int(
            R.string.clipboard_suggestion_timeout,
            "clipboard_item_timeout",
            30,
            -1,
            Int.MAX_VALUE,
            "s"
        ) { clipboardListening.getValue() && clipboardSuggestion.getValue() }
        val clipboardReturnAfterPaste = switch(
            R.string.clipboard_return_after_paste, "clipboard_return_after_paste", false
        ) { clipboardListening.getValue() }
        val clipboardMaskSensitive = switch(
            R.string.clipboard_mask_sensitive, "clipboard_mask_sensitive", true
        ) { clipboardListening.getValue() }
    }

    inner class Symbols : ManagedPreferenceCategory(R.string.emoji_and_symbols, sharedPreferences) {
        val hideUnsupportedEmojis = switch(
            R.string.hide_unsupported_emojis,
            "hide_unsupported_emojis",
            true
        )

        val defaultEmojiSkinTone = enumList(
            R.string.default_emoji_skin_tone,
            "default_emoji_skin_tone",
            EmojiModifier.SkinTone.Default,
        )
    }

    inner class Voice : ManagedPreferenceCategory(R.string.voice_input_settings, sharedPreferences) {
        // API key 存独立 SP 文件 voice_secrets.xml（明文，见规格 §6-R1），经
        // data_extraction_rules/full_backup_content 排除以免进入云备份；
        // 不得出现在代码字面量/BuildConfig。
        private val voiceSecrets =
            appContext.getSharedPreferences(VOICE_SECRETS_PREFS_NAME, Context.MODE_PRIVATE)

        // androidx Preference UI 的读写也路由到 voice_secrets，绕过默认 SP
        private val voiceSecretsDataStore = object : PreferenceDataStore() {
            override fun putString(key: String, value: String?) {
                voiceSecrets.edit { putString(key, value) }
            }

            override fun getString(key: String, defValue: String?): String? =
                voiceSecrets.getString(key, defValue)
        }

        init {
            // 迁移：旧版本误存默认 SP（会被云备份带走）的 key 挪到 voice_secrets 并清除。
            // 仅旧值非空且 voice_secrets 尚无值时写入，防覆盖用户新值；
            // 旧值可能不是 String（ClassCastException），runCatching 兜底；
            // 无论迁移成败始终移除旧键；commit() 保证 crash 窗口内不丢一致性。
            val legacyKey = "voice_input_dashscope_api_key"
            val legacyValue = runCatching {
                sharedPreferences.getString(legacyKey, "")
            }.getOrNull()
            if (!legacyValue.isNullOrEmpty() &&
                voiceSecrets.getString(legacyKey, "").isNullOrEmpty()
            ) {
                voiceSecrets.edit(commit = true) { putString(legacyKey, legacyValue) }
            }
            if (sharedPreferences.contains(legacyKey)) {
                sharedPreferences.edit(commit = true) { remove(legacyKey) }
            }
        }

        // UI：EditTextPassword 掩码输入（init 块注册，保持 apiKey→model→language 顺序）。
        val voiceInputDashscopeApiKey =
            ManagedPreference.PString(
                voiceSecrets, "voice_input_dashscope_api_key", ""
            ).apply { register() }

        init {
            // CTO 修订（评审 M2）：三项 enableUiOn 统一为 builtin 被选中即可见
            // （不绑定 showVoiceInputButton，空格长按用户也需要配置入口）。
            ManagedPreferenceUi.EditTextPassword(
                R.string.voice_dashscope_api_key,
                voiceInputDashscopeApiKey.key,
                "",
                dataStore = voiceSecretsDataStore,
                enableUiOn = {
                    VoiceInputManager.isBuiltinId(keyboard.preferredVoiceInput.getValue())
                }
            ).registerUi()
        }

        val voiceInputDashscopeModel = enumList(
            R.string.voice_dashscope_model,
            "voice_input_dashscope_model",
            DashScopeModelEnum.QwenAudio31Message
        ) {
            VoiceInputManager.isBuiltinId(keyboard.preferredVoiceInput.getValue())
        }

        // partial 文本在编辑器内的实时预览模式（详见 FcitxInputMethodService
        // .setVoiceComposingText 的注释——远程桌面类 TYPE_NULL 编辑器会把
        // composing 当 commit 转发造成双重输入）
        val voiceInputPreviewMode = enumList(
            R.string.voice_preview_mode,
            "voice_input_preview_mode",
            VoicePreviewMode.Auto
        ) {
            VoiceInputManager.isBuiltinId(keyboard.preferredVoiceInput.getValue())
        }

        // UI：EditTextString 普通输入。
        val voiceInputDashscopeLanguage =
            ManagedPreference.PString(
                sharedPreferences, "voice_input_dashscope_language", ""
            ).apply { register() }

        init {
            ManagedPreferenceUi.EditTextString(
                R.string.voice_dashscope_language,
                voiceInputDashscopeLanguage.key,
                "",
                hint = R.string.voice_dashscope_language_hint,
                enableUiOn = {
                    VoiceInputManager.isBuiltinId(keyboard.preferredVoiceInput.getValue())
                }
            ).registerUi()
        }
    }

    private val providers = mutableListOf<ManagedPreferenceProvider>()

    fun <T : ManagedPreferenceProvider> registerProvider(
        providerF: (SharedPreferences) -> T
    ): T {
        val provider = providerF(sharedPreferences)
        providers.add(provider)
        return provider
    }

    private fun <T : ManagedPreferenceProvider> T.register() = this.apply {
        registerProvider { this }
    }

    val internal = Internal().register()
    val keyboard = Keyboard().register()
    val candidates = Candidates().register()
    val clipboard = Clipboard().register()
    val symbols = Symbols().register()
    val voice = Voice().register()
    val advanced = Advanced().register()

    @Keep
    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) return@OnSharedPreferenceChangeListener
            providers.forEach {
                it.fireChange(key)
            }
        }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            listOf(
                internal.verboseLog,
                internal.editorInfoInspector,
                advanced.ignoreSystemCursor,
                advanced.disableAnimation,
                advanced.vivoKeypressWorkaround
            ).forEach {
                it.putValueTo(this@edit)
            }
            listOf(
                keyboard,
                candidates,
                clipboard
            ).forEach { category ->
                category.managedPreferences.forEach {
                    it.value.putValueTo(this@edit)
                }
            }
        }
    }

    companion object {
        private const val VOICE_SECRETS_PREFS_NAME = "voice_secrets"

        private var instance: AppPrefs? = null

        /**
         * MUST call before use
         */
        fun init(sharedPreferences: SharedPreferences) {
            if (instance != null)
                return
            instance = AppPrefs(sharedPreferences)
            sharedPreferences.registerOnSharedPreferenceChangeListener(getInstance().onSharedPreferenceChangeListener)
        }

        fun getInstance() = instance!!
    }
}

/**
 * 内置 DashScope 引擎可选模型（CTO 修订：只列流式可用模型——非流式 Generation
 * 模型会让引擎 start() 直接报错，不该出现在候选）。
 * [modelId] 为传给引擎的实际模型 ID；pref 存储的是枚举 name。
 */
enum class DashScopeModelEnum(
    val modelId: String,
    override val stringRes: Int
) : ManagedPreferenceEnum {
    FunAsrRealtime("fun-asr-realtime", R.string.voice_model_fun_asr_realtime),
    QwenAudioStreaming(
        "qwen-audio-3.0-asr-flash-streaming",
        R.string.voice_model_qwen_audio_streaming
    ),
    QwenAudio31Streaming(
        "qwen-audio-3.1-asr-flash-streaming",
        R.string.voice_model_qwen_audio_31_streaming
    ),
    QwenAudio31Message(
        "qwen-audio-3.1-asr-flash-message",
        R.string.voice_model_qwen_audio_31_message
    ),
    Qwen3Realtime("qwen3-asr-flash-realtime", R.string.voice_model_qwen3_realtime),
}

/**
 * 语音 partial 文本在编辑器内的预览模式。
 * [Auto] 仅在检测为正常文本编辑器时预览（TYPE_NULL 类编辑器——如远程桌面
 * App——把 setComposingText 当 commitText 转发，预览会产生双重输入）；
 * [Always] 总是预览；[FinalOnly] 从不预览，只上屏最终修正文本。
 */
enum class VoicePreviewMode(override val stringRes: Int) : ManagedPreferenceEnum {
    Auto(R.string.voice_preview_mode_auto),
    Always(R.string.voice_preview_mode_always),
    FinalOnly(R.string.voice_preview_mode_final_only),
}
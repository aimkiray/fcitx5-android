/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import java.io.File

/**
 * A dictionary shipped inside the app data dir (system layer). The bundled
 * file itself must not be renamed or deleted, so disabling is done by placing
 * a `<name>.dict.disable` marker into the user dictionary dir, which the
 * engine honors across all pinyin data layers.
 */
class BuiltinDictionary(
    override val file: File,
    private val userDictDir: File
) : PinyinDictionary() {

    override val type: Type
        get() = Type.LibIME

    private val delegate by lazy { LibIMEDictionary(file) }

    private val disableMarker: File
        get() = File(userDictDir, "${file.name}.${LibIMEDictionary.DISABLE}")

    override val isEnabled: Boolean
        get() = !disableMarker.exists()

    override fun enable() {
        disableMarker.delete()
    }

    override fun disable() {
        disableMarker.createNewFile()
    }

    override fun toTextDictionary(dest: File): TextDictionary = delegate.toTextDictionary(dest)

    override fun toLibIMEDictionary(dest: File): LibIMEDictionary = delegate

}

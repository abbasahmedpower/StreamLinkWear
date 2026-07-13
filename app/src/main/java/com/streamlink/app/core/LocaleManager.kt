package com.streamlink.app.core

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

data class AppLanguage(val tag: String, val nativeName: String, val isRtl: Boolean = false)

object LocaleManager {
    val supported = listOf(
        AppLanguage("en", "English"),
        AppLanguage("ar", "العربية", isRtl = true),
        AppLanguage("es", "Español"),
        AppLanguage("fr", "Français"),
        AppLanguage("de", "Deutsch"),
        AppLanguage("pt", "Português"),
        AppLanguage("it", "Italiano"),
        AppLanguage("ru", "Русский"),
        AppLanguage("tr", "Türkçe"),
        AppLanguage("zh-rCN", "中文（简体）"),
        AppLanguage("ja", "日本語"),
        AppLanguage("ko", "한국어")
    )

    fun setLocale(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun currentTag(): String =
        AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0)?.language ?: "en"
}

package com.streamlink.app.about

object AboutStrings {
    private val strings = mapOf(
        AppLanguage.ENGLISH to mapOf(
            "app_name" to "Horus Al-Ferdous",
            "version" to "Version",
            "developer" to "Developed by Al-Ferdous Team",
            "contact" to "Contact Support",
            "website" to "Visit Website"
        ),
        AppLanguage.ARABIC to mapOf(
            "app_name" to "حورس الفردوس",
            "version" to "الإصدار",
            "developer" to "تطوير فريق الفردوس",
            "contact" to "تواصل مع الدعم",
            "website" to "زيارة الموقع"
        )
    )

    fun get(key: String, language: AppLanguage = AppLanguage.getCurrent()): String {
        return strings[language]?.get(key) ?: strings[AppLanguage.ENGLISH]?.get(key) ?: key
    }
}

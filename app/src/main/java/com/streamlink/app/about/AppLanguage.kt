package com.streamlink.app.about

import androidx.compose.runtime.Immutable
import java.util.Locale

@Immutable
enum class AppLanguage(val isRtl: Boolean) {
    ENGLISH(false),
    ARABIC(true);

    companion object {
        fun getCurrent(): AppLanguage {
            val language = Locale.getDefault().language
            return if (language == "ar") ARABIC else ENGLISH
        }
    }
}

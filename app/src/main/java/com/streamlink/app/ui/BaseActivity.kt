package com.streamlink.app.ui

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.streamlink.app.utils.LocaleHelper

open class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val lang = prefs.getString("selected_language", "en") ?: "en"
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, lang))
    }
}

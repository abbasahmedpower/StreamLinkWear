package com.streamlink.app.ui

import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity()
// ✅ NANO-FIX: اتشالت attachBaseContext + LocaleHelper بالكامل.
// AppCompatDelegate.setApplicationLocales() (اللي بتتنادى من LocaleManager)
// كافية لوحدها وهي المصدر الرسمي الوحيد للغة التطبيق دلوقتي.

package com.streamlink.shared

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * TrustedDeviceStore — نظام "الثقة" الدائم للأجهزة المقترنة.
 *
 * المفهوم: Trust on First Use (TOFU)
 *   - المرة الأولى: يتصل الجهاز عبر QR → يُحفظ deviceId + pairingCode مشفَّرَين.
 *   - المرات التالية: التطبيق يتعرّف على الجهاز ويتصل مباشرة دون QR.
 *
 * الأمان:
 *   - البيانات محفوظة في EncryptedSharedPreferences (AES256-GCM).
 *   - المفتاح محمي بـ Android Keystore — لا يظهر أبداً في الـ storage.
 *   - التشفير يتم تلقائياً عبر Jetpack Security Crypto.
 *
 * Nano-Level Design:
 *   - لا allocations على الـ hot path — القراءة/الكتابة O(1) مباشرة من/إلى prefs.
 *   - `@Volatile` على الـ cache لمنع أي data race بين خيوط الـ UI وخيوط الشبكة.
 *   - Singleton آمن ضد التعدد (double-checked locking).
 */
class TrustedDeviceStore private constructor(context: Context) {

    private val tag = "TrustedDeviceStore"

    // مفتاح واحد لكل الأجهزة (يمكن توسيعه لـ multi-device لاحقاً)
    // الاسم مقصود يكون opaque — لا يعطي أي معلومة لو قرأه أحد
    companion object {
        private const val PREFS_FILE   = "sl_trusted_v1"
        private const val KEY_DEVICE_ID = "d_id"
        private const val KEY_PIN      = "d_pin"
        private const val KEY_DEVICE_NAME = "d_name"
        private const val KEY_LAST_SEEN = "d_ts"

        @Volatile private var instance: TrustedDeviceStore? = null

        fun get(context: Context): TrustedDeviceStore =
            instance ?: synchronized(this) {
                instance ?: TrustedDeviceStore(context.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // ✅ FIX #5 (أمني): بدل Fallback لتخزين PIN بنص صريح، نرفع SecurityException.
        // الـ caller (شاشة الـ Onboarding) يمسك الاستثناء ده ويطلب QR كل مرة
        // بدل ما يحفظ الـ pairingCode على القرص بدون تشفير من غير ما المستخدم يدري.
        // الأجهزة اللي Keystore فيها فاشل (نادر جداً) هتحتاج QR في كل جلسة — وده
        // أفضل بكتير من تسريب بيانات الأمان بصمت.
        Log.e(tag, "فشل تهيئة Keystore — رفض التهيئة لضمان أمان بيانات الاقتران: ${e.message}")
        throw SecurityException("Cannot initialize TrustedDeviceStore without Android Keystore", e)
    }

    // ── In-Memory Cache ───────────────────────────────────────────────────────
    // يُجنّب قراءة الـ disk في كل استدعاء من الـ hot path.
    @Volatile private var cachedDeviceId: String? = prefs.getString(KEY_DEVICE_ID, null)
    @Volatile private var cachedPin: String? = prefs.getString(KEY_PIN, null)

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * هل يوجد جهاز موثوق محفوظ؟
     * استدعاء O(1) — يعتمد على الـ cache فقط.
     */
    val hasTrustedDevice: Boolean get() = cachedDeviceId != null && cachedPin != null

    /**
     * استرجاع الـ pairingCode للجهاز الموثوق.
     * يُستخدم لإعادة الاتصال التلقائي دون QR.
     * Returns null إذا لا يوجد جهاز محفوظ.
     */
    val trustedPairingCode: String? get() = cachedPin

    /** اسم الجهاز الموثوق للعرض في الـ UI. */
    val trustedDeviceName: String? get() = prefs.getString(KEY_DEVICE_NAME, null)

    /** آخر وقت تم فيه الاتصال بهذا الجهاز (Unix ms). */
    val lastSeenMs: Long get() = prefs.getLong(KEY_LAST_SEEN, 0L)

    /**
     * يُسجّل جهازاً جديداً كموثوق بعد نجاح الإقران.
     * يُستدعى مرة واحدة فقط بعد أول QR scan ناجح.
     *
     * @param deviceId   معرّف فريد للجهاز (من ConnectionPayload)
     * @param pairingCode الكود الذي تم التحقق منه بنجاح
     * @param deviceName  اسم الجهاز للعرض (اختياري)
     */
    fun trust(deviceId: String, pairingCode: String, deviceName: String = "Phone") {
        prefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_PIN, pairingCode)
            .putString(KEY_DEVICE_NAME, deviceName)
            .putLong(KEY_LAST_SEEN, System.currentTimeMillis())
            .apply()

        // تحديث الـ cache فوراً — خيوط الشبكة تقرأ من هنا
        cachedDeviceId = deviceId
        cachedPin = pairingCode
        Log.i(tag, "Device trusted: $deviceName ($deviceId) — future connections will skip QR")
    }

    /**
     * تحديث آخر وقت اتصال لتجنّب قراءة/كتابة ثقيلة على الـ hot path.
     * يُستدعى عند كل اتصال ناجح.
     */
    fun updateLastSeen() {
        prefs.edit().putLong(KEY_LAST_SEEN, System.currentTimeMillis()).apply()
    }

    /**
     * إلغاء الثقة — يمحو الجهاز المحفوظ ويُجبر المستخدم على QR مجدداً.
     * يُستدعى من شاشة الإعدادات ("فصل الجهاز").
     */
    fun revoke() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_PIN)
            .remove(KEY_DEVICE_NAME)
            .remove(KEY_LAST_SEEN)
            .apply()
        cachedDeviceId = null
        cachedPin = null
        Log.i(tag, "Trusted device revoked — QR pairing required on next connection")
    }
}

/**
 * Extension function for easy singleton access.
 * Usage: context.trustedDeviceStore.hasTrustedDevice
 */
val Context.trustedDeviceStore: TrustedDeviceStore
    get() = TrustedDeviceStore.get(this)

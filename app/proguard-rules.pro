# ── كود التطبيق: obfuscation + shrinking طبيعي.
#    بنحافظ بس على اللي الـ reflection/serialization/DI محتاجينه بالاسم وقت التشغيل. ──
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Hilt/Dagger — generated entry points والـ injected constructors
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp
-keep class **_HiltComponents$* { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.android.AndroidEntryPoint <init>(...);
}

# Kotlinx Serialization — الـ protocol DTOs بتتقرا بالاسم وقت الـ (de)serialization
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep class com.streamlink.shared.protocol.** { *; }
-keep class com.streamlink.shared.protocol.**$* { *; }
-keepclasseswithmembers class com.streamlink.shared.protocol.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * extends java.lang.Enum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# مكتبات خارجية بتتكسر لو اتعملها obfuscate
-keep class org.tensorflow.** { *; }
-keep class org.webrtc.** { *; }
-keep class io.getstream.** { *; }
-dontwarn org.tensorflow.**
-dontwarn org.webrtc.**

# ─────────────────────────────────────────────────────────────────────────────
# ML Kit Barcode Scanning
# بدون الـ rules دي الـ R8 بيمسح كلاسات داخلية بتتعمل لها reflection وقت التشغيل
# ─────────────────────────────────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_barcode.**

# ─────────────────────────────────────────────────────────────────────────────
# CameraX
# ─────────────────────────────────────────────────────────────────────────────
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep class androidx.camera.view.** { *; }
-dontwarn androidx.camera.**

# ─────────────────────────────────────────────────────────────────────────────
# Shared Module — تم إزالة القاعدة الشاملة للسماح للـ R8 بضغط وحذف الكود الميت
# (يتم حماية الـ protocol classes بالأعلى في قسم kotlinx.serialization)
# ─────────────────────────────────────────────────────────────────────────────

# =============================================================================
# HORUS LINK — Closed-Loop Control & Telemetry Layer
# =============================================================================

# MediaCodec JNI Bridge — تغيير الأسماء يكسر الـ Native C++ Callbacks
-keep class android.media.MediaCodec** { *; }
-keep class android.media.MediaFormat** { *; }
-keep class android.media.Image** { *; }

# HardwareEncoder — الـ Actuator يستدعي دواله مباشرة بالاسم
-keepclassmembers class com.streamlink.app.capture.HardwareEncoder {
    public void setBitrate(int);
    public void forceKeyframe();
    public void reconfigure(...);
}

# Fuzzy Engine & State Vector — يجب الحفاظ على أسماء المتغيرات للـ Logging والـ Debug
-keep class com.streamlink.shared.telemetry.** { *; }
-keep class com.streamlink.app.telemetry.** { *; }

# HardwareActuator
-keep class com.streamlink.app.telemetry.HardwareActuator { *; }

# DirectSocketServer — يستخدم Reflection داخلياً عبر الـ Java NIO
-keep class com.streamlink.shared.DirectSocketServer { *; }
-keepclassmembers class com.streamlink.shared.DirectSocketServer {
    public int getQueueDepth();
    public long getDroppedFrames();
}

# Wear Telemetry Sender & ViewModel — الـ MessageClient يحتاجها بالاسم
-keep class com.streamlink.app.core.WearTelemetrySender { *; }
-keep class com.streamlink.app.ui.viewmodel.TelemetryViewModel { *; }
-keep class com.streamlink.app.ui.viewmodel.TelemetryViewModelFactory { *; }

# ViewModel constructors — needed by ViewModelProvider.Factory
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}

# Gson data classes — must preserve field names for JSON serialization
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }

# Wearable Message API
-keep class com.google.android.gms.wearable.** { *; }
-dontwarn com.google.android.gms.wearable.**

# Kotlin Coroutines & Flow — لا تمس الـ State machines الداخلية
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Preserve crash stack traces in Release
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

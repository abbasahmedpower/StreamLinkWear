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
# Shared Module — كل الـ data classes والـ enums والـ protocol
# محتاجين يكونوا محمييين عشان بيتعملوا serialize/deserialize بالاسم
# ─────────────────────────────────────────────────────────────────────────────
-keep class com.streamlink.shared.** { *; }
-keepnames class com.streamlink.shared.** { *; }

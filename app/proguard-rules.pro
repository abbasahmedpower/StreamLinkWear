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

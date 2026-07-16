-keep class com.streamlink.wear.service.WearForegroundService { *; }
-keep class com.streamlink.wear.tile.StreamLinkTileService { *; }
-keep class com.streamlink.wear.WearApp { *; }
-keep class org.webrtc.** { *; }
-keep class io.getstream.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class dagger.** { *; }
-keep class hilt.** { *; }

-dontwarn org.webrtc.**

# Keep protocol DTOs and Enums
-keep class com.streamlink.shared.protocol.** { *; }
-keep class com.streamlink.shared.protocol.**$* { *; }

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keepclassmembers class * extends java.lang.Enum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.streamlink.shared.protocol.** {
    kotlinx.serialization.KSerializer serializer(...);
}

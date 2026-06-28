# Custom ProGuard Rules for StreamLink Wear

# Keep ByteBuffer and Socket logic intact for low-latency networking
-keep class java.nio.ByteBuffer { *; }
-keep class java.nio.DirectByteBuffer { *; }
-keep class * extends java.nio.Buffer { *; }
-dontwarn java.nio.**

-keep class java.net.Socket { *; }
-dontwarn java.net.**

# Protect domain modules and hardware dealing with code reflection and networking
-keep class com.streamlink.shared.domain.** { *; }

# Protect stream protocol constants and wire protocol signals from minification
-keepclassmembers class * extends java.lang.Enum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep StreamProtocol constants
-keep class com.streamlink.shared.StreamProtocol { *; }

# If using Hilt/Dagger for Dependency Injection
-keep class dagger.hilt.** { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponent { *; }
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.**
-dontwarn dagger.hilt.**

# Keep Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

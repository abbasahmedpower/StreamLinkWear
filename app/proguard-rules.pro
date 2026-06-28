# Custom ProGuard Rules for StreamLink Wear

# Keep ByteBuffer and Socket logic intact for low-latency networking
-keep class * extends java.nio.Buffer { *; }
-dontwarn java.nio.**

-keep class java.net.Socket { *; }
-dontwarn java.net.**

# Keep StreamProtocol constants
-keep class com.streamlink.shared.StreamProtocol { *; }

# Keep Dagger/Hilt components
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.**

# Keep Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

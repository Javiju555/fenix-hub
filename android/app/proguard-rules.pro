# Strip all debug/verbose log calls from release builds.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# Ktor + Netty + SLF4J (SLF4J JVM binding absent on Android — safe to ignore)
-keep class io.ktor.** { *; }
-keep class io.netty.** { *; }
-dontwarn io.netty.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Argon2
-keep class com.lambdapioneer.argon2kt.** { *; }

# ZXing
-keep class com.journeyapps.** { *; }
-keep class com.google.zxing.** { *; }

# Keep crypto utils (reflection-sensitive)
-keep class com.fenixhub.mobile.util.** { *; }

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

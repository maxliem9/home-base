# Keep Moshi model classes
-keepclassmembers class com.homebase.android.data.model.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }

# Keep Retrofit interfaces
-keep interface com.homebase.android.data.api.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Google Tink (via androidx.security:security-crypto) pulls in errorprone annotations
# as compile-time-only metadata. These classes are absent at runtime and never
# executed — safe to ignore.
-dontwarn com.google.errorprone.annotations.**

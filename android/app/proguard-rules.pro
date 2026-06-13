# Keep Moshi model classes
-keepclassmembers class com.homebase.android.data.model.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }

# Keep Retrofit interfaces
-keep interface com.homebase.android.data.api.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Google Tink (pulled in transitively via androidx.security:security-crypto for
# encrypted token storage) references these errorprone annotations at compile
# time but does not ship them at runtime. R8's vital run aborts with
# "Missing class com.google.errorprone.annotations.*" — suppress those warnings.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

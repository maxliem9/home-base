# Keep Moshi model classes
-keepclassmembers class com.homebase.android.data.model.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }

# Keep Retrofit interfaces
-keep interface com.homebase.android.data.api.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# WebView JavaScript bridges
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Room nested database/entity/DAO classes
-keep class com.nexuscast.player.CacheDatabase$** { *; }
-keep class com.nexuscast.player.PlaylistDatabase$** { *; }

# Sentry stack trace quality
-keepattributes SourceFile,LineNumberTable

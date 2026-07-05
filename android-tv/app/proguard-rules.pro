# WebView JavaScript bridges
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Room nested database/entity/DAO classes
-keep class com.digipal.signage.CacheDatabase$** { *; }
-keep class com.digipal.signage.PlaylistDatabase$** { *; }

# Sentry stack trace quality
-keepattributes SourceFile,LineNumberTable

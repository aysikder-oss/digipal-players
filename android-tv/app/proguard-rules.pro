# WebView
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**
-keep class com.nexuscast.launcher.** { *; }
-keep class * extends android.webkit.WebViewClient { *; }
-keep class * extends android.webkit.WebChromeClient { *; }
-keepclassmembers class com.nexuscast.launcher.MainActivity$WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep WebView JavaScript interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep WebViewClient and WebChromeClient
-keep class com.devinai.app.DevinWebViewClient { *; }
-keep class com.devinai.app.DevinWebChromeClient { *; }

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

# Material Design
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

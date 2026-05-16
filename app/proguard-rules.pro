# Karunada-Vanya ProGuard rules
# Keep WebView JavaScript interface if added later
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

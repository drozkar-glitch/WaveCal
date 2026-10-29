-keep class cz.mares.wavecal.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn kotlinx.**
-dontwarn org.jetbrains.**

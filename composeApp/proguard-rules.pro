-keep class cz.mares.wavecal.MainActivity { *; }
-keep class cz.mares.wavecal.AgendaWidget { *; }
-keep class cz.mares.wavecal.AlarmReceiver { *; }
-keep class cz.mares.wavecal.CalendarSyncReceiver { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn kotlinx.**
-dontwarn org.jetbrains.**

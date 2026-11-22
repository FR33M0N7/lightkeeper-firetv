package local.lightkeeper.firetv;

/** The log, under the tag Lightkeeper: adb logcat -s Lightkeeper */
final class Log {
    private static final String TAG = "Lightkeeper";
    static void i(String text) { android.util.Log.i(TAG, text); }
    static void e(String text, Throwable t) { android.util.Log.e(TAG, text, t); }
}

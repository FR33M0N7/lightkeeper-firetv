package local.lightkeeper.firetv;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Does nothing itself, but with an accessibility service on, Fire TV's Home is slower to draw,
 * so HomeKey has Lightkeeper in front before Amazon's Home shows: Home looks like it opens
 * Lightkeeper directly. (Found by trying, on Fire OS 8.1.8.6.) Fire OS keeps the Home key
 * itself from accessibility services, so this cannot catch it. Turned on over ADB (README).
 */
public class HomeKeyService extends AccessibilityService {
    @Override protected void onServiceConnected() {
        Log.i("home key: accessibility service on");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}
}

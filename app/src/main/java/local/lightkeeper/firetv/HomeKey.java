package local.lightkeeper.firetv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * The Home key, as GlassHouse's key filter gave it to Lightkeeper on webOS: Fire OS lets no
 * app replace its launcher, so this watches the system log for Fire TV's Home being started
 * (the Home key, Back from the last app, waking up) and puts Lightkeeper in front of it.
 * Needs READ_LOGS, and SYSTEM_ALERT_WINDOW to start an activity from the background; both are
 * granted over ADB. Amazon's Home may show for a moment first.
 */
final class HomeKey {
    private static final String HOME_CAT = "cat=[android.intent.category.HOME]";
    private static final String FIRE_HOME = "cmp=com.amazon.tv.launcher/";
    // Fire TV's launcher says so itself when Home comes while it is already started.
    private static final String FIRE_HOME_SAYS = "Received a Home Intent";
    private long lastHome;

    private final Context ctx;
    private volatile Process proc;
    private volatile boolean running;

    HomeKey(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    boolean allowed() {
        return ctx.checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED;
    }

    void start() {
        if (running) return;
        if (!allowed()) { Log.i("home key: READ_LOGS not granted, the Home key stays with Fire TV"); return; }
        running = true;
        Thread t = new Thread(this::watch, "lightkeeper-homekey");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        Process p = proc;
        if (p != null) p.destroy();
    }

    private void watch() {
        while (running) {
            try {
                // Only lines from now on (-T 1): the activity manager's STARTs and Fire TV's launcher.
                proc = new ProcessBuilder("logcat", "-T", "1", "-v", "brief", "ActivityTaskManager:I", "HomeActivity_vNext:I", "*:S")
                        .redirectErrorStream(true).start();
                Log.i("home key: watching for Fire TV's Home");
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line;
                while (running && (line = r.readLine()) != null) {
                    if ((line.contains("START u0") && line.contains(HOME_CAT) && line.contains(FIRE_HOME))
                            || line.contains(FIRE_HOME_SAYS)) home();
                }
            } catch (Exception e) {
                Log.e("home key", e);
            }
            if (running) try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        }
    }

    private void home() {
        long now = System.currentTimeMillis();
        if (now - lastHome < 1500) return; // one press logs both lines
        lastHome = now;
        Lightkeeper lk = Lightkeeper.get(ctx);
        // "Key that opens Lightkeeper": Home, or none.
        if (!"home".equals(lk.store.config.optString("key", "home"))) return;
        // Started at once, Fire TV's Home finishes coming up over it: after it has, and once more
        // in case it was slow (the second only brings Lightkeeper forward again).
        for (long delay : new long[]{450, 1300}) main.postDelayed(this::open, delay);
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    private void open() {
        Intent i = new Intent(ctx, MainActivity.class).putExtra(MainActivity.FROM_HOME_KEY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { ctx.startActivity(i); } catch (Exception e) { Log.e("home key: open", e); }
    }
}

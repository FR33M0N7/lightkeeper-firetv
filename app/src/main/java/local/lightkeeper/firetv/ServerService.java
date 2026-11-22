package local.lightkeeper.firetv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;

/** Keeps the settings server up while other apps are in front, for the phone's settings page. */
public class ServerService extends Service {
    private HomeKey homeKey;
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // Each time the screen comes on: a new random background, as webOS's power on.
            Lightkeeper.get(c).store.sessionMark = String.valueOf(System.currentTimeMillis());
        }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { Lightkeeper.get(c).apps.forget(); }
    };

    static void start(Context ctx) {
        Intent i = new Intent(ctx, ServerService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        Lightkeeper lk = Lightkeeper.get(this);
        lk.store.sessionMark = String.valueOf(System.currentTimeMillis());
        registerReceiver(screen, new IntentFilter(Intent.ACTION_SCREEN_ON));
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
        try { lk.server.start(); } catch (Exception e) { Log.e("server", e); }
        homeKey = new HomeKey(this);
        homeKey.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("server", "Settings server", NotificationManager.IMPORTANCE_MIN));
            Notification n = new Notification.Builder(this, "server").setSmallIcon(R.drawable.icon)
                    .setContentTitle("Lightkeeper").setContentText("Settings at port " + Lightkeeper.get(this).server.port()).build();
            startForeground(1, n);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        unregisterReceiver(screen);
        unregisterReceiver(packages);
        if (homeKey != null) homeKey.stop();
        Lightkeeper.get(this).server.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

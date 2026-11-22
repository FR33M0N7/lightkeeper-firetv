package local.lightkeeper.firetv;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONObject;

/** What webOS's applicationManager did: the apps the TV has, their icons, and opening them. */
final class Apps {
    static final class App {
        final String id, title;
        final ResolveInfo info;
        App(String id, String title, ResolveInfo info) { this.id = id; this.title = title; this.info = info; }
    }

    // webOS ids the Home page itself uses.
    static final String LG_HOME = "com.webos.app.home";
    static final String BROWSER = "com.webos.app.browser";

    // What Down on the lower row opens, as LG's Home on webOS: a launcher with every app,
    // Projectivy where it is installed, else Fire TV's own Home.
    private static final String FULL_LAUNCHER = "com.spocky.projengmenu";
    private static final ComponentName FIRE_HOME = new ComponentName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.HomeActivity");

    private final Context ctx;
    private Map<String, App> cache;
    private long cachedAt;

    Apps(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    /** The apps with a launcher entry, Fire TV's (leanback) before phone ones; cached for a minute. */
    synchronized Map<String, App> list() {
        if (cache != null && System.currentTimeMillis() - cachedAt < 60000) return cache;
        PackageManager pm = ctx.getPackageManager();
        Map<String, App> out = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName()) || out.containsKey(pkg)) continue;
                CharSequence label = r.loadLabel(pm);
                out.put(pkg, new App(pkg, label == null ? pkg : label.toString().trim(), r));
            }
        }
        cache = out;
        cachedAt = System.currentTimeMillis();
        return out;
    }

    synchronized void forget() { cache = null; }

    /** As appChoices: by title. */
    List<App> choices() {
        List<App> all = new ArrayList<>(list().values());
        Collections.sort(all, (a, b) -> a.title.toLowerCase(Locale.ROOT).compareTo(b.title.toLowerCase(Locale.ROOT)));
        return all;
    }

    /** An app's icon as a PNG, 192 px on its longer side, or null. */
    byte[] icon(String id) {
        App a = list().get(id);
        if (a == null) return null;
        try {
            Drawable d = a.info.loadIcon(ctx.getPackageManager());
            int w = Math.max(1, d.getIntrinsicWidth()), h = Math.max(1, d.getIntrinsicHeight());
            float s = 192f / Math.max(w, h);
            int bw = Math.max(1, Math.round(w * s)), bh = Math.max(1, Math.round(h * s));
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, bw, bh);
            d.draw(new Canvas(b));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } catch (Exception e) {
            Log.e("icon " + id, e);
            return null;
        }
    }

    /** applicationManager/launch: {id, params}. True when something opened. */
    boolean launch(String id, JSONObject params, int port) {
        try {
            String target = params == null ? null : params.optString("target", null);
            if (BROWSER.equals(id) && target != null) {
                // The gear opens the settings, which the server serves, in a page of the app's own.
                if (target.startsWith("http://127.0.0.1:" + port)) return start(new Intent(ctx, SettingsActivity.class).putExtra("url", target));
                return start(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
            }
            PackageManager pm = ctx.getPackageManager();
            if (LG_HOME.equals(id)) {
                Intent full = pm.getLeanbackLaunchIntentForPackage(FULL_LAUNCHER);
                return start(full != null ? full : new Intent(Intent.ACTION_MAIN).setComponent(FIRE_HOME));
            }
            Intent i = pm.getLeanbackLaunchIntentForPackage(id);
            if (i == null) i = pm.getLaunchIntentForPackage(id);
            if (i == null) { Log.i("no launch intent for " + id); return false; }
            return start(i);
        } catch (Exception e) {
            Log.e("launch " + id, e);
            return false;
        }
    }

    private boolean start(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e("start " + i, e);
            return false;
        }
    }
}

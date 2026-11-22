package local.lightkeeper.firetv;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The settings and the pictures kept for them: server.js's config, backgrounds, logos and PIN. */
final class Store {
    static final String VERSION = "1.1.1";
    static final int DEFAULT_PORT = 8090;
    static final int MAX_TILES = 50;
    static final long MIN_FREE = 64L * 1024 * 1024;

    static final Pattern ID_RE = Pattern.compile("^[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$");
    static final Pattern MINE_ID = Pattern.compile("^my-[0-9a-f]{8}$");
    static final Pattern LOGO_ID = Pattern.compile("^up-[0-9a-f]{8}$");
    static final Pattern WEB_ID = Pattern.compile("^web\\.[a-z0-9]{1,16}$");
    static final Pattern WEB_URL = Pattern.compile("^https?://([A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+\\])(:\\d{1,5})?([/?#][^\\s<>\"'\\\\]*)?$");
    static final Pattern INPUT = Pattern.compile("^com\\.webos\\.app\\.(hdmi|livetv$)");

    static final List<String> CLOCK_STYLES = Arrays.asList("thin", "classic", "bold", "mono", "rounded", "digital");
    static final List<String> CLOCK_SIZES = Arrays.asList("small", "medium", "large");
    static final List<String> DEVICES = Arrays.asList("ps5", "ps4", "ps3", "ps2", "ps1", "xboxseries", "xbox", "xbox360", "switch2", "switch", "wiiu", "wii",
            "gamecube", "n64", "snes", "nes", "dreamcast", "saturn", "megadrive", "genesis", "atari", "steamdeck", "appletv",
            "shield", "chromecast", "googletv", "firetv", "roku", "console", "player", "tv", "hdmi");

    // The tiles a first start picks, those of them this Fire TV has, in this order.
    static final List<String> USUAL_APPS = Arrays.asList("com.netflix.ninja", "com.amazon.firebat", "org.smarttube.stable",
            "com.amazon.firetv.youtube", "com.disney.disneyplus", "com.plexapp.android", "com.spotify.tv.android");

    final Context ctx;
    final File data, mine, myLogosDir, haTokenFile, configFile, bgSessionFile;
    final List<String> presets = new ArrayList<>();
    final Set<String> catalog = new HashSet<>();
    final long started = System.currentTimeMillis();
    JSONObject config;
    private JSONObject bgSession;
    /** A new mark each time the screen comes on: a new random background. */
    volatile String sessionMark = "";

    Store(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        data = new File(this.ctx.getFilesDir(), "lightkeeper");
        mine = new File(data, "backgrounds");
        myLogosDir = new File(data, "logos");
        haTokenFile = new File(data, "ha-token");
        configFile = new File(data, "config.json");
        bgSessionFile = new File(data, "background-session.json");
        try {
            JSONArray idx = new JSONArray(asset("app/backgrounds/index.json"));
            for (int i = 0; i < idx.length(); i++) presets.add(idx.getJSONObject(i).getString("id"));
        } catch (Exception e) { presets.add("seascape"); }
        try {
            JSONArray c = new JSONArray(asset("app/catalog.json"));
            for (int i = 0; i < c.length(); i++) catalog.add(c.getString(i));
        } catch (Exception e) { Log.e("catalog.json", e); }
        config = load();
    }

    String asset(String name) throws IOException {
        return new String(assetBytes(name), StandardCharsets.UTF_8);
    }

    byte[] assetBytes(String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) { return readAll(in); }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return readAll(in); }
    }

    static void writeAtomic(File f, byte[] b) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) throw new IOException("cannot make " + dir);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(b); out.getFD().sync(); }
        if (!tmp.renameTo(f)) throw new IOException("cannot write " + f);
    }

    /* ---------- the config ---------- */

    JSONObject defaults() {
        try {
            return new JSONObject()
                    .put("clock", "24").put("clockStyle", "thin").put("clockSize", "medium").put("clockSide", "left")
                    .put("cityTime", false).put("units", "c").put("weather", JSONObject.NULL).put("tiles", JSONObject.NULL)
                    .put("tileStyle", "text").put("labels", "all").put("twoRows", false).put("rowBreak", JSONObject.NULL)
                    .put("openApps", false).put("startTile", "last").put("hideGear", false)
                    .put("preset", presets.contains("seascape") ? "seascape" : presets.get(0))
                    .put("background", new JSONObject().put("custom", false).put("rev", 0))
                    .put("mine", new JSONArray()).put("myLogos", new JSONObject()).put("randomBackground", false)
                    .put("backgroundPool", JSONObject.NULL).put("veil", 80).put("key", "home").put("openAtPowerOn", true)
                    .put("tvInput", false).put("port", DEFAULT_PORT).put("pin", JSONObject.NULL)
                    .put("haUrl", JSONObject.NULL).put("haShow", new JSONArray());
        } catch (JSONException e) { throw new RuntimeException(e); }
    }

    private JSONObject load() {
        JSONObject c = defaults();
        try {
            JSONObject saved = new JSONObject(new String(readFile(configFile), StandardCharsets.UTF_8));
            for (Iterator<String> it = c.keys(); it.hasNext(); ) {
                String k = it.next();
                if (saved.has(k)) c.put(k, saved.get(k));
            }
        } catch (Exception ignored) {}
        int p = c.optInt("port", DEFAULT_PORT);
        try { if (p < 1024 || p > 65535) c.put("port", DEFAULT_PORT); } catch (JSONException ignored) {}
        return c;
    }

    synchronized void save() throws IOException {
        try { writeAtomic(configFile, (config.toString(2) + "\n").getBytes(StandardCharsets.UTF_8)); }
        catch (JSONException e) { throw new IOException(e); }
    }

    /* ---------- backgrounds ---------- */

    File minePath(String id, boolean thumb) { return new File(mine, id + (thumb ? "-thumb" : "") + ".jpg"); }

    List<String> mineIds() {
        List<String> out = new ArrayList<>();
        JSONArray a = config.optJSONArray("mine");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            String id = a.optString(i, "");
            if (MINE_ID.matcher(id).matches() && minePath(id, false).exists()) out.add(id);
        }
        return out;
    }

    String newMineId() {
        String id;
        do { id = "my-" + hex(4); } while (minePath(id, false).exists());
        return id;
    }

    /** The background chosen: an uploaded picture while it is there, else one the app carries (as an asset path). */
    Object chosenFile() {
        JSONObject b = config.optJSONObject("background");
        String id = b == null ? "" : b.optString("id", "");
        if (b != null && b.optBoolean("custom") && MINE_ID.matcher(id).matches() && minePath(id, false).exists()) return minePath(id, false);
        String preset = config.optString("preset");
        return "app/backgrounds/" + (presets.contains(preset) ? preset : presets.get(0)) + ".jpg";
    }

    List<String> bgPool() {
        List<String> all = new ArrayList<>(presets);
        all.addAll(mineIds());
        JSONArray pool = config.optJSONArray("backgroundPool");
        if (pool == null) return all;
        List<String> out = new ArrayList<>();
        for (String b : all) for (int i = 0; i < pool.length(); i++) if (b.equals(pool.optString(i))) { out.add(b); break; }
        return out;
    }

    /** A background for each time the screen comes on, never the one of the time before. */
    synchronized String sessionBackground() {
        if (!config.optBoolean("randomBackground")) return null;
        List<String> pool = bgPool();
        if (pool.size() < 2) return null;
        if (bgSession == null) {
            try { bgSession = new JSONObject(new String(readFile(bgSessionFile), StandardCharsets.UTF_8)); }
            catch (Exception e) { bgSession = new JSONObject(); }
        }
        String now = bgSession.optString("now", null);
        if (sessionMark.equals(bgSession.optString("mark", null)) && now != null && pool.contains(now)) return now;
        List<String> others = new ArrayList<>(pool);
        others.remove(now);
        String pick = others.get(new SecureRandom().nextInt(others.size()));
        try {
            bgSession = new JSONObject().put("mark", sessionMark).put("now", pick);
            writeAtomic(bgSessionFile, (bgSession.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        Log.i("background for this time the screen is on: " + pick);
        return pick;
    }

    long freeSpace() { return data.exists() ? data.getUsableSpace() : ctx.getFilesDir().getUsableSpace(); }

    JSONArray mineSizes() throws JSONException {
        JSONArray out = new JSONArray();
        for (String id : mineIds()) out.put(new JSONObject().put("id", id).put("bytes", minePath(id, false).length() + minePath(id, true).length()));
        return out;
    }

    /* ---------- logos ---------- */

    File logoPath(String id) { return new File(myLogosDir, id + ".png"); }

    JSONObject myLogos() {
        JSONObject all = config.optJSONObject("myLogos"), out = new JSONObject();
        if (all == null) return out;
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String id = it.next();
            try { if (LOGO_ID.matcher(id).matches() && logoPath(id).exists()) out.put(id, all.get(id)); } catch (JSONException ignored) {}
        }
        return out;
    }

    String newLogoId() {
        String id;
        do { id = "up-" + hex(4); } while (logoPath(id).exists());
        return id;
    }

    /* ---------- PIN ---------- */

    private int pinFails;
    private long pinLockedUntil;

    synchronized boolean pinOk(String given) {
        JSONObject pin = config.optJSONObject("pin");
        if (pin == null) return true;
        if (System.currentTimeMillis() < pinLockedUntil) return false;
        given = given == null ? "" : given;
        if (!given.isEmpty() && MessageDigest.isEqual(pinHash(given, pin.optString("salt")).getBytes(StandardCharsets.UTF_8),
                pin.optString("hash").getBytes(StandardCharsets.UTF_8))) { pinFails = 0; return true; }
        if (!given.isEmpty() && ++pinFails >= 5) { pinFails = 0; pinLockedUntil = System.currentTimeMillis() + 60000; }
        return false;
    }

    boolean pinLocked() { return System.currentTimeMillis() < pinLockedUntil; }

    static String pinHash(String pin, String salt) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static String hex(int bytes) {
        byte[] r = new byte[bytes];
        new SecureRandom().nextBytes(r);
        StringBuilder b = new StringBuilder();
        for (byte x : r) b.append(String.format("%02x", x));
        return b.toString();
    }

    /* ---------- validation, as server.js's validate ---------- */

    static String str(Object v, int max) {
        if (!(v instanceof String)) return "";
        String s = ((String) v).replaceAll("[\\u0000-\\u001f\\u007f]", "").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    static boolean num(Object v, double min, double max) {
        if (!(v instanceof Number)) return false;
        double d = ((Number) v).doubleValue();
        return !Double.isNaN(d) && !Double.isInfinite(d) && d >= min && d <= max;
    }

    static boolean isInt(Object v) { return v instanceof Number && ((Number) v).doubleValue() % 1 == 0; }

    /** The cleaned change, or a String naming what is wrong. */
    Object validate(JSONObject body) throws JSONException {
        JSONObject out = new JSONObject();
        for (Iterator<String> it = body.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = body.get(k);
            switch (k) {
                case "clock": if (!"24".equals(v) && !"12".equals(v)) return k; out.put(k, v); break;
                case "clockStyle": if (!CLOCK_STYLES.contains(v)) return k; out.put(k, v); break;
                case "clockSize": if (!CLOCK_SIZES.contains(v)) return k; out.put(k, v); break;
                case "clockSide": if (!"left".equals(v) && !"right".equals(v)) return k; out.put(k, v); break;
                case "units": if (!"c".equals(v) && !"f".equals(v)) return k; out.put(k, v); break;
                case "tileStyle": if (!Arrays.asList("text", "logo", "icon").contains(v)) return k; out.put(k, v); break;
                case "labels": if (!Arrays.asList("all", "selected", "none").contains(v)) return k; out.put(k, v); break;
                case "startTile": if (!"last".equals(v) && !"first".equals(v)) return k; out.put(k, v); break;
                case "cityTime": case "twoRows": case "openAtPowerOn": case "tvInput": case "openApps":
                case "hideGear": case "randomBackground":
                    if (!(v instanceof Boolean)) return k; out.put(k, v); break;
                case "rowBreak":
                    if (v != JSONObject.NULL && (!isInt(v) || !num(v, 0, MAX_TILES))) return k;
                    out.put(k, v == JSONObject.NULL ? JSONObject.NULL : ((Number) v).intValue()); break;
                case "veil": if (!num(v, 0, 100)) return k; out.put(k, Math.round(((Number) v).doubleValue())); break;
                case "port":
                    if (!isInt(v) || !num(v, 1024, 65535)) return k; out.put(k, ((Number) v).intValue()); break;
                case "preset": if (!presets.contains(v) && !mineIds().contains(v)) return k; out.put(k, v); break;
                case "backgroundPool": {
                    if (v == JSONObject.NULL) { out.put(k, JSONObject.NULL); break; }
                    List<String> ids = new ArrayList<>(presets);
                    ids.addAll(mineIds());
                    if (!(v instanceof JSONArray)) return k;
                    JSONArray a = (JSONArray) v;
                    if (a.length() > ids.size()) return k;
                    Set<String> seen = new HashSet<>();
                    for (int i = 0; i < a.length(); i++) {
                        Object b = a.get(i);
                        if (!(b instanceof String) || !ids.contains(b) || !seen.add((String) b)) return k;
                    }
                    out.put(k, a); break;
                }
                // Only Home: Fire TV has no GlassHouse key filter; Launcher Manager hands Home over.
                case "key": if (!"none".equals(v) && !"home".equals(v)) return k; out.put(k, v); break;
                case "weather": {
                    if (v == JSONObject.NULL) { out.put(k, JSONObject.NULL); break; }
                    if (!(v instanceof JSONObject)) return k;
                    JSONObject w = (JSONObject) v;
                    Object lat = w.opt("lat"), lon = w.opt("lon");
                    if (!num(lat, -90, 90) || !num(lon, -180, 180) || str(w.opt("name"), 80).isEmpty()) return k;
                    out.put(k, new JSONObject().put("name", str(w.opt("name"), 80)).put("lat", lat).put("lon", lon));
                    break;
                }
                case "tiles": {
                    if (!(v instanceof JSONArray) || ((JSONArray) v).length() > MAX_TILES) return k;
                    JSONArray a = (JSONArray) v, tiles = new JSONArray();
                    Set<String> seen = new HashSet<>();
                    JSONObject mineLogos = myLogos();
                    for (int i = 0; i < a.length(); i++) {
                        if (!(a.get(i) instanceof JSONObject)) return k;
                        JSONObject t = a.getJSONObject(i);
                        Object id = t.opt("id");
                        if (!(id instanceof String) || !ID_RE.matcher((String) id).matches() || !seen.add((String) id) || str(t.opt("title"), 40).isEmpty()) return k;
                        JSONObject tile = new JSONObject().put("id", id).put("title", str(t.opt("title"), 40));
                        if (t.has("device")) {
                            if (!INPUT.matcher((String) id).find() || !DEVICES.contains(t.opt("device"))) return k;
                            tile.put("device", t.get("device"));
                        }
                        boolean web = WEB_ID.matcher((String) id).matches();
                        if (web || t.has("url")) {
                            Object u = t.opt("url");
                            if (!web || !(u instanceof String) || ((String) u).length() > 500 || !WEB_URL.matcher((String) u).matches()) return k;
                            tile.put("url", u);
                        }
                        Object logo = t.opt("logo");
                        if (logo instanceof String && (catalog.contains(logo) || mineLogos.has((String) logo))) tile.put("logo", logo);
                        tiles.put(tile);
                    }
                    out.put(k, tiles); break;
                }
                default: return k;
            }
        }
        return out;
    }

    /** The tiles whose app is gone: the Home leaves them out, the settings keep them. */
    static JSONArray appsGone(JSONArray tiles, Map<String, Apps.App> apps) {
        JSONArray out = new JSONArray();
        if (tiles == null || apps == null) return out;
        for (int i = 0; i < tiles.length(); i++) {
            JSONObject t = tiles.optJSONObject(i);
            String id = t == null ? "" : t.optString("id");
            if (!apps.containsKey(id) && !INPUT.matcher(id).find() && !id.startsWith("web.") && !id.startsWith("ha.")) out.put(id);
        }
        return out;
    }
}

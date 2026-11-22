package local.lightkeeper.firetv;

import android.util.Base64;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/** server.js's handle(), for Fire TV: the settings page, the Home's config, icons, pictures, HA. */
final class Server implements Http.Handler {
    private static final int MAX_JSON = 64 * 1024;
    private static final int MAX_IMAGE = 12 * 1024 * 1024;
    private static final int MAX_THUMB = 512 * 1024;
    private static final int MAX_LOGO = 1024 * 1024;
    private static final int MAX_LOGOS = 50;

    // Sections of the settings page that are webOS's only: the remote key through GlassHouse,
    // power on, the TV's inputs and screen saver; updates from GitHub's .ipk; uninstall.
    private static final String FIRE_CSS = "<style id=\"firetv\">" +
            "section[aria-labelledby=\"h-remote\"],section[aria-labelledby=\"h-update\"],section[aria-labelledby=\"h-remove\"]{display:none!important}" +
            "</style>";
    private static final String SETTINGS_CSP = "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data: blob:; connect-src 'self' https://geocoding-api.open-meteo.com https://api.open-meteo.com; frame-ancestors 'none'";

    private final Store store;
    private final Apps apps;
    private final Ha ha;
    private final Http http;
    private String settingsHtml;

    Server(Store store, Apps apps) {
        this.store = store;
        this.apps = apps;
        this.ha = new Ha(store);
        this.http = new Http(this);
    }

    /** On the port in the config, else the next free one up to ten on, as server.js does. */
    void start() throws IOException {
        int want = store.config.optInt("port", Store.DEFAULT_PORT);
        IOException last = null;
        for (int p = want; p < want + 10; p++) {
            try { http.listen(p); Log.i("settings at port " + p); return; } catch (IOException e) { last = e; }
        }
        throw last;
    }

    void stop() { http.close(); }

    int port() { return http.port(); }

    /* ---------- helpers ---------- */

    private static Http.Res json(int status, Object... kv) throws Exception {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1] == null ? JSONObject.NULL : kv[i + 1]);
        return Http.Res.json(status, o);
    }

    private static String type(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".js")) return "text/javascript; charset=utf-8";
        return "application/octet-stream";
    }

    private Http.Res asset(String path, String cache) throws Exception {
        byte[] b;
        try { b = store.assetBytes(path); } catch (IOException e) { return json(404, "ok", false, "error", "not found"); }
        Http.Res r = new Http.Res(200, b, type(path));
        if (cache != null) r.h("Cache-Control", cache);
        return r;
    }

    private Http.Res file(File f, String cache) throws Exception {
        byte[] b;
        try { b = Store.readFile(f); } catch (IOException e) { return json(404, "ok", false, "error", "not found"); }
        Http.Res r = new Http.Res(200, b, type(f.getName()));
        if (cache != null) r.h("Cache-Control", cache);
        return r;
    }

    static String lanAddress() {
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            String[] order = {"eth", "wlan", ""};
            for (String prefix : order) for (NetworkInterface n : all) {
                if (!n.isUp() || n.isLoopback() || !n.getName().startsWith(prefix)) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static boolean isLocal(String addr) {
        addr = addr == null ? "" : addr.replaceFirst("^::ffff:", "");
        Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)$").matcher(addr);
        if (m.matches()) {
            int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31) || (a == 169 && b == 254);
        }
        String l = addr.toLowerCase(Locale.ROOT);
        return l.equals("::1") || l.equals("0:0:0:0:0:0:0:1") || l.startsWith("fc") || l.startsWith("fd") ||
                l.matches("^fe[89ab].*");
    }

    /** An IP literal or localhost only: a name rebound to the TV's address is refused. */
    private static boolean hostOk(Http.Req req) {
        String h = String.valueOf(req.header("host")).toLowerCase(Locale.ROOT);
        Matcher m = Pattern.compile("^\\[([^\\]]+)\\](:\\d+)?$").matcher(h);
        h = m.matches() ? m.group(1) : h.replaceFirst(":\\d+$", "");
        return h.equals("localhost") || h.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") || h.contains(":");
    }

    private static boolean loopback(Http.Req req) {
        String a = req.remote == null ? "" : req.remote.replaceFirst("^::ffff:", "");
        return a.equals("127.0.0.1") || a.equals("::1") || a.equals("0:0:0:0:0:0:0:1");
    }

    /** From the Home: this device, by a file:// page or one on its own servers. */
    private static boolean fromHome(Http.Req req) {
        String o = req.header("origin");
        return loopback(req) && (o == null || o.equals("null") || o.startsWith("file://") ||
                o.matches("^http://(127\\.0\\.0\\.1|localhost)(:\\d+)?$"));
    }

    private static String mime(Http.Req req) {
        String t = req.header("content-type");
        return t == null ? "" : t.split(";")[0].trim().toLowerCase(Locale.ROOT);
    }

    private static boolean postOk(Http.Req req, String type) {
        if (!mime(req).equals(type)) return false;
        String origin = req.header("origin");
        return origin == null || origin.replaceFirst("^https?://", "").equals(req.header("host"));
    }

    private static JSONObject readJson(Http.Req req) throws Exception {
        byte[] b = req.body(MAX_JSON);
        if (b == null) return null;
        try { return new JSONObject(new String(b, StandardCharsets.UTF_8)); } catch (Exception e) { return new JSONObject().put("__bad", true); }
    }

    private static int u16(byte[] b, int i) { return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff); }
    private static long u32(byte[] b, int i) { return ((long) u16(b, i) << 16) | u16(b, i + 2); }

    /** The size of a JPEG from its first frame header, or null. */
    static int[] jpegSize(byte[] b) {
        if (b.length < 4 || (b[0] & 0xff) != 0xFF || (b[1] & 0xff) != 0xD8) return null;
        int i = 2;
        while (i + 9 < b.length) {
            if ((b[i] & 0xff) != 0xFF) return null;
            int m = b[i + 1] & 0xff;
            if (m == 0xFF) { i++; continue; }
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { i += 2; continue; }
            if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return new int[]{u16(b, i + 7), u16(b, i + 5)};
            i += 2 + u16(b, i + 2);
        }
        return null;
    }

    private static final byte[] PNG_SIG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /** The size of a PNG with alpha, or null. */
    static long[] pngSize(byte[] b) {
        if (b == null || b.length < 8 + 25 + 12) return null;
        for (int i = 0; i < 8; i++) if (b[i] != PNG_SIG[i]) return null;
        if (u32(b, 8) != 13 || !new String(b, 12, 4, StandardCharsets.US_ASCII).equals("IHDR")) return null;
        if (!new String(b, b.length - 8, 4, StandardCharsets.US_ASCII).equals("IEND")) return null;
        if (b[25] != 4 && b[25] != 6) return null;
        return new long[]{u32(b, 16), u32(b, 20)};
    }

    /* ---------- config ---------- */

    /** A first start picks the usual apps this TV has, or its first few. */
    private void pickTiles() throws Exception {
        if (store.config.opt("tiles") != JSONObject.NULL && store.config.optJSONArray("tiles") != null) return;
        Map<String, Apps.App> all = apps.list();
        if (all.isEmpty()) return;
        JSONArray tiles = new JSONArray();
        for (String id : Store.USUAL_APPS) if (all.containsKey(id)) tiles.put(new JSONObject().put("id", id).put("title", Store.str(all.get(id).title, 40)));
        if (tiles.length() == 0) for (Apps.App a : apps.choices()) {
            if (tiles.length() >= 5) break;
            tiles.put(new JSONObject().put("id", a.id).put("title", Store.str(a.title, 40)));
        }
        store.config.put("tiles", tiles);
        store.save();
        Log.i("first start: " + tiles.length() + " tiles");
    }

    JSONObject publicConfig() throws Exception {
        JSONObject c = new JSONObject(store.config.toString());
        c.remove("pin");
        c.put("pinSet", store.config.optJSONObject("pin") != null);
        c.put("version", Store.VERSION);
        c.put("address", lanAddress());
        c.put("port", port());
        c.put("portTaken", port() != store.config.optInt("port", Store.DEFAULT_PORT) ? store.config.optInt("port") : 0);
        c.put("saverPausedUntil", 0);
        c.put("started", store.started);
        String bg = store.sessionBackground();
        c.put("backgroundNow", bg == null ? JSONObject.NULL : bg);
        c.put("inputStatus", "off");
        c.put("appsGone", Store.appsGone(store.config.optJSONArray("tiles"), apps.list()));
        return c;
    }

    /* ---------- requests ---------- */

    @Override
    public Http.Res handle(Http.Req req) throws Exception {
        if (!isLocal(req.remote)) return json(403, "ok", false, "error", "local network only");
        if (!hostOk(req)) return json(403, "ok", false, "error", "use the TV's address");
        String p = req.path, m = req.method;
        Matcher mm;

        if (m.equals("GET") && (p.equals("/") || p.equals("/settings"))) {
            if (settingsHtml == null) settingsHtml = store.asset("app/service/settings.html").replaceFirst("</head>", FIRE_CSS + "</head>");
            return new Http.Res(200, settingsHtml.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8")
                    .h("Content-Security-Policy", SETTINGS_CSP).h("X-Frame-Options", "DENY");
        }
        if (m.equals("GET") && p.equals("/logos.js")) {
            // With the Fire TV package names, so the settings' preview shows the Home's logos.
            byte[] js = (store.asset("app/logos.js") + "\n" + store.asset("app/fire-logos.js")).getBytes(StandardCharsets.UTF_8);
            return new Http.Res(200, js, "text/javascript; charset=utf-8").h("Cache-Control", "no-cache");
        }
        if (m.equals("GET") && p.matches("^/logos/[a-z0-9]+\\.png$")) return asset("app" + p, "max-age=86400");
        if (m.equals("GET") && p.matches("^/jost(-[235]00)?\\.woff2$")) return asset("app" + p, "max-age=86400");
        if (m.equals("GET") && p.matches("^/clock/(classic|mono|rounded|digital)\\.woff2$")) return asset("app" + p, "max-age=86400");
        if (m.equals("GET") && p.matches("^/backgrounds/((thumbs/)?[a-z0-9-]+\\.jpg|index\\.json)$")) {
            return asset("app" + p, p.endsWith(".json") ? "no-cache" : "max-age=86400");
        }
        if (m.equals("GET") && p.equals("/background.jpg")) {
            Object f = store.chosenFile();
            return f instanceof File ? file((File) f, "max-age=31536000") : asset((String) f, "max-age=31536000");
        }
        if (m.equals("GET") && (mm = Pattern.compile("^/mine/(my-[0-9a-f]{8})(-thumb)?\\.jpg$").matcher(p)).matches()) {
            boolean thumb = mm.group(2) != null && store.minePath(mm.group(1), true).exists();
            return file(store.minePath(mm.group(1), thumb), mm.group(2) != null && !thumb ? "no-store" : "max-age=31536000");
        }
        if (m.equals("GET") && (mm = Pattern.compile("^/mylogos/(up-[0-9a-f]{8})\\.png$").matcher(p)).matches()) {
            return file(store.logoPath(mm.group(1)), "max-age=31536000").h("Content-Security-Policy", "default-src 'none'");
        }
        if (m.equals("GET") && p.equals("/icon")) {
            String id = req.param("id");
            if (id == null || !Store.ID_RE.matcher(id).matches()) return json(400, "ok", false, "error", "bad id");
            byte[] png = apps.icon(id);
            if (png == null) return json(404, "ok", false, "error", "no icon");
            if ("1".equals(req.param("data"))) {
                return json(200, "ok", true, "src", "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP))
                        .h("Access-Control-Allow-Origin", "*");
            }
            return new Http.Res(200, png, "image/png").h("Cache-Control", "max-age=86400").h("Content-Security-Policy", "default-src 'none'");
        }
        if (m.equals("GET") && p.equals("/api/config")) {
            pickTiles();
            JSONObject c = publicConfig();
            c.put("keyStatus", "off");
            return Http.Res.json(200, c).h("Access-Control-Allow-Origin", "*");
        }
        if (p.equals("/api/open") || p.equals("/api/close")) {
            if (!fromHome(req)) return json(403, "ok", false, "error", "the Home only");
            Http.Res r;
            // Fire OS does not let an app list or close others: the open apps button stays empty.
            if (m.equals("GET") && p.equals("/api/open")) r = json(200, "ok", true, "apps", new JSONArray());
            else if (m.equals("OPTIONS")) r = Http.Res.empty(204).h("Access-Control-Allow-Methods", "POST")
                    .h("Access-Control-Allow-Headers", "Content-Type").h("Access-Control-Max-Age", "600");
            else if (m.equals("POST")) r = json(200, "ok", true, "closed", new JSONArray(), "apps", new JSONArray());
            else r = json(405, "ok", false, "error", "not allowed");
            return cors(req, r);
        }
        if (p.equals("/api/ha/states") || p.equals("/api/ha/press")) return haHome(req, m, p);
        if (!p.startsWith("/api/")) return json(404, "ok", false, "error", "not found");
        if (!store.pinOk(req.header("x-lightkeeper-pin"))) return json(401, "ok", false, "error", store.pinLocked() ? "locked" : "pin");
        if (p.equals("/api/ha") || p.equals("/api/ha/entities")) return haSettings(req, m, p);

        if (m.equals("GET") && p.equals("/api/tv")) {
            JSONArray list = new JSONArray();
            for (Apps.App a : apps.choices()) list.put(new JSONObject().put("id", a.id).put("title", a.title));
            return json(200, "ok", true, "apps", list, "inputs", new JSONArray(), "keyStatus", "off",
                    "keys", new JSONArray(), "glasshouse", false);
        }
        if (m.equals("POST") && p.equals("/api/config")) return postConfig(req);
        if (m.equals("GET") && p.equals("/api/backgrounds")) {
            return json(200, "ok", true, "mine", store.mineSizes(), "free", store.freeSpace(), "keepFree", Store.MIN_FREE);
        }
        if (m.equals("POST") && p.equals("/api/background")) {
            if (!postOk(req, "image/jpeg")) return json(415, "ok", false, "error", "a JPEG is expected");
            byte[] b = req.body(MAX_IMAGE);
            if (b == null) return json(413, "ok", false, "error", "too large");
            int[] size = jpegSize(b);
            if (size == null || size[0] < 640 || size[0] > 3840 || size[1] > 2160) return json(400, "ok", false, "error", "a JPEG up to 3840 × 2160 is expected");
            long free = store.freeSpace();
            if (free - b.length < Store.MIN_FREE) return json(507, "ok", false, "error", "space", "free", free, "keepFree", Store.MIN_FREE);
            synchronized (store) {
                String id = store.newMineId();
                Store.writeAtomic(store.minePath(id, false), b);
                JSONArray mine = new JSONArray(store.mineIds());
                store.config.put("mine", mine);
                store.config.put("background", new JSONObject().put("custom", true).put("id", id).put("rev", rev() + 1));
                store.save();
                return json(200, "ok", true, "id", id, "config", publicConfig());
            }
        }
        String tid = req.param("id");
        if (tid != null && !Store.MINE_ID.matcher(tid).matches()) tid = null;
        if (m.equals("POST") && p.equals("/api/background/thumb")) {
            if (!postOk(req, "image/jpeg")) return json(415, "ok", false, "error", "a JPEG is expected");
            if (tid == null || !store.mineIds().contains(tid)) return json(404, "ok", false, "error", "no such picture");
            byte[] b = req.body(MAX_THUMB);
            int[] size = b == null ? null : jpegSize(b);
            if (size == null || size[0] > 640) return json(400, "ok", false, "error", "a JPEG up to 640 wide is expected");
            Store.writeAtomic(store.minePath(tid, true), b);
            return json(200, "ok", true);
        }
        if (m.equals("DELETE") && p.equals("/api/background")) {
            synchronized (store) {
                JSONObject bg = store.config.getJSONObject("background");
                String gone = tid != null ? tid : bg.optBoolean("custom") ? bg.optString("id", null) : null;
                if (gone != null) {
                    //noinspection ResultOfMethodCallIgnored
                    store.minePath(gone, false).delete();
                    //noinspection ResultOfMethodCallIgnored
                    store.minePath(gone, true).delete();
                    store.config.put("mine", new JSONArray(store.mineIds()));
                    JSONArray pool = store.config.optJSONArray("backgroundPool");
                    if (pool != null) {
                        JSONArray kept = new JSONArray();
                        for (int i = 0; i < pool.length(); i++) if (!gone.equals(pool.optString(i))) kept.put(pool.optString(i));
                        store.config.put("backgroundPool", kept);
                    }
                }
                if (bg.optBoolean("custom") && (gone == null || gone.equals(bg.optString("id")))) {
                    store.config.put("background", new JSONObject().put("custom", false).put("rev", rev() + 1));
                }
                store.save();
                return json(200, "ok", true, "config", publicConfig());
            }
        }
        if (m.equals("POST") && p.equals("/api/logo")) {
            if (!postOk(req, "image/png")) return json(415, "ok", false, "error", "a PNG is expected");
            byte[] b = req.body(MAX_LOGO);
            if (b == null) return json(413, "ok", false, "error", "too large");
            long[] size = pngSize(b);
            if (size == null || size[0] == 0 || size[1] == 0 || size[0] > 2000 || size[1] > 240) {
                return json(400, "ok", false, "error", "a PNG with transparency up to 2000 × 240 is expected");
            }
            synchronized (store) {
                JSONObject logos = store.myLogos();
                if (logos.length() >= MAX_LOGOS) return json(409, "ok", false, "error", "logos", "max", MAX_LOGOS);
                long free = store.freeSpace();
                if (free - b.length < Store.MIN_FREE) return json(507, "ok", false, "error", "space", "free", free, "keepFree", Store.MIN_FREE);
                String id = store.newLogoId();
                Store.writeAtomic(store.logoPath(id), b);
                logos.put(id, new JSONArray().put(size[0]).put(size[1]));
                store.config.put("myLogos", logos);
                store.save();
                return json(200, "ok", true, "id", id, "config", publicConfig());
            }
        }
        if (m.equals("DELETE") && p.equals("/api/logo")) {
            String lid = req.param("id");
            if (lid == null || !Store.LOGO_ID.matcher(lid).matches()) return json(400, "ok", false, "error", "bad id");
            synchronized (store) {
                //noinspection ResultOfMethodCallIgnored
                store.logoPath(lid).delete();
                store.config.put("myLogos", store.myLogos());
                JSONArray tiles = store.config.optJSONArray("tiles");
                if (tiles != null) for (int i = 0; i < tiles.length(); i++) {
                    JSONObject t = tiles.optJSONObject(i);
                    if (t != null && lid.equals(t.optString("logo"))) t.remove("logo");
                }
                store.save();
                return json(200, "ok", true, "config", publicConfig());
            }
        }
        if (m.equals("POST") && p.equals("/api/screensaver")) {
            JSONObject body = readJson(req);
            if (body == null || !(body.opt("pause") instanceof Boolean)) return json(400, "ok", false, "error", "pause expected");
            return json(200, "ok", true, "config", publicConfig());
        }
        if (m.equals("POST") && p.equals("/api/pin")) {
            if (!postOk(req, "application/json")) return json(415, "ok", false, "error", "bad request");
            JSONObject body = readJson(req);
            Object pin = body == null ? null : body.opt("pin");
            synchronized (store) {
                if ("".equals(pin)) store.config.put("pin", JSONObject.NULL);
                else if (pin instanceof String && ((String) pin).matches("^\\d{4,8}$")) {
                    String salt = Store.hex(16);
                    store.config.put("pin", new JSONObject().put("salt", salt).put("hash", Store.pinHash((String) pin, salt)));
                } else return json(400, "ok", false, "error", "a PIN is 4 to 8 digits");
                store.save();
            }
            return json(200, "ok", true, "config", publicConfig());
        }
        if (m.equals("GET") && p.equals("/api/update")) {
            return json(200, "ok", true, "current", Store.VERSION, "latest", null, "error", null, "available", false, "status", null, "test", false);
        }
        if (m.equals("POST") && (p.equals("/api/update") || p.equals("/api/uninstall"))) {
            return json(409, "ok", false, "error", "on Fire TV, install or remove the APK instead");
        }
        return json(404, "ok", false, "error", "not found");
    }

    private int rev() { JSONObject b = store.config.optJSONObject("background"); return b == null ? 0 : b.optInt("rev", 0); }

    private static Http.Res cors(Http.Req req, Http.Res r) {
        String o = req.header("origin");
        if (o != null) r.h("Access-Control-Allow-Origin", o).h("Vary", "Origin");
        return r;
    }

    private Http.Res postConfig(Http.Req req) throws Exception {
        if (!postOk(req, "application/json")) return json(415, "ok", false, "error", "bad request");
        JSONObject body = readJson(req);
        if (body == null) return json(413, "ok", false, "error", "too large");
        if (body.has("__bad")) return json(400, "ok", false, "error", "malformed JSON");
        synchronized (store) {
            Object v = store.validate(body);
            if (v instanceof String) return json(400, "ok", false, "error", "invalid: " + v);
            JSONObject change = (JSONObject) v;
            if (change.has("weather") && change.get("weather") == JSONObject.NULL) change.put("cityTime", false);
            if (Boolean.TRUE.equals(change.opt("cityTime"))) {
                Object w = change.has("weather") ? change.get("weather") : store.config.opt("weather");
                if (w == null || w == JSONObject.NULL) return json(400, "ok", false, "error", "the clock in the city's time needs a city for the weather");
            }
            if (change.has("port") && change.getInt("port") != port()) {
                try { http.listen(change.getInt("port")); Log.i("settings moved to port " + change.getInt("port")); }
                catch (IOException e) { return json(409, "ok", false, "error", "port " + change.getInt("port") + " is taken"); }
            }
            String mine = change.has("preset") && Store.MINE_ID.matcher(change.optString("preset")).matches() ? change.getString("preset") : null;
            if (mine != null) change.remove("preset");
            for (Iterator<String> it = change.keys(); it.hasNext(); ) { String k = it.next(); store.config.put(k, change.get(k)); }
            if (mine != null) store.config.put("background", new JSONObject().put("custom", true).put("id", mine).put("rev", rev() + 1));
            else if (change.has("preset") && store.config.getJSONObject("background").optBoolean("custom")) {
                store.config.put("background", new JSONObject().put("custom", false).put("rev", rev() + 1));
            }
            store.save();
            return json(200, "ok", true, "config", publicConfig(), "keyStatus", "off");
        }
    }

    /* ---------- Home Assistant ---------- */

    private Http.Res haHome(Http.Req req, String m, String p) throws Exception {
        if (!fromHome(req)) return json(403, "ok", false, "error", "the Home only");
        if (m.equals("GET") && p.equals("/api/ha/states")) return cors(req, json(200, "ok", true, "states", ha.states()));
        if (m.equals("OPTIONS") && p.equals("/api/ha/press")) {
            return cors(req, Http.Res.empty(204).h("Access-Control-Allow-Methods", "POST")
                    .h("Access-Control-Allow-Headers", "Content-Type").h("Access-Control-Max-Age", "600"));
        }
        if (m.equals("POST") && p.equals("/api/ha/press")) {
            if (!mime(req).equals("application/json")) return cors(req, json(415, "ok", false, "error", "bad request"));
            JSONObject body = readJson(req);
            String id = body == null ? "" : body.optString("id", "");
            boolean tiled = false;
            JSONArray tiles = store.config.optJSONArray("tiles");
            if (tiles != null) for (int i = 0; i < tiles.length(); i++) {
                JSONObject t = tiles.optJSONObject(i);
                if (t != null && id.equals(Ha.tile(t.optString("id")))) tiled = true;
            }
            if (!tiled) return cors(req, json(400, "ok", false, "error", "not a tile"));
            String token = ha.token(), url = store.config.optString("haUrl", "");
            if (url.isEmpty() || "null".equals(url) || token.isEmpty()) return cors(req, json(409, "ok", false, "error", "Home Assistant is off"));
            String domain = id.split("\\.")[0];
            List<String> path = new ArrayList<>();
            path.add("/api/services/" + domain + "/" + Ha.PRESS.get(domain));
            Ha.Answer r = Ha.ask(url, token, path, new JSONObject().put("entity_id", id)).get(0);
            boolean ok = r.code == 200;
            ha.note(ok ? "answering" : Ha.error(r.code));
            return cors(req, json(ok ? 200 : 502, "ok", ok, "states", ha.states(), "error", ok ? null : Ha.error(r.code)));
        }
        return cors(req, json(405, "ok", false, "error", "not allowed"));
    }

    private Http.Res haSettings(Http.Req req, String m, String p) throws Exception {
        if (m.equals("GET") && p.equals("/api/ha/entities")) {
            String token = ha.token(), url = store.config.optString("haUrl", "");
            if (url.isEmpty() || "null".equals(url) || token.isEmpty()) return json(409, "ok", false, "error", "Home Assistant is off");
            List<String> path = new ArrayList<>();
            path.add("/api/states");
            Ha.Answer r = Ha.ask(url, token, path, null).get(0);
            JSONArray list = null;
            if (r.code == 200) try { list = new JSONArray(r.body); } catch (Exception ignored) {}
            if (list == null) return json(502, "ok", false, "error", Ha.error(r.code == 200 ? 0 : r.code));
            List<String> ids = ha.ids();
            JSONObject states = new JSONObject();
            JSONArray entities = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject e = list.optJSONObject(i);
                String eid = e == null ? "" : e.optString("entity_id", "");
                if (!Ha.ENTITY.matcher(eid).matches()) continue;
                JSONObject en = Ha.entity(e);
                if (ids.contains(eid)) states.put(eid, en);
                entities.put(new JSONObject().put("id", eid).put("n", en.optString("n")));
            }
            return json(200, "ok", true, "entities", entities, "states", states);
        }
        if (!m.equals("POST") || !p.equals("/api/ha")) return json(404, "ok", false, "error", "not found");
        if (!postOk(req, "application/json")) return json(415, "ok", false, "error", "bad request");
        JSONObject body = readJson(req);
        if (body == null) return json(413, "ok", false, "error", "too large");
        if (body.has("__bad")) return json(400, "ok", false, "error", "malformed JSON");
        if (body.has("show")) {
            Object v = body.get("show");
            if (!(v instanceof JSONArray) || ((JSONArray) v).length() > Ha.SHOW) return json(400, "ok", false, "error", "invalid: show");
            JSONArray a = (JSONArray) v, show = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null || !Ha.ENTITY.matcher(e.optString("id", "")).matches()) return json(400, "ok", false, "error", "invalid: show");
                show.put(new JSONObject().put("id", e.getString("id")).put("name", Store.str(e.opt("name"), 40)));
            }
            synchronized (store) { store.config.put("haShow", show); store.save(); }
            return json(200, "ok", true, "config", publicConfig());
        }
        if (body.has("url") && body.get("url") == JSONObject.NULL) {
            synchronized (store) {
                ha.dropToken();
                store.config.put("haUrl", JSONObject.NULL);
                store.config.put("haShow", new JSONArray());
                JSONArray tiles = store.config.optJSONArray("tiles");
                if (tiles != null) {
                    JSONArray kept = new JSONArray();
                    for (int i = 0; i < tiles.length(); i++) if (!tiles.getJSONObject(i).optString("id").startsWith("ha.")) kept.put(tiles.get(i));
                    store.config.put("tiles", kept);
                }
                store.save();
            }
            Log.i("home assistant: off");
            return json(200, "ok", true, "config", publicConfig());
        }
        String url = body.opt("url") instanceof String ? body.getString("url").replaceAll("^\\s+|/*\\s*$", "") : "";
        if (!Ha.URL_RE.matcher(url).matches()) return json(400, "ok", false, "error", "address");
        String given = body.opt("token") instanceof String ? body.getString("token").replaceAll("\\s+", "") : "";
        if (!given.isEmpty() && !Ha.TOKEN_RE.matcher(given).matches()) return json(400, "ok", false, "error", "token");
        if (given.isEmpty() && !url.equals(store.config.optString("haUrl"))) return json(400, "ok", false, "error", "token needed");
        String token = given.isEmpty() ? ha.token() : given;
        if (token.isEmpty()) return json(400, "ok", false, "error", "token needed");
        List<String> path = new ArrayList<>();
        path.add("/api/");
        Ha.Answer r = Ha.ask(url, token, path, null).get(0);
        if (r.code != 200) return json(502, "ok", false, "error", Ha.error(r.code));
        synchronized (store) {
            if (!given.isEmpty()) ha.saveToken(given);
            store.config.put("haUrl", url);
            store.save();
        }
        Log.i("home assistant: connected to " + url);
        return json(200, "ok", true, "config", publicConfig());
    }
}

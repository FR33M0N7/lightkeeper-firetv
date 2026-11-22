package local.lightkeeper.firetv;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/** Home Assistant, as server.js asks it: values beside the clock, tiles that switch things. */
final class Ha {
    static final int TIMEOUT = 4000;
    static final int MAX = 8 * 1024 * 1024;
    static final int SHOW = 4;
    static final Pattern ENTITY = Pattern.compile("^[a-z0-9_]+\\.[a-z0-9_]+$");
    static final Pattern URL_RE = Pattern.compile("^https?://([A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+\\])(:\\d{1,5})?$");
    static final Pattern TOKEN_RE = Pattern.compile("^[A-Za-z0-9._~+/=-]{20,2000}$");
    static final Map<String, String> PRESS = new HashMap<>();
    static {
        PRESS.put("light", "toggle"); PRESS.put("switch", "toggle"); PRESS.put("input_boolean", "toggle");
        PRESS.put("scene", "turn_on"); PRESS.put("script", "turn_on");
    }
    private static final Pattern TILE = Pattern.compile("^ha\\.(([a-z0-9_]+)\\.[a-z0-9_]+)$");

    static final class Answer { int code; String body = ""; }

    private final Store store;
    private String last = "";

    Ha(Store store) { this.store = store; }

    String token() {
        try { return new String(Store.readFile(store.haTokenFile), StandardCharsets.UTF_8).trim(); }
        catch (Exception e) { return ""; }
    }

    static String tile(String id) {
        Matcher m = TILE.matcher(id == null ? "" : id);
        return m.matches() && PRESS.containsKey(m.group(2)) ? m.group(1) : null;
    }

    /** The entities the Home shows: those beside the clock, then the tiles'. */
    List<String> ids() {
        List<String> ids = new ArrayList<>();
        JSONArray show = store.config.optJSONArray("haShow");
        if (show != null) for (int i = 0; i < show.length(); i++) {
            JSONObject e = show.optJSONObject(i);
            if (e != null && !ids.contains(e.optString("id"))) ids.add(e.optString("id"));
        }
        JSONArray tiles = store.config.optJSONArray("tiles");
        if (tiles != null) for (int i = 0; i < tiles.length(); i++) {
            JSONObject t = tiles.optJSONObject(i);
            String e = t == null ? null : tile(t.optString("id"));
            if (e != null && !ids.contains(e)) ids.add(e);
        }
        return ids;
    }

    /** These paths at base, all at once; code 0 where HA did not answer in time. */
    static List<Answer> ask(String base, String token, List<String> paths, JSONObject post) {
        List<Answer> out = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (String p : paths) {
            Answer a = new Answer();
            out.add(a);
            Thread t = new Thread(() -> one(base + p, token, post, a));
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) try { t.join(TIMEOUT + 1000); } catch (InterruptedException ignored) {}
        return out;
    }

    private static void one(String url, String token, JSONObject post, Answer a) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(TIMEOUT);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Content-Type", "application/json");
            if (post != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                try (OutputStream o = c.getOutputStream()) { o.write(post.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                    if (b.size() > MAX) { a.code = 0; return; }
                }
                in.close();
            }
            a.body = b.toString("UTF-8");
            a.code = code;
        } catch (Exception e) {
            a.code = 0;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String error(int code) { return code == 401 || code == 403 ? "token" : code == 0 ? "no answer" : "HA answered " + code; }

    void note(String what) {
        if (!what.equals(last)) Log.i("home assistant: " + what);
        last = what;
    }

    static JSONObject entity(JSONObject e) throws Exception {
        JSONObject a = e == null ? null : e.optJSONObject("attributes");
        if (a == null) a = new JSONObject();
        return new JSONObject().put("s", Store.str(e == null ? null : e.opt("state"), 60))
                .put("u", Store.str(a.opt("unit_of_measurement"), 12)).put("n", Store.str(a.opt("friendly_name"), 60));
    }

    /** {entity: {s, u, n}} for the entities the Home shows. One ask at a time. */
    synchronized JSONObject states() throws Exception {
        List<String> ids = ids();
        String token = token(), url = store.config.optString("haUrl", "");
        JSONObject states = new JSONObject();
        if (url.isEmpty() || "null".equals(url) || token.isEmpty() || ids.isEmpty()) return states;
        List<String> paths = new ArrayList<>();
        for (String id : ids) paths.add("/api/states/" + id);
        List<Answer> res = ask(url, token, paths, null);
        int bad = 0;
        for (int i = 0; i < res.size(); i++) {
            Answer r = res.get(i);
            JSONObject e = null;
            if (r.code == 200) try { e = new JSONObject(r.body); } catch (Exception ignored) {}
            if (e != null && ids.get(i).equals(e.optString("entity_id"))) states.put(ids.get(i), entity(e));
            else if (r.code != 404) bad = r.code == 0 ? -1 : r.code;
        }
        note(bad != 0 ? error(bad == -1 ? 0 : bad) : "answering");
        return states;
    }

    void saveToken(String token) throws Exception {
        Store.writeAtomic(store.haTokenFile, (token + "\n").getBytes(StandardCharsets.UTF_8));
    }

    void dropToken() { //noinspection ResultOfMethodCallIgnored
        new File(store.haTokenFile.getPath()).delete();
    }
}

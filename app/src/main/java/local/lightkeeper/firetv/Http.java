package local.lightkeeper.firetv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

/** A small HTTP/1.1 server, one request per connection: what server.js gets from node's http. */
final class Http {
    interface Handler { Res handle(Req req) throws Exception; }

    static final class Req {
        String method, path, query, remote;
        final Map<String, String> headers = new LinkedHashMap<>();
        InputStream in;
        long length = -1;

        String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }

        /** The body, or null when longer than limit (as readBody's over). */
        byte[] body(int limit) throws IOException {
            if (length > limit) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            long left = length < 0 ? 0 : length;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
                if (out.size() > limit) return null;
            }
            return out.toByteArray();
        }

        /** The value of name in the query string, decoded, or null. */
        String param(String name) {
            if (query == null) return null;
            for (String part : query.split("&")) {
                int i = part.indexOf('=');
                String k = i < 0 ? part : part.substring(0, i);
                if (k.equals(name)) {
                    try { return java.net.URLDecoder.decode(i < 0 ? "" : part.substring(i + 1), "UTF-8"); }
                    catch (Exception e) { return null; }
                }
            }
            return null;
        }
    }

    static final class Res {
        int status;
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body;

        Res(int status, byte[] body, String type) {
            this.status = status;
            this.body = body;
            headers.put("Cache-Control", "no-store");
            headers.put("X-Content-Type-Options", "nosniff");
            headers.put("Referrer-Policy", "no-referrer");
            if (type != null) headers.put("Content-Type", type);
        }

        static Res json(int status, JSONObject o) {
            return new Res(status, o.toString().getBytes(StandardCharsets.UTF_8), "application/json");
        }

        static Res empty(int status) { return new Res(status, new byte[0], null); }

        Res h(String k, String v) { headers.put(k, v); return this; }
    }

    private final Handler handler;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private volatile ServerSocket socket;

    Http(Handler handler) { this.handler = handler; }

    int port() { ServerSocket s = socket; return s == null ? 0 : s.getLocalPort(); }

    /** Listens on port, on every address; the old socket closes once the new one is up. */
    synchronized void listen(int port) throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress((InetAddress) null, port), 50);
        ServerSocket old = socket;
        socket = s;
        if (old != null) try { old.close(); } catch (IOException ignored) {}
        Thread t = new Thread(() -> accept(s), "lightkeeper-http-" + port);
        t.setDaemon(true);
        t.start();
    }

    void close() {
        ServerSocket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (IOException ignored) {}
    }

    private void accept(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                Socket c = s.accept();
                pool.execute(() -> serve(c));
            } catch (IOException e) {
                if (s.isClosed()) return;
            }
        }
    }

    private void serve(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(15000);
            InputStream in = sock.getInputStream();
            Req req = new Req();
            req.remote = sock.getInetAddress().getHostAddress();
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            req.method = parts[0];
            String u = parts[1];
            int q = u.indexOf('?');
            req.path = q < 0 ? u : u.substring(0, q);
            req.query = q < 0 ? "" : u.substring(q + 1);
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int i = h.indexOf(':');
                if (i > 0) req.headers.put(h.substring(0, i).trim().toLowerCase(Locale.ROOT), h.substring(i + 1).trim());
            }
            String len = req.header("content-length");
            try { req.length = len == null ? 0 : Long.parseLong(len); } catch (NumberFormatException e) { req.length = 0; }
            req.in = in;
            Res res;
            try {
                res = handler.handle(req);
            } catch (Exception e) {
                Log.e("request " + req.method + " " + req.path, e);
                res = Res.json(500, new JSONObject().put("ok", false).put("error", "server error"));
            }
            write(sock.getOutputStream(), res, "HEAD".equals(req.method));
        } catch (Exception ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') b.append((char) ch);
            if (b.length() > 16384) throw new IOException("header too long");
        }
        return ch == -1 && b.length() == 0 ? null : b.toString();
    }

    private static final Map<Integer, String> REASONS = new LinkedHashMap<>();
    static {
        REASONS.put(200, "OK"); REASONS.put(204, "No Content"); REASONS.put(400, "Bad Request");
        REASONS.put(401, "Unauthorized"); REASONS.put(403, "Forbidden"); REASONS.put(404, "Not Found");
        REASONS.put(405, "Method Not Allowed"); REASONS.put(409, "Conflict"); REASONS.put(413, "Payload Too Large");
        REASONS.put(415, "Unsupported Media Type"); REASONS.put(500, "Internal Server Error"); REASONS.put(502, "Bad Gateway");
        REASONS.put(507, "Insufficient Storage");
    }

    private static void write(OutputStream out, Res res, boolean head) throws IOException {
        byte[] body = res.body == null ? new byte[0] : res.body;
        StringBuilder b = new StringBuilder();
        String reason = REASONS.get(res.status);
        b.append("HTTP/1.1 ").append(res.status).append(' ').append(reason == null ? "Status" : reason).append("\r\n");
        for (Map.Entry<String, String> e : res.headers.entrySet()) b.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        b.append("Content-Length: ").append(body.length).append("\r\n");
        b.append("Connection: close\r\n\r\n");
        out.write(b.toString().getBytes(StandardCharsets.UTF_8));
        if (!head) out.write(body);
        out.flush();
    }
}

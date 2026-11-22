package local.lightkeeper.firetv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/** The Home: Lightkeeper's own page, unchanged, in a full-screen WebView at 1920 × 1080. */
public class MainActivity extends Activity {
    static final String FROM_HOME_KEY = "fromHomeKey";
    private WebView web;
    private boolean shown;
    private long relaunchedAt;

    final class Bridge {
        @JavascriptInterface public boolean launch(String id, String params) {
            JSONObject p = null;
            try { p = new JSONObject(params); } catch (Exception ignored) {}
            Lightkeeper lk = Lightkeeper.get(MainActivity.this);
            return lk.apps.launch(id, p, lk.server.port());
        }
        @JavascriptInterface public int port() { return Lightkeeper.get(MainActivity.this).server.port(); }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ServerService.start(this);
        // chrome://inspect over adb, in debug builds only.
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        // The page is laid out for 1920 CSS pixels across, as webOS's resolution 1920x1080.
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int across = Math.max(dm.widthPixels, dm.heightPixels);
        web.setInitialScale(Math.round(across * 100f / 1920f));
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (android.os.Build.VERSION.SDK_INT >= 26) web.setDefaultFocusHighlightEnabled(false);
        web.addJavascriptInterface(new Bridge(), "LightkeeperAndroid");
        web.setWebViewClient(new WebViewClient() {
            // Fire OS kills WebView's renderer when memory is short (a 4K Max has little):
            // the WebView then stays blank, so a new one loads the Home again.
            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                Log.i("renderer gone (" + (detail.didCrash() ? "crash" : "killed") + "): loading the Home again");
                if (view == web) {
                    ((android.view.ViewGroup) view.getParent()).removeView(view);
                    view.destroy();
                    web = null;
                    recreate();
                }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i("page: " + m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/app/index.html");
        web.requestFocus();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        relaunch();
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) { web.onResume(); web.requestFocus(); }
        if (shown) relaunch();
        shown = true;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    /** Home again, or back from an app: as webOS's relaunch of the Home (once, for the Home key's two starts). */
    private void relaunch() {
        long now = System.currentTimeMillis();
        if (now - relaunchedAt < 1500) return;
        relaunchedAt = now;
        if (web != null) web.evaluateJavascript("window.lightkeeperRelaunch && lightkeeperRelaunch()", null);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        // Back closes the open apps panel, as webOS's 461; the Home itself stays.
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && web != null) web.evaluateJavascript("window.lightkeeperKey && lightkeeperKey(461)", null);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }
}

package com.ramong.duelcards;

import android.app.Activity;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

/**
 * Plays the game page in a full-screen WebView. Every file is served from the APK's assets,
 * so the game works offline.
 */
public class MainActivity extends Activity {

    // Google's reserved host for serving app files to a WebView over https (what WebViewAssetLoader uses).
    // An https origin gives the page working localStorage and same-origin fonts.
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String GAME_PAGE = "game.html";
    private static final String BRIDGE_TAG = "<script src=\"android-bridge.js\"></script>";

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int table = getColor(R.color.table);

        web = new WebView(this);
        web.setBackgroundColor(table); // no white flash before the page paints
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true); // settings and the win/loss record live in localStorage
        settings.setMediaPlaybackRequiresUserGesture(false); // lets music resume when you come back
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        web.setWebViewClient(new AssetClient());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(table);
        root.addView(web);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 15 draws apps edge to edge; keep the table clear of the status and navigation bars.
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    return WindowInsets.CONSUMED;
                }
            });
        }
        setContentView(root);

        web.loadUrl("https://" + APP_HOST + "/" + GAME_PAGE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.resumeTimers();
        web.onResume();
        web.evaluateJavascript("window.duelAndroid && duelAndroid.resume()", null);
    }

    @Override
    protected void onPause() {
        // Stop the music when the app leaves the screen; the page itself never listens for that.
        web.evaluateJavascript("window.duelAndroid && duelAndroid.pause()", null);
        web.onPause();
        web.pauseTimers();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("!!(window.duelAndroid && duelAndroid.back())", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String closedDialog) {
                // No dialog to close: leave the app but keep the duel where it is.
                if (!"true".equals(closedDialog)) moveTaskToBack(true);
            }
        });
    }

    private final class AssetClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            String host = url.getHost();
            if (APP_HOST.equals(host)) return asset(url.getPath());
            // The page links Google Fonts; answer with the copies bundled in the APK.
            if ("fonts.googleapis.com".equals(host)) return asset("/fonts/fonts.css");
            return notFound(); // nothing is fetched from the internet
        }
    }

    private WebResourceResponse asset(String path) {
        if (path == null || path.length() < 2 || path.contains("..")) return notFound();
        String name = path.substring(1);
        try {
            InputStream in = getAssets().open(name);
            if (name.equals(GAME_PAGE)) in = withBridge(in);
            String type = mimeType(name);
            boolean text = type.startsWith("text/");
            return new WebResourceResponse(type, text ? "utf-8" : null, in);
        } catch (IOException e) {
            return notFound();
        }
    }

    /** Adds android-bridge.js to the top of the page so it runs before the game's own scripts. */
    private static InputStream withBridge(InputStream in) throws IOException {
        String html = new String(readAll(in), StandardCharsets.UTF_8);
        int head = html.toLowerCase(Locale.ROOT).indexOf("<head");
        int at = head < 0 ? 0 : html.indexOf('>', head) + 1;
        html = html.substring(0, at) + BRIDGE_TAG + html.substring(at);
        return new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = src.read(buf)) != -1; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String mimeType(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "html": return "text/html";
            case "js": return "text/javascript";
            case "css": return "text/css";
            case "woff2": return "font/woff2";
            case "png": return "image/png";
            case "webp": return "image/webp";
            default: return "application/octet-stream";
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                Collections.<String, String>emptyMap(), new ByteArrayInputStream(new byte[0]));
    }
}

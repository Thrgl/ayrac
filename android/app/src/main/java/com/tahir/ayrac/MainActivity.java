package com.tahir.ayrac;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ayraç for Android: ayrac.html in a WebView, with the same bridge the desktop app offers
 * (window.pywebview.api.* — see assets/web/android-bridge.js) implemented here in Java.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/web/index.html";
    private static final int REQ_FILE_INPUT = 1, REQ_CREATE = 2, REQ_OPEN = 3;

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Claude claude = new Claude();
    private WebView web;
    private Store store;
    private Speech speech;
    private SharedPreferences secrets;
    private volatile Dictionary dict;
    private volatile boolean dictTried;
    private ValueCallback<Uri[]> fileCallback;
    private volatile CompletableFuture<Uri> docResult;
    private volatile Uri pendingOpen;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(new File(getFilesDir(), "Ayrac"));
        secrets = getSharedPreferences("ayrac-secrets", MODE_PRIVATE);
        store.log("info", "start: Ayraç " + version() + " Android " + Build.VERSION.RELEASE);
        speech = new Speech(this, (id, type, at) ->
                js("window.__ayracTts && window.__ayracTts(" + JSONObject.quote(id) + "," + JSONObject.quote(type) + "," + at + ")"));

        WebView.setWebContentsDebuggingEnabled(Boolean.TRUE.equals(store.kvGet("devMode")));
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);

        WebViewAssetLoader assets = new WebViewAssetLoader.Builder().setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return assets.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u);  // dictionary links, links inside books
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/epub+zip", "application/octet-stream", "application/json"});
                startActivityForResult(i, REQ_FILE_INPUT);
                return true;
            }
        });
        web.addJavascriptInterface(new Native(), "AyracNative");
        handleIntent(getIntent());
        web.loadUrl(START);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
        if (pendingOpen != null) js("window.ayracOpenExternal && window.ayracOpenExternal()");
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) u = intent.getData();
        else if (Intent.ACTION_SEND.equals(intent.getAction())) u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u != null) {
            pendingOpen = u;
            store.log("info", "open request: " + u);
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        Uri u = result == RESULT_OK && data != null ? data.getData() : null;
        if (req == REQ_FILE_INPUT) {
            if (fileCallback != null) fileCallback.onReceiveValue(u != null ? new Uri[]{u} : null);
            fileCallback = null;
        } else if ((req == REQ_CREATE || req == REQ_OPEN) && docResult != null) {
            docResult.complete(u);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Let the page close a card, panel or the open book first; leave the app only from the library.
        web.evaluateJavascript("(window.ayracBack && window.ayracBack()) ? '1' : '0'", v -> {
            if (!"\"1\"".equals(v)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onDestroy() {
        speech.shutdown();
        pool.shutdownNow();
        super.onDestroy();
    }

    // ---------- helpers ----------
    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            store.log("warn", "cannot open " + u + ": " + e);
        }
    }

    /** Ask the user where to save (create=true) or which file to open; blocks the calling worker thread. */
    private Uri pickDocument(boolean create, String mime, String name) throws Exception {
        CompletableFuture<Uri> f = new CompletableFuture<>();
        docResult = f;
        runOnUiThread(() -> {
            Intent i = new Intent(create ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType(mime);
            if (create) i.putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(i, create ? REQ_CREATE : REQ_OPEN);
        });
        return f.get();
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        String last = u.getLastPathSegment();
        return last != null ? last : "kitap.epub";
    }

    /** The dictionary ships in the APK (assets/dict); SQLite needs it as a file, so it is copied once per version. */
    private Dictionary dictionary() {
        if (dictTried) return dict;
        synchronized (this) {
            if (dictTried) return dict;
            dictTried = true;
            File f = new File(getFilesDir(), "dict/en-tr.sqlite");
            try {
                String marker = version();
                File stamp = new File(f.getPath() + ".version");
                boolean fresh = f.exists() && stamp.exists()
                        && marker.equals(new String(Store.readAll(new java.io.FileInputStream(stamp)), StandardCharsets.UTF_8));
                if (!fresh) {
                    //noinspection ResultOfMethodCallIgnored
                    f.getParentFile().mkdirs();
                    try (InputStream in = getAssets().open("dict/en-tr.sqlite"); OutputStream os = new FileOutputStream(f)) {
                        byte[] buf = new byte[1 << 16];
                        for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
                    }
                    try (OutputStream os = new FileOutputStream(stamp)) {
                        os.write(marker.getBytes(StandardCharsets.UTF_8));
                    }
                }
                dict = new Dictionary(f);
            } catch (Exception e) {
                store.log("error", "dictionary: " + e);
            }
            return dict;
        }
    }

    private String apiKey() {
        return secrets.getString("apiKey", null);
    }

    private JSONObject aiStatus() throws Exception {
        String key = apiKey();
        Object model = store.kvGet("aiModel");
        return new JSONObject().put("configured", key != null).put("source", key != null ? "device" : JSONObject.NULL)
                .put("keyring", key != null).put("model", model instanceof String ? model : "opus");
    }

    private JSONObject appInfo() throws Exception {
        File[] epubs = store.books.listFiles((d, n) -> n.endsWith(".epub"));
        Dictionary d = dictionary();
        return new JSONObject().put("version", version()).put("dataDir", store.dir.getPath())
                .put("logFile", store.logFile.getPath()).put("books", epubs == null ? 0 : epubs.length)
                .put("dictionary", d != null ? d.file.getPath() : JSONObject.NULL)
                .put("devMode", Boolean.TRUE.equals(store.kvGet("devMode")))
                .put("exe", "Android " + Build.VERSION.RELEASE + " · " + Build.MODEL);
    }

    private static String str(JSONArray a, int i) {
        Object v = a.opt(i);
        return v == null || v == JSONObject.NULL ? null : String.valueOf(v);
    }

    private static String toJson(Object r) {
        if (r == null || r == JSONObject.NULL) return "null";
        if (r instanceof String) return JSONObject.quote((String) r);
        return String.valueOf(r);  // JSONObject, JSONArray, Boolean, Number
    }

    private Object dispatch(String method, JSONArray a) throws Exception {
        switch (method) {
            case "kv_all": return store.kvAll();
            case "kv_set": store.kvSet(a.getString(0), a.opt(1)); return true;
            case "kv_del": store.kvDel(a.getString(0)); return true;
            case "books_list": return store.booksList();
            case "book_meta": return store.bookMeta(a.getString(0));
            case "book_put": return store.bookPut(a.getJSONObject(0), str(a, 1), str(a, 2), str(a, 3));
            case "book_file": return store.bookFile(a.getString(0));
            case "book_delete": store.bookDelete(a.getString(0)); return true;
            case "initial_file": {
                Uri u = pendingOpen;
                pendingOpen = null;
                if (u == null) return null;
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    return new JSONObject().put("name", displayName(u)).put("b64", Base64.encodeToString(Store.readAll(in), Base64.NO_WRAP));
                }
            }
            case "dict_info": {
                Dictionary d = dictionary();
                JSONObject r = d != null ? new JSONObject(d.meta.toString()) : new JSONObject();
                return r.put("available", d != null);
            }
            case "dict_lookup": {
                Dictionary d = dictionary();
                if (d == null) return new JSONObject().put("found", false).put("unavailable", true);
                return d.lookup(str(a, 0), a.optJSONArray(1), a.optJSONArray(2));
            }
            case "dict_lookup_many": {
                Dictionary d = dictionary();
                JSONArray items = a.getJSONArray(0), out = new JSONArray();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    out.put(d == null ? new JSONObject().put("found", false)
                            : d.lookup(it.optString("word"), it.optJSONArray("prev"), it.optJSONArray("next")));
                }
                return out;
            }
            case "open_cambridge":
                openExternal(Uri.parse("https://dictionary.cambridge.org/dictionary/english-turkish/" + Uri.encode(String.valueOf(str(a, 0)).trim().toLowerCase(Locale.ROOT))));
                return true;
            case "ai_status": return aiStatus();
            case "ai_set_key": {
                String k = str(a, 0);
                if (k != null && !k.trim().isEmpty()) secrets.edit().putString("apiKey", k.trim()).apply();
                return aiStatus();
            }
            case "ai_clear_key": secrets.edit().remove("apiKey").apply(); return aiStatus();
            case "ai_set_model": {
                String m = str(a, 0);
                if ("opus".equals(m) || "haiku".equals(m)) store.kvSet("aiModel", m);
                return aiStatus();
            }
            case "ask": {
                Object m = store.kvGet("aiModel");
                return claude.ask(apiKey(), "haiku".equals(m) ? Claude.HAIKU : Claude.OPUS, a.getString(0), str(a, 1));
            }
            case "log": store.log(String.valueOf(str(a, 0)), String.valueOf(str(a, 1))); return true;
            case "app_info": return appInfo();
            case "set_dev_mode": {
                boolean on = a.optBoolean(0);
                store.kvSet("devMode", on);
                runOnUiThread(() -> WebView.setWebContentsDebuggingEnabled(on));
                return appInfo();
            }
            case "open_path": return false;
            case "backup_export": {
                String name = "Ayrac-yedek-" + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()) + ".zip";
                Uri u = pickDocument(true, "application/zip", name);
                if (u == null) return new JSONObject().put("ok", false).put("cancelled", true);
                try (OutputStream os = getContentResolver().openOutputStream(u)) {
                    return store.writeBackup(os, version()).put("path", displayName(u));
                }
            }
            case "backup_import": {
                Uri u = pickDocument(false, "application/zip", null);
                if (u == null) return new JSONObject().put("ok", false).put("cancelled", true);
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    return store.restoreBackup(in, version());
                }
            }
            case "save_text_file": {
                String name = String.valueOf(str(a, 0));
                Uri u = pickDocument(true, name.endsWith(".csv") ? "text/csv" : "text/plain", name);
                if (u == null) return new JSONObject().put("ok", false).put("cancelled", true);
                try (OutputStream os = getContentResolver().openOutputStream(u)) {
                    os.write(String.valueOf(str(a, 1)).getBytes(StandardCharsets.UTF_8));
                }
                return new JSONObject().put("ok", true).put("path", displayName(u));
            }
            case "check_update":
            case "set_update_source":
                return new JSONObject().put("configured", false).put("current", version());
            case "install_update":
                return new JSONObject().put("ok", false).put("message", "Telefonda güncelleme, yeni APK'yı kurarak yapılır.");
            default:
                throw new IllegalArgumentException("unknown method " + method);
        }
    }

    /** Called from android-bridge.js. */
    private final class Native {
        @JavascriptInterface
        public void call(String id, String method, String argsJson) {
            pool.execute(() -> {
                String out;
                try {
                    out = "true," + toJson(dispatch(method, new JSONArray(argsJson)));
                } catch (Throwable t) {
                    store.log("error", method + ": " + t);
                    out = "false," + JSONObject.quote(String.valueOf(t.getMessage()));
                }
                js("window.__ayracResolve(" + JSONObject.quote(id) + "," + out + ")");
            });
        }

        @JavascriptInterface
        public void speak(String id, String text, String lang, float rate) {
            speech.speak(id, text, lang, rate);
        }

        @JavascriptInterface
        public void stopSpeaking() {
            speech.stop();
        }
    }
}

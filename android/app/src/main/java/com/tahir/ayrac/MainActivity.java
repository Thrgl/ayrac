package com.tahir.ayrac;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
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
import org.json.JSONException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ayraç for Android: ayrac.html in a WebView, with the same bridge the desktop app offers
 * (window.pywebview.api.* — see assets/web/android-bridge.js) implemented here in Java.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/web/index.html";
    private static final int REQ_FILE_INPUT = 1, REQ_CREATE = 2, REQ_OPEN = 3, REQ_TREE = 4;

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
        speech = new Speech(this, new NeuralVoice(new File(store.dir, "voices"), store), (id, type, at) ->
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
                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/epub+zip", "application/octet-stream", "application/json", "text/plain"});
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
        } else if ((req == REQ_CREATE || req == REQ_OPEN || req == REQ_TREE) && docResult != null) {
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

    /** The window behind the page, the status bar and the navigation bar take the page's background colour. */
    @SuppressWarnings("deprecation")
    private void setBarColors(int color, boolean dark) {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(color));
        w.setStatusBarColor(color);
        w.setNavigationBarColor(color);
        web.setBackgroundColor(color);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : light, light);
            }
        } else {
            View d = w.getDecorView();
            int flags = d.getSystemUiVisibility() & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            d.setSystemUiVisibility(flags);
        }
    }

    private static boolean catalogHost(java.net.URL u) {
        return "https".equals(u.getProtocol()) && ("www.gutenberg.org".equals(u.getHost()) || "gutenberg.org".equals(u.getHost()));
    }

    /** GET from Project Gutenberg only (free books); same rule as ayrac_app.Api._catalog_fetch. */
    private byte[] catalogFetch(String url, int limit) throws Exception {
        java.net.URL u = new java.net.URL(url);
        if (!catalogHost(u)) throw new IllegalArgumentException("izin verilmeyen adres");
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(40_000);
        c.setRequestProperty("User-Agent", "Ayrac/" + version() + " (EPUB reader)");
        try {
            int code = c.getResponseCode();
            if (!catalogHost(c.getURL())) throw new IllegalArgumentException("izin verilmeyen yönlendirme");
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            try (InputStream in = c.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > limit) throw new java.io.IOException("dosya çok büyük");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Ask the user for a folder (the update folder); blocks the calling worker thread. */
    private Uri pickFolder() throws Exception {
        CompletableFuture<Uri> f = new CompletableFuture<>();
        docResult = f;
        runOnUiThread(() -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE));
        return f.get();
    }

    private static final Pattern APK_NAME = Pattern.compile("^Ayrac-(\\d+)\\.(\\d+)\\.(\\d+)\\.apk$", Pattern.CASE_INSENSITIVE);

    private static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        return 0;
    }

    private static int[] parseVersion(String v) {
        int[] out = new int[3];
        String[] p = String.valueOf(v).split("\\.");
        for (int i = 0; i < 3 && i < p.length; i++) {
            try {
                out[i] = Integer.parseInt(p[i].replaceAll("\\D.*", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** The newest Ayrac-x.y.z.apk in the update folder; set by checkUpdate for install_update. */
    private volatile Uri updateApk;

    /** Same answer shape as the desktop app's check_update, from the update folder instead of a web address. */
    private JSONObject checkUpdate() throws JSONException {
        JSONObject r = new JSONObject().put("current", version());
        Object f = store.kvGet("updateFolder");
        updateApk = null;
        if (!(f instanceof String)) return r.put("configured", false);
        Uri tree = Uri.parse((String) f);
        String treeId = DocumentsContract.getTreeDocumentId(tree), folder = "güncelleme klasörü", bestName = null;
        int[] best = null;
        Uri bestUri = null;
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try {
            try (Cursor c = getContentResolver().query(DocumentsContract.buildDocumentUriUsingTree(tree, treeId), cols, null, null, null)) {
                if (c != null && c.moveToFirst()) folder = c.getString(1);
            }
            try (Cursor c = getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId), cols, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    Matcher m = APK_NAME.matcher(String.valueOf(c.getString(1)));
                    if (!m.matches()) continue;
                    int[] v = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
                    if (best == null || compareVersions(v, best) > 0) {
                        best = v;
                        bestName = c.getString(1);
                        bestUri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                    }
                }
            }
        } catch (RuntimeException e) {  // permission gone (folder deleted or access revoked)
            store.log("warn", "check_update: " + e);
            return r.put("configured", true).put("ok", false).put("source", folder)
                    .put("message", "Güncelleme klasörü okunamadı; geliştirici panelinden yeniden seç.");
        }
        boolean newer = best != null && compareVersions(best, parseVersion(version())) > 0;
        if (newer) updateApk = bestUri;
        String latest = best == null ? "" : best[0] + "." + best[1] + "." + best[2];
        store.log("info", "check_update: current " + version() + ", latest " + (latest.isEmpty() ? "-" : latest) + " in " + folder);
        return r.put("configured", true).put("ok", true).put("source", folder).put("latest", latest.isEmpty() ? version() : latest)
                .put("newer", newer).put("notes", newer ? bestName + " · güncelleme klasöründe" : "");
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
            case "dict_profile": {
                Dictionary d = dictionary();
                return d == null ? null : d.profile(a.optJSONArray(0));
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
            case "set_bar_colors": {
                int c = Color.parseColor(String.valueOf(str(a, 0)));
                boolean dark = a.optBoolean(1);
                runOnUiThread(() -> setBarColors(c, dark));
                return true;
            }
            case "catalog_search": {
                String q = ("l.en " + (str(a, 0) == null ? "" : str(a, 0))).trim();
                if (q.length() > 125) q = q.substring(0, 125);
                int start = a.optInt(1, 1);
                String url = "https://www.gutenberg.org/ebooks/search.opds/?query=" + Uri.encode(q) + "&sort_order=downloads"
                        + (start > 1 ? "&start_index=" + start : "");
                try {
                    return new JSONObject().put("ok", true).put("xml", new String(catalogFetch(url, 3_000_000), StandardCharsets.UTF_8));
                } catch (Exception e) {
                    store.log("warn", "catalog_search: " + e);
                    return new JSONObject().put("ok", false).put("message", String.valueOf(e.getMessage()));
                }
            }
            case "catalog_download": {
                String id = String.valueOf(str(a, 0));
                if (!id.matches("\\d{1,7}")) return new JSONObject().put("ok", false).put("message", "izin verilmeyen kitap numarası");
                try {
                    byte[] data = catalogFetch("https://www.gutenberg.org/ebooks/" + id + ".epub3.images", 60_000_000);
                    store.log("info", "catalog_download: " + id + " (" + data.length + " bytes)");
                    return new JSONObject().put("ok", true).put("b64", Base64.encodeToString(data, Base64.NO_WRAP));
                } catch (Exception e) {
                    store.log("warn", "catalog_download " + id + ": " + e);
                    return new JSONObject().put("ok", false).put("message", String.valueOf(e.getMessage()));
                }
            }
            case "voice_status": return new JSONObject().put("ok", true).put("voices", speech.neural.status());
            case "voice_install": return new JSONObject().put("ok", speech.neural.install(String.valueOf(str(a, 0))));
            case "voice_remove": speech.neural.remove(String.valueOf(str(a, 0))); return new JSONObject().put("ok", true).put("voices", speech.neural.status());
            case "voice_select": speech.neural.select(str(a, 0)); return new JSONObject().put("ok", true).put("active", speech.neural.active());
            case "set_reminder": {
                boolean on = a.optBoolean(0);
                if (on && Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
                    runOnUiThread(() -> requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 5));
                ReminderReceiver.configure(this, on, str(a, 1));
                return new JSONObject().put("ok", true).put("on", on);
            }
            case "reminder_test": {
                String text = ReminderReceiver.message(this);
                ReminderReceiver.show(this, text != null ? text : "Bugünlük her şey tamam: tekrar bekleyen kelime yok, hedefini de doldurdun.");
                return true;
            }
            case "check_update": return checkUpdate();
            case "set_update_source": {
                // The update folder on the phone (developer mode): new Ayrac-x.y.z.apk files are picked up from it.
                Uri tree = pickFolder();
                if (tree != null) {
                    getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    store.kvSet("updateFolder", tree.toString());
                }
                return checkUpdate();
            }
            case "install_update": {
                JSONObject info = checkUpdate();
                Uri apk = updateApk;
                if (!info.optBoolean("newer") || apk == null)
                    return new JSONObject().put("ok", false).put("message", "Güncelleme klasöründe daha yeni bir Ayraç APK'sı yok.");
                if (!getPackageManager().canRequestPackageInstalls()) {
                    // Android asks once: "allow Ayraç to install apps"; after that the update opens directly.
                    runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))));
                    return new JSONObject().put("ok", false).put("message", "Ayraç'a “uygulama yükleme” izni ver, sonra yeniden Güncelle'ye bas.");
                }
                store.log("info", "install_update: " + apk);
                Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(apk, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                runOnUiThread(() -> startActivity(i));
                return new JSONObject().put("ok", true).put("version", info.optString("latest"));
            }
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

        /** The neural voice makes the next sentence while this one is read (no pause between sentences). */
        @JavascriptInterface
        public void prefetch(String text, float rate) {
            speech.neural.prefetch(text, rate);
        }
    }
}

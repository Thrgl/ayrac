package com.tahir.ayrac;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The app's data in its private folder, in the same layout as the desktop app's %APPDATA%\Ayrac:
 * data.json (settings, notebook, positions), books/&lt;sha1&gt;.epub|.json|.cover, logs/ayrac.log.
 * Backups are the same ZIP format, so a desktop backup can be restored on the phone and back.
 */
final class Store {
    private static final Pattern BOOK_FILE = Pattern.compile("^[0-9a-f]{20}\\.(epub|json|cover)$");

    final File dir, books, logFile;
    private final File kvFile;
    private JSONObject kv;

    Store(File dir) {
        this.dir = dir;
        books = new File(dir, "books");
        kvFile = new File(dir, "data.json");
        logFile = new File(new File(dir, "logs"), "ayrac.log");
        books.mkdirs();
        kv = loadKv();
    }

    private JSONObject loadKv() {
        try {
            return new JSONObject(read(kvFile));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    private void saveKv() throws IOException {
        writeAtomic(kvFile, kv.toString().getBytes(StandardCharsets.UTF_8));
    }

    synchronized JSONObject kvAll() throws JSONException {
        return new JSONObject(kv.toString());
    }

    synchronized Object kvGet(String key) {
        return kv.opt(key);
    }

    synchronized void kvSet(String key, Object value) throws JSONException, IOException {
        kv.put(key, value);
        saveKv();
    }

    synchronized void kvDel(String key) throws IOException {
        kv.remove(key);
        saveKv();
    }

    // ---------- library ----------
    static String stem(String bookId) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(bookId.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.substring(0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    JSONArray booksList() {
        JSONArray out = new JSONArray();
        File[] metas = books.listFiles((d, n) -> n.endsWith(".json"));
        if (metas == null) return out;
        for (File m : metas) {
            String base = m.getName().substring(0, m.getName().length() - 5);
            if (!new File(books, base + ".epub").exists()) continue;
            try {
                JSONObject meta = new JSONObject(read(m));
                File cover = new File(books, base + ".cover");
                if (cover.exists()) {
                    meta.put("cover", "data:" + meta.optString("coverType", "image/jpeg") + ";base64,"
                            + Base64.encodeToString(Files.readAllBytes(cover.toPath()), Base64.NO_WRAP));
                }
                out.put(meta);
            } catch (IOException | JSONException e) {
                log("error", "books_list: cannot read " + m.getName() + ": " + e);
            }
        }
        log("info", "books_list: " + out.length() + " books");
        return out;
    }

    JSONObject bookMeta(String id) {
        try {
            return new JSONObject(read(new File(books, stem(id) + ".json")));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    boolean bookPut(JSONObject meta, String fileB64, String coverB64, String coverType) throws IOException, JSONException {
        String s = stem(meta.getString("id"));
        File epub = new File(books, s + ".epub");
        if (fileB64 != null) writeAtomic(epub, Base64.decode(fileB64, Base64.DEFAULT));
        if (!epub.exists()) return false;
        if (coverB64 != null) {
            writeAtomic(new File(books, s + ".cover"), Base64.decode(coverB64, Base64.DEFAULT));
            meta.put("coverType", coverType != null ? coverType : "image/jpeg");
        } else {
            JSONObject old = bookMeta(meta.getString("id"));
            if (old != null && old.has("coverType")) meta.put("coverType", old.getString("coverType"));
        }
        writeAtomic(new File(books, s + ".json"), meta.toString().getBytes(StandardCharsets.UTF_8));
        return true;
    }

    String bookFile(String id) throws IOException {
        File epub = new File(books, stem(id) + ".epub");
        return epub.exists() ? Base64.encodeToString(Files.readAllBytes(epub.toPath()), Base64.NO_WRAP) : null;
    }

    void bookDelete(String id) {
        String s = stem(id);
        for (String ext : new String[]{".epub", ".json", ".cover"}) //noinspection ResultOfMethodCallIgnored
            new File(books, s + ext).delete();
    }

    // ---------- backup (same ZIP as the desktop app) ----------
    synchronized JSONObject writeBackup(OutputStream os, String version) throws IOException, JSONException {
        int bookCount = 0;
        int words = kv.optJSONArray("words") != null ? kv.optJSONArray("words").length() : 0;
        try (ZipOutputStream z = new ZipOutputStream(os)) {
            put(z, "ayrac-backup.json", new JSONObject().put("app", "Ayrac").put("version", version)
                    .put("created", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date())).toString().getBytes(StandardCharsets.UTF_8));
            put(z, "data.json", kv.toString().getBytes(StandardCharsets.UTF_8));
            File[] files = books.listFiles((d, n) -> BOOK_FILE.matcher(n).matches());
            if (files != null) for (File f : files) {
                put(z, "books/" + f.getName(), Files.readAllBytes(f.toPath()));
                if (f.getName().endsWith(".epub")) bookCount++;
            }
        }
        log("info", "backup written (" + bookCount + " books, " + words + " words)");
        return new JSONObject().put("ok", true).put("books", bookCount).put("words", words);
    }

    synchronized JSONObject restoreBackup(InputStream in, String version) throws IOException, JSONException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.equals("ayrac-backup.json") || n.equals("data.json")
                        || (n.startsWith("books/") && BOOK_FILE.matcher(n.substring(6)).matches())) {
                    files.put(n, readAll(z));
                }
            }
        }
        if (!files.containsKey("ayrac-backup.json") || !files.containsKey("data.json")) {
            return new JSONObject().put("ok", false).put("message", "Bu dosya bir Ayraç yedeği değil.");
        }
        JSONObject newKv = new JSONObject(new String(files.get("data.json"), StandardCharsets.UTF_8));
        // Keep the state before restoring.
        File safety = new File(new File(dir, "backups"), "geri-yuklemeden-once-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".zip");
        //noinspection ResultOfMethodCallIgnored
        safety.getParentFile().mkdirs();
        try (OutputStream os = new FileOutputStream(safety)) {
            writeBackup(os, version);
        }
        File[] old = books.listFiles((d, n) -> BOOK_FILE.matcher(n).matches());
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        int bookCount = 0;
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (!e.getKey().startsWith("books/")) continue;
            writeAtomic(new File(books, e.getKey().substring(6)), e.getValue());
            if (e.getKey().endsWith(".epub")) bookCount++;
        }
        kv = newKv;
        saveKv();
        int words = kv.optJSONArray("words") != null ? kv.optJSONArray("words").length() : 0;
        log("info", "backup restored (" + bookCount + " books, " + words + " words)");
        return new JSONObject().put("ok", true).put("books", bookCount).put("words", words);
    }

    // ---------- log ----------
    synchronized void log(String level, String msg) {
        try {
            //noinspection ResultOfMethodCallIgnored
            logFile.getParentFile().mkdirs();
            if (logFile.length() > 1_000_000) //noinspection ResultOfMethodCallIgnored
                logFile.renameTo(new File(logFile.getParentFile(), "ayrac.old.log"));
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date())
                    + " [" + level + "] " + (msg.length() > 2000 ? msg.substring(0, 2000) : msg) + "\n";
            try (OutputStream os = new FileOutputStream(logFile, true)) {
                os.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }

    // ---------- helpers ----------
    private static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void writeAtomic(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(data);
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("cannot write " + f);
        }
    }
}

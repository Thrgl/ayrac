package com.tahir.ayrac;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Neural reading voices on the phone (Piper, run by sherpa-onnx): the same four voices as the desktop app
 * (ayrac_app.VOICES), downloaded once into files/Ayrac/voices. Sentences are made on a worker thread and played
 * with AudioTrack, so reading goes on with the screen off; the next sentences can be made ahead (prefetch).
 */
final class NeuralVoice {
    /** id -> {folder, label, accent, MB} */
    static final Map<String, String[]> VOICES = new LinkedHashMap<>();

    static {
        VOICES.put("lessac", new String[]{"vits-piper-en_US-lessac-medium", "Lessac · kadın", "en-US", "64"});
        VOICES.put("ryan", new String[]{"vits-piper-en_US-ryan-medium", "Ryan · erkek", "en-US", "64"});
        VOICES.put("alba", new String[]{"vits-piper-en_GB-alba-medium", "Alba · kadın", "en-GB", "64"});
        VOICES.put("northern", new String[]{"vits-piper-en_GB-northern_english_male-medium", "Northern · erkek", "en-GB", "64"});
    }

    private static final String URL_FMT = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/%s.tar.bz2";

    private final File dir;
    private final Store store;
    private final Map<String, JSONObject> progress = new ConcurrentHashMap<>();
    private final ExecutorService maker = Executors.newSingleThreadExecutor();   // makes audio
    private final ExecutorService player = Executors.newSingleThreadExecutor();  // plays it, in order
    private final Map<String, Future<GeneratedAudio>> ahead = new ConcurrentHashMap<>();
    private volatile String selected;  // voice id, or null: Android's own voice reads
    private OfflineTts tts;
    private String ttsVoice;
    private volatile AudioTrack track;
    private volatile int generation;  // bumped by stop(): sentences of an older generation are dropped

    NeuralVoice(File dir, Store store) {
        this.dir = dir;
        this.store = store;
    }

    private File folder(String vid) {
        return new File(dir, VOICES.get(vid)[0]);
    }

    boolean installed(String vid) {
        File d = VOICES.containsKey(vid) ? folder(vid) : null;
        if (d == null || !new File(d, "tokens.txt").isFile()) return false;
        File[] onnx = d.listFiles((x, n) -> n.endsWith(".onnx"));
        return onnx != null && onnx.length > 0;
    }

    JSONArray status() throws JSONException {
        JSONArray out = new JSONArray();
        for (Map.Entry<String, String[]> e : VOICES.entrySet()) {
            String[] v = e.getValue();
            JSONObject o = new JSONObject().put("id", e.getKey()).put("label", v[1]).put("accent", v[2])
                    .put("mb", Integer.parseInt(v[3])).put("installed", installed(e.getKey()));
            JSONObject p = progress.get(e.getKey());
            if (p != null) synchronized (p) { o.put("progress", new JSONObject(p.toString())); }
            out.put(o);
        }
        return out;
    }

    /** Selects the reading voice (null or "": the system voice). */
    void select(String vid) {
        selected = vid != null && VOICES.containsKey(vid) && installed(vid) ? vid : null;
        if (selected != null) maker.execute(() -> { try { engine(selected); } catch (Exception ignored) { } });  // load it now
    }

    boolean active() {
        return selected != null;
    }

    boolean install(String vid) throws JSONException {
        if (!VOICES.containsKey(vid)) return false;
        JSONObject p = progress.get(vid);
        if (installed(vid) || (p != null && ("downloading".equals(p.optString("state")) || "extracting".equals(p.optString("state"))))) return true;
        JSONObject np = new JSONObject().put("state", "downloading").put("done", 0).put("total", 64L * 1024 * 1024);
        progress.put(vid, np);
        new Thread(() -> download(vid, np), "voice-" + vid).start();
        return true;
    }

    private void download(String vid, JSONObject p) {
        String name = VOICES.get(vid)[0];
        File part = new File(dir, name + ".tar.bz2.part"), tmp = new File(dir, ".unpack-" + name);
        try {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            HttpURLConnection c = (HttpURLConnection) new URL(String.format(URL_FMT, name)).openConnection();
            c.setConnectTimeout(30_000);
            c.setReadTimeout(60_000);
            c.setInstanceFollowRedirects(true);
            long done = 0;
            try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream os = new FileOutputStream(part)) {
                long total = c.getContentLengthLong();
                if (total > 0) synchronized (p) { p.put("total", total); }
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    os.write(buf, 0, n);
                    done += n;
                    synchronized (p) { p.put("done", done); }
                }
            } finally {
                c.disconnect();
            }
            synchronized (p) { p.put("state", "extracting"); }
            deleteTree(tmp);
            try (TarArchiveInputStream tar = new TarArchiveInputStream(new BZip2CompressorInputStream(new BufferedInputStream(new FileInputStream(part))))) {
                String root = tmp.getCanonicalPath() + File.separator;
                for (TarArchiveEntry e; (e = tar.getNextTarEntry()) != null; ) {
                    File out = new File(tmp, e.getName());
                    if (!out.getCanonicalPath().startsWith(root)) continue;  // no paths outside the folder
                    if (e.isDirectory()) { //noinspection ResultOfMethodCallIgnored
                        out.mkdirs(); continue; }
                    if (!e.isFile()) continue;
                    //noinspection ResultOfMethodCallIgnored
                    out.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[1 << 16];
                        for (int n; (n = tar.read(buf)) > 0; ) os.write(buf, 0, n);
                    }
                }
            }
            deleteTree(folder(vid));
            if (!new File(tmp, name).renameTo(folder(vid))) throw new IOException("kurulamadı");
            deleteTree(tmp);
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            boolean ok = installed(vid);
            synchronized (p) { p.put("state", ok ? "ready" : "error"); }
            store.log("info", "voice " + vid + " installed");
        } catch (Exception e) {
            synchronized (p) { try { p.put("state", "error").put("message", String.valueOf(e.getMessage())); } catch (JSONException ignored) { } }
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            deleteTree(tmp);
            store.log("error", "voice " + vid + ": " + e);
        }
    }

    void remove(String vid) {
        if (!VOICES.containsKey(vid)) return;
        if (vid.equals(selected)) selected = null;
        maker.execute(() -> {
            if (vid.equals(ttsVoice) && tts != null) { tts.release(); tts = null; ttsVoice = null; }
            deleteTree(folder(vid));
        });
        progress.remove(vid);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** On the maker thread only. */
    private OfflineTts engine(String vid) {
        if (tts != null && vid.equals(ttsVoice)) return tts;
        if (tts != null) tts.release();
        File d = folder(vid);
        File[] onnx = d.listFiles((x, n) -> n.endsWith(".onnx"));
        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        vits.setModel(onnx[0].getPath());
        vits.setTokens(new File(d, "tokens.txt").getPath());
        vits.setDataDir(new File(d, "espeak-ng-data").getPath());
        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setVits(vits);
        model.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(model);
        config.setMaxNumSentences(1);
        tts = new OfflineTts(null, config);  // no AssetManager: the files are on disk
        ttsVoice = vid;
        return tts;
    }

    private Future<GeneratedAudio> make(String text, float rate) {
        String vid = selected;
        return maker.submit(() -> engine(vid).generate(text, 0, rate > 0 ? rate : 1f));
    }

    /** Same key for a prepared sentence and the one asked for (rate rounded: 0.9 and 0.89999 are one speed). */
    private String key(String text, float rate) {
        return selected + "|" + Math.round(rate * 100) + "|" + text.trim();
    }

    /** Makes a coming sentence ahead of time (the page knows what comes next). */
    void prefetch(String text, float rate) {
        if (selected == null || text == null || text.trim().isEmpty()) return;
        String key = key(text, rate);
        if (ahead.containsKey(key)) return;
        if (ahead.size() > 4) ahead.clear();
        ahead.put(key, make(text, rate));
    }

    /** Speaks one sentence; events as Android's TTS would send them: start, end, error. */
    void speak(String id, String text, float rate, Speech.Events events) {
        String key = key(text, rate);
        Future<GeneratedAudio> f = ahead.remove(key);
        Future<GeneratedAudio> audio = f != null ? f : make(text, rate);
        int gen = generation;
        player.execute(() -> {
            try {
                GeneratedAudio a = audio.get();
                if (gen != generation) return;
                play(id, a, gen, events);
            } catch (Exception e) {
                if (gen == generation) events.onSpeech(id, "error", 0);
                store.log("error", "neural voice: " + e);
            }
        });
    }

    private void play(String id, GeneratedAudio a, int gen, Speech.Events events) {
        float[] s = a.getSamples();
        short[] pcm = new short[s.length];
        for (int i = 0; i < s.length; i++) pcm[i] = (short) (Math.max(-1f, Math.min(1f, s[i])) * 32767);
        int min = AudioTrack.getMinBufferSize(a.getSampleRate(), AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(a.getSampleRate())
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min, 8192)).setTransferMode(AudioTrack.MODE_STREAM).build();
        track = t;
        try {
            t.play();
            events.onSpeech(id, "start", 0);
            int off = 0;
            while (off < pcm.length && gen == generation) {
                int n = t.write(pcm, off, Math.min(4096, pcm.length - off));
                if (n <= 0) break;
                off += n;
            }
            // wait until what was written has been heard
            while (gen == generation && t.getPlaybackHeadPosition() < pcm.length) Thread.sleep(20);
            if (gen == generation) events.onSpeech(id, "end", 0);
        } catch (InterruptedException ignored) {
        } finally {
            track = null;
            try { t.stop(); } catch (IllegalStateException ignored) { }
            t.release();
        }
    }

    void stop() {
        generation++;
        ahead.clear();
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); t.flush(); } catch (IllegalStateException ignored) { }
        }
    }

    void shutdown() {
        stop();
        maker.shutdownNow();
        player.shutdownNow();
    }
}

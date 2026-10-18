package com.tahir.ayrac;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Offline English-Turkish dictionary (en-tr.sqlite from desktop/build_dict.py). Same rules as ayrac_app.Dictionary. */
final class Dictionary {
    /** Words that can sit inside a separable phrasal verb: "take it off", "pick them up". */
    private static final Set<String> OBJECT_WORDS = new HashSet<>(Arrays.asList(
            "it", "him", "her", "them", "me", "us", "you", "this", "that", "these", "those", "one", "something", "someone"));
    /** Inflections only; -er/-est/-ly make new words. */
    private static final String[][] VARIANTS = {
            {"'s$", ""}, {"s'$", "s"}, {"ies$", "y"}, {"ied$", "y"}, {"(.)\\1ed$", "$1"}, {"(.)\\1ing$", "$1"},
            {"ing$", "e"}, {"ing$", ""}, {"ed$", "e"}, {"ed$", ""}, {"es$", ""}, {"s$", ""}};
    private static final Pattern USAGE_LABEL = Pattern.compile("\\(.*\\)\\.?");

    /** The four tables of en-tr.sqlite. Android reads them with its own SQLite; the unit tests use JDBC. */
    interface Source {
        /** meta rows as {k, v}. */
        List<String[]> meta();
        /** entries.data (JSON) for a word, or null. */
        String entryData(String word);
        /** forms row {lemma, note}, or null. */
        String[] form(String word);
        /** freq.zipf, or null (also when the table is missing: dictionaries before 1.7.0). */
        Double zipf(String word);
    }

    /** Android: the dictionary file opened read-only with android.database.sqlite. */
    static final class AndroidSource implements Source {
        private final SQLiteDatabase db;

        AndroidSource(File file) {
            db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        }

        @Override public List<String[]> meta() {
            List<String[]> out = new ArrayList<>();
            try (Cursor c = db.rawQuery("SELECT k, v FROM meta", null)) {
                while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1)});
            } catch (SQLiteException ignored) {
            }
            return out;
        }

        @Override public String entryData(String word) {
            try (Cursor c = db.rawQuery("SELECT data FROM entries WHERE word = ?", new String[]{word})) {
                return c.moveToFirst() ? c.getString(0) : null;
            }
        }

        @Override public String[] form(String word) {
            try (Cursor c = db.rawQuery("SELECT lemma, note FROM forms WHERE form = ?", new String[]{word})) {
                return c.moveToFirst() ? new String[]{c.getString(0), c.getString(1)} : null;
            }
        }

        @Override public Double zipf(String word) {
            try (Cursor c = db.rawQuery("SELECT zipf FROM freq WHERE word = ?", new String[]{word})) {
                return c.moveToFirst() ? c.getDouble(0) : null;
            } catch (SQLiteException e) {
                return null;
            }
        }
    }

    final File file;
    final JSONObject meta = new JSONObject();
    private final Source db;

    Dictionary(File file) {
        this(file, new AndroidSource(file));
    }

    Dictionary(File file, Source source) {
        this.file = file;
        db = source;
        for (String[] kv : db.meta()) {
            try {
                meta.put(kv[0], kv[1]);
            } catch (JSONException ignored) {
            }
        }
    }

    synchronized JSONArray entry(String word) {
        String data = db.entryData(word);
        if (data == null) return null;
        try {
            JSONArray raw = new JSONArray(data), out = new JSONArray();
            for (int i = 0; i < raw.length(); i++) {
                JSONObject e = raw.getJSONObject(i);
                if (e.has("k")) {  // Turkish Wiktionary sometimes has only a label such as "(Birleşik Krallık)"
                    JSONArray k = e.getJSONArray("k"), keep = new JSONArray();
                    for (int j = 0; j < k.length(); j++) {
                        String s = k.getString(j);
                        if (!USAGE_LABEL.matcher(s.trim()).matches()) keep.put(s);
                    }
                    if (keep.length() == 0) e.remove("k"); else e.put("k", keep);
                }
                if (e.has("t") || e.has("d") || e.has("k")) out.put(e);
            }
            return out.length() == 0 ? null : out;
        } catch (JSONException e) {
            return null;
        }
    }

    private synchronized String[] form(String word) {
        return db.form(word);
    }

    private synchronized Double zipf(String word) {
        return db.zipf(word);
    }

    /**
     * {word: [lemma, zipf]} for many words at once (book level, hard words before a chapter); same rules as
     * ayrac_app.Dictionary.profile. Words the frequency list does not know (mostly names) are left out.
     */
    JSONObject profile(JSONArray words) throws JSONException {
        JSONObject out = new JSONObject();
        for (int i = 0; words != null && i < words.length(); i++) {
            String w = words.optString(i, "").toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("^[ ']+|[ ']+$", "");
            if (w.isEmpty() || out.has(w)) continue;
            String[] f = form(w);
            String lemma = f != null ? f[0].toLowerCase(Locale.ROOT) : w;
            Double z = zipf(lemma);
            if (z == null && lemma.equals(w)) {
                for (String v : variants(w)) {
                    z = zipf(v);
                    if (z != null) { lemma = v; break; }
                }
            }
            Double own = lemma.equals(w) ? null : zipf(w);
            if (own != null) {
                if (z == null || own - z >= 1) lemma = w;  // far more common than its "lemma": "the" is listed as a form of "thee"
                z = z == null ? own : Math.max(z, own);
            }
            if (z != null) out.put(w, new JSONArray().put(lemma).put(Math.round(z * 100) / 100.0));
        }
        return out;
    }

    static String cefrFromZipf(Double z) {
        if (z == null) return "";
        double[] limits = {5.5, 4.8, 4.2, 3.7, 3.2};
        String[] levels = {"A1", "A2", "B1", "B2", "C1"};
        for (int i = 0; i < limits.length; i++) if (z >= limits[i]) return levels[i];
        return "C2";
    }

    private static List<String> variants(String w) {
        List<String> out = new ArrayList<>();
        for (String[] r : VARIANTS) {
            String v = w.replaceAll(r[0], r[1]);
            if (!v.equals(w) && v.length() > 1 && !out.contains(v)) out.add(v);
        }
        return out;
    }

    /** {lemma, note} for an inflected form, or {null, null}. */
    private String[] lemmaOf(String w) {
        String[] f = form(w);
        if (f != null) return new String[]{f[0].toLowerCase(Locale.ROOT), f[1]};
        for (String v : variants(w)) if (entry(v) != null) return new String[]{v, null};
        return new String[]{null, null};
    }

    private static boolean hasTranslations(JSONArray entries) {
        for (int i = 0; entries != null && i < entries.length(); i++) if (entries.optJSONObject(i).has("t")) return true;
        return false;
    }

    private static boolean hasMeaning(JSONArray entries) {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e.has("t") || e.has("k")) return true;
        }
        return false;
    }

    private List<String> withLemma(String x) {
        List<String> out = new ArrayList<>();
        out.add(x);
        String l = lemmaOf(x)[0];
        if (l != null && !l.equals(x)) out.add(l);
        return out;
    }

    private static List<String> lowerWords(JSONArray a, boolean fromEnd) {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "");
            if (!s.isEmpty()) out.add(s.toLowerCase(Locale.ROOT).replace('’', '\''));
        }
        if (fromEnd) return out.size() > 2 ? out.subList(out.size() - 2, out.size()) : out;
        return out.size() > 2 ? out.subList(0, 2) : out;
    }

    JSONObject lookup(String word, JSONArray prevWords, JSONArray nextWords) throws JSONException {
        String w = word == null ? "" : word.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("^[ ']+|[ ']+$", "");
        JSONObject res = new JSONObject().put("found", false);
        if (w.isEmpty()) return res;
        res.put("word", w);
        JSONArray own = entry(w);
        String[] ln = lemmaOf(w);
        String lemma = ln[0], note = ln[1];
        JSONArray lem = lemma != null && !lemma.equals(w) ? entry(lemma) : null;
        if (own != null && lem != null && !hasTranslations(own) && hasTranslations(lem)) {
            // An inflected form ("looked") whose own entry has no Turkish translation: lead with the base word.
            String formNote = null;
            for (int i = 0; i < own.length() && formNote == null; i++) {
                JSONArray k = own.getJSONObject(i).optJSONArray("k");
                for (int j = 0; k != null && j < k.length(); j++) {
                    String s = k.getString(j);
                    if (s.contains("çekim") || s.contains("hâli")) { formNote = s; break; }
                }
            }
            res.put("found", true).put("head", lemma).put("note", formNote != null ? formNote : note != null ? note : "")
                    .put("entries", lem).put("also", new JSONObject().put("head", w).put("note", "").put("entries", own));
        } else if (own != null) {
            res.put("found", true).put("head", w).put("entries", own);
            if (lem != null) res.put("also", new JSONObject().put("head", lemma).put("note", note != null ? note : "").put("entries", lem));
        } else if (lem != null) {
            res.put("found", true).put("head", lemma).put("note", note != null ? note : "").put("entries", lem);
        }

        // Phrasal verbs, idioms and compounds around the word: "took off", tapping "off", "take it off".
        List<String> prev = lowerWords(prevWords, true), next = lowerWords(nextWords, false);
        List<String> candidates = new ArrayList<>();
        for (String b : withLemma(w)) {
            if (!next.isEmpty()) candidates.add(b + " " + next.get(0));
            if (next.size() > 1) {
                candidates.add(b + " " + next.get(0) + " " + next.get(1));
                if (OBJECT_WORDS.contains(next.get(0))) candidates.add(b + " " + next.get(1));
            }
        }
        if (!prev.isEmpty()) {
            for (String p1 : withLemma(prev.get(prev.size() - 1))) {
                candidates.add(p1 + " " + w);
                if (!next.isEmpty()) candidates.add(p1 + " " + w + " " + next.get(0));
            }
        }
        if (prev.size() > 1) {
            String p1 = prev.get(1);
            for (String p2 : withLemma(prev.get(0))) {
                candidates.add(p2 + " " + p1 + " " + w);
                if (OBJECT_WORDS.contains(p1)) candidates.add(p2 + " " + w);
            }
        }
        candidates.sort((a, b) -> Integer.compare(b.split(" ").length, a.split(" ").length));
        JSONArray phrases = new JSONArray();
        for (String c : new LinkedHashSet<>(candidates)) {
            JSONArray e = entry(c);
            if (e != null && hasMeaning(e)) phrases.put(new JSONObject().put("head", c).put("entries", e));
            if (phrases.length() >= 3) break;
        }
        if (phrases.length() > 0) res.put("phrases", phrases);
        if (res.has("head")) {
            Double z = zipf(res.getString("head"));
            res.put("zipf", z == null ? JSONObject.NULL : z).put("cefr", cefrFromZipf(z));
        }
        return res;
    }
}

package com.tahir.ayrac;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs desktop/tests/dict_cases.json (the cases the Python tests use) against the Java dictionary, so a fix on one
 * side that is missing on the other shows up as a failing test. Needs en-tr.sqlite: AYRAC_DICT, or
 * %TEMP%\ayrac-build\dict\en-tr.sqlite; skipped when it is not found.
 * Run: powershell -ExecutionPolicy Bypass -File android\test.ps1
 */
public class DictionaryTest {
    private static Connection con;
    private static Dictionary dict;
    private static JSONObject cases;

    /** The same four tables through JDBC instead of android.database. */
    private static final class JdbcSource implements Dictionary.Source {
        private String one(String sql, String arg, int columns, String[] out) {
            try (PreparedStatement st = con.prepareStatement(sql)) {
                if (arg != null) st.setString(1, arg);
                try (ResultSet rs = st.executeQuery()) {
                    if (!rs.next()) return null;
                    for (int i = 0; i < columns; i++) out[i] = rs.getString(i + 1);
                    return out[0];
                }
            } catch (SQLException e) {
                return null;
            }
        }

        @Override public List<String[]> meta() {
            List<String[]> out = new ArrayList<>();
            try (PreparedStatement st = con.prepareStatement("SELECT k, v FROM meta"); ResultSet rs = st.executeQuery()) {
                while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2)});
            } catch (SQLException ignored) {
            }
            return out;
        }

        @Override public String entryData(String word) {
            return one("SELECT data FROM entries WHERE word = ?", word, 1, new String[1]);
        }

        @Override public String[] form(String word) {
            String[] out = new String[2];
            return one("SELECT lemma, note FROM forms WHERE form = ?", word, 2, out) == null ? null : out;
        }

        @Override public Double zipf(String word) {
            String z = one("SELECT zipf FROM freq WHERE word = ?", word, 1, new String[1]);
            return z == null ? null : Double.valueOf(z);
        }
    }

    private static File dictionaryFile() {
        String env = System.getenv("AYRAC_DICT");
        if (env != null && new File(env).exists()) return new File(env);
        File tmp = new File(System.getenv().getOrDefault("TEMP", ""), "ayrac-build/dict/en-tr.sqlite");
        return tmp.exists() ? tmp : null;
    }

    private static File casesFile() {
        // Gradle runs unit tests in android/app.
        for (String p : new String[]{"../../desktop/tests/dict_cases.json", "../desktop/tests/dict_cases.json"}) {
            File f = new File(p);
            if (f.exists()) return f;
        }
        throw new IllegalStateException("dict_cases.json not found from " + new File(".").getAbsolutePath());
    }

    @BeforeClass
    public static void open() throws Exception {
        cases = new JSONObject(new String(Files.readAllBytes(casesFile().toPath()), StandardCharsets.UTF_8));
        File f = dictionaryFile();
        if (f == null) return;
        con = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath().replace('\\', '/') + "?open_mode=1");
        dict = new Dictionary(f, new JdbcSource());
    }

    @AfterClass
    public static void close() throws SQLException {
        if (con != null) con.close();
    }

    private static JSONArray arr(JSONObject c, String key) {
        return c.has(key) ? c.optJSONArray(key) : new JSONArray();
    }

    private static List<String> phrases(JSONObject res) {
        List<String> out = new ArrayList<>();
        JSONArray p = res.optJSONArray("phrases");
        for (int i = 0; p != null && i < p.length(); i++) out.add(p.optJSONObject(i).optString("head"));
        return out;
    }

    private static List<String> translations(JSONObject res) {
        List<String> out = new ArrayList<>();
        JSONArray entries = res.optJSONArray("entries");
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONArray t = entries.optJSONObject(i).optJSONArray("t");
            for (int j = 0; t != null && j < t.length(); j++) {
                for (String w : t.optJSONArray(j).optString(1).split(", ")) out.add(w);
            }
        }
        return out;
    }

    private static String label(JSONObject c) throws org.json.JSONException {
        return arr(c, "prev").join(" ").replace("\"", "") + " [" + c.optString("word") + "] " + arr(c, "next").join(" ").replace("\"", "");
    }

    @Test
    public void lookupCases() throws Exception {
        assumeTrue("en-tr.sqlite not found", dict != null);
        JSONArray list = cases.getJSONArray("lookup");
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.getJSONObject(i);
            String what = label(c);
            JSONObject res = dict.lookup(c.getString("word"), arr(c, "prev"), arr(c, "next"));
            if (c.has("found") && !c.getBoolean("found")) {
                assertFalse(what, res.getBoolean("found"));
                continue;
            }
            assertTrue(what, res.getBoolean("found"));
            if (c.has("head")) assertEquals(what, c.getString("head"), res.optString("head"));
            if (c.has("head_or_also")) {
                JSONObject also = res.optJSONObject("also");
                String want = c.getString("head_or_also");
                assertTrue(what, want.equals(res.optString("head")) || (also != null && want.equals(also.optString("head"))));
            }
            if (c.has("phrase")) assertTrue(what + " phrases " + phrases(res), phrases(res).contains(c.getString("phrase")));
            if (c.has("tr")) assertTrue(what + " tr " + translations(res), translations(res).contains(c.getString("tr")));
            if (c.has("cefr")) assertEquals(what + " zipf " + res.opt("zipf"), c.getString("cefr"), res.optString("cefr"));
        }
    }

    @Test
    public void profileCases() throws Exception {
        assumeTrue("en-tr.sqlite not found", dict != null);
        JSONArray list = cases.getJSONArray("profile");
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.getJSONObject(i);
            String word = c.getString("word"), key = word.toLowerCase();
            JSONObject p = dict.profile(new JSONArray().put(word));
            if (c.optBoolean("missing")) {
                assertFalse(word, p.has(key));
                continue;
            }
            assertTrue(word, p.has(key));
            assertEquals(word, c.getString("lemma"), p.getJSONArray(key).getString(0));
            if (c.has("zipf_min")) assertTrue(word, p.getJSONArray(key).getDouble(1) > c.getDouble("zipf_min"));
        }
    }
}

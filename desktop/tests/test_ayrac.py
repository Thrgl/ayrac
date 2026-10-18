"""Tests for the desktop app's dictionary lookups and backups.

Run:  & "$env:TEMP\\ayrac-build\\venv\\Scripts\\python.exe" -m pytest desktop\\tests -q
The dictionary tests need en-tr.sqlite (built by build_dict.py); set AYRAC_DICT to its path, or it is looked up in
%TEMP%\\ayrac-build\\dict and desktop\\dist\\dict. They are skipped when it is not found.
"""

import importlib
import json
import os
import sys
import zipfile
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))


@pytest.fixture()
def app(tmp_path, monkeypatch):
    """ayrac_app with %APPDATA% pointed at a temporary folder, so tests never touch the real library."""
    monkeypatch.setenv("APPDATA", str(tmp_path))
    import ayrac_app
    return importlib.reload(ayrac_app)


def dictionary_path():
    candidates = [os.environ.get("AYRAC_DICT"),
                  Path(os.environ.get("TEMP", "")) / "ayrac-build" / "dict" / "en-tr.sqlite",
                  HERE.parent / "dist" / "dict" / "en-tr.sqlite"]
    return next((Path(c) for c in candidates if c and Path(c).exists()), None)


@pytest.fixture(scope="module")
def d():
    path = dictionary_path()
    if not path:
        pytest.skip("en-tr.sqlite not found")
    import ayrac_app
    return ayrac_app.Dictionary(path)


def translations(res):
    return [w for e in res.get("entries", []) for _, words in e.get("t", []) for w in words.split(", ")]


def phrases(res):
    return [p["head"] for p in res.get("phrases", [])]


# ---------- dictionary ----------

@pytest.mark.parametrize("word, prev, nxt", [
    ("take", ["will"], ["off", "the"]),
    ("took", ["plane"], ["off", "at"]),      # past form
    ("off", ["plane", "took"], ["at"]),      # tapping the second word
    ("take", ["please"], ["it", "off"]),     # object pronoun in between
    ("off", ["take", "it"], []),
])
def test_take_off_is_found(d, word, prev, nxt):
    assert "take off" in phrases(d.lookup(word, prev, nxt))


def test_look_after(d):
    res = d.lookup("looked", ["Lewis", "had"], ["after", "her"])
    assert res["head"] == "look"
    assert "look after" in phrases(res)


def test_inflected_form_leads_with_base_word(d):
    res = d.lookup("travelled")
    assert res["found"] and res["head"] == "travel"
    assert "seyahat etmek" in translations(res)


def test_irregular_plural(d):
    res = d.lookup("children")
    assert res["found"]
    assert res["head"] == "child" or (res.get("also") or {}).get("head") == "child"


def test_derived_words_are_not_cut_down(d):
    # "-er" makes a new word: "charmer" must not turn into "charm".
    res = d.lookup("charmer", ["snake"], [])
    assert res["head"] == "charmer"
    assert "snake charmer" in phrases(res)


def test_unknown_word(d):
    assert d.lookup("zzzzqx")["found"] is False


def test_usage_label_is_not_a_definition(d):
    for res in (d.lookup("travelled"), d.lookup("colour")):
        for e in res.get("entries", []):
            for k in e.get("k", []):
                assert not (k.startswith("(") and k.rstrip(".").endswith(")"))


@pytest.mark.parametrize("word, expected", [("the", "A1"), ("travel", "A2"), ("impressed", "B1"), ("charmer", "C2")])
def test_cefr_estimate(d, word, expected):
    res = d.lookup(word)
    assert res["cefr"] == expected, (word, res.get("zipf"))


def test_cefr_scale():
    import ayrac_app
    assert [ayrac_app.cefr_from_zipf(z) for z in (7, 5, 4.5, 4, 3.5, 2)] == ["A1", "A2", "B1", "B2", "C1", "C2"]
    assert ayrac_app.cefr_from_zipf(None) == ""


# ---------- backup ----------

def add_book(app, api, book_id="id:test-book"):
    epub = b"PK fake epub"
    meta = {"id": book_id, "title": "Test", "percent": 0.5}
    import base64
    assert api.book_put(meta, base64.b64encode(epub).decode(), base64.b64encode(b"cover").decode(), "image/jpeg")
    return epub


def test_backup_round_trip(app, tmp_path):
    api = app.Api(None)
    epub = add_book(app, api)
    api.kv_set("words", [{"id": "w-travel", "head": "travel"}])
    out = tmp_path / "backup.zip"
    res = api._write_backup(out)
    assert res == {"ok": True, "path": str(out), "books": 1, "words": 1}

    # Change everything, then restore.
    api.book_delete("id:test-book")
    api.kv_set("words", [])
    res = api._restore_backup(out)
    assert res["ok"] and res["books"] == 1 and res["words"] == 1
    assert api.kv_all()["words"][0]["head"] == "travel"
    assert [b["title"] for b in api.books_list()] == ["Test"]
    import base64
    assert base64.b64decode(api.book_file("id:test-book")) == epub
    # The state before restoring was saved first.
    assert Path(res["safety"]).exists()


def test_settings_move_from_json_to_sqlite(app, tmp_path):
    app.DATA_DIR.mkdir(parents=True, exist_ok=True)
    app.KV_FILE.write_text(json.dumps({"words": [{"id": "w-a", "head": "a"}], "prefs": {"theme": "sepia"}}), encoding="utf-8")
    api = app.Api(None)
    assert api.kv_all()["prefs"] == {"theme": "sepia"}
    assert not app.KV_FILE.exists() and list(app.DATA_DIR.glob("data.json.*.bak"))
    api.kv_set("pos:x", {"i": 2, "frac": 0.5})
    api.kv_del("prefs")
    again = app.Api(None)  # read back from SQLite
    assert again.kv_all() == {"words": [{"id": "w-a", "head": "a"}], "pos:x": {"i": 2, "frac": 0.5}}


def test_books_are_served_over_local_http(app):
    import urllib.error
    import urllib.request
    api = app.Api(None)
    epub = add_book(app, api)
    with urllib.request.urlopen(api.book_url("id:test-book")) as r:
        assert r.read() == epub and r.headers["Access-Control-Allow-Origin"] == "*"
    cover = api.books_list()[0]["cover"]
    with urllib.request.urlopen(cover) as r:
        assert r.read() == b"cover" and r.headers["Content-Type"] == "image/jpeg"
    wrong = api.book_url("id:test-book").replace(api._server.token, "x" * 24)
    with pytest.raises(urllib.error.HTTPError):
        urllib.request.urlopen(wrong)


def test_restore_rejects_other_zips(app, tmp_path):
    api = app.Api(None)
    bad = tmp_path / "other.zip"
    with zipfile.ZipFile(bad, "w") as z:
        z.writestr("hello.txt", "not a backup")
    res = api._restore_backup(bad)
    assert res["ok"] is False


def test_restore_ignores_unexpected_paths(app, tmp_path):
    api = app.Api(None)
    evil = tmp_path / "evil.zip"
    with zipfile.ZipFile(evil, "w") as z:
        z.writestr("ayrac-backup.json", json.dumps({"app": "Ayrac"}))
        z.writestr("data.json", "{}")
        z.writestr("books/../../escape.txt", "x")
        z.writestr("books/notabook.exe", "x")
    assert api._restore_backup(evil)["ok"]
    assert not (tmp_path / "escape.txt").exists()
    assert not any(app.BOOKS_DIR.glob("*.exe"))

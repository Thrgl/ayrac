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

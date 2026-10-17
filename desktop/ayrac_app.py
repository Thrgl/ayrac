"""Ayraç desktop app: the EPUB reader page in a native window (pywebview / Edge WebView2).

The page (web/index.html, built from ../ayrac.html) talks to this file through `window.pywebview.api`:
settings, vocabulary and reading positions go to %APPDATA%/Ayrac/data.json, books to %APPDATA%/Ayrac/books,
and Claude requests go to the Anthropic API with the key the user saved (Windows Credential Manager).
"""

import base64
import hashlib
import json
import os
import re
import sqlite3
import sys
import threading
import time
import traceback
import urllib.parse
import zipfile
from pathlib import Path

import anthropic
import keyring
import keyring.errors
import webview

APP_NAME = "Ayraç"
DATA_DIR = Path(os.environ.get("APPDATA") or Path.home()) / "Ayrac"
BOOKS_DIR = DATA_DIR / "books"
KV_FILE = DATA_DIR / "data.json"
KEYRING_SERVICE = "Ayrac"
KEYRING_USER = "anthropic_api_key"
MODELS = {"opus": "claude-opus-5-5", "haiku": "claude-haiku-5-5"}


def resource(rel: str) -> Path:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base / rel


def app_version() -> str:
    for p in (resource("VERSION"), Path(__file__).resolve().parent / "VERSION"):
        if p.exists():
            return p.read_text(encoding="utf-8").strip()
    return "dev"


LOG_FILE = DATA_DIR / "logs" / "ayrac.log"


def log(level: str, msg: str) -> None:
    """Append one line to %APPDATA%/Ayrac/logs/ayrac.log (kept under ~1 MB)."""
    try:
        LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
        if LOG_FILE.exists() and LOG_FILE.stat().st_size > 1_000_000:
            os.replace(LOG_FILE, LOG_FILE.with_suffix(".old.log"))
        stamp = time.strftime("%Y-%m-%d %H:%M:%S")
        with LOG_FILE.open("a", encoding="utf-8") as f:
            f.write(f"{stamp} [{level}] {msg}\n")
    except OSError:
        pass


def write_atomic(path: Path, data: bytes) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_bytes(data)
    os.replace(tmp, path)


def book_stem(book_id: str) -> str:
    return hashlib.sha1(book_id.encode("utf-8")).hexdigest()[:20]


DICT_FILE = "en-tr.sqlite"
# Files a backup may contain under books/ (and nothing else, so a ZIP cannot write outside that folder).
BOOK_FILE_RE = re.compile(r"^[0-9a-f]{20}\.(epub|json|cover)$")
# Words that can sit inside a separable phrasal verb: "take it off", "pick them up".
OBJECT_WORDS = {"it", "him", "her", "them", "me", "us", "you", "this", "that", "these", "those", "one", "something", "someone"}


def find_dictionary() -> Path | None:
    """The dictionary ships next to Ayrac.exe (install folder); during development it sits in ./dict."""
    for base in (Path(sys.executable).resolve().parent, Path(__file__).resolve().parent, resource("")):
        p = base / "dict" / DICT_FILE
        if p.exists():
            return p
    return None


class Dictionary:
    """Offline English-Turkish dictionary built from Wiktionary by build_dict.py."""

    def __init__(self, path: Path):
        self.path = path
        self._con = sqlite3.connect(f"file:{path}?mode=ro", uri=True, check_same_thread=False)
        self._lock = threading.Lock()
        self.meta = dict(self._con.execute("SELECT k, v FROM meta").fetchall())

    def _entry(self, word):
        with self._lock:
            row = self._con.execute("SELECT data FROM entries WHERE word = ?", (word,)).fetchone()
        if not row:
            return None
        out = []
        for e in json.loads(row[0]):
            # Turkish Wiktionary sometimes has only a usage label as the "definition", e.g. "(Birleşik Krallık)".
            if "k" in e:
                e["k"] = [k for k in e["k"] if not re.fullmatch(r"\(.*\)\.?", k.strip())]
                if not e["k"]:
                    del e["k"]
            if any(key in e for key in ("t", "d", "k")):
                out.append(e)
        return out or None

    def _form(self, word):
        with self._lock:
            return self._con.execute("SELECT lemma, note FROM forms WHERE form = ?", (word,)).fetchone()

    @staticmethod
    def _variants(w):
        out = []
        for pattern, repl in ((r"'s$", ""), (r"s'$", "s"), (r"ies$", "y"), (r"ied$", "y"), (r"(.)\1ed$", r"\1"),
                              (r"(.)\1ing$", r"\1"), (r"ing$", "e"), (r"ing$", ""), (r"ed$", "e"), (r"ed$", ""),
                              (r"es$", ""), (r"s$", "")):  # inflections only; -er/-ly make new words
            v = re.sub(pattern, repl, w)
            if v != w and len(v) > 1 and v not in out:
                out.append(v)
        return out

    def lemma_of(self, w):
        """(lemma, note) for an inflected form, or (None, None)."""
        f = self._form(w)
        if f:
            return f[0].lower(), f[1]
        for v in self._variants(w):
            if self._entry(v):
                return v, None
        return None, None

    def lookup(self, word, prev=None, nxt=None):
        w = (word or "").lower().replace("’", "'").strip(" '")
        if not w:
            return {"found": False}
        res = {"found": False, "word": w}
        own = self._entry(w)
        lemma, note = self.lemma_of(w)
        lem_entries = self._entry(lemma) if lemma and lemma != w else None
        has_tr = lambda entries: any("t" in e for e in entries or [])
        if own and lem_entries and not has_tr(own) and has_tr(lem_entries):
            # An inflected form ("looked", "travelled") whose own entry has no Turkish translation:
            # lead with the base word; its Turkish grammar note ("look sözcüğünün geçmiş zaman çekimi") explains the form.
            form_note = next((k for e in own for k in e.get("k", []) if "çekim" in k or "hâli" in k), None)
            res.update(found=True, head=lemma, note=form_note or note or "", entries=lem_entries,
                       also={"head": w, "note": "", "entries": own})
        elif own:
            res.update(found=True, head=w, entries=own)
            if lem_entries:
                res["also"] = {"head": lemma, "note": note or "", "entries": lem_entries}
        elif lem_entries:
            res.update(found=True, head=lemma, note=note or "", entries=lem_entries)

        # Phrasal verbs, idioms and compounds around the tapped word ("looked after" -> "look after").
        # Works from either word ("took" or "off"), from past forms ("took off" -> "take off")
        # and across an object pronoun ("take it off" -> "take off").
        prev = [p.lower().replace("’", "'") for p in (prev or []) if p][-2:]
        nxt = [n.lower().replace("’", "'") for n in (nxt or []) if n][:2]

        def with_lemma(x):
            lx, _ = self.lemma_of(x)
            return [x] + ([lx] if lx and lx != x else [])

        bases = with_lemma(w)
        candidates = []
        for b in bases:
            if nxt:
                candidates.append(f"{b} {nxt[0]}")
            if len(nxt) > 1:
                candidates.append(f"{b} {nxt[0]} {nxt[1]}")
                if nxt[0] in OBJECT_WORDS:
                    candidates.append(f"{b} {nxt[1]}")
        if prev:
            for p1 in with_lemma(prev[-1]):
                candidates.append(f"{p1} {w}")
                if nxt:
                    candidates.append(f"{p1} {w} {nxt[0]}")
        if len(prev) > 1:
            for p2 in with_lemma(prev[-2]):
                candidates.append(f"{p2} {prev[-1]} {w}")
                if prev[-1] in OBJECT_WORDS:
                    candidates.append(f"{p2} {w}")
        phrases, seen = [], set()
        for c in sorted(candidates, key=lambda s: -s.count(" ")):
            if c in seen:
                continue
            seen.add(c)
            e = self._entry(c)
            if e and any("t" in x or "k" in x for x in e):
                phrases.append({"head": c, "entries": e})
            if len(phrases) >= 3:
                break
        if phrases:
            res["phrases"] = phrases
        return res


class Api:
    """Methods here are callable from the page as `await window.pywebview.api.<name>(...)`."""

    def __init__(self, initial_path: str | None):
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        BOOKS_DIR.mkdir(exist_ok=True)
        self._lock = threading.Lock()
        self._kv = self._load_kv()
        self._initial_path = initial_path
        self._client = None
        self._client_key = None
        self._dict = None
        self._dict_tried = False
        self._cambridge = None

    # ---------- offline dictionary ----------
    def _dictionary(self):
        if not self._dict_tried:
            self._dict_tried = True
            path = find_dictionary()
            if path:
                try:
                    self._dict = Dictionary(path)
                except sqlite3.Error:
                    self._dict = None
        return self._dict

    def dict_info(self):
        d = self._dictionary()
        return {"available": bool(d), **(d.meta if d else {})}

    def dict_lookup(self, word, prev=None, nxt=None):
        d = self._dictionary()
        if not d:
            return {"found": False, "unavailable": True}
        return d.lookup(word, prev, nxt)

    def dict_lookup_many(self, items):
        """items: [{word, prev, next}] -> list of lookups in the same order (for multi-word selection)."""
        d = self._dictionary()
        if not d:
            return [{"found": False, "unavailable": True} for _ in items]
        return [d.lookup(i.get("word"), i.get("prev"), i.get("next")) for i in items]

    # ---------- Cambridge Dictionary, shown as its own website in an app window ----------
    def open_cambridge(self, word):
        q = urllib.parse.quote((word or "").strip().lower())
        url = f"https://dictionary.cambridge.org/dictionary/english-turkish/{q}"
        win = self._cambridge
        if win is not None:
            try:
                win.load_url(url)
                win.restore()
                win.show()
                return True
            except Exception:
                self._cambridge = None
        win = webview.create_window(f"Cambridge Dictionary · {word}", url, width=980, height=820, text_select=True)

        def on_closed():
            self._cambridge = None

        win.events.closed += on_closed
        self._cambridge = win
        return True

    # ---------- key/value settings (prefs, vocabulary, reading positions) ----------
    def _load_kv(self) -> dict:
        try:
            return json.loads(KV_FILE.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}

    def _save_kv(self) -> None:
        write_atomic(KV_FILE, json.dumps(self._kv, ensure_ascii=False).encode("utf-8"))

    def kv_all(self):
        with self._lock:
            return dict(self._kv)

    def kv_set(self, key, value):
        with self._lock:
            self._kv[str(key)] = value
            self._save_kv()
        return True

    def kv_del(self, key):
        with self._lock:
            self._kv.pop(str(key), None)
            self._save_kv()
        return True

    # ---------- developer mode ----------
    def log(self, level, msg):
        log(str(level), str(msg)[:2000])
        return True

    def app_info(self):
        d = self._dictionary()
        return {
            "version": app_version(),
            "dataDir": str(DATA_DIR),
            "logFile": str(LOG_FILE),
            "books": len(list(BOOKS_DIR.glob("*.epub"))),
            "dictionary": str(d.path) if d else None,
            "devMode": bool(self._kv.get("devMode")),
            "exe": sys.executable,
        }

    def set_dev_mode(self, on):
        self.kv_set("devMode", bool(on))
        log("info", f"developer mode {'on' if on else 'off'}")
        return self.app_info()

    def open_path(self, which):
        target = {"data": DATA_DIR, "log": LOG_FILE, "books": BOOKS_DIR}.get(which)
        if target and target.exists():
            os.startfile(target)
            return True
        return False

    # ---------- backup ----------
    def _write_backup(self, path):
        with self._lock:
            kv = json.dumps(self._kv, ensure_ascii=False)
            words = len(self._kv.get("words") or [])
        files = [f for f in BOOKS_DIR.iterdir() if BOOK_FILE_RE.match(f.name)]
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("ayrac-backup.json", json.dumps(
                {"app": "Ayrac", "version": app_version(), "created": time.strftime("%Y-%m-%d %H:%M:%S")}))
            z.writestr("data.json", kv)
            for f in files:
                z.write(f, "books/" + f.name)
        books = sum(1 for f in files if f.suffix == ".epub")
        log("info", f"backup written: {path} ({books} books, {words} words)")
        return {"ok": True, "path": str(path), "books": books, "words": words}

    def backup_export(self):
        """Ask where to save, then write one ZIP: data.json (settings, notebook, positions) + books/."""
        name = f"Ayrac-yedek-{time.strftime('%Y-%m-%d')}.zip"
        picked = webview.windows[0].create_file_dialog(
            webview.FileDialog.SAVE, directory=str(Path.home() / "Documents"), save_filename=name,
            file_types=("Ayraç yedeği (*.zip)",))
        if not picked:
            return {"ok": False, "cancelled": True}
        path = picked if isinstance(picked, str) else picked[0]
        try:
            return self._write_backup(path)
        except OSError as e:
            log("error", f"backup_export: {e!r}")
            return {"ok": False, "message": str(e)}

    def backup_import(self):
        """Replace the library and notebook with a backup ZIP. The current state is saved first."""
        picked = webview.windows[0].create_file_dialog(
            webview.FileDialog.OPEN, directory=str(Path.home() / "Documents"), file_types=("Ayraç yedeği (*.zip)",))
        if not picked:
            return {"ok": False, "cancelled": True}
        return self._restore_backup(picked if isinstance(picked, str) else picked[0])

    def _restore_backup(self, path):
        try:
            with zipfile.ZipFile(path) as z:
                names = set(z.namelist())
                if "ayrac-backup.json" not in names or "data.json" not in names:
                    return {"ok": False, "message": "Bu dosya bir Ayraç yedeği değil."}
                kv = json.loads(z.read("data.json").decode("utf-8"))
                book_names = [n for n in names if n.startswith("books/") and BOOK_FILE_RE.match(n[6:])]
                safety = DATA_DIR / "backups" / f"geri-yuklemeden-once-{time.strftime('%Y%m%d-%H%M%S')}.zip"
                safety.parent.mkdir(exist_ok=True)
                self._write_backup(safety)
                for f in BOOKS_DIR.iterdir():
                    if BOOK_FILE_RE.match(f.name):
                        f.unlink()
                for n in book_names:
                    (BOOKS_DIR / n[6:]).write_bytes(z.read(n))
            with self._lock:
                self._kv = kv
                self._save_kv()
        except (OSError, ValueError, zipfile.BadZipFile) as e:
            log("error", f"backup_import: {e!r}")
            return {"ok": False, "message": "Yedek okunamadı: " + str(e)}
        books = sum(1 for n in book_names if n.endswith(".epub"))
        log("info", f"backup restored from {path} ({books} books); previous state saved to {safety}")
        return {"ok": True, "books": books, "words": len(kv.get("words") or []), "safety": str(safety)}

    # ---------- library ----------
    def books_list(self):
        out = []
        files = list(BOOKS_DIR.glob("*.json"))
        for meta_file in files:
            try:
                meta = json.loads(meta_file.read_text(encoding="utf-8"))
            except (OSError, ValueError) as e:
                log("error", f"books_list: cannot read {meta_file.name}: {e!r}")
                continue
            if not meta_file.with_suffix(".epub").exists():
                log("warn", f"books_list: {meta_file.name} has no .epub next to it")
                continue
            cover = meta_file.with_suffix(".cover")
            if cover.exists():
                ctype = meta.get("coverType") or "image/jpeg"
                meta["cover"] = f"data:{ctype};base64," + base64.b64encode(cover.read_bytes()).decode("ascii")
            out.append(meta)
        log("info", f"books_list: {len(out)} books ({len(files)} metadata files in {BOOKS_DIR})")
        return out

    def book_meta(self, book_id):
        try:
            return json.loads((BOOKS_DIR / (book_stem(book_id) + ".json")).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None

    def book_put(self, meta, file_b64=None, cover_b64=None, cover_type=None):
        stem = BOOKS_DIR / book_stem(meta["id"])
        epub = stem.with_suffix(".epub")
        if file_b64:
            write_atomic(epub, base64.b64decode(file_b64))
        if not epub.exists():
            return False
        if cover_b64:
            write_atomic(stem.with_suffix(".cover"), base64.b64decode(cover_b64))
            meta["coverType"] = cover_type or "image/jpeg"
        else:
            old = self.book_meta(meta["id"]) or {}
            if old.get("coverType"):
                meta["coverType"] = old["coverType"]
        write_atomic(stem.with_suffix(".json"), json.dumps(meta, ensure_ascii=False).encode("utf-8"))
        return True

    def book_file(self, book_id):
        epub = (BOOKS_DIR / book_stem(book_id)).with_suffix(".epub")
        return base64.b64encode(epub.read_bytes()).decode("ascii") if epub.exists() else None

    def book_delete(self, book_id):
        stem = BOOKS_DIR / book_stem(book_id)
        for ext in (".epub", ".json", ".cover"):
            stem.with_suffix(ext).unlink(missing_ok=True)
        return True

    def initial_file(self):
        path, self._initial_path = self._initial_path, None
        if not path:
            return None
        try:
            p = Path(path)
            return {"name": p.name, "b64": base64.b64encode(p.read_bytes()).decode("ascii")}
        except OSError:
            return None

    # ---------- Claude ----------
    def _key(self):
        env = os.environ.get("ANTHROPIC_API_KEY")
        if env:
            return env, "env"
        try:
            stored = keyring.get_password(KEYRING_SERVICE, KEYRING_USER)
        except keyring.errors.KeyringError:
            stored = None
        return (stored, "keyring") if stored else (None, None)

    def ai_status(self):
        key, source = self._key()
        try:
            in_keyring = bool(keyring.get_password(KEYRING_SERVICE, KEYRING_USER))
        except keyring.errors.KeyringError:
            in_keyring = False
        return {"configured": bool(key), "source": source, "keyring": in_keyring, "model": self._kv.get("aiModel", "opus")}

    def ai_set_key(self, key):
        key = (key or "").strip()
        if key:
            keyring.set_password(KEYRING_SERVICE, KEYRING_USER, key)
        return self.ai_status()

    def ai_clear_key(self):
        try:
            keyring.delete_password(KEYRING_SERVICE, KEYRING_USER)
        except keyring.errors.PasswordDeleteError:
            pass
        return self.ai_status()

    def ai_set_model(self, model):
        if model in MODELS:
            self.kv_set("aiModel", model)
        return self.ai_status()

    def _get_client(self, key):
        if self._client is None or self._client_key != key:
            self._client = anthropic.Anthropic(api_key=key, timeout=120.0, max_retries=2)
            self._client_key = key
        return self._client

    def ask(self, prompt, effort="low"):
        """One single-turn request. Returns {ok, text, truncated} or {ok: False, code, message}."""
        key, _ = self._key()
        if not key:
            return {"ok": False, "code": "no_key"}
        model = MODELS.get(self._kv.get("aiModel", "opus"), MODELS["opus"])
        params = dict(
            model=model,
            max_tokens=16000,
            output_config={"effort": effort if effort in ("low", "medium", "high") else "low"},
            messages=[{"role": "user", "content": str(prompt)}],
        )
        client = self._get_client(key)
        try:
            if model == MODELS["opus"]:
                # On a policy decline, let the API re-run the request on a suitable fallback model.
                response = client.beta.messages.create(
                    betas=["server-side-fallback-2026-07-01"], fallbacks="default", **params
                )
            else:
                response = client.messages.create(**params)
        except anthropic.AuthenticationError:
            return {"ok": False, "code": "bad_key"}
        except anthropic.PermissionDeniedError:
            return {"ok": False, "code": "bad_key"}
        except anthropic.RateLimitError:
            return {"ok": False, "code": "rate_limited"}
        except anthropic.BadRequestError as e:
            return {"ok": False, "code": "bad_request", "message": e.message}
        except anthropic.APIStatusError as e:
            return {"ok": False, "code": "upstream_error", "message": e.message}
        except anthropic.APIConnectionError:
            return {"ok": False, "code": "connection"}

        if response.stop_reason == "refusal":
            return {"ok": False, "code": "refused"}
        text = "".join(block.text for block in response.content if block.type == "text")
        if not text.strip():
            return {"ok": False, "code": "empty_completion"}
        return {"ok": True, "text": text, "truncated": response.stop_reason == "max_tokens"}


_instance_mutex = None


def focus_running_instance() -> bool:
    """Keep one copy running: two WebView2 windows on the same data folder take each other down.
    If Ayraç is already open, bring its window forward and report True."""
    global _instance_mutex
    if sys.platform != "win32":
        return False
    import ctypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateMutexW.restype = ctypes.c_void_p
    _instance_mutex = kernel32.CreateMutexW(None, False, "Local\\AyracSingleInstance")
    if ctypes.get_last_error() != 183:  # ERROR_ALREADY_EXISTS
        return False
    user32 = ctypes.WinDLL("user32")
    hwnd = user32.FindWindowW(None, APP_NAME)
    if hwnd:
        user32.ShowWindow(hwnd, 9)  # SW_RESTORE
        user32.SetForegroundWindow(hwnd)
    return True


def main():
    if focus_running_instance():
        log("info", "second launch: focused the window that is already open")
        return
    initial = next((a for a in sys.argv[1:] if a.lower().endswith(".epub")), None)
    api = Api(initial)
    dev = bool(api.kv_all().get("devMode")) or "--debug" in sys.argv
    log("info", f"start: Ayraç {app_version()} exe={sys.executable} data={DATA_DIR} dev={dev}")
    sys.excepthook = lambda t, v, tb: log("error", "".join(traceback.format_exception(t, v, tb)))
    webview.settings["OPEN_EXTERNAL_LINKS_IN_BROWSER"] = True
    webview.create_window(
        APP_NAME,
        str(resource("web/index.html")),
        js_api=api,
        width=1180,
        height=860,
        min_size=(420, 560),
        background_color="#eef0ec",
        text_select=True,
    )
    webview.start(
        gui="edgechromium",
        private_mode=False,
        storage_path=str(DATA_DIR / "webview"),
        icon=str(resource("ayrac.ico")),
        debug=dev,  # developer mode: right-click → Inspect opens the web inspector
    )


if __name__ == "__main__":
    main()

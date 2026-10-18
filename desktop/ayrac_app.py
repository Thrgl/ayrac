"""Ayraç desktop app: the EPUB reader page in a native window (pywebview / Edge WebView2).

The page (web/index.html, built from ../ayrac.html) talks to this file through `window.pywebview.api`:
settings, vocabulary and reading positions go to %APPDATA%/Ayrac/data.sqlite, books to %APPDATA%/Ayrac/books,
and Claude requests go to the Anthropic API with the key the user saved (Windows Credential Manager).
"""

import base64
import hashlib
import io
import json
import os
import re
import secrets
import shutil
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import traceback
import urllib.parse
import urllib.request
import wave
import zipfile
from array import array
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from multiprocessing import AuthenticationError
from multiprocessing.connection import Client, Listener
from pathlib import Path

import anthropic
import keyring
import keyring.errors
import webview

APP_NAME = "Ayraç"
DATA_DIR = Path(os.environ.get("APPDATA") or Path.home()) / "Ayrac"
BOOKS_DIR = DATA_DIR / "books"
KV_FILE = DATA_DIR / "data.json"  # before 2.1.0; imported into KV_DB once (backups still use this JSON shape)
KV_DB = DATA_DIR / "data.sqlite"
KEYRING_SERVICE = "Ayrac"
KEYRING_USER = "anthropic_api_key"
MODELS = {"opus": "claude-opus-5-5", "haiku": "claude-haiku-5-5"}
# Free books: Project Gutenberg's catalogue feed; the app fetches only from these hosts.
CATALOG_FEED = "https://www.gutenberg.org/ebooks/search.opds/"
CATALOG_HOSTS = {"www.gutenberg.org", "gutenberg.org"}
# Neural reading voices (Piper medium, from the sherpa-onnx model releases): id -> (folder, label, accent, MB)
VOICES = {
    "lessac": ("vits-piper-en_US-lessac-medium", "Lessac · kadın", "en-US", 64),
    "ryan": ("vits-piper-en_US-ryan-medium", "Ryan · erkek", "en-US", 64),
    "alba": ("vits-piper-en_GB-alba-medium", "Alba · kadın", "en-GB", 64),
    "northern": ("vits-piper-en_GB-northern_english_male-medium", "Northern · erkek", "en-GB", 64),
}
VOICE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/{}.tar.bz2"
VOICES_DIR = DATA_DIR / "voices"


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


def cefr_from_zipf(z):
    """Rough CEFR level from how common a word is (Zipf scale). An estimate, not an official level."""
    if z is None:
        return ""
    for limit, level in ((5.5, "A1"), (4.8, "A2"), (4.2, "B1"), (3.7, "B2"), (3.2, "C1")):
        if z >= limit:
            return level
    return "C2"


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

    def _zipf(self, word):
        try:
            with self._lock:
                row = self._con.execute("SELECT zipf FROM freq WHERE word = ?", (word,)).fetchone()
        except sqlite3.OperationalError:  # dictionary built before 1.7.0 has no freq table
            return None
        return row[0] if row else None

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

    def profile(self, words):
        """{word: [lemma, zipf]} for many words at once (book difficulty, hard words before a chapter).
        zipf is the lemma's frequency, or the word's own when the lemma has none; words the frequency list
        does not know (mostly names) are left out."""
        out = {}
        for word in words:
            w = (word or "").lower().replace("’", "'").strip(" '")
            if not w or w in out:
                continue
            f = self._form(w)
            lemma = f[0].lower() if f else w
            z = self._zipf(lemma)
            if z is None and lemma == w:
                for v in self._variants(w):
                    z = self._zipf(v)
                    if z is not None:
                        lemma = v
                        break
            own = self._zipf(w) if lemma != w else None
            if own is not None:
                if z is None or own - z >= 1:  # far more common than its "lemma": "the" is listed as a form of "thee"
                    lemma = w
                z = own if z is None else max(z, own)
            if z is not None:
                out[w] = [lemma, round(z, 2)]
        return out

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
        if res.get("head"):
            z = self._zipf(res["head"])
            res["zipf"] = z
            res["cefr"] = cefr_from_zipf(z)
        return res


class BookServer:
    """Serves book files and covers to the page as plain HTTP (http://127.0.0.1:<port>/<token>/book/<stem>),
    instead of sending whole EPUBs and covers through the bridge as base64. Only this machine can connect,
    and only with the random token of this run."""

    TYPES = {"book": (".epub", "application/epub+zip"), "cover": (".cover", None)}
    AUDIO_KEEP = 16  # spoken sentences kept in memory (the current one and the prefetched next ones)

    def __init__(self):
        self.token = secrets.token_urlsafe(18)
        self.audio = {}  # stem -> WAV bytes, newest last
        self._audio_lock = threading.Lock()
        server = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                parts = self.path.split("?")[0].strip("/").split("/")
                if len(parts) == 3 and parts[0] == server.token and parts[1] == "tts":
                    with server._audio_lock:
                        data = server.audio.get(parts[2])
                    if data is None:
                        self.send_error(404)
                        return
                    self.send_response(200)
                    self.send_header("Content-Type", "audio/wav")
                    self.send_header("Content-Length", str(len(data)))
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers()
                    self.wfile.write(data)
                    return
                if len(parts) != 3 or parts[0] != server.token or parts[1] not in server.TYPES \
                        or not re.fullmatch(r"[0-9a-f]{20}", parts[2]):
                    self.send_error(404)
                    return
                ext, ctype = server.TYPES[parts[1]]
                f = BOOKS_DIR / (parts[2] + ext)
                if not f.is_file():
                    self.send_error(404)
                    return
                if ctype is None:
                    try:
                        ctype = json.loads(f.with_suffix(".json").read_text(encoding="utf-8")).get("coverType") or "image/jpeg"
                    except (OSError, ValueError):
                        ctype = "image/jpeg"
                data = f.read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(data)

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, name="book-server", daemon=True).start()

    def url(self, kind: str, stem: str) -> str:
        return f"http://127.0.0.1:{self.port}/{self.token}/{kind}/{stem}"

    def add_audio(self, wav: bytes) -> str:
        stem = secrets.token_hex(8)
        with self._audio_lock:
            self.audio[stem] = wav
            while len(self.audio) > self.AUDIO_KEEP:
                self.audio.pop(next(iter(self.audio)))
        return self.url("tts", stem)


class Voices:
    """Neural reading voices (Piper, run by sherpa-onnx): downloaded once into %APPDATA%/Ayrac/voices,
    then everything is on this computer. Piper is used because it is ~20x faster than real time even on
    two cores, so reading aloud never waits; Kokoro sounds more natural but only just keeps up."""

    def __init__(self):
        self._tts = {}
        self._lock = threading.Lock()
        self.progress = {}  # voice id -> {"state": downloading|extracting|ready|error, "done", "total", "message"}

    @staticmethod
    def folder(vid):
        return VOICES_DIR / VOICES[vid][0]

    def installed(self, vid):
        d = self.folder(vid)
        return (d / "tokens.txt").is_file() and any(d.glob("*.onnx"))

    def status(self):
        return [{"id": vid, "label": v[1], "accent": v[2], "mb": v[3], "installed": self.installed(vid),
                 **({"progress": self.progress[vid]} if vid in self.progress else {})} for vid, v in VOICES.items()]

    def install(self, vid):
        if vid not in VOICES:
            return False
        if self.installed(vid) or self.progress.get(vid, {}).get("state") in ("downloading", "extracting"):
            return True
        self.progress[vid] = {"state": "downloading", "done": 0, "total": VOICES[vid][3] * 1024 * 1024}
        threading.Thread(target=self._download, args=(vid,), name=f"voice-{vid}", daemon=True).start()
        return True

    def _download(self, vid):
        name = VOICES[vid][0]
        part = VOICES_DIR / (name + ".tar.bz2.part")
        p = self.progress[vid]
        try:
            VOICES_DIR.mkdir(parents=True, exist_ok=True)
            req = urllib.request.Request(VOICE_URL.format(name), headers={"User-Agent": f"Ayrac/{app_version()}"})
            with urllib.request.urlopen(req, timeout=60) as r, part.open("wb") as f:
                p["total"] = int(r.headers.get("Content-Length") or p["total"])
                while chunk := r.read(1 << 16):
                    f.write(chunk)
                    p["done"] += len(chunk)
            p["state"] = "extracting"
            # Unpack beside the final place and move it in when complete, so a half-unpacked voice never counts.
            tmp = VOICES_DIR / f".unpack-{name}"
            shutil.rmtree(tmp, ignore_errors=True)
            with tarfile.open(part, "r:bz2") as t:
                t.extractall(tmp, filter="data")  # the archive holds one folder: <name>/
            shutil.rmtree(self.folder(vid), ignore_errors=True)
            os.replace(tmp / name, self.folder(vid))
            shutil.rmtree(tmp, ignore_errors=True)
            part.unlink(missing_ok=True)
            p["state"] = "ready" if self.installed(vid) else "error"
            log("info", f"voice {vid} installed")
        except Exception as e:  # noqa: BLE001 - shown to the user, logged
            p.update(state="error", message=str(e))
            part.unlink(missing_ok=True)
            log("error", f"voice {vid}: {e!r}")

    def remove(self, vid):
        with self._lock:
            self._tts.pop(vid, None)
        shutil.rmtree(self.folder(vid), ignore_errors=True)
        self.progress.pop(vid, None)

    def _engine(self, vid):
        with self._lock:
            if vid not in self._tts:
                import sherpa_onnx  # only when a neural voice is used
                d = self.folder(vid)
                model = sherpa_onnx.OfflineTtsModelConfig(num_threads=min(4, os.cpu_count() or 2))
                model.vits = sherpa_onnx.OfflineTtsVitsModelConfig(
                    model=str(next(d.glob("*.onnx"))), tokens=str(d / "tokens.txt"), data_dir=str(d / "espeak-ng-data"))
                self._tts[vid] = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=model, max_num_sentences=1))
            return self._tts[vid]

    def wav(self, vid, text, speed):
        """Speak text with voice vid; returns (WAV bytes, seconds)."""
        audio = self._engine(vid).generate(text, sid=0, speed=float(speed or 1.0))
        pcm = array("h", (int(max(-1.0, min(1.0, s)) * 32767) for s in audio.samples)).tobytes()
        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(audio.sample_rate)
            w.writeframes(pcm)
        return buf.getvalue(), len(audio.samples) / audio.sample_rate


class KvStore:
    """Settings, notebook, reading positions and stats: one SQLite row per key, so saving the reading position
    no longer rewrites the whole notebook. data.json from older versions is imported once and kept as .bak."""

    def __init__(self, path: Path, legacy_json: Path):
        self._lock = threading.Lock()
        self._con = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None)
        self._con.execute("PRAGMA journal_mode=WAL")
        self._con.execute("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID")
        empty = self._con.execute("SELECT 1 FROM kv LIMIT 1").fetchone() is None
        if empty and legacy_json.exists():
            try:
                self.replace_all(json.loads(legacy_json.read_text(encoding="utf-8")))
                os.replace(legacy_json, legacy_json.with_name(f"data.json.{time.strftime('%Y%m%d-%H%M%S')}.bak"))
                log("info", f"settings moved from {legacy_json.name} to {path.name}")
            except (OSError, ValueError) as e:
                log("error", f"could not import {legacy_json}: {e!r}")
        self._cache = {k: json.loads(v) for k, v in self._con.execute("SELECT k, v FROM kv")}

    def all(self) -> dict:
        with self._lock:
            return dict(self._cache)

    def get(self, key, default=None):
        with self._lock:
            return self._cache.get(key, default)

    def set(self, key, value):
        data = json.dumps(value, ensure_ascii=False)
        with self._lock:
            self._con.execute("INSERT OR REPLACE INTO kv (k, v) VALUES (?, ?)", (key, data))
            self._cache[key] = value

    def delete(self, key):
        with self._lock:
            self._con.execute("DELETE FROM kv WHERE k = ?", (key,))
            self._cache.pop(key, None)

    def replace_all(self, data: dict):
        with self._lock:
            self._con.execute("BEGIN")
            self._con.execute("DELETE FROM kv")
            self._con.executemany("INSERT INTO kv (k, v) VALUES (?, ?)",
                                  [(str(k), json.dumps(v, ensure_ascii=False)) for k, v in data.items()])
            self._con.execute("COMMIT")
            self._cache = dict(data)


class Api:
    """Methods here are callable from the page as `await window.pywebview.api.<name>(...)`."""

    def __init__(self, initial_path: str | None):
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        BOOKS_DIR.mkdir(exist_ok=True)
        self._lock = threading.Lock()
        self._kv = KvStore(KV_DB, KV_FILE)
        self._server = BookServer()
        self._voices = Voices()
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

    def dict_profile(self, words):
        """{word: [lemma, zipf]} for a list of words; None without the dictionary."""
        d = self._dictionary()
        return d.profile(words[:60000]) if d else None

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
    def kv_all(self):
        return self._kv.all()

    def kv_set(self, key, value):
        self._kv.set(str(key), value)
        return True

    def kv_del(self, key):
        self._kv.delete(str(key))
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

    # ---------- export ----------
    def save_text_file(self, filename, content):
        """Save text the page generated (e.g. the notebook as CSV) where the user picks."""
        ext = Path(filename).suffix.lstrip(".").lower() or "txt"
        picked = webview.windows[0].create_file_dialog(
            webview.FileDialog.SAVE, directory=str(Path.home() / "Documents"), save_filename=filename,
            file_types=(f"{ext.upper()} (*.{ext})",))
        if not picked:
            return {"ok": False, "cancelled": True}
        path = picked if isinstance(picked, str) else picked[0]
        try:
            Path(path).write_text(content, encoding="utf-8", newline="")
        except OSError as e:
            log("error", f"save_text_file: {e!r}")
            return {"ok": False, "message": str(e)}
        log("info", f"saved {path}")
        return {"ok": True, "path": str(path)}

    # ---------- backup ----------
    def _write_backup(self, path):
        data = self._kv.all()
        kv = json.dumps(data, ensure_ascii=False)
        words = len(data.get("words") or [])
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
            self._kv.replace_all(kv)
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
            if cover.exists():  # a URL from the local book server, not the image itself
                meta["cover"] = self._server.url("cover", meta_file.stem) + f"?v={int(cover.stat().st_mtime)}"
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
        """The EPUB as base64 (kept for older pages; 2.1.0+ fetches book_url instead)."""
        epub = (BOOKS_DIR / book_stem(book_id)).with_suffix(".epub")
        return base64.b64encode(epub.read_bytes()).decode("ascii") if epub.exists() else None

    def book_url(self, book_id):
        stem = book_stem(book_id)
        return self._server.url("book", stem) if (BOOKS_DIR / (stem + ".epub")).exists() else None

    def book_delete(self, book_id):
        stem = BOOKS_DIR / book_stem(book_id)
        for ext in (".epub", ".json", ".cover"):
            stem.with_suffix(ext).unlink(missing_ok=True)
        return True

    def open_external(self, path):
        """An .epub was opened while Ayraç is running (double-click, "Open with"): show it in this window."""
        p = Path(str(path))
        if p.suffix.lower() != ".epub" or not p.is_file():
            log("warn", f"open request ignored: {path}")
            return False
        self._initial_path = str(p)
        log("info", f"open request: {p}")
        win = webview.windows[0]
        win.restore()
        win.evaluate_js("window.ayracOpenExternal && window.ayracOpenExternal()")
        return True

    # ---------- updates ----------
    def _update_source(self):
        """Manifest URL from settings, else the one the installer wrote next to Ayrac.exe (update-source.txt)."""
        url = self._kv.get("updateUrl")
        if url:
            return url
        f = Path(sys.executable).resolve().parent / "update-source.txt"
        try:
            return f.read_text(encoding="utf-8").strip() or None
        except OSError:
            return None

    def check_update(self):
        """Read the update manifest: {"version": "1.9.1", "setup_url": "...", "notes": "..."}."""
        url = self._update_source()
        if not url:
            return {"configured": False, "current": app_version()}
        try:
            req = urllib.request.Request(url, headers={"User-Agent": f"Ayrac/{app_version()}"})
            with urllib.request.urlopen(req, timeout=10) as r:
                m = json.loads(r.read().decode("utf-8-sig"))
            latest = str(m.get("version", ""))
            setup = urllib.parse.urljoin(url, str(m.get("setup_url") or ""))
        except (OSError, ValueError) as e:
            log("warn", f"check_update {url}: {e!r}")
            return {"configured": True, "ok": False, "source": url, "current": app_version(), "message": str(e)}
        newer = version_tuple(latest) > version_tuple(app_version())
        log("info", f"check_update: current {app_version()}, latest {latest} from {url}")
        return {"configured": True, "ok": True, "source": url, "current": app_version(), "latest": latest,
                "newer": newer, "notes": str(m.get("notes", "")), "setup_url": setup}

    def set_update_source(self, url):
        self.kv_set("updateUrl", (url or "").strip() or None)
        return self.check_update()

    def install_update(self):
        """Download the newer setup and run it; the installer closes this window and opens the new version."""
        info = self.check_update()
        if not info.get("newer") or not info.get("setup_url", "").lower().endswith(".exe"):
            return {"ok": False, "message": "Yeni sürüm bulunamadı."}
        dest = Path(tempfile.gettempdir()) / f"Ayrac-Setup-{info['latest']}.exe"
        try:
            with urllib.request.urlopen(info["setup_url"], timeout=60) as r, dest.open("wb") as f:
                shutil.copyfileobj(r, f)
        except OSError as e:
            log("error", f"install_update download: {e!r}")
            return {"ok": False, "message": "İndirilemedi: " + str(e)}
        log("info", f"install_update: running {dest}")
        # Start the installer through WMI so it is not part of this app's process tree: the PyInstaller
        # bootloader keeps its children in one job, and the Ayraç the installer reopens would go down with it.
        start_outside_container(f'"{dest}" /SILENT /SUPPRESSMSGBOXES /NORESTART')
        threading.Timer(1.5, lambda: webview.windows[0].destroy()).start()
        return {"ok": True, "version": info["latest"]}

    # ---------- neural reading voices (Piper via sherpa-onnx) ----------
    def voice_status(self):
        return {"ok": True, "voices": self._voices.status()}

    def voice_install(self, vid):
        return {"ok": self._voices.install(str(vid))}

    def voice_remove(self, vid):
        if str(vid) in VOICES:
            self._voices.remove(str(vid))
        return self.voice_status()

    def voice_speak(self, vid, text, speed=1.0):
        """One sentence as audio: {"url"} of a WAV the page plays (served by the local book server)."""
        vid, text = str(vid), str(text or "").strip()[:2000]
        if vid not in VOICES or not self._voices.installed(vid):
            return {"ok": False, "message": "Ses kurulu değil."}
        if not text:
            return {"ok": False, "message": "Metin yok."}
        try:
            wav, secs = self._voices.wav(vid, text, speed)
        except Exception as e:  # noqa: BLE001
            log("error", f"voice_speak: {e!r}")
            return {"ok": False, "message": str(e)}
        return {"ok": True, "url": self._server.add_audio(wav), "seconds": round(secs, 2)}

    # ---------- free books (Project Gutenberg's own catalogue feed, OPDS) ----------
    def _catalog_fetch(self, url, limit):
        """GET an https URL on the catalog hosts only (the page cannot use this to reach anything else)."""
        def allowed(u):
            p = urllib.parse.urlparse(u)
            return p.scheme == "https" and p.hostname in CATALOG_HOSTS
        if not allowed(url):
            raise ValueError("izin verilmeyen adres")
        req = urllib.request.Request(url, headers={"User-Agent": f"Ayrac/{app_version()} (EPUB reader)"})
        with urllib.request.urlopen(req, timeout=40) as r:
            if not allowed(r.geturl()):
                raise ValueError("izin verilmeyen yönlendirme")
            data = r.read(limit + 1)
        if len(data) > limit:
            raise ValueError("dosya çok büyük")
        return data

    def catalog_search(self, query="", start=1):
        """One page (25 books) of Project Gutenberg's search feed, most downloaded first. The page parses the
        OPDS (Atom) text; only English books (l.en): Gutenberg's books are free of US copyright."""
        q = ("l.en " + str(query or "")[:120]).strip()
        params = {"query": q, "sort_order": "downloads"}
        if int(start or 1) > 1:
            params["start_index"] = int(start)
        try:
            return {"ok": True, "xml": self._catalog_fetch(CATALOG_FEED + "?" + urllib.parse.urlencode(params), 3_000_000).decode("utf-8")}
        except (OSError, ValueError) as e:
            log("warn", f"catalog_search {q!r}: {e!r}")
            return {"ok": False, "message": str(e)}

    def catalog_download(self, book_id):
        """The EPUB of Gutenberg book <book_id> (a number), as base64 for the page to open like any other book."""
        if not re.fullmatch(r"\d{1,7}", str(book_id or "")):
            return {"ok": False, "message": "izin verilmeyen kitap numarası"}
        url = f"https://www.gutenberg.org/ebooks/{book_id}.epub3.images"
        try:
            data = self._catalog_fetch(url, 60_000_000)
        except (OSError, ValueError) as e:
            log("warn", f"catalog_download {url}: {e!r}")
            return {"ok": False, "message": str(e)}
        log("info", f"catalog_download: {url} ({len(data)} bytes)")
        return {"ok": True, "b64": base64.b64encode(data).decode("ascii")}

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


def version_tuple(v: str):
    return tuple(int(x) for x in re.findall(r"\d+", v or "")[:3])


# An .epub opened while Ayraç is already running is handed to the open window over this per-user pipe.
PIPE = r"\\.\pipe\Ayrac-" + re.sub(r"\W", "", os.environ.get("USERNAME", "user"))


def _pipe_key() -> bytes:
    f = DATA_DIR / ".pipekey"
    try:
        return f.read_bytes()
    except OSError:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        key = os.urandom(32)
        f.write_bytes(key)
        return key


def send_to_running_instance(path: str) -> bool:
    try:
        with Client(PIPE, family="AF_PIPE", authkey=_pipe_key()) as conn:
            conn.send(os.path.abspath(path))
        return True
    except (OSError, EOFError, AuthenticationError) as e:
        log("warn", f"could not hand {path} to the open window: {e!r}")
        return False


def serve_open_requests(api) -> None:
    def run():
        try:
            listener = Listener(PIPE, family="AF_PIPE", authkey=_pipe_key())
        except OSError as e:
            log("warn", f"open-request pipe unavailable: {e!r}")
            return
        while True:
            try:
                with listener.accept() as conn:
                    path = conn.recv()
                api.open_external(path)
            except (OSError, EOFError, AuthenticationError) as e:
                log("warn", f"open request failed: {e!r}")

    threading.Thread(target=run, name="open-requests", daemon=True).start()


def start_outside_container(command_line: str) -> None:
    """Start a program through WMI, so it runs outside this process tree and outside any app-package container."""
    ps = ("Invoke-CimMethod -ClassName Win32_Process -MethodName Create "
          f"-Arguments @{{CommandLine='{command_line.replace(chr(39), chr(39) * 2)}'}} | Out-Null")
    subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                   creationflags=subprocess.CREATE_NO_WINDOW, timeout=60, check=False)


def data_dir_is_redirected() -> bool:
    """True when Windows redirects our %APPDATA% writes into some app package's private copy.

    That happens when Ayraç is started from a packaged app's shell (for example Claude's terminal): the
    window would then read and write a separate library that the normal desktop shortcut never sees."""
    local = os.environ.get("LOCALAPPDATA")
    if not local or not getattr(sys, "frozen", False):
        return False
    probe = DATA_DIR / f".probe-{os.getpid()}"
    try:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        probe.write_text("x", encoding="ascii")
        return any(Path(local, "Packages").glob(f"*/LocalCache/Roaming/Ayrac/{probe.name}"))
    except OSError:
        return False
    finally:
        try:
            probe.unlink()
        except OSError:
            pass


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


def voice_selftest(out):
    """Developer check of the bundled speech engine: Ayrac.exe --voice-selftest <folder with a Piper voice> <out.txt>."""
    try:
        import sherpa_onnx
        d = Path(sys.argv[sys.argv.index("--voice-selftest") + 1])
        model = sherpa_onnx.OfflineTtsModelConfig(num_threads=2)
        model.vits = sherpa_onnx.OfflineTtsVitsModelConfig(model=str(next(d.glob("*.onnx"))), tokens=str(d / "tokens.txt"),
                                                           data_dir=str(d / "espeak-ng-data"))
        t0 = time.time()
        a = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=model)).generate("Hello from the packaged app.", sid=0, speed=1.0)
        Path(out).write_text(f"ok {len(a.samples) / a.sample_rate:.2f}s audio in {time.time() - t0:.2f}s", encoding="utf-8")
    except Exception as e:  # noqa: BLE001
        Path(out).write_text("error " + repr(e), encoding="utf-8")


def main():
    if "--voice-selftest" in sys.argv:
        voice_selftest(sys.argv[-1])
        return
    initial = next((a for a in sys.argv[1:] if a.lower().endswith(".epub")), None)
    if data_dir_is_redirected():
        args = " ".join(f'"{a}"' for a in sys.argv[1:])
        start_outside_container(f'"{sys.executable}" {args}'.strip())
        return
    if focus_running_instance():
        if initial and send_to_running_instance(initial):
            log("info", f"second launch: sent {initial} to the open window")
        else:
            log("info", "second launch: focused the window that is already open")
        return
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
    serve_open_requests(api)
    webview.start(
        gui="edgechromium",
        private_mode=False,
        storage_path=str(DATA_DIR / "webview"),
        icon=str(resource("ayrac.ico")),
        debug=dev,  # developer mode: right-click → Inspect opens the web inspector
    )


if __name__ == "__main__":
    main()

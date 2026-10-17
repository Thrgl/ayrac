"""Builds the offline English-Turkish dictionary (SQLite) that Ayraç looks words up in.

Sources (both CC BY-SA 4.0, extracted with wiktextract and published by kaikki.org):
  * English Wiktionary  - English definitions, IPA, inflected forms, Turkish translations grouped by sense
  * Turkish Wiktionary  - Turkish-language definitions of English words
Both files are streamed, never stored on disk.

Usage: python build_dict.py OUT.sqlite
"""

import gzip
import json
import os
import re
import sqlite3
import sys
import time
import urllib.request

from wordfreq import top_n_list, zipf_frequency

EN_URL = "https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl.gz"  # English words only, ~520 MB
TR_URL = "https://kaikki.org/dictionary/downloads/tr/tr-extract.jsonl.gz"
UA = {"User-Agent": "Ayrac-dictionary-builder/1.0 (personal EPUB reader)"}
SKIP_TAGS = {"obsolete", "archaic", "rare", "dated"}
COMMON = set(top_n_list("en", 60000))


LIMIT = int(os.environ.get("AYRAC_DICT_LIMIT", "0"))  # testing: stop after this many English lines
OFFLINE = os.environ.get("AYRAC_DICT_OFFLINE") == "1"  # testing: use the files already on disk as they are


def download(url, dest):
    """Save url to dest, resuming a partial download; returns dest."""
    if OFFLINE:
        return dest
    have = os.path.getsize(dest) if os.path.exists(dest) else 0
    head = urllib.request.urlopen(urllib.request.Request(url, headers=UA, method="HEAD"))
    total = int(head.headers.get("Content-Length") or 0)
    if total and have == total:
        return dest
    if have > total:
        have = 0
    headers = dict(UA, Range=f"bytes={have}-") if have else UA
    resp = urllib.request.urlopen(urllib.request.Request(url, headers=headers))
    mode = "ab" if have and resp.status == 206 else "wb"
    done = have if mode == "ab" else 0
    last = time.time()
    with resp, open(dest, mode) as f:
        while chunk := resp.read(1 << 20):
            f.write(chunk)
            done += len(chunk)
            if time.time() - last > 30:
                last = time.time()
                log(f"{os.path.basename(dest)}: {done >> 20} / {total >> 20} MB")
    return dest


def stream(url):
    path = download(url, os.path.join(os.path.dirname(os.path.abspath(OUT)), os.path.basename(url)))
    f = open(path, "rb")
    return f, gzip.GzipFile(fileobj=f)


def short(text, n=220):
    text = re.sub(r"\s+", " ", text or "").strip()
    return text if len(text) <= n else text[: n - 1].rstrip() + "…"


def pick_ipa(sounds):
    us = uk = None
    for s in sounds or []:
        ipa, tags = s.get("ipa"), set(s.get("tags") or [])
        if not ipa or not ipa.startswith("/"):
            continue
        if us is None and tags & {"US", "General-American"}:
            us = ipa
        if uk is None and tags & {"UK", "Received-Pronunciation"}:
            uk = ipa
        if us is None and uk is None and not tags:
            us = ipa
    return us, uk


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def main(out_path):
    # 1) Turkish Wiktionary: Turkish definitions of English words.
    trdefs = {}
    resp, gz = stream(TR_URL)
    with resp, gz:
        for line in gz:
            o = json.loads(line)
            if o.get("lang_code") != "en" or not o.get("word"):
                continue
            defs = []
            for s in o.get("senses") or []:
                g = (s.get("glosses") or [None])[-1]
                if g and g not in defs:
                    defs.append(short(g, 200))
            if defs:
                trdefs.setdefault(o["word"].lower(), []).append({"p": o.get("pos") or "", "k": defs[:6]})
    log(f"Turkish Wiktionary: {len(trdefs)} English headwords with Turkish definitions")

    # 2) English Wiktionary, streamed.
    entries, forms = {}, {}
    resp, gz = stream(EN_URL)
    n = kept = 0
    with resp, gz:
        for line in gz:
            n += 1
            if LIMIT and n > LIMIT:
                break
            if n % 250000 == 0:
                log(f"{n:,} lines, {kept:,} entries kept")
            if b'"lang_code": "en"' not in line:
                continue
            o = json.loads(line)
            word = o.get("word")
            if o.get("lang_code") != "en" or not word:
                continue
            low = word.lower()
            senses = o.get("senses") or []

            def turkish(t):
                return t.get("lang_code") == "tr" and t.get("word") and not SKIP_TAGS & set(t.get("tags") or [])

            trans = [t for t in o.get("translations") or [] if turkish(t)]
            for s in senses:
                trans += [dict(t, sense=t.get("sense") or (s.get("glosses") or [""])[-1])
                          for t in s.get("translations") or [] if turkish(t)]

            defs, only_forms = [], True
            for s in senses:
                fo = s.get("form_of") or s.get("alt_of")
                gloss = (s.get("glosses") or [None])[-1]
                if fo and fo[0].get("word"):
                    if low not in forms and fo[0]["word"].lower() != low:
                        forms[low] = (fo[0]["word"], short(gloss or "", 120))
                    continue
                only_forms = False
                if not gloss or SKIP_TAGS & set(s.get("tags") or []):
                    continue
                if gloss not in defs:
                    defs.append(short(gloss))

            in_common = low in COMMON
            if not (trans or (in_common and not only_forms) or low in trdefs):
                continue
            if " " in low and not (trans or low in trdefs):
                continue

            groups = {}
            for t in trans:
                label = short(t.get("sense") or "", 90)
                words = groups.setdefault(label, [])
                if t["word"] not in words:
                    words.append(t["word"])
            us, uk = pick_ipa(o.get("sounds"))
            e = {"p": o.get("pos") or ""}
            if us or uk:
                e["i"] = [us or "", uk or ""]
            if defs:
                e["d"] = defs[:6]
            if groups:
                e["t"] = [[k, ", ".join(v[:6])] for k, v in list(groups.items())[:8]]
            if len(e) > 1:
                entries.setdefault(low, []).append(e)
                kept += 1
    log(f"English Wiktionary: {n:,} lines read, {len(entries):,} headwords kept, {len(forms):,} inflected forms seen")

    # 3) Merge Turkish definitions and write SQLite.
    for low, items in trdefs.items():
        if low in entries or low in COMMON or " " in low:
            target = entries.setdefault(low, [])
            for item in items:
                same = next((e for e in target if e["p"] == item["p"] and "k" not in e), None)
                if same:
                    same["k"] = item["k"]
                else:
                    target.append(item)

    con = sqlite3.connect(out_path)
    con.executescript("""
        DROP TABLE IF EXISTS entries; DROP TABLE IF EXISTS forms; DROP TABLE IF EXISTS meta; DROP TABLE IF EXISTS freq;
        CREATE TABLE entries (word TEXT PRIMARY KEY, data TEXT NOT NULL) WITHOUT ROWID;
        CREATE TABLE forms (form TEXT PRIMARY KEY, lemma TEXT NOT NULL, note TEXT) WITHOUT ROWID;
        CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT);
        -- Zipf frequency (wordfreq): 7 = "the", 3 = about once per million words. The app turns it into a CEFR estimate.
        CREATE TABLE freq (word TEXT PRIMARY KEY, zipf REAL NOT NULL) WITHOUT ROWID;
    """)
    con.executemany("INSERT INTO freq VALUES (?, ?)",
                    ((w, z) for w in entries for z in [zipf_frequency(w, "en")] if z > 0))
    con.executemany("INSERT INTO entries VALUES (?, ?)",
                    ((w, json.dumps(v, ensure_ascii=False, separators=(",", ":"))) for w, v in entries.items()))
    con.executemany("INSERT INTO forms VALUES (?, ?, ?)",
                    ((f, l, note) for f, (l, note) in forms.items() if l.lower() in entries))
    con.executemany("INSERT INTO meta VALUES (?, ?)", [
        ("source", "Wiktionary (en, tr) via kaikki.org / wiktextract"),
        ("license", "CC BY-SA 4.0"),
        ("built", time.strftime("%Y-%m-%d")),
    ])
    con.commit()
    con.execute("VACUUM")
    con.close()
    log(f"wrote {out_path}")


if __name__ == "__main__":
    OUT = sys.argv[1]
    main(OUT)

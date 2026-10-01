"""Direct Korean-Wiktionary English headwords, supplemented by NIKL reverse entries."""
import json
import re
import sqlite3
import subprocess
from pathlib import Path

URL = "https://kaikki.org/kowiktionary/%EC%98%81%EC%96%B4/kaikki.org-dictionary-%EC%98%81%EC%96%B4.jsonl"
POS = {"noun": "명사", "verb": "동사", "adj": "형용사", "adv": "부사", "prep": "전치사",
       "conj": "접속사", "pron": "대명사", "article": "관사", "det": "한정사",
       "intj": "감탄사", "phrase": "숙어", "num": "수사", "name": "고유명사",
       "prefix": "접두사", "suffix": "접미사", "abbrev": "약어", "proverb": "속담"}

def direct_records(lines):
    records = {}
    for line in lines:
        entry = json.loads(line)
        if entry.get("lang_code") != "en":
            continue
        word = " ".join(entry.get("word", "").lower().split())
        if not re.fullmatch(r"[a-z][a-z '\-]*", word):
            continue
        pos = POS.get(entry.get("pos"), "")
        if not pos:
            categories = [c.get("name", "") for c in entry.get("categories", []) if isinstance(c, dict)]
            labels = [v for v in POS.values() if "영어 " + v in categories]
            if len(labels) == 1:
                pos = labels[0]
        for sense in entry.get("senses", []):
            gloss = "; ".join(g.strip() for g in sense.get("glosses", [])
                              if isinstance(g, str) and re.search("[가-힣]", g))
            if not gloss:
                continue
            value = ("[" + pos + "] " if pos else "") + gloss
            values = records.setdefault(word, [])
            if value not in values:
                values.append(value)
    return records

def add_direct_dictionary(root, destination):
    path = Path(root) / ".learning_data/kowiktionary-english.jsonl"
    path.parent.mkdir(exist_ok=True)
    if not path.exists():
        temporary = path.with_suffix(".part")
        subprocess.run(["curl", "--fail", "--location", "--retry", "3", "--max-time", "120",
                        URL, "--output", str(temporary)], check=True)
        # Validate the entire JSONL before replacing a known-good cache.
        with temporary.open(encoding="utf8") as source:
            records = direct_records(source)
        temporary.replace(path)
    else:
        with path.open(encoding="utf8") as source:
            records = direct_records(source)
    assert len(records) > 10000, "Incomplete Korean-Wiktionary English data"
    assert "preliminary" in records and any("예비" in g for g in records["preliminary"])
    expected = ("initial", "important", "ordinary", "simple", "prepare", "eat", "go",
                "basic", "evidence", "appropriate", "consequence", "significant", "effective",
                "available", "possible", "particular", "general", "specific", "immediate",
                "primary", "improve", "increase", "decrease", "result", "research")
    assert all(word in records for word in expected), "Missing ordinary English headword"
    with sqlite3.connect(destination) as db:
        db.execute("ALTER TABLE words ADD COLUMN source TEXT DEFAULT 'NIKL'")
        new = 0
        for word, senses in records.items():
            old = db.execute("SELECT 1 FROM words WHERE word=?", (word,)).fetchone()
            new += old is None
            # Direct English -> Korean senses take priority over reverse synonyms.
            korean = "\n".join(str(i + 1) + ". " + s for i, s in enumerate(senses[:4]))
            db.execute("INSERT OR REPLACE INTO words(word,meaning_ko,meaning_en,ipa,source) VALUES(?,?,'','','KOWIKTIONARY')", (word, korean))
        total = db.execute("SELECT count(*) FROM words").fetchone()[0]
        # The primary key handles exact lookup; FTS4 handles partial multi-word input.
        db.execute("DROP TABLE IF EXISTS words_fts")
        db.execute("CREATE VIRTUAL TABLE words_fts USING fts4(word)")
        db.execute("INSERT INTO words_fts(docid, word) SELECT rowid, word FROM words")
        indexed = db.execute("SELECT count(*) FROM words_fts").fetchone()[0]
        assert indexed == total, "Incomplete FTS phrase index"
    print("Direct Korean-Wiktionary headwords:", len(records), "new headwords:", new, "total:", total)

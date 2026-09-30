"""Build exact English headword index from NIKL equivalents, plus bilingual examples.

Dictionary adaptation: NIKL Korean Basic Dictionary, CC BY-SA 2.0 KR.
Source mirror: binjang/NIKL-dictionary-parser, pinned January 2024 export.
Never index English definition text as if it were an English headword.
"""
import concurrent.futures
import json
import re
import sqlite3
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PIN = '52ffffdc0a6fd8d00d7dce45cffcc0930ebbdaea'
FILES = [f'{i}_5000_20240101.json' for i in range(1, 11)] + ['11_1960_20240101.json']

def as_list(value):
    return value if isinstance(value, list) else [value] if value else []

def features(value):
    return {item.get('att'): item.get('val', '') for item in as_list(value)}

def download(name):
    path = ROOT / '.learning_data' / name
    path.parent.mkdir(exist_ok=True)
    for attempt in range(3):
        try:
            if not path.exists():
                url = f'https://raw.githubusercontent.com/binjang/NIKL-dictionary-parser/{PIN}/2024_01/{name}?full={attempt}'
                temporary = path.with_suffix('.part')
                urllib.request.urlretrieve(url, temporary)
                with temporary.open(encoding='utf8') as source:
                    json.load(source)
                temporary.replace(path)
            else:
                with path.open(encoding='utf8') as source:
                    json.load(source)
            return path
        except (ValueError, OSError):
            path.unlink(missing_ok=True)
            if attempt == 2:
                raise
            time.sleep(1)

def build_dictionary(paths, destination):
    records = {}
    for path in paths:
        with path.open(encoding='utf8') as source:
            entries = json.load(source)['LexicalResource']['Lexicon']['LexicalEntry']
        for entry in entries:
            lemmas = as_list(entry.get('Lemma'))
            head = next((features(lemma.get('feat')).get('writtenForm', '') for lemma in lemmas), '')
            for sense in as_list(entry.get('Sense')):
                korean = features(sense.get('feat')).get('definition', '').strip()
                if not head or not korean:
                    continue
                for equivalent in as_list(sense.get('Equivalent')):
                    f = features(equivalent.get('feat'))
                    if f.get('language') != '영어':
                        continue
                    english = f.get('definition', '').strip()
                    for lemma in re.split(r'[,;]', f.get('lemma', '')):
                        lemma = ' '.join(lemma.lower().split())
                        if not re.fullmatch(r"[a-z][a-z '\-]*", lemma):
                            continue
                        # Exact translated headwords only; no synonym inference.
                        item = (f'{head}: {korean}', english)
                        if item not in records.setdefault(lemma, []):
                            records[lemma].append(item)
        del entries
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.unlink(missing_ok=True)
    with sqlite3.connect(destination) as db:
        db.execute('CREATE TABLE words(word TEXT PRIMARY KEY COLLATE NOCASE, meaning_ko TEXT, meaning_en TEXT, ipa TEXT)')
        for word, senses in records.items():
            selected = senses[:6]
            ko = '\n'.join(f'{i+1}. {s[0]}' for i,s in enumerate(selected))
            en = '\n'.join(f'{i+1}. {s[1]}' for i,s in enumerate(selected) if s[1])
            db.execute('INSERT INTO words VALUES(?,?,?,?)', (word, ko, en, ''))
    assert len(records) > 10000, f'Incomplete dictionary: {len(records)}'
    assert 'spontaneous' in records
    assert all('자적:' not in ko for ko, _ in records['spontaneous'])
    print(f'Built {len(records)} exact English headwords from NIKL equivalents')

def build_examples(destination):
    archive = ROOT / '.learning_data' / 'kor-eng.zip'
    if not archive.exists():
        request = urllib.request.Request('https://www.manythings.org/anki/kor-eng.zip', headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(request, timeout=60) as response:
            archive.write_bytes(response.read())
    with zipfile.ZipFile(archive) as z:
        data = z.read('kor.txt').decode('utf8')
    destination.unlink(missing_ok=True)
    with sqlite3.connect(destination) as db:
        db.execute('CREATE TABLE examples(id INTEGER PRIMARY KEY, english TEXT, korean TEXT, credit TEXT)')
        db.execute('CREATE TABLE tokens(token TEXT, example_id INTEGER)')
        for line in data.splitlines():
            row = line.split('\t')
            if len(row) < 3:
                continue
            en, ko, credit = row[:3]
            if not any('가' <= ch <= '힣' for ch in ko) or len(en) > 220:
                continue
            eid = db.execute('INSERT INTO examples(english,korean,credit) VALUES(?,?,?)', (en,ko,credit)).lastrowid
            db.executemany('INSERT INTO tokens VALUES(?,?)', [(w,eid) for w in set(re.findall(r"[a-z]+(?:'[a-z]+)?", en.lower()))])
        db.execute('CREATE INDEX token_index ON tokens(token)')
        count = db.execute('SELECT count(*) FROM examples').fetchone()[0]
    assert count > 1000
    print(f'Built {count} attributed human bilingual sentence pairs')

if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
        paths = list(executor.map(download, FILES))
    assets = ROOT / 'app/src/main/assets'
    build_dictionary(paths, assets / 'word_dictionary.sqlite')
    build_examples(assets / 'bilingual_examples.sqlite')

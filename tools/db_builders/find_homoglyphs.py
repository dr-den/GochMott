# -*- coding: utf-8 -*-
"""
Поиск смешанных по алфавиту слов в готовой базе.

    python tools/find_homoglyphs.py app/src/main/assets/dict.db
    python tools/find_homoglyphs.py app/src/main/assets/dict.db --out work/homoglyphs.tsv
    python tools/find_homoglyphs.py app/src/main/assets/dict.db --dict maciev1961

ЗАЧЕМ

Латинская буква внутри кириллического слова не ломает поиск: `ChechenNormalizer`
переводит `a c e o p x y k` в кириллицу, и статья находится. Поэтому ни одна
проверка ключей такое не поймает — а в карточке пользователь увидит чужую
букву, и скопированный текст не совпадёт ни с чем.

Ищем не «строки, где есть латиница» (их полно законно: `usb`, `shift`, `web`),
а СЛОВА, ВНУТРИ КОТОРЫХ АЛФАВИТЫ СМЕШАНЫ. Слово `usb-адаптер` даёт два чистых
токена и не срабатывает; `xӀост` — один смешанный, и это ошибка.

ЧТО В ОТЧЁТЕ

Для каждого случая — исправленный вариант и главный аргумент за него:
сколько раз это же слово встречается в базе НАПИСАННЫМ ЧИСТО. Если `хӀост`
попадается ещё пять раз одной кириллицей, а `xӀост` один раз с латинской `x`,
доказывать больше нечего. Ноль в этой колонке — повод посмотреть глазами:
возможно, слово и правда латинское.

Скрипт ничего не правит. Он говорит, сколько их и где, чтобы выбрать между
правкой руками в JSONL и правилом в `repair.py`.
"""
import argparse, collections, os, re, sqlite3, sys, unicodedata as ud

PAL = 'Ӏ'

# Латиница -> кириллица. Тот же набор, что в ChechenNormalizer, плюс прописные:
# именно эти пары неразличимы в шрифте, поэтому именно они и путаются при наборе.
LAT2CYR = {
    'a': 'а', 'c': 'с', 'e': 'е', 'o': 'о', 'p': 'р', 'x': 'х', 'y': 'у', 'k': 'к',
    'A': 'А', 'B': 'В', 'C': 'С', 'E': 'Е', 'H': 'Н', 'K': 'К', 'M': 'М', 'O': 'О',
    'P': 'Р', 'T': 'Т', 'X': 'Х', 'Y': 'У',
    'i': PAL, 'I': PAL, 'l': PAL, '|': PAL, 'İ': PAL, 'І': PAL, 'і': PAL,
}
CYR2LAT = {v: k for k, v in LAT2CYR.items() if v not in ('Ӏ',)}

LATIN = re.compile(r'[A-Za-zÀ-ÿİ]')
CYRIL = re.compile(r'[\u0400-\u04FF]')
TOKEN = re.compile(r'[A-Za-zÀ-ÿİ\u0400-\u04FF\u0300-\u036f]+')

# Откуда берём ОТОБРАЖАЕМЫЙ текст. Ключи (`*_norm`, `*_fold`) не смотрим: там
# латиница уже переведена нормализатором, и ошибки не видно.
SOURCES = [
    ('lemmas',     'headword',   'id', "1"),
    ('forms',      'form',       'lemma_id', "source = 'dict'"),
    ('glosses',    'text',       'lemma_id', "lang = 'ce'"),
    ('examples',   'ce',         'lemma_id', "1"),
    ('examples',   'ru',         'lemma_id', "ru IS NOT NULL"),
    ('subs',       'text',       'example_id', "1"),
    ('cross_refs', 'to_headword', 'from_lemma_id', "1"),
]


def strip_marks(s):
    return ''.join(c for c in s if ud.category(c) != 'Mn')


def mixed_tokens(text):
    """Токены, внутри которых встретились оба алфавита."""
    out = []
    for m in TOKEN.finditer(text or ''):
        t = m.group(0)
        bare = strip_marks(t)
        if LATIN.search(bare) and CYRIL.search(bare):
            out.append(t)
    return out


def repair(token):
    """Чинит меньшинство: если букв кириллицы больше — латинские переводим в
    кириллицу, и наоборот. Слово вроде `xӀост` (одна латинская на пять
    кириллических) чинится в кириллицу; `opendIct` — в латиницу."""
    bare = strip_marks(token)
    n_lat = len(LATIN.findall(bare))
    n_cyr = len(CYRIL.findall(bare))
    table = LAT2CYR if n_cyr >= n_lat else CYR2LAT
    return ''.join(table.get(c, c) for c in token)


def main(argv=None):
    ap = argparse.ArgumentParser(description='смешанные по алфавиту слова в dict.db')
    ap.add_argument('db')
    ap.add_argument('--dict', metavar='CODE', help='только один словарь')
    ap.add_argument('--out', metavar='TSV', default='homoglyphs.tsv')
    ap.add_argument('--limit-print', type=int, default=25)
    args = ap.parse_args(argv)

    db = sqlite3.connect(f'file:{args.db}?mode=ro', uri=True)
    code_of = dict(db.execute('SELECT id, code FROM dicts').fetchall())
    only = None
    if args.dict:
        row = db.execute('SELECT id FROM dicts WHERE code = ?', (args.dict,)).fetchone()
        if not row:
            raise SystemExit(f'нет словаря {args.dict!r}; есть: '
                             + ', '.join(sorted(code_of.values())))
        only = row[0]

    # Сначала считаем, как часто каждое слово написано ЧИСТО — это и будет
    # доказательством при разборе.
    clean_freq = collections.Counter()
    findings = []
    for table, col, owner, cond in SOURCES:
        has = db.execute(f"SELECT COUNT(*) FROM pragma_table_info('{table}')"
                         f" WHERE name = '{col}'").fetchone()[0]
        if not has:
            continue
        sql = (f'SELECT dict_id, {owner}, {col} FROM {table} '
               f'WHERE {cond} AND {col} IS NOT NULL')
        if only is not None:
            sql += f' AND dict_id = {only}'
        for did, owner_id, text in db.execute(sql):
            for m in TOKEN.finditer(text or ''):
                t = m.group(0)
                bare = strip_marks(t)
                lat, cyr = LATIN.search(bare), CYRIL.search(bare)
                if lat and cyr:
                    findings.append([code_of.get(did, did), table, col, owner_id,
                                     t, repair(t), text])
                elif cyr:
                    clean_freq[bare.lower()] += 1

    # Отдельно — `slug`. Он не отображается и не участвует в поиске, но по нему
    # опознаются статьи в reviewed.tsv, и расхождение с заголовком означает, что
    # repair.py починил текст статьи, а её id оставил в сыром виде.
    sql = 'SELECT dict_id, id, slug, headword FROM lemmas'
    if only is not None:
        sql += f' WHERE dict_id = {only}'
    slug_bad = [(code_of.get(d, d), lid, sl, hw)
                for d, lid, sl, hw in db.execute(sql) if mixed_tokens(sl)]

    # Палочка, набранная не тем знаком. Это НЕ смешение алфавитов — `І` U+0406
    # и `і` U+0456 кириллические, — но в чеченском тексте они означают ровно
    # палочку `Ӏ` U+04C0, набранную украинской буквой. Строчная `ӏ` U+04CF тоже
    # не годится: ChechenNormalizer канонизирует в U+04C0.
    WRONG_PAL = {'\u0406': 'І (укр. І)', '\u0456': 'і (укр. і)', '\u04CF': 'ӏ (строчная)'}
    pal_bad = collections.Counter()
    pal_where = {}
    for table, col, owner, cond in SOURCES:
        sql = f'SELECT dict_id, {col} FROM {table} WHERE {cond} AND {col} IS NOT NULL'
        if only is not None:
            sql += f' AND dict_id = {only}'
        for did, text in db.execute(sql):
            for ch in set(text or '') & set(WRONG_PAL):
                pal_bad[(code_of.get(did, did), ch)] += (text or '').count(ch)
                pal_where.setdefault((code_of.get(did, did), ch), text[:50])

    print(f'\n{args.db}')
    if pal_bad:
        print('  ! палочка набрана не тем знаком:')
        for (d, ch), n in pal_bad.most_common():
            print(f'      {d}: {WRONG_PAL[ch]} -> Ӏ  {n} раз, напр. {pal_where[(d, ch)]!r}')
    if slug_bad:
        print(f'  ! в slug смешаны алфавиты: {len(slug_bad)}. На экран он не идёт и'
              f' в поиске не участвует,\n'
              f'    но по нему опознаются статьи в reviewed.tsv — значит repair.py'
              f' чинит текст,\n    а id статьи оставляет сырым. Примеры:')
        for d, lid, sl, hw in slug_bad[:8]:
            print(f'      {d}:{sl!r}  заголовок {hw!r}')
    print(f'  смешанных по алфавиту слов: {len(findings)}')
    if not findings:
        print('  чисто.')
        return 0

    for row in findings:
        row.append(clean_freq.get(strip_marks(row[5]).lower(), 0))

    by_dict = collections.Counter(r[0] for r in findings)
    by_char = collections.Counter(
        c for r in findings for c in strip_marks(r[4])
        if (LATIN.match(c) and CYRIL.search(strip_marks(r[4]))) and c in LAT2CYR)
    by_field = collections.Counter(f'{r[1]}.{r[2]}' for r in findings)

    print('\n  по словарям: ' + ', '.join(f'{k} {v}' for k, v in by_dict.most_common()))
    print('  по полям:    ' + ', '.join(f'{k} {v}' for k, v in by_field.most_common()))
    print('  какие буквы: ' + ', '.join(f'{k!r}->{LAT2CYR[k]!r} {v}'
                                        for k, v in by_char.most_common(12)))

    confirmed = sum(1 for r in findings if r[7] > 0)
    print(f'\n  из них подтверждено частотой: {confirmed} — то же слово встречается '
          f'в базе чисто написанным')
    print(f'  без подтверждения:           {len(findings) - confirmed} '
          f'— посмотреть глазами, слово может быть и правда латинским')

    uniq = collections.Counter((r[4], r[5]) for r in findings)
    print(f'\n  различных слов: {len(uniq)}')
    for (bad, good), n in uniq.most_common(args.limit_print):
        freq = next(r[7] for r in findings if r[4] == bad)
        print(f'    {bad!r:24} -> {good!r:24} встретилось {n:>4},'
              f' чисто написано {freq:>5}')

    with open(args.out, 'w', encoding='utf-8') as f:
        f.write('dict\ttable\tfield\towner_id\ttoken\tsuggested\tclean_freq\tcontext\n')
        for d, tbl, col, oid, bad, good, ctx, freq in findings:
            f.write(f'{d}\t{tbl}\t{col}\t{oid}\t{bad}\t{good}\t{freq}\t{ctx[:120]}\n')
    print(f'\n  подробности: {args.out} ({len(findings)} строк)')

    print('\n  Что дальше: если различных слов единицы — правьте в JSONL. Если это\n'
          '  одна-две буквы на сотнях слов — это системная замена при наборе или\n'
          '  OCR, и ей место правилом в repair.py, а не руками.')
    return 0


if __name__ == '__main__':
    sys.exit(main())

# -*- coding: utf-8 -*-
"""
*.jsonl -> dict.db  (поисковая база приложения GochMott), схема v4 — многословарная.

    python tools/build_app_db.py app/src/main/assets/dict.db \
        --dict maciev1961=work/maciev.jsonl

    python tools/build_app_db.py app/src/main/assets/dict.db \
        --dict maciev1961=work/maciev.jsonl \
        --dict karasaev1978=work/karasaev.jsonl \
        --dict math1997_ce=work/math_ce.jsonl \
        --dict math1997_ru=work/math_ru.jsonl \
        --links

Только стандартная библиотека. Файл БД пересоздаётся с нуля.

ЧТО ИЗМЕНИЛОСЬ ПРОТИВ v3

  * НЕСКОЛЬКО ИСТОЧНИКОВ В ОДНОМ ФАЙЛЕ. Таблица `dicts`, колонка `dict_id` на
    каждой строке — в том числе денормализованно на `forms`, `glosses`,
    `examples`, `trans_index`, чтобы фильтр по источнику не требовал джойна
    на `lemmas` и покрывающий индекс оставался покрывающим.

  * ДВА НАПРАВЛЕНИЯ. У Мациева заголовок чеченский, у Карасаева–Мациева 1978 —
    русский. Поэтому `word_forms` -> `forms(lang)` (сторона заголовка) и
    `ru_index` -> `trans_index(lang)` (сторона перевода): таблицы задают РОЛЬ,
    а язык лежит в колонке. `glosses.ru`/`ru_norm` -> `text`/`text_norm`+`lang`
    по той же причине: у словаря рус->чеч глосс чеченский, и колонка `ru` была
    бы враньём, вкомпилированным в схему.

  * НОРМАЛИЗАТОР ВЫБИРАЕТСЯ ПО ЯЗЫКУ. `normalize_ce` для чеченского,
    `normalize_ru` для русского. Прогнать русский заголовок через
    `normalize_ce` нельзя: там '1', 'i', 'l', '|' -> палочка, и «1-й» станет
    «Ӏ-й». В приложении ровно то же: ChechenNormalizer для чеченского ввода,
    RuNormalizer — для русского.

  * `lemma_links` — сшивка статей МЕЖДУ книгами, лесенкой по убыванию
    надёжности, с записью расхождений. Поля книг не сливаются никогда:
    у каждой книги своя лемма со своей грамматикой, связь лишь показывает,
    что это одно слово.

  * `cross_refs` разрешаются ТОЛЬКО ВНУТРИ СВОЕЙ КНИГИ. «см.» у Мациева
    показывает на его же страницу; увести её в словарь 2017 года значило бы
    вложить автору в рот то, чего он не говорил. Сборка падает, если ссылка
    пересекла границу словаря.

  * `source` у формы: `dict` напечатано | `gen` сгенерировано заменой
    показателя | `linked` ключ пришёл из другой книги. У `gen`/`linked`
    заполнен `donor_dict_id` — чей показатель породил строку.
"""
import argparse, json, os, re, sqlite3, sys, unicodedata as ud
from collections import defaultdict, Counter

TILDE = '̃'   # чёрточка долготы (комбинирующая)
ACUTE = '́'   # русское ударение
PAL   = 'Ӏ'   # Ӏ — канонический вид палочки

DB_USER_VERSION = 6   # держать синхронно с DatabaseHelper.EXPECTED_DB_VERSION


# --------------------------------------------------------------------------
# 0. Паспорта словарей. Код в --dict CODE=PATH берётся отсюда.
#    `book` группирует две половины одной книги (чеч->рус и рус->чеч разделы
#    двуязычных словарей — это два источника с разными lang_src, но одна книга).
# --------------------------------------------------------------------------

DICTS = {
    'maciev1961': dict(
        book='maciev1961', title='Чеченско-русский словарь',
        authors='Мациев А. Г.', year=1961, place='М.',
        publisher='Гос. изд-во иностранных и национальных словарей',
        lang_src='ce', lang_tgt='ru', priority=10),

    'karasaev1978': dict(
        book='karasaev1978', title='Русско-чеченский словарь',
        authors='Карасаев А. Т., Мациев А. Г.', year=1978, place='М.',
        publisher='Русский язык',
        lang_src='ru', lang_tgt='ce', priority=20),

    'math1997_ce': dict(
        book='math1997',
        title='Чеченско-русский, русско-чеченский словарь математических терминов',
        authors='Умархаджиев С. М., Ахматукаев А. А.', year=1997, place='Грозный',
        publisher='', lang_src='ce', lang_tgt='ru', priority=30),
    'math1997_ru': dict(
        book='math1997',
        title='Чеченско-русский, русско-чеченский словарь математических терминов',
        authors='Умархаджиев С. М., Ахматукаев А. А.', year=1997, place='Грозный',
        publisher='', lang_src='ru', lang_tgt='ce', priority=31),

    'comp2017_ru': dict(
        book='comp2017',
        title='Русско-чеченский, чеченско-русский словарь компьютерной лексики',
        authors='Умархаджиев С. М. и др.', year=2017, place='Грозный',
        publisher='Академия наук ЧР', lang_src='ru', lang_tgt='ce', priority=40),
    'comp2017_ce': dict(
        book='comp2017',
        title='Русско-чеченский, чеченско-русский словарь компьютерной лексики',
        authors='Умархаджиев С. М. и др.', year=2017, place='Грозный',
        publisher='Академия наук ЧР', lang_src='ce', lang_tgt='ru', priority=41),
}


def citation(m):
    bits = [m['authors'], m['title'] + '.']
    tail = ', '.join(x for x in (m.get('place'), m.get('publisher')) if x)
    if tail:
        bits.append(tail + ',')
    bits.append(str(m['year']))
    return ' '.join(bits)


# --------------------------------------------------------------------------
# 1. Нормализаторы. Порты кода приложения — менять только вместе с ним.
# --------------------------------------------------------------------------

# ChechenNormalizer.MAP. Применяется ПОСЛЕ lower(), поэтому ключи строчные.
_CE_MAP = {
    'ӏ': PAL, 'Ӏ': PAL, 'i': PAL, 'l': PAL, '1': PAL,
    '|': PAL, 'І': PAL, 'і': PAL,
    'a': 'а', 'c': 'с', 'e': 'е', 'o': 'о',
    'p': 'р', 'x': 'х', 'y': 'у', 'k': 'к',
    'ѕ': 'з',
}
_WS = re.compile(r'\s+')


def normalize_ce(s):
    """Порт ChechenNormalizer.normalize(). Ключ ТОЧНОГО поиска по чеченскому."""
    if not s:
        return ''
    s = ud.normalize('NFC', s).lower()
    s = ''.join(_CE_MAP.get(ch, ch) for ch in s)
    s = ''.join(ch for ch in s if ud.category(ch) not in ('Mn', 'Mc', 'Me'))
    return ud.normalize('NFC', _WS.sub(' ', s).strip())


def normalize_ru(s):
    """Ключ точного поиска по русскому: NFC, lower, ё->е, снято ударение.

    ВАЖНО: русскую строку нельзя прогонять через normalize_ce — там '1', 'i',
    'l', '|' превращаются в палочку (это способ набрать Ӏ с обычной раскладки).
    Порт RuNormalizer.normalize() в приложении.
    """
    if not s:
        return ''
    s = ud.normalize('NFC', s).lower().replace('ё', 'е')
    s = ''.join(ch for ch in s if ud.category(ch) not in ('Mn', 'Mc', 'Me'))
    return _WS.sub(' ', s).strip()


# FuzzyKey.CE_FOLD / RU_FOLD
_FOLD_CE = {'ю': 'у', 'я': 'а', 'э': 'е', 'ё': 'е'}
_FOLD_RU = {'а': 'о', 'ё': 'е', 'и': 'е'}
_FOLD_DROP = ('ь', 'ъ', 'Ӏ', 'ӏ')


def _fold(source, table):
    """Порт FuzzyKey.fold(): выбросить ь/ъ/палочку и всё нележащее, схлопнуть дубли."""
    out = []
    for raw in source:
        if raw in _FOLD_DROP or not raw.isalpha():
            continue
        ch = table.get(raw, raw)
        if out and out[-1] == ch:
            continue
        out.append(ch)
    return ''.join(out)


def fuzzy_ce(s):
    """Порт FuzzyKey.chechen(). Скелет для примерного поиска."""
    return _fold(normalize_ce(s), _FOLD_CE)


def fuzzy_ru(s):
    """Порт FuzzyKey.russian()."""
    return _fold((s or '').lower(), _FOLD_RU)


NORMALIZE = {'ce': normalize_ce, 'ru': normalize_ru}
FUZZY = {'ce': fuzzy_ce, 'ru': fuzzy_ru}


def restore_long(t, positions):
    """('агӀо', [3]) -> 'агӀо̃'. Обратная split_length() из repair.py."""
    if not positions:
        return t
    out = list(t)
    for i in sorted(positions, reverse=True):
        if 0 <= i < len(t):
            out.insert(i + 1, TILDE)
    return ''.join(out)


# --------------------------------------------------------------------------
# 2. Snowball Russian. Порт org.tartarus.snowball.ext.RussianStemmer
#    (lucene-analysis-common 9.11.1), который использует RuStem.kt.
#    Проверяется флагом --verify против пакета snowballstemmer.
#    БЕЗ ИЗМЕНЕНИЙ ПРОТИВ v3.
# --------------------------------------------------------------------------

_VOWELS = frozenset('аеиоуыэюя')

_PERFECTIVE_1 = ('вшись', 'вши', 'в')                        # после а/я
_PERFECTIVE_2 = ('ывшись', 'ившись', 'ывши', 'ивши', 'ыв', 'ив')
_ADJECTIVE = ('ими', 'ыми', 'его', 'ого', 'ему', 'ому', 'ее', 'ие', 'ые', 'ое',
              'ей', 'ий', 'ый', 'ой', 'ем', 'им', 'ым', 'ом', 'их', 'ых',
              'ую', 'юю', 'ая', 'яя', 'ою', 'ею')
_PARTICIPLE_1 = ('ющ', 'ем', 'нн', 'вш', 'щ')                # после а/я
_PARTICIPLE_2 = ('ивш', 'ывш', 'ующ')
_REFLEXIVE = ('ся', 'сь')
_VERB_1 = ('ешь', 'нно', 'ете', 'йте', 'ла', 'на', 'ли', 'ем', 'ло', 'но',
           'ет', 'ют', 'ны', 'ть', 'й', 'л', 'н')            # после а/я
_VERB_2 = ('ейте', 'уйте', 'ила', 'ыла', 'ена', 'ите', 'или', 'ыли', 'ило',
           'ыло', 'ено', 'ует', 'уют', 'ены', 'ить', 'ыть', 'ишь', 'ей', 'уй',
           'ил', 'ыл', 'им', 'ым', 'ен', 'ят', 'ит', 'ыт', 'ую', 'ю')
_NOUN = ('иями', 'ями', 'ами', 'иях', 'ях', 'ах', 'ией', 'ием', 'иям', 'иях',
         'ев', 'ов', 'ие', 'ье', 'еи', 'ии', 'ей', 'ой', 'ий', 'ям', 'ем',
         'ам', 'ом', 'ию', 'ью', 'ия', 'ья', 'а', 'е', 'и', 'й', 'о', 'у',
         'ы', 'ь', 'ю', 'я')
_SUPERLATIVE = ('ейше', 'ейш')
_DERIVATIONAL = ('ость', 'ост')

# Snowball среди вариантов берёт САМЫЙ ДЛИННЫЙ — сортируем один раз.
_PERFECTIVE_1 = tuple(sorted(_PERFECTIVE_1, key=len, reverse=True))
_PERFECTIVE_2 = tuple(sorted(_PERFECTIVE_2, key=len, reverse=True))
_ADJECTIVE = tuple(sorted(_ADJECTIVE, key=len, reverse=True))
_PARTICIPLE_1 = tuple(sorted(_PARTICIPLE_1, key=len, reverse=True))
_PARTICIPLE_2 = tuple(sorted(_PARTICIPLE_2, key=len, reverse=True))
_VERB_1 = tuple(sorted(_VERB_1, key=len, reverse=True))
_VERB_2 = tuple(sorted(_VERB_2, key=len, reverse=True))
_NOUN = tuple(sorted(set(_NOUN), key=len, reverse=True))
_SUPERLATIVE = tuple(sorted(_SUPERLATIVE, key=len, reverse=True))
_DERIVATIONAL = tuple(sorted(_DERIVATIONAL, key=len, reverse=True))


def _rv_start(word):
    """RV — область после первой гласной."""
    for i, ch in enumerate(word):
        if ch in _VOWELS:
            return i + 1
    return len(word)


def _region_start(word, frm):
    """Начало области после первой согласной, идущей за гласной (R1/R2)."""
    i = frm
    while i < len(word) - 1:
        if word[i] in _VOWELS and word[i + 1] not in _VOWELS:
            return i + 2
        i += 1
    return len(word)


def ru_stem(word):
    """Порт RuStem.stem(): lower + ё->е, затем Snowball Russian."""
    word = (word or '').lower().replace('ё', 'е').strip()
    if not word:
        return ''

    rv = _rv_start(word)
    r2 = _region_start(word, _region_start(word, 0))

    def take(endings, need_ay=False):
        """Самое длинное окончание из списка, целиком лежащее в RV."""
        nonlocal word
        for end in endings:
            start = len(word) - len(end)
            if start < rv or not word.endswith(end):
                continue
            if need_ay:
                if start - 1 < rv or word[start - 1] not in ('а', 'я'):
                    continue
            word = word[:start]
            return True
        return False

    # ---- Шаг 1
    if not (take(_PERFECTIVE_2) or take(_PERFECTIVE_1, need_ay=True)):
        take(_REFLEXIVE)
        if take(_ADJECTIVE):                                   # ADJECTIVAL
            take(_PARTICIPLE_2) or take(_PARTICIPLE_1, need_ay=True)
        elif not (take(_VERB_2) or take(_VERB_1, need_ay=True)):
            take(_NOUN)

    # ---- Шаг 2
    if len(word) - 1 >= rv and word.endswith('и'):
        word = word[:-1]

    # ---- Шаг 3
    for end in _DERIVATIONAL:
        if word.endswith(end) and len(word) - len(end) >= r2:
            word = word[:-len(end)]
            break

    # ---- Шаг 4. В Snowball это один `among` — ветки ВЗАИМОИСКЛЮЧАЮЩИЕ.
    if word.endswith('нн') and len(word) - 1 >= rv:
        word = word[:-1]
    elif any(word.endswith(e) and len(word) - len(e) >= rv for e in _SUPERLATIVE):
        for end in _SUPERLATIVE:
            if word.endswith(end) and len(word) - len(end) >= rv:
                word = word[:-len(end)]
                if word.endswith('нн') and len(word) - 1 >= rv:
                    word = word[:-1]
                break
    elif word.endswith('ь') and len(word) - 1 >= rv:
        word = word[:-1]

    return word


def approx_key(lang, word):
    """Приблизительный ключ обратного индекса: основа Snowball для русского,
    скелет FuzzyKey для чеченского. Лежит в одной колонке `trans_index.stem`,
    потому что используется одинаково — запасным слоем поиска."""
    return ru_stem(word) if lang == 'ru' else fuzzy_ce(word)


# --------------------------------------------------------------------------
# 3. Схема
# --------------------------------------------------------------------------

SCHEMA = """
PRAGMA journal_mode = OFF;
PRAGMA synchronous  = OFF;

CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);

-- ---- источники ---------------------------------------------------------
-- Один ряд = один словарь ОДНОГО направления. У двуязычной книги два ряда
-- с общим `book`: разделы чеч->рус и рус->чеч устроены по-разному и ищутся
-- по-разному, но в UI показываются одним бейджем.
CREATE TABLE dicts(
    id        INTEGER PRIMARY KEY,
    code      TEXT UNIQUE NOT NULL,
    book      TEXT NOT NULL,
    title     TEXT NOT NULL,
    authors   TEXT NOT NULL,
    year      INTEGER,
    place     TEXT,
    publisher TEXT,
    lang_src  TEXT NOT NULL,          -- язык заголовка: ce | ru
    lang_tgt  TEXT NOT NULL,          -- язык перевода
    priority  INTEGER NOT NULL,       -- порядок при равном ранге выдачи
    n_lemmas  INTEGER NOT NULL DEFAULT 0,
    citation  TEXT NOT NULL);

-- ---- справочники -------------------------------------------------------
CREATE TABLE pos(
    id INTEGER PRIMARY KEY, code TEXT UNIQUE NOT NULL,
    name_ru TEXT NOT NULL, ordering INTEGER NOT NULL);

CREATE TABLE case_type(
    id INTEGER PRIMARY KEY, code TEXT UNIQUE NOT NULL,
    name_ru TEXT NOT NULL, abbr_ru TEXT NOT NULL, ordering INTEGER NOT NULL);

CREATE TABLE number_type(
    id INTEGER PRIMARY KEY, code TEXT UNIQUE NOT NULL, name_ru TEXT NOT NULL);

CREATE TABLE verb_tam(
    id INTEGER PRIMARY KEY, code TEXT UNIQUE NOT NULL,
    name_ru TEXT NOT NULL, ordering INTEGER NOT NULL);

-- Пометы уже человекочитаемы («перен.», «бот.»), расшифровка не нужна.
-- Код общий на все словари, частоты — пословарные: одна и та же «мат.»
-- у Мациева редкость, а в словаре 1997 года почти на каждой статье.
CREATE TABLE labels(
    id INTEGER PRIMARY KEY, code TEXT UNIQUE NOT NULL, n_uses INTEGER NOT NULL);

CREATE TABLE label_uses(
    dict_id  INTEGER NOT NULL REFERENCES dicts(id),
    label_id INTEGER NOT NULL REFERENCES labels(id),
    n_uses   INTEGER NOT NULL,
    PRIMARY KEY(dict_id, label_id));

-- ---- ядро --------------------------------------------------------------
CREATE TABLE lemmas(
    id             INTEGER PRIMARY KEY,
    dict_id        INTEGER NOT NULL REFERENCES dicts(id),
    lang           TEXT    NOT NULL,        -- язык заголовка = dicts.lang_src
    slug           TEXT    NOT NULL,        -- исходный id статьи из JSONL
    headword       TEXT    NOT NULL,        -- СО знаками -> прямо в UI
    headword_norm  TEXT    NOT NULL,        -- ключ точного поиска
    headword_fold  TEXT    NOT NULL,        -- скелет примерного поиска
    homonym        INTEGER,
    pos_id         INTEGER REFERENCES pos(id),
    class_star     INTEGER NOT NULL DEFAULT 0,
    pluralia_tantum INTEGER NOT NULL DEFAULT 0,
    cls_sg         TEXT    NOT NULL DEFAULT '[]',
    cls_pl         TEXT    NOT NULL DEFAULT '[]',
    obj_num        TEXT,
    subj_num       TEXT,
    gram           TEXT    NOT NULL DEFAULT '{}',  -- грамматика стороны заголовка,
                                                   -- как её даёт книга: для русских
                                                   -- заголовков {"gender":"м",
                                                   -- "aspect":"несов","forms":"-ая, -ое"}
    labels         TEXT    NOT NULL DEFAULT '[]',
    flags          TEXT    NOT NULL DEFAULT '[]',
    src_ref        INTEGER,                        -- строка/абзац в исходнике
    richness       INTEGER NOT NULL DEFAULT 0,     -- ВЫВЕДЕНО, не из книги: насколько
                                                   -- статья подробна. Только для
                                                   -- ранжирования, показывать нельзя
    ordering       INTEGER NOT NULL,               -- порядок ВНУТРИ своего словаря
    UNIQUE(dict_id, slug));

CREATE TABLE lemma_pos(
    lemma_id INTEGER NOT NULL REFERENCES lemmas(id),
    pos_id   INTEGER NOT NULL REFERENCES pos(id),
    ordering INTEGER NOT NULL,
    PRIMARY KEY(lemma_id, pos_id));

CREATE TABLE lemma_class(
    id       INTEGER PRIMARY KEY,
    dict_id  INTEGER NOT NULL REFERENCES dicts(id),
    lemma_id INTEGER NOT NULL REFERENCES lemmas(id),
    marker   TEXT    NOT NULL,              -- в | й | д | б
    number   TEXT    NOT NULL,              -- sg | pl
    ordering INTEGER NOT NULL);

-- Единственный вход прямого поиска по СТОРОНЕ ЗАГОЛОВКА. Заголовок, варианты,
-- парадигма и сгенерированные классные формы лежат тут вместе, различаются `kind`.
--
-- ВАЖНО: строки с source<>'dict' — ТОЛЬКО КЛЮЧИ ПОИСКА, показывать их как формы
-- слова нельзя. Замена показателя даёт верный ключ, но не всегда принятую
-- орфографию: у `даа` й-класс пишется `яа`, а генератор даёт `йаа`.
-- В карточке статьи фильтруйте: WHERE source='dict'.
CREATE TABLE forms(
    id            INTEGER PRIMARY KEY,
    dict_id       INTEGER NOT NULL REFERENCES dicts(id),
    lemma_id      INTEGER NOT NULL REFERENCES lemmas(id),
    sense_id      INTEGER REFERENCES senses(id),
    lang          TEXT    NOT NULL,         -- ce | ru
    form          TEXT    NOT NULL,         -- СО знаками
    form_norm     TEXT    NOT NULL,         -- ключ поиска
    form_fold     TEXT    NOT NULL,
    kind          TEXT    NOT NULL,   -- headword|variant|paradigm|class|expansion
    is_headword   INTEGER NOT NULL DEFAULT 0,
    case_id       INTEGER REFERENCES case_type(id),
    number_id     INTEGER REFERENCES number_type(id),
    tam_id        INTEGER REFERENCES verb_tam(id),
    cls           TEXT    NOT NULL DEFAULT '[]',
    star          INTEGER NOT NULL DEFAULT 0,
    source        TEXT    NOT NULL DEFAULT 'dict',   -- dict | gen | linked
    donor_dict_id INTEGER REFERENCES dicts(id),      -- чей показатель породил строку
    ordering      INTEGER NOT NULL DEFAULT 0);

CREATE TABLE blocks(
    id       INTEGER PRIMARY KEY,
    dict_id  INTEGER NOT NULL REFERENCES dicts(id),
    lemma_id INTEGER NOT NULL REFERENCES lemmas(id),
    n        INTEGER,
    pos_id   INTEGER REFERENCES pos(id),
    labels   TEXT NOT NULL DEFAULT '[]');

CREATE TABLE senses(
    id        INTEGER PRIMARY KEY,
    dict_id   INTEGER NOT NULL REFERENCES dicts(id),
    lemma_id  INTEGER NOT NULL REFERENCES lemmas(id),
    block_id  INTEGER REFERENCES blocks(id),
    block_n   INTEGER,
    sense_no  INTEGER,                      -- номер из книги, NULL если значение одно
    ordering  INTEGER NOT NULL,
    pos_id    INTEGER REFERENCES pos(id),
    labels    TEXT NOT NULL DEFAULT '[]',
    cls_sg    TEXT NOT NULL DEFAULT '[]',
    cls_pl    TEXT NOT NULL DEFAULT '[]',
    pluralia_tantum INTEGER NOT NULL DEFAULT 0);

-- Одно значение = несколько глоссов: «ослабле́ние; утомле́ние» — два перевода.
-- `sep` хранит разделитель ПЕРЕД глоссом: ',' синонимы, ';' более далёкое.
-- `lang` = язык перевода (dicts.lang_tgt): у словаря рус->чеч он чеченский.
CREATE TABLE glosses(
    id        INTEGER PRIMARY KEY,
    dict_id   INTEGER NOT NULL REFERENCES dicts(id),
    sense_id  INTEGER NOT NULL REFERENCES senses(id),
    lemma_id  INTEGER NOT NULL REFERENCES lemmas(id),
    idx       INTEGER NOT NULL,
    lang      TEXT NOT NULL,
    text      TEXT NOT NULL,                -- СО знаками -> прямо в UI
    text_norm TEXT NOT NULL,                -- ключ поиска по фразе целиком
    text_fold TEXT NOT NULL,                -- скелет фразы
    sep       TEXT,
    note      TEXT,                         -- уточнение: «кисть»
    gov       TEXT,                         -- управление: «чем-л.»
    labels    TEXT NOT NULL DEFAULT '[]',
    -- Грамматика САМОГО ПЕРЕВОДА, как её даёт книга: у словаря рус->чеч это
    -- классный показатель чеченского слова, «абрис (ю)» -> {"cls":["й"]}.
    -- Стоит ПОСЛЕДНЕЙ намеренно: код, читающий glosses по позиции столбца,
    -- от появления колонки не поедет.
    gram      TEXT NOT NULL DEFAULT '{}');

-- У примера обе стороны названы по ЯЗЫКУ, а не по роли: `ce` всегда чеченский,
-- `ru` всегда русский, какое бы из них ни было заголовочным в этой книге.
CREATE TABLE examples(
    id        INTEGER PRIMARY KEY,
    dict_id   INTEGER NOT NULL REFERENCES dicts(id),
    lemma_id  INTEGER NOT NULL REFERENCES lemmas(id),
    sense_id  INTEGER REFERENCES senses(id),
    is_idiom  INTEGER NOT NULL DEFAULT 0,
    idx       INTEGER NOT NULL,
    ce        TEXT NOT NULL,
    ce_norm   TEXT NOT NULL,
    ce_fold   TEXT NOT NULL,
    ru        TEXT,
    ru_norm   TEXT,
    ru_fold   TEXT,
    kind      TEXT,                          -- phrase | посл. | погов.
    note      TEXT,
    note_kind TEXT,                          -- «букв.» и т. п.
    gov       TEXT,
    labels    TEXT NOT NULL DEFAULT '[]');

CREATE TABLE subs(
    id         INTEGER PRIMARY KEY,
    dict_id    INTEGER NOT NULL REFERENCES dicts(id),
    example_id INTEGER NOT NULL REFERENCES examples(id),
    idx        INTEGER NOT NULL,
    letter     TEXT,                         -- «а», «б»
    lang       TEXT NOT NULL,
    text       TEXT NOT NULL,
    text_norm  TEXT NOT NULL,
    note       TEXT,
    gov        TEXT,
    labels     TEXT NOT NULL DEFAULT '[]');

-- Отсылки автора: «понуд. от», «см.», «прил. к». Разрешаются ТОЛЬКО внутри
-- своей книги — см. шапку файла.
CREATE TABLE cross_refs(
    id               INTEGER PRIMARY KEY,
    dict_id          INTEGER NOT NULL REFERENCES dicts(id),
    from_lemma_id    INTEGER NOT NULL REFERENCES lemmas(id),
    rel              TEXT NOT NULL,
    to_headword      TEXT NOT NULL,
    to_headword_norm TEXT NOT NULL,
    to_lemma_id      INTEGER REFERENCES lemmas(id),
    to_homonyms      TEXT NOT NULL DEFAULT '[]');

-- Обратный индекс: сторона ПЕРЕВОДА -> статья. Висит на глоссе, а не на статье,
-- поэтому выдача может показать, какое именно значение совпало.
-- `src` задаёт вес: 0 глосс, 1 пример, 2 идиома, 3 протянуто по отсылке.
CREATE TABLE trans_index(
    id        INTEGER PRIMARY KEY,
    dict_id   INTEGER NOT NULL REFERENCES dicts(id),
    lang      TEXT    NOT NULL,              -- язык слова = dicts.lang_tgt
    word      TEXT    NOT NULL,              -- нормализованное слово перевода
    stem      TEXT    NOT NULL,              -- Snowball (ru) | скелет FuzzyKey (ce)
    lemma_id  INTEGER NOT NULL REFERENCES lemmas(id),
    src       INTEGER NOT NULL,
    target_id INTEGER);                      -- glosses.id либо examples.id

-- Сшивка статей между книгами. НЕ слияние: у каждой книги остаётся своя лемма
-- со своей грамматикой, связь лишь говорит «это одно и то же слово».
-- `conflict` перечисляет поля, в которых книги расходятся — это не ошибка,
-- а материал для ручного разбора и для показа в карточке.
CREATE TABLE lemma_links(
    id         INTEGER PRIMARY KEY,
    a_lemma_id INTEGER NOT NULL REFERENCES lemmas(id),
    b_lemma_id INTEGER NOT NULL REFERENCES lemmas(id),
    a_dict_id  INTEGER NOT NULL REFERENCES dicts(id),
    b_dict_id  INTEGER NOT NULL REFERENCES dicts(id),
    lang       TEXT    NOT NULL,
    method     TEXT    NOT NULL,             -- norm | class | fold+gloss | manual
    confidence REAL    NOT NULL,
    conflict   TEXT    NOT NULL DEFAULT '[]',
    -- Связь просмотрел человек и подтвердил: расхождение в классе настоящее,
    -- обе книги правы. UI по этому флагу может не показывать значок спора.
    reviewed   INTEGER NOT NULL DEFAULT 0,
    note       TEXT,
    UNIQUE(a_lemma_id, b_lemma_id));
"""

INDEXES = """
CREATE INDEX ix_lemmas_norm    ON lemmas(headword_norm);
CREATE INDEX ix_lemmas_fold    ON lemmas(headword_fold);
CREATE INDEX ix_lemmas_order   ON lemmas(dict_id, ordering);
CREATE INDEX ix_lemmas_rich    ON lemmas(richness);
CREATE INDEX ix_forms_norm     ON forms(form_norm, lang);
CREATE INDEX ix_forms_fold     ON forms(form_fold, lang);
CREATE INDEX ix_forms_lemma    ON forms(lemma_id);
CREATE INDEX ix_senses_lemma   ON senses(lemma_id);
CREATE INDEX ix_glosses_sense  ON glosses(sense_id);
CREATE INDEX ix_glosses_lemma  ON glosses(lemma_id);
CREATE INDEX ix_glosses_norm   ON glosses(text_norm, lang);
CREATE INDEX ix_glosses_fold   ON glosses(text_fold, lang);
CREATE INDEX ix_examples_lemma ON examples(lemma_id);
CREATE INDEX ix_examples_sense ON examples(sense_id);
CREATE INDEX ix_examples_norm  ON examples(ce_norm);
CREATE INDEX ix_examples_rnorm ON examples(ru_norm);
CREATE INDEX ix_subs_example   ON subs(example_id);
CREATE INDEX ix_blocks_lemma   ON blocks(lemma_id);
CREATE INDEX ix_lclass_lemma   ON lemma_class(lemma_id);
CREATE INDEX ix_xref_from      ON cross_refs(from_lemma_id);
CREATE INDEX ix_xref_norm      ON cross_refs(dict_id, to_headword_norm);
CREATE INDEX ix_ti_word        ON trans_index(word, lang);
CREATE INDEX ix_ti_stem        ON trans_index(stem, lang);
CREATE INDEX ix_ti_lemma       ON trans_index(lemma_id);
CREATE INDEX ix_links_a        ON lemma_links(a_lemma_id);
CREATE INDEX ix_links_b        ON lemma_links(b_lemma_id);
"""

POS = [('сущ', 'существительное'), ('прил', 'прилагательное'), ('гл', 'глагол'),
       ('масд', 'масдар'), ('прич', 'причастие'), ('деепр', 'деепричастие'),
       ('нареч', 'наречие'), ('числ', 'числительное'), ('мест', 'местоимение'),
       ('послелог', 'послелог'), ('союз', 'союз'), ('частица', 'частица'),
       ('межд', 'междометие'), ('звукоподр', 'звукоподражание'),
       ('приставка', 'приставка')]

CASES = [('nom', 'именительный', 'им.'), ('gen', 'родительный', 'род.'),
         ('dat', 'дательный', 'дат.'), ('erg', 'эргативный', 'эрг.'),
         ('all', 'местный', 'местн.'), ('instr', 'творительный', 'твор.'),
         ('cmp', 'сравнительный', 'сравн.'), ('subst', 'вещественный', 'вещ.')]

NUMBERS = [('sg', 'единственное'), ('pl', 'множественное')]

TAMS = [('inf', 'инфинитив'), ('masd', 'масдар'), ('pres', 'настоящее время'),
        ('past_wit', 'очевидно-прошедшее'), ('perf', 'прошедшее совершенное'),
        ('imperf', 'прошедшее несовершенное'), ('fut', 'будущее время'),
        ('imp', 'повелительное'), ('cvb', 'деепричастие'), ('ptcp', 'причастие')]

# Служебные слова не индексируются — но только если в глоссе есть что-то ещё.
# Иначе перевод «и» у союза `а` стал бы ненаходимым.
RU_STOPWORDS = frozenset("""
и в во на с со к ко о об обо от до по за из у не ни же ли бы что чего чему чем
кто кого кому кем то та те тот та́к как так вот все всё весь вся был была было
были быть есть его ее её их он она оно они мы вы ты мой моя свой своя этот эта
это эти для при над под про без через или а но да там тут ещё еще уже лишь
либо нибудь кое чей чья чьи который которая которые
""".split())

# Для чеченского готового списка нет, а выдумывать его — вносить в базу
# лингвистическое решение, которого ни в одной книге не написано. Поэтому
# служебные слова берём ИЗ ДАННЫХ: самые частотные токены переводов. Список
# кладётся в meta, его видно и можно оспорить.
CE_STOP_TOP = 40


# --------------------------------------------------------------------------
# 4. Классные показатели (без изменений против v3)
# --------------------------------------------------------------------------

CLASS_MARKERS = ('в', 'й', 'д', 'б')
CE_VOWELS = frozenset('аеиоуыэюяё')


def exponent_candidates(norm):
    """Позиции, где может стоять классный показатель: в/й/д/б перед гласной."""
    out = []
    for i, ch in enumerate(norm):
        if ch not in CLASS_MARKERS:
            continue
        if i + 1 >= len(norm) or norm[i + 1] not in CE_VOWELS:
            continue
        if ch == 'й' and i > 0 and norm[i - 1] in 'иьуюы':
            continue
        out.append(i)
    return out


def exponent_confirmed(norm, headwords):
    """Позиция, подтверждённая самим словарём: `диса*` -> есть `биса`, `виса`, `йиса`."""
    best, best_hits = None, 0
    for i in exponent_candidates(norm):
        hits = sum(1 for c in CLASS_MARKERS
                   if c != norm[i] and (norm[:i] + c + norm[i + 1:]) in headwords)
        if hits > best_hits:
            best, best_hits = i, hits
    return best, best_hits


def exponent_positional(norm):
    """Запасное правило: показатель — последний в/й/д/б перед гласной."""
    cands = exponent_candidates(norm)
    return cands[-1] if cands else None


# Цель отсылки часто напечатана вместе с номером омонима и обрывком дефиса.
_XREF_TAIL = re.compile(r'[\s‐-―־−\-.,;:]*([0-9]+)?'
                        r'[\s‐-―־−\-.,;:]*$')


def clean_xref_target(text):
    """'ба̃н1-' -> ('ба̃н', 1)."""
    m = _XREF_TAIL.search(text or '')
    if not m:
        return text, None
    hom = int(m.group(1)) if m.group(1) else None
    return text[:m.start()], hom


# --------------------------------------------------------------------------
# 5. Сборка
# --------------------------------------------------------------------------

# Палочка U+04C0 НЕ входит в диапазон а-я — без неё в классе «жамӀ» распадётся
# на «жам» и потеряет ключ.
WORD_RE = {'ru': re.compile(r'[а-яё]+'),
           'ce': re.compile(r'[а-яёӀ]+')}


def index_words(lang, text_norm):
    """Слова перевода для обратного индекса.

    Служебные выбрасываются, НО ТОЛЬКО ЕСЛИ в глоссе остаётся что-то ещё:
    у союза `а` перевод — ровно «и», у `бу` — ровно «есть», и выбросить их
    значило бы сделать статью ненаходимой. Тот же отбор обязан применяться
    и к глоссам, протянутым по отсылке (src=3), иначе статьи вида «см. бу»
    молча теряют единственный ключ.
    """
    words = WORD_RE[lang].findall(text_norm or '')
    significant = [w for w in words
                   if len(w) > 1 and (lang != 'ru' or w not in RU_STOPWORDS)]
    return significant or [w for w in words if w]


def J(x):
    return json.dumps(x if x is not None else [], ensure_ascii=False)


def JO(x):
    return json.dumps(x if x is not None else {}, ensure_ascii=False)


def marked(v):
    """{t, long, raw} или голая строка -> строка со знаками."""
    if isinstance(v, dict):
        return restore_long(v.get('t') or '', v.get('long') or [])
    return v or ''


def keyed(lang, v):
    """-> (со знаками, norm, fold) с нормализатором нужного языка."""
    m = marked(v)
    return m, NORMALIZE[lang](m), FUZZY[lang](m)


def load_entries(path):
    """Читает JSONL, разводит совпавшие id (`атлас` -> `атлас#2`)."""
    entries, seen = [], set()
    with open(path, encoding='utf-8') as f:
        for ln, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            slug = d.get('id') or f'__noid_{ln}'
            if slug in seen:
                base, k = slug, 2
                while f'{base}#{k}' in seen:
                    k += 1
                slug = f'{base}#{k}'
            seen.add(slug)
            d['_slug'] = slug
            entries.append(d)
    return entries


def build(db_path, sources, class_forms='safe', want_fts=True, want_links=False,
          reviewed=None):
    """sources — список (code, jsonl_path) в порядке приоритета показа."""
    log = []
    if os.path.exists(db_path):
        os.remove(db_path)
    db = sqlite3.connect(db_path)
    db.executescript(SCHEMA)

    # ---- паспорта источников -------------------------------------------
    dict_id = {}
    for i, (code, _path) in enumerate(sources, 1):
        if code not in DICTS:
            raise SystemExit(f'неизвестный код словаря: {code!r}. '
                             f'Известны: {", ".join(sorted(DICTS))}. '
                             f'Новый словарь заводится в DICTS в этом файле.')
        m = DICTS[code]
        dict_id[code] = i
        db.execute(
            'INSERT INTO dicts(id,code,book,title,authors,year,place,publisher,'
            'lang_src,lang_tgt,priority,citation) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',
            (i, code, m['book'], m['title'], m['authors'], m['year'], m.get('place'),
             m.get('publisher'), m['lang_src'], m['lang_tgt'], m['priority'],
             citation(m)))

    # ---- справочники ----------------------------------------------------
    pos_id = {}
    for i, (code, name) in enumerate(POS, 1):
        db.execute('INSERT INTO pos(id,code,name_ru,ordering) VALUES(?,?,?,?)',
                   (i, code, name, i))
        pos_id[code] = i
    case_id = {}
    for i, (code, name, abbr) in enumerate(CASES, 1):
        db.execute('INSERT INTO case_type(id,code,name_ru,abbr_ru,ordering)'
                   ' VALUES(?,?,?,?,?)', (i, code, name, abbr, i))
        case_id[code] = i
    num_id = {}
    for i, (code, name) in enumerate(NUMBERS, 1):
        db.execute('INSERT INTO number_type(id,code,name_ru) VALUES(?,?,?)', (i, code, name))
        num_id[code] = i
    tam_id = {}
    for i, (code, name) in enumerate(TAMS, 1):
        db.execute('INSERT INTO verb_tam(id,code,name_ru,ordering) VALUES(?,?,?,?)',
                   (i, code, name, i))
        tam_id[code] = i

    def pos_ref(codes):
        """id первой части речи; неизвестный код заводим на лету, а не теряем."""
        for code in codes or []:
            if code not in pos_id:
                pos_id[code] = len(pos_id) + 1
                db.execute('INSERT INTO pos(id,code,name_ru,ordering) VALUES(?,?,?,?)',
                           (pos_id[code], code, code, 99))
            return pos_id[code]
        return None

    label_uses = defaultdict(int)          # (dict_id, code) -> n

    # ---- глобальные счётчики id ----------------------------------------
    ids = {'form': 0, 'block': 0, 'sense': 0, 'gloss': 0, 'example': 0,
           'sub': 0, 'xref': 0, 'lemma': 0, 'link': 0}
    forms_seen = set()                     # (lemma_id, form_norm, kind)
    ti_rows = []                           # (dict_id, lang, word, stem, lemma, src, target)
    ti_seen = set()
    glosses_of_lemma = defaultdict(list)   # lemma_id -> [(gloss_id, text_norm)]
    ce_token_df = Counter()                # для частотного стоп-листа чеченского

    def push_ti(did, lang, text_norm, lemma, src, target):
        chosen = index_words(lang, text_norm)
        if lang == 'ce':
            ce_token_df.update(set(chosen))
        added = 0
        for w in chosen:
            key = (w, lemma, src, target)
            if key in ti_seen:
                continue
            ti_seen.add(key)
            ti_rows.append((did, lang, w, approx_key(lang, w), lemma, src, target))
            added += 1
        return added

    per_dict = {}                          # code -> служебные структуры источника
    gen_stats_all = {}
    counts_src = {}

    # ======================= проход по источникам ========================
    for code, path in sources:
        did = dict_id[code]
        meta = DICTS[code]
        lsrc, ltgt = meta['lang_src'], meta['lang_tgt']
        entries = load_entries(path)
        counts_src[code] = len(entries)

        lemma_id_of_slug = {}
        by_norm = defaultdict(list)
        headword_norms = set()

        def note_labels(*groups):
            for g in groups:
                for c in g or []:
                    label_uses[(did, c)] += 1

        def add_ti(text_norm, lemma, src, target):
            push_ti(did, ltgt, text_norm, lemma, src, target)

        def add_form(lemma, obj, kind, sense=None, order=0, source='dict', donor=None):
            form, norm, fold = keyed(lsrc, obj.get('form') if isinstance(obj, dict) else obj)
            if not norm:
                return
            key = (lemma, norm, kind)
            if key in forms_seen:
                return
            forms_seen.add(key)
            ids['form'] += 1
            m = obj if isinstance(obj, dict) else {}
            db.execute(
                'INSERT INTO forms(id,dict_id,lemma_id,sense_id,lang,form,form_norm,'
                'form_fold,kind,is_headword,case_id,number_id,tam_id,cls,star,source,'
                'donor_dict_id,ordering) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                (ids['form'], did, lemma, sense, lsrc, form, norm, fold, kind,
                 int(kind == 'headword'), case_id.get(m.get('case')),
                 num_id.get(m.get('num')), tam_id.get(m.get('vform')),
                 J(m.get('cls')), int(bool(m.get('star'))), source, donor, order))

        def add_examples(lemma, sense, items, is_idiom):
            for k, ex in enumerate(items or []):
                ce, ce_norm, ce_fold = keyed('ce', ex.get('ce'))
                # ВНИМАНИЕ: '' и None — разные значения. В книге бывает пример
                # без перевода (`ru: ""`), и v3 хранила там пустую строку.
                # `or None` здесь означало бы тихо переписать такие статьи.
                _raw_ru = ex.get('ru')
                ru = marked(_raw_ru) if _raw_ru is not None else None
                ids['example'] += 1
                eid = ids['example']
                note_labels(ex.get('labels'))
                db.execute(
                    'INSERT INTO examples(id,dict_id,lemma_id,sense_id,is_idiom,idx,'
                    'ce,ce_norm,ce_fold,ru,ru_norm,ru_fold,kind,note,note_kind,gov,labels)'
                    ' VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                    (eid, did, lemma, sense, int(is_idiom), k, ce, ce_norm, ce_fold,
                     ru, normalize_ru(ru) if ru else None,
                     fuzzy_ru(ru) if ru else None,
                     ex.get('kind'), ex.get('note'), ex.get('note_kind'),
                     ex.get('gov'), J(ex.get('labels'))))
                # в обратный индекс идёт СТОРОНА ПЕРЕВОДА этой книги
                tgt_text = ru if ltgt == 'ru' else ce
                if tgt_text:
                    add_ti(NORMALIZE[ltgt](tgt_text), lemma, 2 if is_idiom else 1, eid)
                for j, sub in enumerate(ex.get('subs') or []):
                    stext = marked(sub.get('ru') or sub.get('text') or '')
                    ids['sub'] += 1
                    note_labels(sub.get('labels'))
                    db.execute(
                        'INSERT INTO subs(id,dict_id,example_id,idx,letter,lang,text,'
                        'text_norm,note,gov,labels) VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                        (ids['sub'], did, eid, j, sub.get('letter'), ltgt, stext,
                         NORMALIZE[ltgt](stext), sub.get('note'), sub.get('gov'),
                         J(sub.get('labels'))))
                    add_ti(NORMALIZE[ltgt](stext), lemma, 2 if is_idiom else 1, eid)

        def add_senses(lemma, senses, block_id, block_n):
            for k, s in enumerate(senses or []):
                ids['sense'] += 1
                sid = ids['sense']
                note_labels(s.get('labels'))
                db.execute(
                    'INSERT INTO senses(id,dict_id,lemma_id,block_id,block_n,sense_no,'
                    'ordering,pos_id,labels,cls_sg,cls_pl,pluralia_tantum)'
                    ' VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',
                    (sid, did, lemma, block_id, block_n, s.get('n'), k,
                     pos_ref(s.get('pos')), J(s.get('labels')), J(s.get('cls_sg')),
                     J(s.get('cls_pl')), int(bool(s.get('plurale_tantum')))))
                for j, f in enumerate(s.get('forms') or []):
                    add_form(lemma, f, 'paradigm', sense=sid, order=j)
                for j, g in enumerate(s.get('glosses') or []):
                    gtext = marked(g.get('ru') if g.get('ru') is not None else g.get('text'))
                    gnorm = NORMALIZE[ltgt](gtext)
                    gfold = FUZZY[ltgt](gtext)
                    ids['gloss'] += 1
                    gid = ids['gloss']
                    note_labels(g.get('labels'))
                    db.execute(
                        'INSERT INTO glosses(id,dict_id,sense_id,lemma_id,idx,lang,text,'
                        'text_norm,text_fold,sep,note,gov,labels,gram)'
                        ' VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                        (gid, did, sid, lemma, j, ltgt, gtext, gnorm, gfold,
                         g.get('sep'), g.get('note'), g.get('gov'),
                         J(g.get('labels')), JO(g.get('gram'))))
                    glosses_of_lemma[lemma].append((gid, gnorm))
                    add_ti(gnorm, lemma, 0, gid)
                add_examples(lemma, sid, s.get('examples'), False)

        # ---- проход 1: леммы --------------------------------------------
        for k, d in enumerate(entries, 1):
            ids['lemma'] += 1
            lid = ids['lemma']
            hw, hw_norm, hw_fold = keyed(lsrc, d.get('headword'))
            lemma_id_of_slug[d['_slug']] = lid
            by_norm[hw_norm].append((lid, d.get('homonym')))
            headword_norms.add(hw_norm)
            for c in d.get('labels') or []:
                label_uses[(did, c)] += 1
            db.execute(
                'INSERT INTO lemmas(id,dict_id,lang,slug,headword,headword_norm,'
                'headword_fold,homonym,pos_id,class_star,pluralia_tantum,cls_sg,cls_pl,'
                'obj_num,subj_num,gram,labels,flags,src_ref,ordering)'
                ' VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                (lid, did, lsrc, d['_slug'], hw, hw_norm, hw_fold, d.get('homonym'),
                 pos_ref(d.get('pos')), int(bool(d.get('class_star'))),
                 int(bool(d.get('plurale_tantum'))), J(d.get('cls_sg')), J(d.get('cls_pl')),
                 d.get('obj_num'), d.get('subj_num'),
                 JO(dict(d.get('gram') or {},
                         **({'pron': d['pron']} if d.get('pron') else {}))),
                 J(d.get('labels')), J(d.get('flags')), d.get('src_ref'), k))
            for j, c in enumerate(d.get('pos') or []):
                db.execute('INSERT OR IGNORE INTO lemma_pos(lemma_id,pos_id,ordering)'
                           ' VALUES(?,?,?)', (lid, pos_ref([c]), j))
            for number, key in (('sg', 'cls_sg'), ('pl', 'cls_pl')):
                for j, marker in enumerate(d.get(key) or []):
                    db.execute('INSERT INTO lemma_class(dict_id,lemma_id,marker,number,'
                               'ordering) VALUES(?,?,?,?,?)', (did, lid, marker, number, j))

        # ---- проход 2: всё остальное ------------------------------------
        for d in entries:
            lemma = lemma_id_of_slug[d['_slug']]
            add_form(lemma, {'form': d.get('headword'), 'star': d.get('class_star')},
                     'headword')
            for k, v in enumerate(d.get('variants') or []):
                add_form(lemma, v, 'variant', order=k)
            # П. 23 Карасаева: «местко́м (ме́стный комите́т профсою́зной
            # организа́ции)». Расшифровка — это то, по чему статью будут искать,
            # поэтому она форма, а не примечание.
            if d.get('expansion'):
                add_form(lemma, {'form': d['expansion']}, 'expansion')
            for k, f in enumerate(d.get('forms') or []):
                add_form(lemma, f, 'paradigm', order=k)

            for b in d.get('blocks') or []:
                ids['block'] += 1
                bid = ids['block']
                for c in b.get('labels') or []:
                    label_uses[(did, c)] += 1
                db.execute('INSERT INTO blocks(id,dict_id,lemma_id,n,pos_id,labels)'
                           ' VALUES(?,?,?,?,?,?)',
                           (bid, did, lemma, b.get('n'), pos_ref(b.get('pos')),
                            J(b.get('labels'))))
                add_senses(lemma, b.get('senses'), bid, b.get('n'))
            add_senses(lemma, d.get('senses'), None, None)
            add_examples(lemma, None, d.get('idioms'), True)

            for x in d.get('xrefs') or []:
                tgt = marked(x.get('target'))
                tgt, hom = clean_xref_target(tgt)
                homs = x.get('homonyms') or ([hom] if hom is not None else [])
                ids['xref'] += 1
                db.execute(
                    'INSERT INTO cross_refs(id,dict_id,from_lemma_id,rel,to_headword,'
                    'to_headword_norm,to_homonyms) VALUES(?,?,?,?,?,?,?)',
                    (ids['xref'], did, lemma, x.get('rel') or '', tgt,
                     NORMALIZE[lsrc](tgt), J(homs)))

        per_dict[code] = dict(did=did, lsrc=lsrc, ltgt=ltgt, by_norm=by_norm,
                              headword_norms=headword_norms,
                              slugs=lemma_id_of_slug, n=len(entries))

    # ---- пометы ---------------------------------------------------------
    label_id = {}
    total = Counter()
    for (did, code), n in label_uses.items():
        total[code] += n
    for code, n in sorted(total.items()):
        db.execute('INSERT INTO labels(code,n_uses) VALUES(?,?)', (code, n))
        label_id[code] = db.execute('SELECT id FROM labels WHERE code=?', (code,)).fetchone()[0]
    for (did, code), n in label_uses.items():
        db.execute('INSERT INTO label_uses(dict_id,label_id,n_uses) VALUES(?,?,?)',
                   (did, label_id[code], n))

    # ---- разрешение отсылок: строго внутри своей книги -------------------
    linked = 0
    for code, _ in sources:
        st = per_dict[code]
        by_norm = st['by_norm']

        def resolve(norm, homonyms):
            cands = by_norm.get(norm)
            if not cands:
                return None
            if homonyms:
                for lid, hom in cands:
                    if hom == homonyms[0]:
                        return lid
            return cands[0][0]

        rows = db.execute('SELECT id, to_headword_norm, to_homonyms FROM cross_refs'
                          ' WHERE dict_id=?', (st['did'],)).fetchall()
        for xid, norm, homs in rows:
            lid = resolve(norm, json.loads(homs))
            if lid is not None:
                linked += 1
                db.execute('UPDATE cross_refs SET to_lemma_id=? WHERE id=?', (lid, xid))

    # ---- кто на кого ссылается ------------------------------------------
    ref_targets = defaultdict(list)
    for s, t in db.execute('SELECT from_lemma_id, to_lemma_id FROM cross_refs'
                           ' WHERE to_lemma_id IS NOT NULL').fetchall():
        ref_targets[s].append(t)

    # ---- генерация классных форм (только для чеченской стороны) ---------
    gen_total = {'entries': 0, 'forms': 0}
    for code, _ in sources:
        st = per_dict[code]
        if st['lsrc'] != 'ce' or class_forms == 'none':
            continue
        did = st['did']
        gen = {'entries': 0, 'forms': 0, 'confirmed': 0, 'single': 0, 'inherited': 0,
               'guessed': 0, 'unknown': 0, 'samples': []}
        norm_of = dict(db.execute('SELECT id, headword_norm FROM lemmas WHERE dict_id=?',
                                  (did,)).fetchall())
        starred = [r[0] for r in db.execute(
            'SELECT id FROM lemmas WHERE dict_id=? AND class_star=1', (did,))]
        paradigm = defaultdict(list)
        for lid, fnorm in db.execute("SELECT lemma_id, form_norm FROM forms"
                                     " WHERE dict_id=? AND kind='paradigm'", (did,)):
            paradigm[lid].append(fnorm)
        headword_norms = st['headword_norms']
        exponent, origin = {}, {}

        # (1) позицию показывает сам словарь
        for lid in starred:
            p, _ = exponent_confirmed(norm_of[lid], headword_norms)
            if p is not None:
                exponent[lid], origin[lid] = p, 'confirmed'
        # (2) единственный кандидат — не догадка
        for lid in starred:
            if lid in exponent:
                continue
            c = exponent_candidates(norm_of[lid])
            if len(c) == 1:
                exponent[lid], origin[lid] = c[0], 'single'
        # (3) наследование от базового глагола по отсылке
        for _ in range(6):
            grew = False
            for lid in starred:
                if lid in exponent:
                    continue
                for tgt in ref_targets.get(lid, ()):
                    e = exponent.get(tgt)
                    if e is None:
                        continue
                    base, here = norm_of.get(tgt, ''), norm_of[lid]
                    if here.startswith(base[:e + 1]) and len(here) > e and here[e] == base[e]:
                        exponent[lid], origin[lid] = e, 'inherited'
                        grew = True
                        break
            if not grew:
                break
        # (4) запасное правило — только по явной просьбе
        for lid in starred:
            if lid in exponent:
                continue
            e = exponent_positional(norm_of[lid])
            if e is not None and class_forms == 'all':
                exponent[lid], origin[lid] = e, 'guessed'
            else:
                gen['unknown'] += 1
        for lid in exponent:
            gen[origin[lid]] += 1

        for lid, exp in exponent.items():
            norm = norm_of[lid]
            made = 0
            for marker in CLASS_MARKERS:
                if marker == norm[exp]:
                    continue
                variant = norm[:exp] + marker + norm[exp + 1:]
                if variant in headword_norms:
                    continue
                add_this = [(variant, exp)]
                for fnorm in paradigm[lid]:
                    if exp < len(fnorm) and fnorm[exp] == norm[exp]:
                        add_this.append((fnorm[:exp] + marker + fnorm[exp + 1:], exp))
                for v, o in add_this:
                    if (lid, v, 'class') in forms_seen:
                        continue
                    forms_seen.add((lid, v, 'class'))
                    ids['form'] += 1
                    db.execute(
                        'INSERT INTO forms(id,dict_id,lemma_id,lang,form,form_norm,'
                        'form_fold,kind,source,donor_dict_id,ordering)'
                        ' VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                        (ids['form'], did, lid, 'ce', v, v, fuzzy_ce(v),
                         'class', 'gen', did, o))
                    made += 1
            if made:
                gen['entries'] += 1
                gen['forms'] += made
                if len(gen['samples']) < 6:
                    gen['samples'].append((norm, exp, origin[lid], made))
        gen_stats_all[code] = gen
        gen_total['entries'] += gen['entries']
        gen_total['forms'] += gen['forms']

    # ---- trans_index по отсылкам ----------------------------------------
    # Статьи вида «понуд. от X» своих переводов не имеют и иначе не находятся.
    propagated = 0
    for lemma, in db.execute('SELECT id FROM lemmas').fetchall():
        if glosses_of_lemma.get(lemma):
            continue
        did, ltgt = db.execute(
            'SELECT l.dict_id, d.lang_tgt FROM lemmas l JOIN dicts d ON d.id=l.dict_id'
            ' WHERE l.id=?', (lemma,)).fetchone()
        seen_hop, frontier = {lemma}, list(ref_targets.get(lemma, ()))
        for _ in range(2):
            nxt = []
            for tgt in frontier:
                if tgt in seen_hop:
                    continue
                seen_hop.add(tgt)
                if glosses_of_lemma.get(tgt):
                    for gid, tnorm in glosses_of_lemma[tgt]:
                        propagated += push_ti(did, ltgt, tnorm, lemma, 3, gid)
                else:
                    nxt.extend(ref_targets.get(tgt, ()))
            if not nxt:
                break
            frontier = nxt

    # ---- частотный стоп-лист чеченского ---------------------------------
    ce_stop = set()
    if ce_token_df:
        ce_stop = {w for w, _ in ce_token_df.most_common(CE_STOP_TOP)}
        keep_by_lemma = defaultdict(int)
        for r in ti_rows:
            if r[1] == 'ce' and r[2] not in ce_stop:
                keep_by_lemma[(r[4], r[6])] += 1
        ti_rows = [r for r in ti_rows
                   if r[1] != 'ce' or r[2] not in ce_stop or not keep_by_lemma[(r[4], r[6])]]

    db.executemany('INSERT INTO trans_index(dict_id,lang,word,stem,lemma_id,src,target_id)'
                   ' VALUES(?,?,?,?,?,?,?)', ti_rows)

    # ---- сшивка статей между книгами ------------------------------------
    link_stats = {'norm': 0, 'class': 0, 'fold+gloss': 0, 'conflicts': 0}
    link_report = []
    link_stale = []
    if want_links and len(sources) > 1:
        link_stats, link_report, link_stale = build_links(db, ids, reviewed)

    # ---- индексы, FTS, метаданные ---------------------------------------
    # Насколько статья подробна. Не факт из книги, а мера для ранжирования:
    # при равном совпадении полная статья Мациева должна стоять выше голого
    # «термин -> термин» из отраслевого словаря.
    db.execute("""
        UPDATE lemmas SET richness =
            (SELECT COUNT(*) FROM senses  s WHERE s.lemma_id = lemmas.id)
          + (SELECT COUNT(*) FROM glosses g WHERE g.lemma_id = lemmas.id)
          + 2 * (SELECT COUNT(*) FROM examples e WHERE e.lemma_id = lemmas.id)
          + (SELECT COUNT(*) FROM forms f WHERE f.lemma_id = lemmas.id
               AND f.source = 'dict' AND f.kind = 'paradigm')
    """)

    db.executescript(INDEXES)
    for did in dict_id.values():
        db.execute('UPDATE dicts SET n_lemmas=(SELECT COUNT(*) FROM lemmas'
                   ' WHERE dict_id=?) WHERE id=?', (did, did))

    fts = False
    if want_fts:
        try:
            db.executescript(
                "CREATE VIRTUAL TABLE forms_trgm USING fts5("
                "  form_norm, content='forms', content_rowid='id',"
                "  tokenize='trigram');"
                "INSERT INTO forms_trgm(rowid, form_norm) SELECT id, form_norm FROM forms;")
            fts = True
        except sqlite3.OperationalError as e:
            log.append(f'FTS5 недоступна, forms_trgm не создана: {e}')

    counts = {t: db.execute(f'SELECT COUNT(*) FROM {t}').fetchone()[0] for t in (
        'dicts', 'lemmas', 'forms', 'senses', 'glosses', 'examples', 'subs',
        'cross_refs', 'trans_index', 'blocks', 'lemma_class', 'labels', 'lemma_links')}
    for k, v in [('schema_version', DB_USER_VERSION),
                 ('sources', ','.join(c for c, _ in sources)),
                 ('normalizer', 'ChechenNormalizer.kt / RuNormalizer.kt / '
                                'FuzzyKey.kt / RuStem.kt'),
                 ('generated_class_forms', gen_total['forms']),
                 ('ce_stopwords', ' '.join(sorted(ce_stop))),
                 ('fts5', int(fts))]:
        db.execute('INSERT INTO meta(key,value) VALUES(?,?)', (k, str(v)))

    db.execute(f'PRAGMA user_version = {DB_USER_VERSION}')
    db.commit()
    db.execute('VACUUM')

    return db, counts, gen_stats_all, {
        'linked': linked, 'xrefs': ids['xref'], 'propagated': propagated,
        'fts': fts, 'log': log, 'links': link_stats, 'link_report': link_report,
        'link_stale': link_stale, 'per_dict': counts_src}


# --------------------------------------------------------------------------
# 6. lemma_links — сшивка статей между книгами
# --------------------------------------------------------------------------

DECISIONS = ('ok', 'unlink', 'link')


def load_reviewed(path):
    """reviewed.tsv -> {frozenset({(словарь, slug), (словарь, slug)}): (решение, заметка)}

    Формат — первые шесть колонок отчёта `conflicts.tsv`, чтобы строку можно было
    просто скопировать из отчёта и дописать решение:

        a_dict  a_slug  b_dict  b_slug  decision  note

        ok      связь верна, расхождение настоящее — не показывать как спор
        unlink  это разные слова, связь снять
        link    связать вручную то, чего лесенка не нашла

    Пары ненаправленные: порядок сторон в строке значения не имеет.
    Пустые строки и строки с `#` игнорируются.
    """
    out = {}
    if not path or not os.path.exists(path):
        return out
    with open(path, encoding='utf-8') as f:
        for ln, line in enumerate(f, 1):
            line = line.rstrip('\n')
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            cols = line.split('\t')
            if cols[0].strip() == 'a_dict':          # шапка отчёта
                continue
            if len(cols) < 5:
                raise SystemExit(f'{path}:{ln}: нужно минимум 5 колонок, найдено {len(cols)}')
            a_dict, a_slug, b_dict, b_slug, decision = (c.strip() for c in cols[:5])
            note = cols[5].strip() if len(cols) > 5 else None
            if decision not in DECISIONS:
                raise SystemExit(f'{path}:{ln}: решение {decision!r} не из '
                                 f'{", ".join(DECISIONS)}')
            key = frozenset({(a_dict, a_slug), (b_dict, b_slug)})
            if len(key) != 2:
                raise SystemExit(f'{path}:{ln}: обе стороны — одна и та же статья')
            out[key] = (decision, note or None)
    return out


def build_links(db, ids, reviewed=None):
    """Лесенка по убыванию надёжности. Связываются только леммы, чьи ЗАГОЛОВКИ
    на одном языке: `аре` Мациева и `по́ле` Карасаева — это перевод, а не
    тождество, и его делает поиск, а не сшивка."""
    stats = Counter()
    report = []
    reviewed = reviewed or {}
    used = set()
    code_of = dict(db.execute('SELECT id, code FROM dicts').fetchall())
    key_of = {lid: (code_of[did], slug) for lid, did, slug
              in db.execute('SELECT id, dict_id, slug FROM lemmas')}
    by_ident = {}
    for lid, ident in key_of.items():
        by_ident.setdefault(ident, []).append(lid)

    lemmas = db.execute(
        'SELECT id, dict_id, lang, headword, headword_norm, headword_fold,'
        ' pos_id, cls_sg, cls_pl FROM lemmas').fetchall()
    by_key = defaultdict(list)
    by_fold = defaultdict(list)
    rec = {}
    for lid, did, lang, hw, norm, fold, pid, csg, cpl in lemmas:
        rec[lid] = (did, lang, hw, norm, fold, pid, csg, cpl)
        by_key[(lang, norm)].append(lid)
        by_fold[(lang, fold)].append(lid)

    # переводы леммы — для двуязычной сверки на шагах 2 и 3
    trans = defaultdict(set)
    for lid, w in db.execute('SELECT lemma_id, stem FROM trans_index WHERE src IN (0,3)'):
        trans[lid].add(w)
    # и они же словами — чтобы отчёт о расхождениях можно было читать глазами
    gloss_of = defaultdict(list)
    for lid, t in db.execute('SELECT lemma_id, text FROM glosses ORDER BY id'):
        if len(gloss_of[lid]) < 3:
            gloss_of[lid].append(t)
    # У статьи может не быть своих переводов — только отсылка: у Мациева
    # «цхьамза см. цамза», и в отчёте колонка выходила пустой, а судить о паре
    # по пустой колонке нельзя. Показываем переводы ЦЕЛИ, а не только её имя:
    # «см. цамза: штык; копьё» сразу говорит, то же это слово или нет.
    # На словаре 1978 таких статей будут тысячи, так что это не мелочь.
    for lid, rel, tgt, to_id in db.execute(
            'SELECT from_lemma_id, rel, to_headword, to_lemma_id FROM cross_refs'):
        if gloss_of.get(lid):
            continue
        tail = '; '.join(gloss_of.get(to_id, ())) if to_id else ''
        gloss_of[lid].append(f'{rel} {tgt}'.strip() + (f': {tail}' if tail else ''))

    seen = set()

    def norm_cls(js):
        table = {'ю': 'й', 'йу': 'й', 'ду': 'д', 'бу': 'б', 'ву': 'в'}
        return sorted({table.get(x, x) for x in json.loads(js or '[]')})

    def emit(a, b, method, conf):
        if a > b:
            a, b = b, a
        if (a, b) in seen:
            return
        pair = frozenset({key_of[a], key_of[b]})
        decision, note = reviewed.get(pair, (None, None))
        if decision is not None:
            used.add(pair)
        if decision == 'unlink':
            stats['снято вручную'] += 1
            seen.add((a, b))
            return
        seen.add((a, b))
        ra, rb = rec[a], rec[b]
        conflict = []
        if ra[5] is not None and rb[5] is not None and ra[5] != rb[5]:
            conflict.append('pos')
        # У связи по классному показателю расхождение в классе — не спор, а
        # ПОСЫЛКА: `дист` и `йист` у Мациева стоят отдельными статьями («йист
        # см. дист») именно потому, что это одно слово в разных классах.
        # Сравнивать их cls_sg/cls_pl бессмысленно по построению.
        if method != 'class':
            if norm_cls(ra[6]) and norm_cls(rb[6]) and norm_cls(ra[6]) != norm_cls(rb[6]):
                conflict.append('cls_sg')
            if norm_cls(ra[7]) and norm_cls(rb[7]) and norm_cls(ra[7]) != norm_cls(rb[7]):
                conflict.append('cls_pl')
        ids['link'] += 1
        db.execute('INSERT INTO lemma_links(id,a_lemma_id,b_lemma_id,a_dict_id,'
                   'b_dict_id,lang,method,confidence,conflict,reviewed,note)'
                   ' VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                   (ids['link'], a, b, ra[0], rb[0], ra[1], method, conf, J(conflict),
                    int(decision in ('ok', 'link')), note))
        stats[method] += 1
        if conflict:
            # `link` считается решением наравне с `ok`: человек, ставящий связь
            # руками, эту пару уже смотрел — незачем возвращать её в отчёт.
            settled = decision in ('ok', 'link')
            stats['расхождений подтверждено' if settled else 'расхождений'] += 1
            if not settled:
                ka, kb = key_of[a], key_of[b]
                report.append((ka[0], ka[1], kb[0], kb[1], '', '',
                               ra[2], rb[2], method, ','.join(conflict),
                               ra[6], rb[6], ra[7], rb[7],
                               '; '.join(gloss_of.get(a, ())),
                               '; '.join(gloss_of.get(b, ()))))

    # (1) точное совпадение нормализованного заголовка
    for (lang, norm), group in by_key.items():
        if len(group) < 2 or not norm:
            continue
        for i, a in enumerate(group):
            for b in group[i + 1:]:
                if rec[a][0] != rec[b][0]:
                    emit(a, b, 'norm', 1.0)

    # (2) совпадение после нейтрализации классного показателя (только чеченский)
    for (lang, norm), group in by_key.items():
        if lang != 'ce' or not norm:
            continue
        for e in exponent_candidates(norm):
            for marker in CLASS_MARKERS:
                if marker == norm[e]:
                    continue
                other = by_key.get(('ce', norm[:e] + marker + norm[e + 1:]))
                for a in group:
                    for b in other or ():
                        # Подтверждение с другой стороны обязательно и здесь.
                        # Нейтрализация показателя связывает `диса`/`биса` —
                        # это одно слово, у них общие переводы. Но она же
                        # свяжет `ба̃зар` с `дазар` и `дист` с `йист`, а это
                        # разные слова, просто похожие по буквам.
                        if rec[a][0] != rec[b][0] and trans[a] & trans[b]:
                            emit(a, b, 'class', 0.9)

    # (3) совпадение скелета + подтверждение с другой стороны
    for (lang, fold), group in by_fold.items():
        if len(group) < 2 or not fold:
            continue
        for i, a in enumerate(group):
            for b in group[i + 1:]:
                if rec[a][0] == rec[b][0]:
                    continue
                if (min(a, b), max(a, b)) in seen:
                    continue
                if trans[a] & trans[b]:
                    emit(a, b, 'fold+gloss', 0.7)

    # (4) связи, которых лесенка не нашла, — поставленные руками
    for pair, (decision, note) in reviewed.items():
        if decision != 'link' or pair in used:
            continue
        sides = [by_ident.get(ident, []) for ident in pair]
        if not all(sides):
            continue
        for a in sides[0]:
            for b in sides[1]:
                if rec[a][0] != rec[b][0]:
                    emit(a, b, 'manual', 1.0)

    stale = [pair for pair in reviewed if pair not in used]
    return dict(stats), report, stale


# --------------------------------------------------------------------------
# 7. Самопроверки. Гонятся на собранной базе.
# --------------------------------------------------------------------------

def self_checks(db):
    problems = []

    # 1. Нормализаторы ведут себя как код приложения
    key = normalize_ce('мостагӀе')
    for variant in ('мoстагIе', 'мостаг1е', 'МOCТAГIЕ'):
        if normalize_ce(variant) != key:
            problems.append(f'normalize_ce: {variant!r} не сошёлся с {key!r}')
    if normalize_ce('йаха') == normalize_ce('иаха'):
        problems.append('normalize_ce: й и и слиплись — сломается классный показатель')
    if ru_stem('утомления') != ru_stem('утомление'):
        problems.append('ru_stem: словоформы одного слова дали разные основы')
    if normalize_ru('1-й ряд') != '1-й ряд':
        problems.append('normalize_ru: русскую строку тронул чеченский маппинг')

    # 2. Палочка везде в каноническом U+04C0
    for table, col, cond in (('lemmas', 'headword_norm', "lang='ce'"),
                             ('forms', 'form_norm', "lang='ce'"),
                             ('examples', 'ce_norm', '1')):
        n = db.execute(f"SELECT COUNT(*) FROM {table} WHERE {cond}"
                       f" AND {col} LIKE '%' || char(1231) || '%'").fetchone()[0]
        if n:
            problems.append(f'{table}.{col}: {n} строк со строчной палочкой U+04CF')

    # 3. Каждая статья находится прямым поиском по своему заголовку
    n = db.execute("""
        SELECT COUNT(*) FROM lemmas l WHERE NOT EXISTS(
            SELECT 1 FROM forms f
            WHERE f.lemma_id = l.id AND f.form_norm = l.headword_norm)
    """).fetchone()[0]
    if n:
        problems.append(f'{n} статей не находятся по своему заголовку')

    # 4. Каждый глосс сделал свою статью находимой с обратной стороны
    n = db.execute("""
        SELECT COUNT(*) FROM glosses g WHERE g.text_norm <> '' AND NOT EXISTS(
            SELECT 1 FROM trans_index t WHERE t.lemma_id = g.lemma_id)
    """).fetchone()[0]
    if n:
        problems.append(f'{n} глоссов не попали в trans_index')

    # 5. dict_id проставлен везде
    for t in ('lemmas', 'forms', 'senses', 'glosses', 'examples', 'subs',
              'cross_refs', 'trans_index', 'blocks', 'lemma_class'):
        n = db.execute(f'SELECT COUNT(*) FROM {t} WHERE dict_id IS NULL').fetchone()[0]
        if n:
            problems.append(f'{t}: {n} строк без dict_id')

    # 6. Отсылка не пересекает границу словаря
    n = db.execute("""
        SELECT COUNT(*) FROM cross_refs x JOIN lemmas l ON l.id = x.to_lemma_id
        WHERE x.to_lemma_id IS NOT NULL AND l.dict_id <> x.dict_id
    """).fetchone()[0]
    if n:
        problems.append(f'{n} отсылок ведут в другой словарь — это запрещено')

    # 7. Связь соединяет РАЗНЫЕ словари и один язык заголовка
    n = db.execute('SELECT COUNT(*) FROM lemma_links WHERE a_dict_id = b_dict_id').fetchone()[0]
    if n:
        problems.append(f'{n} связей внутри одного словаря')
    n = db.execute("""
        SELECT COUNT(*) FROM lemma_links k
        JOIN lemmas a ON a.id = k.a_lemma_id JOIN lemmas b ON b.id = k.b_lemma_id
        WHERE a.lang <> b.lang
    """).fetchone()[0]
    if n:
        problems.append(f'{n} связей между заголовками разных языков')

    # 8. Язык строки согласован с паспортом словаря
    n = db.execute("""
        SELECT COUNT(*) FROM forms f JOIN dicts d ON d.id = f.dict_id
        WHERE f.lang <> d.lang_src
    """).fetchone()[0]
    if n:
        problems.append(f'forms: {n} строк с языком, не равным dicts.lang_src')
    n = db.execute("""
        SELECT COUNT(*) FROM glosses g JOIN dicts d ON d.id = g.dict_id
        WHERE g.lang <> d.lang_tgt
    """).fetchone()[0]
    if n:
        problems.append(f'glosses: {n} строк с языком, не равным dicts.lang_tgt')

    # 9. Сгенерированные формы — только для чеченской стороны
    n = db.execute("SELECT COUNT(*) FROM forms WHERE source<>'dict' AND lang<>'ce'").fetchone()[0]
    if n:
        problems.append(f'{n} сгенерированных форм на нечеченской стороне')

    orphan = db.execute("""
        SELECT COUNT(*) FROM lemmas l
        WHERE NOT EXISTS(SELECT 1 FROM glosses g WHERE g.lemma_id = l.id)
          AND NOT EXISTS(SELECT 1 FROM trans_index t WHERE t.lemma_id = l.id)
    """).fetchone()[0]
    return problems, orphan


def probe(db, word, lang='ru'):
    """Что отдаст обратный поиск на это слово."""
    return db.execute("""
        SELECT l.headword, l.homonym, g.text, t.src, d.code
        FROM trans_index t
        JOIN lemmas l ON l.id = t.lemma_id
        JOIN dicts  d ON d.id = t.dict_id
        LEFT JOIN glosses g ON g.id = t.target_id AND t.src IN (0,3)
        WHERE t.word = ? AND t.lang = ?
        ORDER BY t.src, d.priority, l.ordering LIMIT 8
    """, (NORMALIZE[lang](word), lang)).fetchall()


def probe_forward(db, word, lang='ce'):
    """Прямой поиск по стороне заголовка — во всех словарях сразу."""
    return db.execute("""
        SELECT l.headword, d.code, MAX(f.is_headword) AS exact
        FROM forms f JOIN lemmas l ON l.id = f.lemma_id
        JOIN dicts d ON d.id = f.dict_id
        WHERE f.form_norm = ? AND f.lang = ?
        GROUP BY l.id ORDER BY exact DESC, d.priority, l.ordering LIMIT 8
    """, (NORMALIZE[lang](word), lang)).fetchall()


def probe_phrase(db, phrase, lang='ru'):
    return db.execute("""
        SELECT l.headword, g.text, d.code FROM glosses g
        JOIN lemmas l ON l.id = g.lemma_id JOIN dicts d ON d.id = g.dict_id
        WHERE g.text_norm = ? AND g.lang = ? LIMIT 5
    """, (NORMALIZE[lang](phrase), lang)).fetchall()


# --------------------------------------------------------------------------
# 8. Точка входа
# --------------------------------------------------------------------------

def main(argv=None):
    ap = argparse.ArgumentParser(description='*.jsonl -> dict.db (схема v4)')
    ap.add_argument('db', nargs='?', default='app/src/main/assets/dict.db')
    ap.add_argument('--dict', action='append', metavar='CODE=PATH', required=True,
                    help='источник; можно повторять. CODE — из таблицы DICTS')
    ap.add_argument('--class-forms', choices=('none', 'safe', 'all'), default='safe')
    ap.add_argument('--no-fts', action='store_true', help='не создавать forms_trgm')
    ap.add_argument('--links', action='store_true',
                    help='строить lemma_links между словарями')
    ap.add_argument('--links-report', metavar='TSV',
                    help='куда выгрузить расхождения сшитых статей')
    ap.add_argument('--reviewed', metavar='TSV',
                    help='файл решений человека по связям (см. load_reviewed)')
    ap.add_argument('--verify', action='store_true',
                    help='сверить порт Snowball с пакетом snowballstemmer')
    args = ap.parse_args(argv)

    sources = []
    for spec in args.dict:
        if '=' not in spec:
            ap.error(f'--dict ждёт CODE=PATH, получено {spec!r}')
        code, path = spec.split('=', 1)
        if not os.path.exists(path):
            ap.error(f'нет файла: {path}')
        sources.append((code, path))

    db, counts, gen, info = build(args.db, sources,
                                  class_forms=args.class_forms,
                                  want_fts=not args.no_fts,
                                  want_links=args.links,
                                  reviewed=load_reviewed(args.reviewed))

    print(f'\n{args.db}  (PRAGMA user_version = {DB_USER_VERSION})')
    print(f'  размер {os.path.getsize(args.db) / 1e6:.1f} МБ\n')
    for code, path in sources:
        m = DICTS[code]
        print(f'  {code:<14}{info["per_dict"][code]:>7} статей   '
              f'{m["lang_src"]}->{m["lang_tgt"]}   {os.path.basename(path)}')
    print()
    for k, v in counts.items():
        print(f'  {k:<14}{v:>9}')

    print(f'\n  отсылок разрешено   {info["linked"]} из {info["xrefs"]}'
          f' ({100 * info["linked"] / max(1, info["xrefs"]):.1f} %)')
    print(f'  trans_index по отсылкам {info["propagated"]:>6}')
    print(f'  forms_trgm (FTS5)   {"есть" if info["fts"] else "нет"}')
    for line in info['log']:
        print(f'  ! {line}')

    for code, g in gen.items():
        if not g['forms']:
            continue
        print(f"\n  классные формы [{code}]: {g['forms']} на {g['entries']} статей")
        print(f"    позиция подтверждена словарём:     {g['confirmed']}")
        print(f"    единственно возможная в слове:     {g['single']}")
        print(f"    унаследована от базового глагола:  {g['inherited']}")
        print(f"    взята запасным правилом:           {g['guessed']}")
        print(f"    осталась неизвестной, не трогаем:  {g['unknown']}")
        for norm, exp, why, made in g['samples']:
            print(f"    {norm}  «{norm[exp]}» на позиции {exp} ({why}), форм {made}")

    if any(info['links'].values()):
        print('\n  сшивка статей между словарями:')
        for k, v in sorted(info['links'].items()):
            print(f'    {k:<14}{v:>7}')
    if args.links_report and info['link_report']:
        with open(args.links_report, 'w', encoding='utf-8') as f:
            # Первые шесть колонок — ровно формат reviewed.tsv: строку из
            # отчёта копируют туда как есть и дописывают решение.
            f.write('a_dict\ta_slug\tb_dict\tb_slug\tdecision\tnote'
                    '\ta_headword\tb_headword\tmethod\tconflict'
                    '\ta_cls_sg\tb_cls_sg\ta_cls_pl\tb_cls_pl'
                    '\ta_glosses\tb_glosses\n')
            for row in info['link_report']:
                f.write('\t'.join(str(x) for x in row) + '\n')
        print(f'    расхождения выгружены: {args.links_report}'
              f' ({len(info["link_report"])} строк)')
    if info.get('link_stale'):
        print(f'\n  ! {len(info["link_stale"])} строк reviewed.tsv ни на что не легли '
              f'(статья переименовалась или исчезла из источника):')
        for pair in info['link_stale'][:10]:
            print('      ' + '  <->  '.join(f'{d}:{s}' for d, s in sorted(pair)))

    problems, orphan = self_checks(db)
    print(f'\n  статей без переводов и без отсылок: {orphan}')
    if problems:
        print('\n  ПРОБЛЕМЫ:')
        for p in problems:
            print(f'    ! {p}')
    else:
        print('  самопроверки пройдены')

    print('\n  контрольные запросы:')
    langs = {d[0] for d in db.execute('SELECT lang_tgt FROM dicts')}
    if 'ru' in langs:
        for w in ('утомление', 'палка', 'каждый', 'ошибки', 'поголовно'):
            rows = probe(db, w, 'ru')
            shown = ', '.join(f'{hw}{"" if h is None else h}[{c}]'
                              for hw, h, _, _, c in rows[:4])
            print(f'    рус «{w}» -> {len(rows)}: {shown or "—"}')
        for ph in ('каждый раз', 'находить ошибки'):
            print(f'    фраза «{ph}» -> {[r[0] for r in probe_phrase(db, ph, "ru")] or "—"}')
    fwd = {d[0] for d in db.execute('SELECT lang_src FROM dicts')}
    if 'ce' in fwd:
        for w in ('куьйго', 'аьхна', 'мохь'):
            rows = probe_forward(db, w, 'ce')
            print(f'    чеч «{w}» -> {[(r[0], r[1]) for r in rows[:4]] or "—"}')

    if args.verify:
        try:
            import snowballstemmer
        except ImportError:
            print('\n  --verify: snowballstemmer не установлен, сверка пропущена')
        else:
            st = snowballstemmer.stemmer('russian')
            vocab = set()
            for (t,) in db.execute("SELECT text_norm FROM glosses WHERE lang='ru'"):
                vocab.update(WORD_RE['ru'].findall(t or ''))
            for (t,) in db.execute('SELECT ru_norm FROM examples WHERE ru_norm IS NOT NULL'):
                vocab.update(WORD_RE['ru'].findall(t or ''))
            bad = [w for w in vocab if ru_stem(w) != st.stemWord(w)]
            print(f'\n  --verify: сверено {len(vocab)} слов, расхождений {len(bad)}')
            for w in bad[:10]:
                print(f'    {w}: порт {ru_stem(w)!r}, эталон {st.stemWord(w)!r}')

    db.close()
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())

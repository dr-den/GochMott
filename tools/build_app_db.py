# -*- coding: utf-8 -*-
"""
maciev.jsonl -> dict.db  (поисковая база для приложения GochMott)

    python tools/build_app_db.py path/to/maciev.jsonl app/src/main/assets/dict.db
    python tools/build_app_db.py ... --verify        # сверить стеммер со snowballstemmer
    python tools/build_app_db.py ... --no-gen-class  # не генерировать классные варианты

Только стандартная библиотека. Файл БД пересоздаётся с нуля.

Чем отличается от `build_db.py` из Приложения Д (архивная база):

  * ТЕКСТ ХРАНИТСЯ СО ЗНАКАМИ. `headword`/`form`/`ce` — с чёрточками долготы,
    `ru` — с ударением. Снятие знаков — задача рендера (одна `filterNot`),
    поэтому массивы позиций `*_long` в приложении не нужны. Архивная база делала
    наоборот: хранила плоскую строку, и размеченную приходилось собирать обратно.
  * КЛЮЧИ ПОИСКА СЧИТАЮТСЯ ПОРТАМИ КОДА ПРИЛОЖЕНИЯ. `norm` = ChechenNormalizer.kt,
    `fold` = FuzzyKey.kt, `stem` = RuStem.kt (Snowball через Lucene). У архивной
    базы `fold_ce` расходился с приложением на палочке: `'Ӏ'.lower()` даёт `ӏ`
    (U+04CF), а ChechenNormalizer канонизирует в `Ӏ` (U+04C0) — 18,5 % статей
    не находились бы точным поиском.
  * ОДНА ТАБЛИЦА СЛОВОФОРМ. Заголовок, варианты, парадигма и сгенерированные
    классные формы лежат в `word_forms` — прямой поиск это один индекс, а не
    UNION трёх таблиц.
  * ЕСТЬ `ru_index`. Обратный поиск рус→чеч, которого в архивной схеме нет вовсе.
    Индекс висит на глоссе, а не на статье, поэтому выдача может показать, какое
    именно значение совпало. Глоссы протянуты по отсылкам: статьи вида
    «понуд. от X» своих переводов не имеют и иначе по-русски не находятся.
  * НЕТ `source`. Полный исходный текст статьи — 13,7 МБ из 30 МБ архивной базы,
    приложению не нужен.
"""
import argparse, json, os, re, sqlite3, sys, unicodedata as ud
from collections import defaultdict

TILDE = '\u0303'   # чёрточка долготы (комбинирующая)
ACUTE = '\u0301'   # русское ударение
PAL   = '\u04C0'   # Ӏ — канонический вид палочки

DB_USER_VERSION = 3   # держать синхронно с DatabaseHelper.EXPECTED_DB_VERSION


# --------------------------------------------------------------------------
# 1. Нормализаторы. Порты кода приложения — менять только вместе с ним.
# --------------------------------------------------------------------------

# ChechenNormalizer.MAP. Применяется ПОСЛЕ lower(), поэтому ключи строчные.
_CE_MAP = {
    '\u04CF': PAL, '\u04C0': PAL, 'i': PAL, 'l': PAL, '1': PAL,
    '|': PAL, '\u0406': PAL, '\u0456': PAL,
    'a': '\u0430', 'c': '\u0441', 'e': '\u0435', 'o': '\u043E',
    'p': '\u0440', 'x': '\u0445', 'y': '\u0443', 'k': '\u043A',
    '\u0455': '\u0437',
}
_WS = re.compile(r'\s+')


def normalize_ce(s):
    """Порт ChechenNormalizer.normalize(). Ключ ТОЧНОГО поиска чеч→рус."""
    if not s:
        return ''
    s = ud.normalize('NFC', s).lower()
    s = ''.join(_CE_MAP.get(ch, ch) for ch in s)
    s = ''.join(ch for ch in s if ud.category(ch) not in ('Mn', 'Mc', 'Me'))
    return ud.normalize('NFC', _WS.sub(' ', s).strip())


# FuzzyKey.CE_FOLD / RU_FOLD
_FOLD_CE = {'ю': 'у', 'я': 'а', 'э': 'е', 'ё': 'е'}
_FOLD_RU = {'а': 'о', 'ё': 'е', 'и': 'е'}
_FOLD_DROP = ('ь', 'ъ', '\u04C0', '\u04CF')


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


def normalize_ru(s):
    """Ключ поиска рус→чеч: NFC, lower, ё→е, снято ударение, схлопнуты пробелы."""
    if not s:
        return ''
    s = ud.normalize('NFC', s).lower().replace('ё', 'е')
    s = ''.join(ch for ch in s if ud.category(ch) not in ('Mn', 'Mc', 'Me'))
    return _WS.sub(' ', s).strip()


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
    """Порт RuStem.stem(): lower + ё→е, затем Snowball Russian."""
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
                # а/я — часть окончания деепричастия, но снимается только суффикс
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

    # ---- Шаг 4. В Snowball это один `among` — ветки ВЗАИМОИСКЛЮЧАЮЩИЕ, и все
    #      внутри RV. Отсюда два неочевидных случая: у 'ль' мягкий знак не
    #      снимается (гласной нет, RV пуста), а у '...ьейш' снимается только
    #      'ейш' — ветка 'ь' уже не отрабатывает.
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


# --------------------------------------------------------------------------
# 3. Схема
# --------------------------------------------------------------------------

SCHEMA = """
PRAGMA journal_mode = OFF;
PRAGMA synchronous  = OFF;

CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);

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

-- Пометы Мациева уже человекочитаемы («перен.», «бот.»), расшифровка не нужна —
-- таблица нужна, чтобы UI мог показать список и посчитать частоты.
CREATE TABLE labels(
    id INTEGER PRIMARY KEY, code TEXT UNIQUE NOT NULL, n_uses INTEGER NOT NULL);

-- ---- ядро --------------------------------------------------------------
CREATE TABLE lemmas(
    id             INTEGER PRIMARY KEY,
    slug           TEXT    NOT NULL,        -- исходный id статьи из maciev.jsonl
    headword       TEXT    NOT NULL,        -- СО знаками долготы -> прямо в UI
    headword_norm  TEXT    NOT NULL,        -- ключ точного поиска (ChechenNormalizer)
    headword_fold  TEXT    NOT NULL,        -- скелет примерного поиска (FuzzyKey)
    homonym        INTEGER,
    pos_id         INTEGER REFERENCES pos(id),
    class_star     INTEGER NOT NULL DEFAULT 0,
    pluralia_tantum INTEGER NOT NULL DEFAULT 0,
    cls_sg         TEXT    NOT NULL DEFAULT '[]',
    cls_pl         TEXT    NOT NULL DEFAULT '[]',
    obj_num        TEXT,
    subj_num       TEXT,
    labels         TEXT    NOT NULL DEFAULT '[]',
    flags          TEXT    NOT NULL DEFAULT '[]',
    ordering       INTEGER NOT NULL);

-- у статьи бывает несколько частей речи (`1.` прил. / `2.` нареч.)
CREATE TABLE lemma_pos(
    lemma_id INTEGER NOT NULL REFERENCES lemmas(id),
    pos_id   INTEGER NOT NULL REFERENCES pos(id),
    ordering INTEGER NOT NULL,
    PRIMARY KEY(lemma_id, pos_id));

CREATE TABLE lemma_class(
    id       INTEGER PRIMARY KEY,
    lemma_id INTEGER NOT NULL REFERENCES lemmas(id),
    marker   TEXT    NOT NULL,              -- в | й | д | б
    number   TEXT    NOT NULL,              -- sg | pl
    ordering INTEGER NOT NULL);

-- Единственный вход прямого поиска: заголовок, варианты, парадигма и
-- сгенерированные классные формы лежат тут вместе, различаются `kind`.
--
-- ВАЖНО: строки с source='gen' (kind='class') — ТОЛЬКО КЛЮЧИ ПОИСКА, их нельзя
-- показывать как формы слова. Замена показателя даёт верный ключ, но не всегда
-- принятую орфографию: у `даа` й-класс пишется `яа`, а генератор даёт `йаа`.
-- В карточке статьи фильтруйте: WHERE source='dict'.
CREATE TABLE word_forms(
    id          INTEGER PRIMARY KEY,
    lemma_id    INTEGER NOT NULL REFERENCES lemmas(id),
    sense_id    INTEGER REFERENCES senses(id),
    form        TEXT    NOT NULL,           -- СО знаками
    form_norm   TEXT    NOT NULL,           -- ключ поиска
    form_fold   TEXT    NOT NULL,
    kind        TEXT    NOT NULL,           -- headword|variant|paradigm|class
    is_headword INTEGER NOT NULL DEFAULT 0,
    case_id     INTEGER REFERENCES case_type(id),
    number_id   INTEGER REFERENCES number_type(id),
    tam_id      INTEGER REFERENCES verb_tam(id),
    cls         TEXT    NOT NULL DEFAULT '[]',
    star        INTEGER NOT NULL DEFAULT 0,
    source      TEXT    NOT NULL DEFAULT 'dict',   -- dict | gen
    ordering    INTEGER NOT NULL DEFAULT 0);

CREATE TABLE blocks(
    id       INTEGER PRIMARY KEY,
    lemma_id INTEGER NOT NULL REFERENCES lemmas(id),
    n        INTEGER,
    pos_id   INTEGER REFERENCES pos(id),
    labels   TEXT NOT NULL DEFAULT '[]');

CREATE TABLE senses(
    id        INTEGER PRIMARY KEY,
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
CREATE TABLE glosses(
    id       INTEGER PRIMARY KEY,
    sense_id INTEGER NOT NULL REFERENCES senses(id),
    lemma_id INTEGER NOT NULL REFERENCES lemmas(id),
    idx      INTEGER NOT NULL,
    ru       TEXT NOT NULL,                 -- СО ударением -> прямо в UI
    ru_norm  TEXT NOT NULL,                 -- ключ поиска по фразе целиком
    sep      TEXT,
    note     TEXT,                          -- уточнение: «кисть»
    gov      TEXT,                          -- управление: «чем-л.»
    labels   TEXT NOT NULL DEFAULT '[]');

CREATE TABLE examples(
    id        INTEGER PRIMARY KEY,
    lemma_id  INTEGER NOT NULL REFERENCES lemmas(id),
    sense_id  INTEGER REFERENCES senses(id),
    is_idiom  INTEGER NOT NULL DEFAULT 0,
    idx       INTEGER NOT NULL,
    ce        TEXT NOT NULL,
    ce_norm   TEXT NOT NULL,
    ce_fold   TEXT NOT NULL,
    ru        TEXT,
    ru_norm   TEXT,
    kind      TEXT,                          -- phrase | посл. | погов.
    note      TEXT,
    note_kind TEXT,                          -- «букв.» и т. п.
    gov       TEXT,
    labels    TEXT NOT NULL DEFAULT '[]');

CREATE TABLE subs(
    id         INTEGER PRIMARY KEY,
    example_id INTEGER NOT NULL REFERENCES examples(id),
    idx        INTEGER NOT NULL,
    letter     TEXT,                         -- «а», «б»
    ru         TEXT NOT NULL,
    ru_norm    TEXT NOT NULL,
    note       TEXT,
    gov        TEXT,
    labels     TEXT NOT NULL DEFAULT '[]');

CREATE TABLE cross_refs(
    id               INTEGER PRIMARY KEY,
    from_lemma_id    INTEGER NOT NULL REFERENCES lemmas(id),
    rel              TEXT NOT NULL,          -- «понуд. от», «см.», «прил. к» …
    to_headword      TEXT NOT NULL,
    to_headword_norm TEXT NOT NULL,
    to_lemma_id      INTEGER REFERENCES lemmas(id),
    to_homonyms      TEXT NOT NULL DEFAULT '[]');

-- Обратный индекс рус->чеч. Висит на ГЛОССЕ, а не на статье: выдача может
-- показать, какое именно значение совпало. `src` задаёт вес при ранжировании.
CREATE TABLE ru_index(
    id        INTEGER PRIMARY KEY,
    word      TEXT    NOT NULL,              -- нормализованное слово
    stem      TEXT    NOT NULL,              -- Snowball
    lemma_id  INTEGER NOT NULL REFERENCES lemmas(id),
    src       INTEGER NOT NULL,              -- 0 глосс, 1 пример, 2 идиома, 3 по отсылке
    target_id INTEGER);                      -- glosses.id либо examples.id
"""

INDEXES = """
CREATE INDEX ix_lemmas_norm    ON lemmas(headword_norm);
CREATE INDEX ix_lemmas_fold    ON lemmas(headword_fold);
CREATE INDEX ix_lemmas_order   ON lemmas(ordering);
CREATE INDEX ix_wf_norm        ON word_forms(form_norm);
CREATE INDEX ix_wf_fold        ON word_forms(form_fold);
CREATE INDEX ix_wf_lemma       ON word_forms(lemma_id);
CREATE INDEX ix_senses_lemma   ON senses(lemma_id);
CREATE INDEX ix_glosses_sense  ON glosses(sense_id);
CREATE INDEX ix_glosses_lemma  ON glosses(lemma_id);
CREATE INDEX ix_glosses_norm   ON glosses(ru_norm);
CREATE INDEX ix_examples_lemma ON examples(lemma_id);
CREATE INDEX ix_examples_sense ON examples(sense_id);
CREATE INDEX ix_examples_norm  ON examples(ce_norm);
CREATE INDEX ix_subs_example   ON subs(example_id);
CREATE INDEX ix_blocks_lemma   ON blocks(lemma_id);
CREATE INDEX ix_lclass_lemma   ON lemma_class(lemma_id);
CREATE INDEX ix_xref_from      ON cross_refs(from_lemma_id);
CREATE INDEX ix_xref_norm      ON cross_refs(to_headword_norm);
CREATE INDEX ix_ru_word        ON ru_index(word);
CREATE INDEX ix_ru_stem        ON ru_index(stem);
CREATE INDEX ix_ru_lemma       ON ru_index(lemma_id);
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


# --------------------------------------------------------------------------
# 4. Классные показатели
# --------------------------------------------------------------------------

CLASS_MARKERS = ('в', 'й', 'д', 'б')
CE_VOWELS = frozenset('аеиоуыэюяё')


def exponent_candidates(norm):
    """Позиции, где может стоять классный показатель: в/й/д/б перед гласной.

    `й` в составе долгих `ий`/`уьй`/`юьй` показателем не бывает (п. 25 промпта),
    поэтому после и/ь/у/ю/ы он не рассматривается — иначе `куьйган` дал бы
    несуществующие `куьвган`, `куьдган`.
    """
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
    """Позиция, подтверждённая самим словарём.

    П. 23 промпта: «формы с другими показателями стоят на своих местах по
    алфавиту со ссылкой» — `диса*` -> есть также `биса`, `виса`, `йиса`.
    Значит для большинства статей позицию не надо угадывать: достаточно
    посмотреть, замена в какой позиции даёт существующие заглавные слова.
    Возвращает (позиция, сколько вариантов нашлось).
    """
    best, best_hits = None, 0
    for i in exponent_candidates(norm):
        hits = sum(1 for c in CLASS_MARKERS
                   if c != norm[i] and (norm[:i] + c + norm[i + 1:]) in headwords)
        if hits > best_hits:
            best, best_hits = i, hits
    return best, best_hits


def exponent_positional(norm):
    """Запасное правило для составных глаголов, чьи варианты словарь не выносил
    отдельными статьями (`бакъдан` -> `бакъван` в книге нет): показатель — это
    последний в/й/д/б перед гласной, т. е. начало последней глагольной морфемы.
    """
    cands = exponent_candidates(norm)
    return cands[-1] if cands else None


# Цель отсылки часто напечатана вместе с номером омонима и обрывком дефиса:
# `ба̃н1-`, `а̃кха1`, `ва̃н1־` (последний — вообще не дефис, а U+05BE из OCR).
# Пускать такую строку в normalize_ce нельзя: там '1' — это способ набрать
# палочку с обычной клавиатуры, и цель превращается в несуществующее `банӀ`.
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

_WORD_RE = re.compile(r'[а-яё]+')


def J(x):
    return json.dumps(x if x is not None else [], ensure_ascii=False)


def ce_field(v):
    """{t, long, raw} или голая строка -> (со знаками, norm, fold)."""
    if isinstance(v, dict):
        marked = restore_long(v.get('t') or '', v.get('long') or [])
    else:
        marked = v or ''
    return marked, normalize_ce(marked), fuzzy_ce(marked)


def index_words(text_norm):
    """Слова глосса для обратного индекса.

    Служебные выбрасываются, но только если в глоссе остаётся что-то ещё:
    у союза `а` перевод — ровно «и», и выбросить его значило бы сделать статью
    ненаходимой по-русски.
    """
    words = _WORD_RE.findall(text_norm or '')
    significant = [w for w in words if len(w) > 1 and w not in RU_STOPWORDS]
    return significant or [w for w in words if w]


def load_entries(path):
    """Читает maciev.jsonl, разводит совпавшие id (`атлас` -> `атлас#2`)."""
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


def build(jsonl_path, db_path, class_forms='safe', want_fts=True):
    entries = load_entries(jsonl_path)
    log = []

    if os.path.exists(db_path):
        os.remove(db_path)
    db = sqlite3.connect(db_path)
    db.executescript(SCHEMA)

    # ---- справочники ----------------------------------------------------
    pos_id = {}
    for i, (code, name) in enumerate(POS, 1):
        db.execute('INSERT INTO pos(id,code,name_ru,ordering) VALUES(?,?,?,?)',
                   (i, code, name, i))
        pos_id[code] = i
    case_id = {}
    for i, (code, name, abbr) in enumerate(CASES, 1):
        db.execute('INSERT INTO case_type(id,code,name_ru,abbr_ru,ordering) VALUES(?,?,?,?,?)',
                   (i, code, name, abbr, i))
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

    label_uses = defaultdict(int)

    def note_labels(*groups):
        for g in groups:
            for code in g or []:
                label_uses[code] += 1

    # ---- проход 1: леммы ------------------------------------------------
    lemma_id_of_slug = {}
    by_norm = defaultdict(list)          # headword_norm -> [(lemma_id, homonym)]
    headword_norms = set()
    for i, d in enumerate(entries, 1):
        hw, hw_norm, hw_fold = ce_field(d.get('headword'))
        lemma_id_of_slug[d['_slug']] = i
        by_norm[hw_norm].append((i, d.get('homonym')))
        headword_norms.add(hw_norm)
        note_labels(d.get('labels'))
        db.execute(
            'INSERT INTO lemmas(id,slug,headword,headword_norm,headword_fold,homonym,'
            'pos_id,class_star,pluralia_tantum,cls_sg,cls_pl,obj_num,subj_num,labels,'
            'flags,ordering) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
            (i, d['_slug'], hw, hw_norm, hw_fold, d.get('homonym'),
             pos_ref(d.get('pos')), int(bool(d.get('class_star'))),
             int(bool(d.get('plurale_tantum'))), J(d.get('cls_sg')), J(d.get('cls_pl')),
             d.get('obj_num'), d.get('subj_num'), J(d.get('labels')), J(d.get('flags')), i))
        for k, code in enumerate(d.get('pos') or []):
            db.execute('INSERT OR IGNORE INTO lemma_pos(lemma_id,pos_id,ordering) VALUES(?,?,?)',
                       (i, pos_ref([code]), k))
        for number, key in (('sg', 'cls_sg'), ('pl', 'cls_pl')):
            for k, marker in enumerate(d.get(key) or []):
                db.execute('INSERT INTO lemma_class(lemma_id,marker,number,ordering)'
                           ' VALUES(?,?,?,?)', (i, marker, number, k))

    # ---- проход 2: всё остальное ---------------------------------------
    ids = {'form': 0, 'block': 0, 'sense': 0, 'gloss': 0, 'example': 0, 'sub': 0, 'xref': 0}
    forms_seen = set()                   # (lemma_id, form_norm, kind) — без дублей
    ru_rows = []                         # (word, stem, lemma_id, src, target_id)
    ru_seen = set()
    glosses_of_lemma = defaultdict(list)  # lemma_id -> [(gloss_id, ru_norm)]

    def add_ru(text_norm, lemma, src, target):
        for w in index_words(text_norm):
            key = (w, lemma, src, target)
            if key in ru_seen:
                continue
            ru_seen.add(key)
            ru_rows.append((w, ru_stem(w), lemma, src, target))

    def add_form(lemma, obj, kind, sense=None, order=0, source='dict'):
        form, norm, fold = ce_field(obj.get('form') if isinstance(obj, dict) else obj)
        if not norm:
            return
        key = (lemma, norm, kind)
        if key in forms_seen:
            return
        forms_seen.add(key)
        ids['form'] += 1
        meta = obj if isinstance(obj, dict) else {}
        db.execute(
            'INSERT INTO word_forms(id,lemma_id,sense_id,form,form_norm,form_fold,kind,'
            'is_headword,case_id,number_id,tam_id,cls,star,source,ordering)'
            ' VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
            (ids['form'], lemma, sense, form, norm, fold, kind,
             int(kind == 'headword'), case_id.get(meta.get('case')),
             num_id.get(meta.get('num')), tam_id.get(meta.get('vform')),
             J(meta.get('cls')), int(bool(meta.get('star'))), source, order))

    def add_examples(lemma, sense, items, is_idiom):
        for k, ex in enumerate(items or []):
            ce, ce_norm, ce_fold = ce_field(ex.get('ce'))
            ru = ex.get('ru')
            ids['example'] += 1
            eid = ids['example']
            note_labels(ex.get('labels'))
            db.execute(
                'INSERT INTO examples(id,lemma_id,sense_id,is_idiom,idx,ce,ce_norm,ce_fold,'
                'ru,ru_norm,kind,note,note_kind,gov,labels)'
                ' VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                (eid, lemma, sense, int(is_idiom), k, ce, ce_norm, ce_fold,
                 ru, normalize_ru(ru) if ru else None, ex.get('kind'), ex.get('note'),
                 ex.get('note_kind'), ex.get('gov'), J(ex.get('labels'))))
            if ru:
                add_ru(normalize_ru(ru), lemma, 2 if is_idiom else 1, eid)
            for j, sub in enumerate(ex.get('subs') or []):
                sru = sub.get('ru') or ''
                ids['sub'] += 1
                note_labels(sub.get('labels'))
                db.execute(
                    'INSERT INTO subs(id,example_id,idx,letter,ru,ru_norm,note,gov,labels)'
                    ' VALUES(?,?,?,?,?,?,?,?,?)',
                    (ids['sub'], eid, j, sub.get('letter'), sru, normalize_ru(sru),
                     sub.get('note'), sub.get('gov'), J(sub.get('labels'))))
                add_ru(normalize_ru(sru), lemma, 2 if is_idiom else 1, eid)

    def add_senses(lemma, senses, block_id, block_n):
        for k, s in enumerate(senses or []):
            ids['sense'] += 1
            sid = ids['sense']
            note_labels(s.get('labels'))
            db.execute(
                'INSERT INTO senses(id,lemma_id,block_id,block_n,sense_no,ordering,pos_id,'
                'labels,cls_sg,cls_pl,pluralia_tantum) VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                (sid, lemma, block_id, block_n, s.get('n'), k, pos_ref(s.get('pos')),
                 J(s.get('labels')), J(s.get('cls_sg')), J(s.get('cls_pl')),
                 int(bool(s.get('plurale_tantum')))))
            for j, f in enumerate(s.get('forms') or []):
                add_form(lemma, f, 'paradigm', sense=sid, order=j)
            for j, g in enumerate(s.get('glosses') or []):
                ru = g.get('ru') or ''
                ru_norm = normalize_ru(ru)
                ids['gloss'] += 1
                gid = ids['gloss']
                note_labels(g.get('labels'))
                db.execute(
                    'INSERT INTO glosses(id,sense_id,lemma_id,idx,ru,ru_norm,sep,note,gov,labels)'
                    ' VALUES(?,?,?,?,?,?,?,?,?,?)',
                    (gid, sid, lemma, j, ru, ru_norm, g.get('sep'), g.get('note'),
                     g.get('gov'), J(g.get('labels'))))
                glosses_of_lemma[lemma].append((gid, ru_norm))
                add_ru(ru_norm, lemma, 0, gid)
            add_examples(lemma, sid, s.get('examples'), False)

    for d in entries:
        lemma = lemma_id_of_slug[d['_slug']]
        hw, hw_norm, _ = ce_field(d.get('headword'))
        add_form(lemma, {'form': d.get('headword'), 'star': d.get('class_star')}, 'headword')
        for k, v in enumerate(d.get('variants') or []):
            add_form(lemma, v, 'variant', order=k)
        for k, f in enumerate(d.get('forms') or []):
            add_form(lemma, f, 'paradigm', order=k)

        for b in d.get('blocks') or []:
            ids['block'] += 1
            bid = ids['block']
            note_labels(b.get('labels'))
            db.execute('INSERT INTO blocks(id,lemma_id,n,pos_id,labels) VALUES(?,?,?,?,?)',
                       (bid, lemma, b.get('n'), pos_ref(b.get('pos')), J(b.get('labels'))))
            add_senses(lemma, b.get('senses'), bid, b.get('n'))
        add_senses(lemma, d.get('senses'), None, None)
        add_examples(lemma, None, d.get('idioms'), True)

        for x in d.get('xrefs') or []:
            tgt, _, _ = ce_field(x.get('target'))
            tgt, hom = clean_xref_target(tgt)
            homs = x.get('homonyms') or ([hom] if hom is not None else [])
            ids['xref'] += 1
            db.execute(
                'INSERT INTO cross_refs(id,from_lemma_id,rel,to_headword,to_headword_norm,'
                'to_homonyms) VALUES(?,?,?,?,?,?)',
                (ids['xref'], lemma, x.get('rel') or '', tgt, normalize_ce(tgt), J(homs)))

    for code, n in label_uses.items():
        db.execute('INSERT INTO labels(code,n_uses) VALUES(?,?)', (code, n))

    # ---- разрешение отсылок ---------------------------------------------
    def resolve(norm, homonyms):
        cands = by_norm.get(norm)
        if not cands:
            return None
        if homonyms:
            for lid, hom in cands:
                if hom == homonyms[0]:
                    return lid
        return cands[0][0]

    linked = 0
    for xid, norm, homs in db.execute(
            'SELECT id, to_headword_norm, to_homonyms FROM cross_refs').fetchall():
        lid = resolve(norm, json.loads(homs))
        if lid is not None:
            linked += 1
            db.execute('UPDATE cross_refs SET to_lemma_id=? WHERE id=?', (lid, xid))

    # ---- кто на кого ссылается ------------------------------------------
    ref_targets = defaultdict(list)
    for src_lemma, dst_lemma in db.execute(
            'SELECT from_lemma_id, to_lemma_id FROM cross_refs'
            ' WHERE to_lemma_id IS NOT NULL').fetchall():
        ref_targets[src_lemma].append(dst_lemma)

    # ---- генерация классных форм ----------------------------------------
    # Позиция классного показателя определяется четырьмя способами по убыванию
    # надёжности. Угадывать её целиком нельзя: старая база так и получила
    # `агадалав` — показатель приклеен в конец слова.
    gen_stats = {'entries': 0, 'forms': 0, 'confirmed': 0, 'single': 0,
                 'inherited': 0, 'guessed': 0, 'unknown': 0,
                 'rule_agrees': 0, 'rule_total': 0,
                 'single_agrees': 0, 'single_total': 0, 'samples': []}
    if class_forms != 'none':
        norm_of = dict(db.execute('SELECT id, headword_norm FROM lemmas').fetchall())
        starred = [r[0] for r in db.execute('SELECT id FROM lemmas WHERE class_star=1')]
        paradigm = defaultdict(list)
        for lid, fnorm in db.execute(
                "SELECT lemma_id, form_norm FROM word_forms WHERE kind='paradigm'"):
            paradigm[lid].append(fnorm)

        exponent, origin = {}, {}

        # (1) Позицию показывает сам словарь: `диса*` -> есть `биса`, `виса`, `йиса`.
        for lid in starred:
            pos_c, _ = exponent_confirmed(norm_of[lid], headword_norms)
            if pos_c is None:
                continue
            exponent[lid], origin[lid] = pos_c, 'confirmed'
            gen_stats['rule_total'] += 1
            if exponent_positional(norm_of[lid]) == pos_c:
                gen_stats['rule_agrees'] += 1
            cands = exponent_candidates(norm_of[lid])
            if len(cands) == 1:                       # проверка уровня (2) ниже
                gen_stats['single_total'] += 1
                if cands[0] == pos_c:
                    gen_stats['single_agrees'] += 1

        # (2) Единственный кандидат — это не догадка. Если в слове ровно одно
        # место, где в/й/д/б стоит перед гласной, показатель может быть только там.
        # Так закрывается больше половины статей со звёздочкой.
        for lid in starred:
            if lid in exponent:
                continue
            cands = exponent_candidates(norm_of[lid])
            if len(cands) == 1:
                exponent[lid], origin[lid] = cands[0], 'single'

        # (3) Наследование от базового глагола по отсылке. Понудительные и
        # потенциальные формы — это база плюс суффикс `-йта`/`-дала`, и показатель
        # сидит в базе, а не в суффиксе. Именно здесь запасное правило врёт
        # системно: у `хададала` оно берёт `д` из `-дала`, тогда как словарь
        # знает `хабадала` — то есть меняется `д` на второй позиции.
        for _ in range(6):
            grew = False
            for lid in starred:
                if lid in exponent:
                    continue
                for tgt in ref_targets.get(lid, ()):
                    e = exponent.get(tgt)
                    if e is None:
                        continue
                    base, here = norm_of[tgt], norm_of[lid]
                    if here.startswith(base[:e + 1]) and here[e] == base[e]:
                        exponent[lid], origin[lid] = e, 'inherited'
                        grew = True
                        break
            if not grew:
                break

        # (4) Запасное правило — только по явной просьбе (--class-forms all).
        for lid in starred:
            if lid in exponent:
                continue
            e = exponent_positional(norm_of[lid])
            if e is not None and class_forms == 'all':
                exponent[lid], origin[lid] = e, 'guessed'
            else:
                gen_stats['unknown'] += 1

        for lid in exponent:
            gen_stats[origin[lid]] += 1

        for lid, exp in exponent.items():
            norm = norm_of[lid]
            made = 0
            for marker in CLASS_MARKERS:
                if marker == norm[exp]:
                    continue
                variant = norm[:exp] + marker + norm[exp + 1:]
                # Вариант уже стоит отдельной статьёй — её парадигма
                # проиндексирована там, дублировать нечего.
                if variant in headword_norms:
                    continue
                add_form(lid, {'form': variant}, 'class', source='gen', order=exp)
                made += 1
                for fnorm in paradigm[lid]:
                    if exp < len(fnorm) and fnorm[exp] == norm[exp]:
                        add_form(lid, {'form': fnorm[:exp] + marker + fnorm[exp + 1:]},
                                 'class', source='gen', order=exp)
                        made += 1
            if made:
                gen_stats['entries'] += 1
                gen_stats['forms'] += made
                if len(gen_stats['samples']) < 6:
                    gen_stats['samples'].append((norm, exp, origin[lid], made))

    # ---- ru_index по отсылкам -------------------------------------------
    # 5 436 статей («понуд. от», «потенц. от», «см.») своих переводов не имеют
    # и по-русски иначе не находятся. Тянем глоссы цели, до двух шагов.
    propagated = 0
    for lemma in range(1, len(entries) + 1):
        if glosses_of_lemma.get(lemma):
            continue
        seen_hop, frontier = {lemma}, list(ref_targets.get(lemma, ()))
        for _ in range(2):
            nxt = []
            for tgt in frontier:
                if tgt in seen_hop:
                    continue
                seen_hop.add(tgt)
                if glosses_of_lemma.get(tgt):
                    for gid, ru_norm in glosses_of_lemma[tgt]:
                        before = len(ru_rows)
                        add_ru(ru_norm, lemma, 3, gid)
                        propagated += len(ru_rows) - before
                else:
                    nxt.extend(ref_targets.get(tgt, ()))
            if not nxt:
                break
            frontier = nxt

    db.executemany('INSERT INTO ru_index(word,stem,lemma_id,src,target_id)'
                   ' VALUES(?,?,?,?,?)', ru_rows)

    # ---- индексы, FTS, метаданные ---------------------------------------
    db.executescript(INDEXES)

    fts = False
    if want_fts:
        try:
            db.executescript(
                "CREATE VIRTUAL TABLE forms_trgm USING fts5("
                "  form_norm, content='word_forms', content_rowid='id',"
                "  tokenize='trigram');"
                "INSERT INTO forms_trgm(rowid, form_norm) SELECT id, form_norm FROM word_forms;")
            fts = True
        except sqlite3.OperationalError as e:
            log.append(f'FTS5 недоступна, forms_trgm не создана: {e}')

    counts = {t: db.execute(f'SELECT COUNT(*) FROM {t}').fetchone()[0] for t in (
        'lemmas', 'word_forms', 'senses', 'glosses', 'examples', 'subs',
        'cross_refs', 'ru_index', 'blocks', 'lemma_class', 'labels')}
    for k, v in [('schema_version', DB_USER_VERSION), ('source', os.path.basename(jsonl_path)),
                 ('normalizer', 'ChechenNormalizer.kt / FuzzyKey.kt / RuStem.kt'),
                 ('generated_class_forms', gen_stats['forms']), ('fts5', int(fts))]:
        db.execute('INSERT INTO meta(key,value) VALUES(?,?)', (k, str(v)))

    db.execute(f'PRAGMA user_version = {DB_USER_VERSION}')
    db.commit()
    db.execute('VACUUM')

    return db, counts, gen_stats, {'linked': linked, 'xrefs': ids['xref'],
                                   'propagated': propagated, 'fts': fts, 'log': log}


# --------------------------------------------------------------------------
# 6. Самопроверки. Гонятся на собранной базе — молчаливо кривая БД дороже
#    лишней минуты сборки.
# --------------------------------------------------------------------------

def self_checks(db):
    problems = []

    # 1. Нормализатор ведёт себя как ChechenNormalizer.selfTest()
    key = normalize_ce('мостагӀе')
    for variant in ('мoстагIе', 'мостаг1е', 'МOCТAГIЕ'):
        if normalize_ce(variant) != key:
            problems.append(f'normalize_ce: {variant!r} не сошёлся с {key!r}')
    if normalize_ce('йаха') == normalize_ce('иаха'):
        problems.append('normalize_ce: й и и слиплись — сломается классный показатель')
    if ru_stem('утомления') != ru_stem('утомление'):
        problems.append('ru_stem: словоформы одного слова дали разные основы')

    # 2. Палочка везде в каноническом U+04C0. Это та самая ошибка архивной сборки:
    #    'Ӏ'.lower() даёт U+04CF, и точный поиск промахивается на 18,5 % статей.
    for table, col in (('lemmas', 'headword_norm'), ('word_forms', 'form_norm'),
                       ('examples', 'ce_norm')):
        n = db.execute(f"SELECT COUNT(*) FROM {table}"
                       f" WHERE {col} LIKE '%' || char(1231) || '%'").fetchone()[0]
        if n:
            problems.append(f'{table}.{col}: {n} строк со строчной палочкой U+04CF')

    # 3. Каждая статья находится прямым поиском по собственному заголовку
    n = db.execute("""
        SELECT COUNT(*) FROM lemmas l WHERE NOT EXISTS(
            SELECT 1 FROM word_forms wf
            WHERE wf.lemma_id = l.id AND wf.form_norm = l.headword_norm)
    """).fetchone()[0]
    if n:
        problems.append(f'{n} статей не находятся по своему заголовку')

    # 4. Каждый глосс сделал свою статью находимой по-русски
    n = db.execute("""
        SELECT COUNT(*) FROM glosses g WHERE g.ru_norm <> '' AND NOT EXISTS(
            SELECT 1 FROM ru_index r WHERE r.lemma_id = g.lemma_id)
    """).fetchone()[0]
    if n:
        problems.append(f'{n} глоссов не попали в ru_index')

    # 5. Статьи без собственных значений должны находиться по отсылке
    orphan = db.execute("""
        SELECT COUNT(*) FROM lemmas l
        WHERE NOT EXISTS(SELECT 1 FROM glosses g WHERE g.lemma_id = l.id)
          AND NOT EXISTS(SELECT 1 FROM ru_index r WHERE r.lemma_id = l.id)
    """).fetchone()[0]
    return problems, orphan


def probe(db, word):
    """Что отдаст обратный поиск на это русское слово."""
    rows = db.execute("""
        SELECT l.headword, l.homonym, g.ru, r.src
        FROM ru_index r
        JOIN lemmas l ON l.id = r.lemma_id
        LEFT JOIN glosses g ON g.id = r.target_id AND r.src IN (0,3)
        WHERE r.word = ?
        ORDER BY r.src, l.ordering
        LIMIT 6
    """, (normalize_ru(word),)).fetchall()
    return rows


def probe_phrase(db, phrase):
    """Точное совпадение фразы целиком — верхняя строка ранжирования."""
    return db.execute("""
        SELECT l.headword, g.ru FROM glosses g
        JOIN lemmas l ON l.id = g.lemma_id
        WHERE g.ru_norm = ? LIMIT 5
    """, (normalize_ru(phrase),)).fetchall()


# --------------------------------------------------------------------------
# 7. Точка входа
# --------------------------------------------------------------------------

def main(argv=None):
    ap = argparse.ArgumentParser(description='maciev.jsonl -> dict.db для приложения')
    ap.add_argument('jsonl', help='путь к work/maciev.jsonl')
    ap.add_argument('db', nargs='?', default='app/src/main/assets/dict.db')
    ap.add_argument('--class-forms', choices=('none', 'safe', 'all'), default='safe',
                    help='формы с другими классными показателями: none — не '
                         'генерировать; safe (по умолчанию) — только там, где '
                         'позиция показателя подтверждена словарём или '
                         'унаследована от базового глагола; all — плюс запасное '
                         'правило, которое системно врёт на -дала/-дайта')
    ap.add_argument('--no-fts', action='store_true', help='не создавать forms_trgm')
    ap.add_argument('--verify', action='store_true',
                    help='сверить порт Snowball с пакетом snowballstemmer на всём словаре')
    args = ap.parse_args(argv)

    db, counts, gen, info = build(args.jsonl, args.db,
                                  class_forms=args.class_forms,
                                  want_fts=not args.no_fts)

    print(f'\n{args.db}  (PRAGMA user_version = {DB_USER_VERSION})')
    print(f'  размер {os.path.getsize(args.db) / 1e6:.1f} МБ\n')
    for k, v in counts.items():
        print(f'  {k:<14}{v:>8}')

    print(f'\n  отсылок разрешено   {info["linked"]} из {info["xrefs"]}'
          f' ({100 * info["linked"] / max(1, info["xrefs"]):.1f} %)')
    print(f'  ru_index по отсылкам{info["propagated"]:>8}  (статьи без своих переводов)')
    print(f'  forms_trgm (FTS5)   {"есть" if info["fts"] else "нет"}')
    for line in info['log']:
        print(f'  ! {line}')

    if gen['forms']:
        def pct(a, b):
            return f'{100 * a / b:.1f} %' if b else '—'
        print(f"\n  классные формы: {gen['forms']} на {gen['entries']} статей")
        print(f"    позиция подтверждена словарём:     {gen['confirmed']}")
        print(f"    единственно возможная в слове:     {gen['single']}")
        print(f"    унаследована от базового глагола:  {gen['inherited']}")
        print(f"    взята запасным правилом:           {gen['guessed']}")
        print(f"    осталась неизвестной, не трогаем:  {gen['unknown']}")
        print(f"    на подтверждённых словарём позициях сошлись:")
        print(f"      единственный кандидат  {gen['single_agrees']}/{gen['single_total']}"
              f"  ({pct(gen['single_agrees'], gen['single_total'])})")
        print(f"      запасное правило       {gen['rule_agrees']}/{gen['rule_total']}"
              f"  ({pct(gen['rule_agrees'], gen['rule_total'])})")
        for norm, exp, why, made in gen['samples']:
            print(f"    {norm}  «{norm[exp]}» на позиции {exp} ({why}), форм {made}")

    problems, orphan = self_checks(db)
    print(f'\n  статей без переводов и без отсылок: {orphan}')
    if problems:
        print('\n  ПРОБЛЕМЫ:')
        for p in problems:
            print(f'    ! {p}')
    else:
        print('  самопроверки пройдены')

    print('\n  контрольные запросы:')
    for w in ('утомление', 'палка', 'каждый', 'ошибки', 'поголовно'):
        rows = probe(db, w)
        shown = ', '.join(f'{hw}{"" if h is None else h}' for hw, h, _, _ in rows[:4])
        print(f'    «{w}» -> {len(rows)}: {shown or "—"}')
    for ph in ('каждый раз', 'находить ошибки'):
        rows = probe_phrase(db, ph)
        print(f'    фраза «{ph}» -> {[r[0] for r in rows] or "—"}')

    if args.verify:
        try:
            import snowballstemmer
        except ImportError:
            print('\n  --verify: snowballstemmer не установлен, сверка пропущена')
        else:
            st = snowballstemmer.stemmer('russian')
            vocab = set()
            for (t,) in db.execute('SELECT ru_norm FROM glosses'):
                vocab.update(_WORD_RE.findall(t or ''))
            for (t,) in db.execute('SELECT ru_norm FROM examples WHERE ru_norm IS NOT NULL'):
                vocab.update(_WORD_RE.findall(t or ''))
            bad = [w for w in vocab if ru_stem(w) != st.stemWord(w)]
            print(f'\n  --verify: сверено {len(vocab)} слов, расхождений {len(bad)}')
            for w in bad[:10]:
                print(f'    {w}: порт {ru_stem(w)!r}, эталон {st.stemWord(w)!r}')

    db.close()
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())

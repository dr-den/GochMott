# -*- coding: utf-8 -*-
"""
Карасаев А. Т., Мациев А. Г. Русско-чеченский словарь (1978) -> JSONL.

    python tools/parse_karasaev.py --odt work/karasaev1978.odt --out work/

Даёт `karasaev1978.jsonl` и `problems_karasaev.tsv`.

ЧЕМ ЭТА КНИГА ОТЛИЧАЕТСЯ ОТ ПРЕДЫДУЩИХ

Направление обратное: заголовок РУССКИЙ, перевод чеченский. Поэтому
`examples.ce` — чеченский перевод, `examples.ru` — русское сочетание; поля
названы по языку, а не по роли, и путать их нельзя.

Разметка живая и богаче, чем в малых словарях:

    жирный   заголовок и русские словосочетания
    курсив   ВЕСЬ аппарат: род, вид, управление, пометы, пояснения,
             и он же на чеченской стороне («цхьаъ» = «кто-л.»)
    светлый  чеченский перевод и структурные знаки

СЕМЬ МЕСТ, ГДЕ НУЖНА АККУРАТНОСТЬ

1. ТОЧКА ПОСЛЕ ПОМЕТЫ УЕЗЖАЕТ В СЛЕДУЮЩИЙ ПРОГОН: `[i]«ж мед»` `[.]«. ангина»`.
   Приняв её за начало перевода, получим «. ангина».

2. ЗАПЯТАЯ ВНУТРИ СЛОВОСОЧЕТАНИЯ НАБРАНА СВЕТЛЫМ: `[b]«он пое́хал»` `[.]«,»`
   `[b]« а я оста́лся »`. Это одно сочетание, а не два и не перевод.

3. ОКОНЧАНИЯ ПРИЛАГАТЕЛЬНОГО СТОЯТ В ОДНОМ ПРОГОНЕ С ПЕРЕВОДОМ:
   `«-ая, -ое анатомически»`. Это грамматика заголовка, а не часть перевода.

4. НОМЕР ОМОНИМА — ЦИФРА ВНУТРИ ЖИРНОГО ЗАГОЛОВКА: «а 1», «а 2».

5. ЗНАЧЕНИЯ НУМЕРУЮТСЯ СВЕТЛЫМ `1)`, А ГРАММАТИЧЕСКИЕ БЛОКИ — ЖИРНЫМ `1.`
   Разные вещи, и разделять их нужно по начертанию, а не по виду скобки.

6. `||` ДЕЛИТ ЗАГОЛОВОК НА ОСНОВУ И ОКОНЧАНИЕ, `~` замещает основу. Пробел
   вокруг тильды значащий: «~ый учёный» -> «авторитетный учёный».

7. ЗА «◊» ИДУТ ИДИОМЫ — они относятся к статье целиком, а не к значению.
   Их 2 434.

ЧТО ОСТАЁТСЯ ЧЕЛОВЕКУ

Всё, что парсер не смог разложить уверенно, попадает в `problems_karasaev.tsv`
с номером абзаца. Это и есть тот остаток, ради которого стоит звать модель:
вложенные скобки, разорванные статьи, редкие конструкции.
"""
import argparse, itertools, json, os, re, sys, unicodedata as ud
import zipfile
from collections import Counter, defaultdict
from xml.etree import ElementTree as ET

T = '{urn:oasis:names:tc:opendocument:xmlns:text:1.0}'
S = '{urn:oasis:names:tc:opendocument:xmlns:style:1.0}'
PAL = 'Ӏ'
SOFT = '­'


# --------------------------------------------------------------------------
# 1. Чтение .odt (тот же слой, что в parse_small_dicts.py)
# --------------------------------------------------------------------------

def load_styles(path):
    z = zipfile.ZipFile(path)
    out = {}
    for name in ('styles.xml', 'content.xml'):
        try:
            f = z.open(name)
        except KeyError:
            continue
        for ev, el in ET.iterparse(f, events=('end',)):
            if el.tag == S + 'style' and el.get(S + 'family') == 'text':
                p = el.find(S + 'text-properties')
                d = {}
                if p is not None:
                    for k, v in p.attrib.items():
                        kk = k.split('}')[-1]
                        if kk in ('font-weight', 'font-style'):
                            d[kk] = v
                out[el.get(S + 'name')] = d
                el.clear()
            elif el.tag in (T + 'p', T + 'h') and name == 'content.xml':
                return out
    return out


def paragraphs(path):
    st = load_styles(path)

    def tagof(name):
        s = st.get(name, {})
        t = ('b' if s.get('font-weight') == 'bold' else '')
        t += ('i' if s.get('font-style') == 'italic' else '')
        return t or '.'

    z = zipfile.ZipFile(path)
    out, depth = [], 0
    with z.open('content.xml') as f:
        for ev, el in ET.iterparse(f, events=('start', 'end')):
            if ev == 'start' and el.tag in (T + 'p', T + 'h'):
                depth += 1
            elif ev == 'end' and el.tag in (T + 'p', T + 'h'):
                depth -= 1
                if depth:
                    continue
                parts = []

                def walk(e, cur='.'):
                    if e.text:
                        parts.append([cur, e.text])
                    for c in e:
                        if c.tag == T + 'span':
                            walk(c, tagof(c.get(T + 'style-name')))
                        elif c.tag == T + 's':
                            parts.append([cur, ' '])
                        elif c.tag == T + 'tab':
                            parts.append([cur, '\t'])
                        else:
                            walk(c, cur)
                        if c.tail:
                            parts.append([cur, c.tail])

                walk(el)
                merged = []
                for tg, tx in parts:
                    if merged and merged[-1][0] == tg:
                        merged[-1][1] += tx
                    else:
                        merged.append([tg, tx])
                out.append([m for m in merged if m[1]])
                el.clear()
    return out


# --------------------------------------------------------------------------
# 2. Текст
# --------------------------------------------------------------------------

_CYR = re.compile(r'[Ѐ-ӿ]')
_LAT = re.compile(r'[A-Za-z]')
_TOKEN = re.compile(r'[A-Za-zЀ-ӿ̀-ͯ]+')
_LATIN_PAL = {
    'I': PAL, 'І': PAL, 'l': PAL, 'ӏ': PAL, 'i': PAL, '|': PAL, 'і': PAL,
    'a': 'а', 'c': 'с', 'e': 'е', 'o': 'о', 'p': 'р', 'x': 'х', 'y': 'у', 'k': 'к',
    'A': 'А', 'B': 'В', 'C': 'С', 'E': 'Е', 'H': 'Н', 'K': 'К', 'M': 'М',
    'O': 'О', 'P': 'Р', 'T': 'Т', 'X': 'Х', 'Y': 'У',
}
_ONLY_CYR = set('бгджзилмнптфцчшщъыьэюяБГДЖЗИЛМНПФЦЧШЩЪЫЬЭЮЯ') | {PAL, 'ӏ'}
_ONLY_LAT = set('bdfghjlnqrstuvwzDFGIJLNQRSUVWZ') - set(_LATIN_PAL)


def _script_of(token):
    bare = ''.join(c for c in token if ud.category(c) != 'Mn')
    cyr_only = any(c in _ONLY_CYR for c in bare)
    lat_only = any(c in _ONLY_LAT for c in bare)
    if cyr_only and lat_only:
        return 'mixed'
    if cyr_only:
        return 'ce'
    if lat_only:
        return 'lat'
    n_cyr = sum(1 for c in bare if _CYR.match(c))
    n_lat = sum(1 for c in bare if _LAT.match(c))
    if n_cyr:
        return 'ce'
    # Слово ЦЕЛИКОМ из латинских двойников: `xIo`, `op`, `xIapa`. По буквам не
    # решается никак — но в этой книге настоящей латиницы практически нет
    # (542 знака на 3 млн), а таких слов 884. Значит это чеченские слова,
    # набранные не в той раскладке.
    return 'ce' if not any(c in _ONLY_LAT for c in bare) else 'lat'


def fix_homoglyphs(text):
    """Латинский двойник -> кириллица там, где слово опознано кириллическим.

    Цифры `1` в списке замен НЕТ намеренно: здесь это номер значения, а не
    способ набрать палочку.
    """
    if not text:
        return text

    def fix(m):
        tok = m.group(0)
        if _script_of(tok) != 'ce':
            return tok
        return ''.join(_LATIN_PAL.get(c, c) for c in tok)

    return _TOKEN.sub(fix, text)


def clean(text):
    """Мягкие переносы прочь, пробелы схлопнуть — но края НЕ обрезать:
    пробел между прогонами несёт границу слова."""
    if text is None:
        return None
    t = ud.normalize('NFC', text).replace(SOFT, '')
    # В книге два знака из Private Use Area (U+F019 в «да̃къа», U+F008 в «~а́»).
    # На экране это пустые квадраты, а в ключах — мусор; выбрасываем.
    t = ''.join(c for c in t if not (0xE000 <= ord(c) <= 0xF8FF))
    return re.sub(r'\s+', ' ', fix_homoglyphs(t))


# --------------------------------------------------------------------------
# 3. Аппарат словаря: что курсив означает
# --------------------------------------------------------------------------

# Род (п. 11) и число — разные вещи, и хранить их в одном поле нельзя:
# `гало́ш||и мн. (ед. ~а ж кало)` несёт число «мн» у заголовка и род «ж»
# у формы единственного числа.
GENDER = {'м', 'ж', 'с', 'м и ж'}
NUMBER = {'мн', 'ед'}

# П. 21: заголовок в этой книге РУССКИЙ, поэтому `предлог` остаётся предлогом.
# (В словарях с чеченским заголовком та же помета значит послелог.)
POS = {
    'нареч': 'нареч', 'союз': 'союз', 'частица': 'частица', 'предлог': 'предлог',
    'межд': 'межд', 'числ': 'числ', 'мест': 'мест', 'прил': 'прил',
    'сущ': 'сущ', 'гл': 'гл', 'вводн. сл': 'вводн_сл', 'предик': 'предик',
}
ASPECT = {'сов', 'несов', 'сов. и несов', 'многокр', 'однокр'}

# Помета — это СОКРАЩЕНИЕ, и в книге оно набрано с точкой. Отличить помету от
# чеченского слова в курсиве по длине нельзя: `тера` и `мед.` одинаково
# коротки, и статья `анало́гия` теряла перевод «цхьаннах тера», потому что оба
# слова уходили в пометы. Поэтому словарь помет собирается предварительным
# проходом по самой книге: берём то, что хотя бы трижды напечатано с точкой.
# Получается 180 помет — ровно список сокращений из предисловия.
LABEL_VOCAB = set()


# «мас.» (масала) и «напр.» — это «например»: за ними идёт иллюстрация,
# и в список помет статьи им попадать незачем.
NOT_A_LABEL = {'мас', 'напр', 'см', 'тж', 'и т. д', 'ср'}


def build_label_vocab(paras, lo, hi):
    seen = Counter()
    for i in range(lo, hi):
        for tag, tx in paras[i]:
            if tag not in ('i', 'bi'):
                continue
            for chunk in re.split(r'[,;]\s*', clean(tx).strip()):
                for w in chunk.split():
                    if re.fullmatch(r'[а-яё.-]{2,14}\.', w):
                        seen[w.rstrip('.')] += 1
    LABEL_VOCAB.clear()
    LABEL_VOCAB.update(k for k, n in seen.items()
                       if n >= 3 and k not in NOT_A_LABEL)
    return LABEL_VOCAB

# Составные пометы. Ищем их ГДЕ УГОДНО в прогоне, а не только в начале:
# «м в разн. знач.» — это род плюс помета, а при разборе по словам от пометы
# оставались обрывки «разн.», «знач.» и мусорное пояснение «в» (826 штук).
MULTIWORD = ('сов. и несов', 'в разн. знач', 'в знач. сущ', 'в знач. сказ',
             'и т. д', 'по гл', 'род. мн', 'наст. от', 'буд. от', 'прош. от',
             'м и ж', 'тк. кратк. ф', 'тк. мн', 'тк. ед', 'в сочет')

# Предлог, которым управляет глагол, стоит ОТДЕЛЬНЫМ словом перед падежным
# вопросом: «к кому-л.», «на кого-что». Если не склеить их обратно, `к`
# останется одиноким огрызком и уедет в перевод — так статья `антипатия`
# получала чеченский перевод «к».
PREP = ('между', 'перед', 'через', 'около', 'после', 'из-за', 'при', 'над',
        'под', 'про', 'без', 'для', 'до', 'по', 'об', 'от', 'со', 'за', 'на',
        'из', 'ко', 'во', 'у', 'о', 'к', 'с', 'в')
# Полная парадигма «чей» тоже управление (п. 17): «~ до́ступ к чьему-л.
# се́рдцу». Без неё «чьему-л» уходило в чеченский текст и рвало идиому надвое.
GOV_WORD = (r'(?:чьего|чьему|чьими|чьим|чьей|чьею|чью|чьих|чьём|чьи'
            r'|кого|что|чего|кому|чему|кем|чем|ком|чём)'
            r'(?:-(?:что|чего|чему|чем|чём|кто|кого|л)|\s+либо)?')
GOV = re.compile(r'^(?:(?:%s)\s+)?%s\b' % ('|'.join(PREP), GOV_WORD))

# П. 28: `см. тж.` — самостоятельная отсылка, а не `см.` со случайной пометой.
# П. 13 и 12: `уменьш. от`, `женск. к` — тоже отсылки, и называть их надо
# полностью, иначе в карточке останется голое «к».
REL = re.compile(
    r'(?:^|\s)('
    r'см\.?\s*тж|женск\.?\s*к|уменьш\.?\s*(?:к|от)|многокр\.?\s*к|однокр\.?\s*к'
    r'|см|по гл|от|ср|к'
    r')\s*\.?\s*$')


def rel_name(raw):
    r = re.sub(r'\s+', ' ', raw.strip().rstrip('.')).lower()
    return {'см': 'см.', 'см. тж': 'см. тж.', 'по гл': 'по гл.', 'ср': 'ср.',
            'женск. к': 'женск. к', 'уменьш. к': 'уменьш. к',
            'уменьш. от': 'уменьш. от', 'многокр. к': 'многокр. к',
            'однокр. к': 'однокр. к', 'от': 'от', 'к': 'к'}.get(r, r)


MULTI_RE = re.compile('(' + '|'.join(
    re.escape(m) for m in sorted(MULTIWORD, key=len, reverse=True)) + ')', re.I)

GOV_PREFIX = re.compile(r'^\s*((?:(?:%s)\s+)?%s\.?)\s+' % ('|'.join(PREP), GOV_WORD))

# П. 21: «предлог с род. п.» — это управление предлога, а НЕ средний род.
# Односимвольное «с» иначе попадает в GENDER, и все предлоги в книге
# получали `gender: с`, а «п.» оставалась мусором в пояснении.
CASE_GOV = re.compile(r'\bс\s+(род|дат|вин|твор|тв|предл|пр|мест)\.?(?:\s*п\.?)?')


def _join_preps(pieces):
    """«к» + «кому-л» -> «к кому-л»."""
    out = []
    i = 0
    while i < len(pieces):
        p = pieces[i]
        if (p.lower() in PREP and i + 1 < len(pieces)
                and GOV.match(pieces[i + 1].lower())):
            out.append(p + ' ' + pieces[i + 1])
            i += 2
            continue
        out.append(p)
        i += 1
    return out


def split_apparatus(text):
    """Курсивный прогон -> (грамматика, пометы, управление, отсылка, остаток)."""
    raw = (text or '').strip()
    rel = None
    m = REL.search(raw.rstrip(' .'))
    if m:
        rel = rel_name(m.group(1))
        raw = raw[:m.start()].strip()

    gram, labels, gov, rest = {}, [], None, []
    mc = CASE_GOV.search(raw)
    if mc:
        gov = mc.group(0).strip(' .')
        raw = (raw[:mc.start()] + ' ' + raw[mc.end():]).strip()
    # Управлений подряд бывает несколько: «дать ~ кому-л. для чего либо».
    # Снимаем все, иначе второе уедет в чеченский перевод.
    while True:
        mg = GOV_PREFIX.match(raw)
        if not mg:
            break
        piece = mg.group(1).strip(' .')
        gov = piece if gov is None else gov + ' ' + piece
        raw = raw[mg.end():]
    if gov and raw.strip():
        return gram, labels, gov, rel, raw.strip()

    pieces, multi_set = [], set()
    for chunk in re.split(r'[,;]\s*', raw.strip(' .')):
        chunk = chunk.strip(' .')
        if not chunk:
            continue
        for k, part in enumerate(MULTI_RE.split(chunk)):
            if k % 2 == 1:
                pieces.append(part)
                multi_set.add(part.strip(' .').lower())
            else:
                pieces.extend(w for w in part.split() if w.strip(' .'))
    for p in _join_preps(pieces):
        p = p.strip(' .')
        if not p:
            continue
        low = p.lower()
        if low in GENDER and 'gender' not in gram:
            gram['gender'] = low
        elif low in NUMBER and 'number' not in gram:
            gram['number'] = low
        elif low in ASPECT and 'aspect' not in gram:
            gram['aspect'] = low
        elif low in POS and 'pos' not in gram:
            gram['pos'] = POS[low]
        elif GOV.match(low):
            gov = p
        elif (low in multi_set
              or (low.rstrip('.') in LABEL_VOCAB if LABEL_VOCAB
                  else 2 < len(p) <= 12 and re.fullmatch(r'[а-яё .-]+', low))):
            labels.append(p if p.endswith('.') else p + '.')
        elif len(low.strip('.-')) < 3 or low.rstrip('.') in NOT_A_LABEL:
            continue          # обрывок разорванной пометы, а не пояснение
        else:
            rest.append(p)
    return gram, labels, gov, rel, ' '.join(rest) or None


# --------------------------------------------------------------------------
# 4. Заголовок
# --------------------------------------------------------------------------

# П. 2: «коса́ 1», а также «выве́шивать1,2» — одна статья на двух омонимов.
_HOM = re.compile(r'\s*([1-9])(?:\s*,\s*[1-9])*\s*$')
_BLOCK_IN_HEAD = re.compile(r'\s+(\d{1,2})\.\s*$')   # п. 3: «бли́зко 1.»
_ENDINGS = re.compile(r'^\s*,?\s*((?:-[а-яё́̃]+)(?:\s*,\s*-[а-яё́̃]+)*)')
# По п. 14 окончания приводятся парой («-ая, -ое»). Одиночное «-м» после
# заголовка — это уже чеченский перевод частицы («-то частица 1) выдел. -м»),
# и глотать его как окончание нельзя.
_ADJ_END = {'ая', 'ое', 'ые', 'ие', 'ий', 'ый', 'ой', 'ья', 'ье', 'ьи', 'яя', 'ее'}


def looks_like_endings(text):
    items = [x.strip(' -') for x in text.split(',') if x.strip(' -')]
    if len(items) > 1:
        return True
    return bool(items) and bare(items[0]) in _ADJ_END
_WORD = re.compile(r'^[а-яёА-ЯЁ́̃~|-]+$')
_WORDS = re.compile(r'^[а-яёА-ЯЁ́̃~|!-]+(?: [а-яёА-ЯЁ́̃~|!-]+){0,2}$')


def bare(s):
    """Слово без ударения и знаков долготы — для сравнений."""
    return re.sub(r'[̀-ͯ]', '', s or '').lower()


def split_head(text):
    """«авторите́тн||ый 1» -> заголовок, основа, омонимы, блок, хвост, варианты.

    Разбираем в том же порядке, в каком автор их ставит:
    двоеточие (п. 9) -> номер блока (п. 3) -> номер омонима (п. 2) ->
    `||` (п. 27) -> перечисление через запятую (п. 25).
    """
    t = (text or '').strip()
    tail = None
    if ':' in t:
        t, tail = t.split(':', 1)
        t, tail = t.strip(), tail.strip()

    block = None
    m = _BLOCK_IN_HEAD.search(t)
    if m:
        block, t = int(m.group(1)), t[:m.start()].strip()

    hom = None
    m = _HOM.search(t)
    if m:
        hom, t = int(m.group(1)), t[:m.start()].strip()

    # П. 25: «сна, сну», «ау́кать, ау́каться» — один заголовок и варианты,
    # а не заголовок с запятой внутри.
    extra = []
    if ',' in t:
        parts = [p.strip() for p in t.split(',')]
        if _WORD.match(parts[0] or '') and all(_WORDS.match(p) for p in parts if p):
            t, extra = parts[0], [p for p in parts[1:] if p]

    stem = None
    if '||' in t:
        a, b = t.split('||', 1)
        stem, t = a.strip(), (a + b).replace('||', '').strip()
    # Тильду в перечислении разворачивать можно только теперь, когда известна
    # основа: «где́||-либо, ~нибудь» -> «гдéнибудь», а не «~нибудь».
    extra = [expand_form(x, t, stem) if x != '~' else '~' for x in extra]
    return t, stem, hom, block, tail, extra


def expand_tilde(text, full, stem):
    if '~' not in (text or ''):
        return text
    return text.replace('~', stem or full)


def expand_form(text, full, stem):
    """Тильда в ФОРМЕ: «~ ец» -> «абазинец». Пробел внутри одного слова
    появляется только из-за того, что окончание набрано отдельным прогоном."""
    return re.sub(r'\s+', '', expand_tilde(text, full, stem) or '')


# --------------------------------------------------------------------------
# 5. Скобки: что внутри них может стоять
# --------------------------------------------------------------------------

_PRON = re.compile(r'(?<!\S)\[[^\]\s]*-[^\]\s]*\]')   # п. 32: «[-э-]», «[сон-]»


CONNECTOR = {'и', 'или', 'а', 'тж', 'тж.'}

_BRACKET = re.compile(r'\[([^\]]*)\]')


def bracket_variants(text, problems=None, src=None, who=''):
    """П. 8: квадратные скобки держат необязательную часть перевода, и это
    не одна строка, а несколько САМОСТОЯТЕЛЬНЫХ переводов.

        [дӀаса]лело              -> «лело», «дӀасалело»
        тӀаьхьа [пондар] лакха   -> «тӀаьхьа лакха», «тӀаьхьа пондар лакха»
        йисар[хо]                -> «йисар», «йисархо»

    Если внутри скобки стоит «или», голого варианта нет — есть выбор из
    перечисленного: «[совгӀатна или загӀанна] дала» это «совгӀатна дала» и
    «загӀанна дала», но не «дала». Таких скобок шесть, и все они попадают в
    problems: в двух из них «или» делит не всё содержимое, а только хвост
    («охьа ца хуу[ш ден или долу]»), и решать это должен человек.
    """
    m = _BRACKET.search(text or '')
    if not m:
        return [text]
    inner = m.group(1).strip()
    before, after = text[:m.start()], text[m.end():]
    if re.search(r'\bили\b', inner):
        alts = [a.strip() for a in re.split(r'\s+или\s+', inner) if a.strip()]
        heads = [before + a + after for a in alts]
        if problems is not None:
            problems.append((src, 'скобка с «или» — проверить глазами',
                             f'{who}: {text[:60]}'))
    else:
        heads = [before + after, before + inner + after]
    out = []
    for h in heads:
        out.extend(bracket_variants(h))
    seen, res = set(), []
    for v in out:
        v = tidy(v)
        if v and v not in seen:
            seen.add(v)
            res.append(v)
    return res

# «найму́(сь)», «выплёскивать(ся)» — возвратная пара в одной статье. Скобка
# держит окончание, которое приклеивается к слову перед ней. Проверено по
# книге: короткое содержимое круглых скобок у заголовка это `ся` (1622 раза)
# и `сь` (68); всё прочее короткое — настоящие пояснения («мех», «рыба»),
# поэтому список закрытый, а не «до четырёх букв».
REFLEX = {'ся', 'сь'}


def tidy(s):
    return re.sub(r'\s+', ' ', s or '').strip(' ,;')


def soft(s):
    """То же, но БЕЗ обрезки краёв: перевод ещё дописывается, и запятая на
    стыке кусков — это запятая внутри фразы («иза дӀавахара, со висира»),
    а не мусор."""
    return re.sub(r'\s+', ' ', s or '')


def _flat(items):
    return re.sub(r'\s+', ' ', ''.join(t for _, t in items)).strip(' ,;.')


def _prefix_chain(head, words):
    """П. 23: «местком» = «мест|ком» из «местный комитет». Каждый кусок
    заголовка должен быть началом очередного слова расшифровки."""
    h, i = bare(head).replace('-', ''), 0
    for w in words:
        w = bare(w)
        if not w or i >= len(h):
            break
        k = 0
        while k < len(w) and i + k < len(h) and w[k] == h[i + k]:
            k += 1
        if k == 0:
            return False
        i += k
    return i == len(h)


def _initials(head, words):
    return bare(head).replace('-', '') == ''.join(bare(w)[:1] for w in words)


def classify_paren(items, headword, stem, head_zone, numbered):
    """Что автор положил в круглые скобки.

    П. 7  «(или къега)»            -> второй перевод
    П. 11 «(ед. ~а ж кало)»        -> форма числа со своим переводом
    П. 2  «(просвещу́, просвети́шь)» -> падежные/личные формы заголовка
    П. 23 «(ме́стный комите́т…)»     -> расшифровка аббревиатуры
    иначе                          -> пояснение
    """
    flat = _flat(items)
    if not flat:
        return None, None

    if flat in REFLEX:
        return 'suffix', flat

    # П. 3: «прорыв 1. (по гл. прорва́ть) … 2. (по гл. прорва́ться)» — в скобках
    # стоит ОТСЫЛКА к глаголу, от которого образовано имя действия, а не
    # пояснение. Цели набраны жирным, помета — курсивом; 582 таких скобки.
    mr = re.match(r'^(по гл|см|ср)\b\.?\s*', flat)
    if mr and any(tag.startswith('b') for tag, _ in items):
        # `merge_runs` уже склеил соседние жирные через запятую
        # («находи́ть1, найти́1»), поэтому разбираем каждую цель отдельно.
        targets = [x.strip(' .,;')
                   for tag, t in items if tag.startswith('b')
                   for x in re.split(r'\s*,\s*', t)]
        targets = [t for t in targets if t]
        if targets:
            return 'ref', (rel_name(mr.group(1)), targets)

    if re.match(r'^или\b', flat, re.I):
        payload = flat.split(None, 1)[1] if ' ' in flat else ''
        # П. 7 работает на обе стороны. Сторону выдаёт начертание: жирным в
        # этой книге набрано русское, светлым и курсивом — чеченское.
        # «вручи́ть (или поднести́) ~ юбиля́ру» — замена в РУССКОМ сочетании.
        side = 'alt_ru' if any(tag == 'b' for tag, _ in items) else 'alt'
        return side, payload

    # П. 11. Ведущее «ед.»/«мн.» — верный признак: так автор вводит вторую
    # форму числа, а жирным внутри скобок идёт сама форма.
    m = re.match(r'^(ед|мн)\b\.?\s*', flat)
    if m and any(tag.startswith('b') for tag, _ in items):
        number = m.group(1)
        forms, cur, gender, ce = [], '', None, []
        for tag, tx in items:
            if tag.startswith('b'):
                if gender is not None or ce:
                    forms.append((cur, gender, ' '.join(ce).strip(' ,;')))
                    cur, gender, ce = '', None, []
                cur += tx
            elif tag == 'i':
                g = tx.strip(' .,')
                if g in GENDER:
                    gender = g
                elif g in CONNECTOR:
                    if cur:
                        forms.append((cur, gender, ' '.join(ce).strip(' ,;')))
                        cur, gender, ce = '', None, []
                elif cur:
                    ce.append(tx)
            else:
                w = tx.strip(' .,')
                if w in CONNECTOR:          # «(мн. ба́ре и ба́ры)» — союз
                    if cur:
                        forms.append((cur, gender, ' '.join(ce).strip(' ,;')))
                        cur, gender, ce = '', None, []
                elif cur and not re.fullmatch(r'[\s,;.]*', tx):
                    ce.append(tx)
        if cur:
            forms.append((cur, gender, ' '.join(ce).strip(' ,;')))
        out = []
        for f, g, c in forms:
            # «(ед. абха́з, абха́зец м …)» — внутри одной пометы рода может
            # стоять несколько форм через запятую. Перевод при этом общий,
            # поэтому вешаем его только на последнюю из группы.
            group = [x for x in (expand_form(one, headword, stem)
                                 for one in re.split(r'\s*,\s*', f)) if x]
            for k, form in enumerate(group):
                item = {'form': form, 'star': False,
                        'num': {'ед': 'sg', 'мн': 'pl'}[number]}
                if g:
                    item['gender'] = g
                if c and k == len(group) - 1:
                    item['ce'] = re.sub(r'\s+', ' ', c).strip()
                out.append(item)
        if out:
            return 'forms', out
        return 'note', flat

    return _classify_rest(items, flat, headword, head_zone, numbered)


def _classify_rest(items, flat, headword, head_zone, numbered):
    if head_zone and not numbered:
        words = [w for w in re.split(r'[\s,;]+', flat) if w]
        # П. 2: личные и падежные формы — те же слова, что заголовок.
        if len(words) > 1 and all(
                bare(w)[:3] and bare(w)[:3] == bare(headword)[:3] for w in words):
            return 'forms', [{'form': w, 'star': False} for w in words]
        # П. 23: расшифровка сокращения.
        if len(words) > 1 and (_prefix_chain(headword, words)
                               or _initials(headword, words)):
            return 'expansion', flat
    return 'note', flat


# --------------------------------------------------------------------------
# 6. Разбор тела статьи
# --------------------------------------------------------------------------

SENSE_NO = re.compile(r'(?:(?<=^)|(?<=[\s;.]))(\d{1,2})\)\s*')
LETTER_NO = re.compile(r'(?:(?<=^)|(?<=[\s;.]))([а-е])\)\s*')   # п. 4
BLOCK_NO = re.compile(r'^\s*(\d{1,2})\.\s*$')
XREF_SENSES = re.compile(r'^\s*(\d{1,2}(?:\s*,\s*\d{1,2})*)\s*[.;]?\s*$')
PUNCT_ONLY = re.compile(r'^[\s,;:.\-–—]*$')
COMMA_VARIANT = re.compile(r'^\s*,\s*([а-яё́̃-]+)\s*$')          # п. 25, «, сну»
RHOMB = '◊'
_HAS_LETTER = re.compile(r'[а-яёА-ЯЁӀ]')

# П. 24. Знак «=» отмечает, что статья описывает не слово, а ЧАСТЬ слова.
# Сторона знака говорит какую: «авиа=» — первая часть сложных, «=рублёвый» —
# вторая. Сам знак набран светлым и лежит рядом с жирным заголовком, поэтому
# без склейки заголовок выходит бессмысленным: «рублёвый» отдельным словом
# не существует, а 16 таких статей парсер вообще не признавал статьями.
_EQ = '='

# Тип морфемы автор называет по-чеченски, в скобках после заголовка.
# Порядок важен: «чолхе-дацдинчу» (сложносокращённые) надо узнать раньше,
# чем общее «хьалхара да̃къа» (первая часть).
MORPH_KIND = [
    (re.compile(r'чолхе-дацдинчу\s+дешнийн\s+(?:хьалхара|хаьлхара)'), 'abbr_first'),
    (re.compile(r'дешхьалхе'),                                          'prefix'),
    (re.compile(r'суффикс'),                                            'suffix'),
    (re.compile(r'шолг[Ӏl]а\s+да'),                                     'second'),
    (re.compile(r'(?:хьалхара|хаьлхара)\s+да'),                         'first'),
    (re.compile(r'б[Ӏl]останехьа\s+ма[ьи]?[Ӏl]на'),                     'antonym'),
    # «…маьӀна долуш» — «в значении…», без указания части слова
    (re.compile(r'ма[ьи]?[Ӏl]на\s+долуш'),                              'meaning'),
    # «…маьӀна долу чолхечу дешнийн да̃къа» — часть есть, а какая, не сказано
    (re.compile(r'чолхечу\s+дешнийн\s+да'),                             'part'),
]
MORPH_MEANS = re.compile(r'«([^»]{1,40})»')


def morph_of(note):
    """«сом» маьӀна долу чолхечу дешнийн шолгӀа дакъа -> вторая часть, «сом»."""
    if not note or 'ма' not in note:
        return None
    for rx, kind in MORPH_KIND:
        if rx.search(note):
            out = {'kind': kind}
            m = MORPH_MEANS.search(note)
            if m:
                out['means'] = m.group(1)
            return out
    return None


_LOST_BOLD = re.compile(r'^[а-яёА-ЯЁ]{1,2}$')


_ALT_SQUARE = re.compile(r'\[\s*или\s[^\]]*\]')


def square_alt_to_round(runs):
    """«бодане [или энжеде] стаг» — п. 7, набранный квадратными скобками.

    Скобка открывается в одном прогоне, а закрывается в другом
    (`[.]«бодане [»` `[i]«или »` `[.]«энжеде] стаг»`), поэтому заменять
    посимвольно внутри прогона нельзя — ищем по склеенному тексту и правим
    ровно два знака на их местах. В книге таких пять.
    """
    flat = ''.join(t for _, t in runs)
    pos = []
    for m in _ALT_SQUARE.finditer(flat):
        pos += [m.start(), m.end() - 1]
    if not pos:
        return runs
    out, off = [], 0
    for tag, tx in runs:
        chars = list(tx)
        for p in pos:
            if off <= p < off + len(chars):
                chars[p - off] = '(' if chars[p - off] == '[' else ')'
        out.append([tag, ''.join(chars)])
        off += len(tx)
    return out


def glue_lost_bold(runs):
    """У десятка статей первая буква заголовка набрана светлой:
    `[.]«г»` `[b]«иперто́ник»`. Абзац переставал быть статьёй целиком."""
    if (len(runs) > 1 and runs[0][0] == '.' and runs[1][0] == 'b'
            and _LOST_BOLD.match(runs[0][1])):
        runs[1][1] = runs[0][1] + runs[1][1]
        runs.pop(0)
    return runs


def glue_eq(runs):
    """Приклеить «=» к заголовку с той стороны, с какой он напечатан."""
    # Дефис перед заголовком — то же самое, что «=»: он говорит, что статья
    # описывает не самостоятельное слово, а то, что цепляется к слову справа
    # («-то частица…»). Набран он отдельным светлым прогоном, и без склейки
    # абзац вообще не признавался статьёй.
    if len(runs) > 1 and runs[0][0] == '.' and runs[0][1].strip() == '-':
        runs[1][1] = '-' + runs[1][1].lstrip()
        runs.pop(0)
    if len(runs) > 1 and runs[0][0] == '.' and runs[0][1].strip() == _EQ:
        runs[1][1] = _EQ + runs[1][1].lstrip()
        runs.pop(0)
    elif (len(runs) > 1 and runs[0][0] == 'b' and runs[1][0] == '.'
            and runs[1][1].lstrip().startswith(_EQ)):
        runs[0][1] = runs[0][1].rstrip() + _EQ
        runs[1][1] = runs[1][1].lstrip()[1:]
    out = []
    for tag, tx in runs:
        if not tx:
            continue
        if out and out[-1][0] == tag:
            out[-1][1] += tx
        else:
            out.append([tag, tx])
    return out


_ONLY_MARKS = re.compile(r'^[\u0300-\u036f]+$')


def glue_marks(runs):
    """Прогон из одних диакритик принадлежит предыдущему слову.

    `[b]«найти»` `[.]«́»` `[b]«2»` — ударение вынесено в отдельный светлый
    прогон, и заголовок разваливался: «найти» без ударения, а номер омонима
    становился русским словосочетанием «2».
    """
    out = []
    for tag, tx in runs:
        if out and tx and _ONLY_MARKS.match(tx):
            out[-1][1] += tx
            continue
        out.append([tag, tx])
    return out


def fold_bi(runs):
    """Полужирный курсив `bi` — не третье начертание, а продолжение соседа.

    Из 457 таких прогонов 388 пустые (типографский мусор между словами), а в
    остальных лежит хвост предыдущего прогона: `[i]«сов»` `[bi]«.»`,
    `[b]«~ »` `[bi]«ец»`. Приняв `bi` за отдельный вид, мы теряли окончание
    формы: «абази́н||цы (ед. ~ец м)» давало вариант «абазин» и глоссу «ец».
    """
    out, prev = [], '.'
    for tag, tx in runs:
        if tag == 'bi':
            tag = '.' if not tx.strip() else prev
        elif tx.strip():
            prev = tag
        out.append([tag, tx])
    return out


def merge_runs(runs):
    """Два вида типографского мусора, оба ломают разбор.

    Светлый прогон из одной пунктуации МЕЖДУ ЖИРНЫМИ — часть словосочетания:
    `[b]«он пое́хал»` `[.]«,»` `[b]« а я оста́лся »` это одно сочетание.

    Пустой ЖИРНЫЙ прогон между светлыми — наоборот, ничего не значит.

    ИСКЛЮЧЕНИЕ (п. 3): если следующий жирный прогон это НОМЕР БЛОКА, склеивать
    нельзя — иначе `[b]«бли́зко»` `[.]« »` `[b]«1.»` даст заголовок «бли́зко 1.»,
    и статья потеряет и заголовок, и структуру. Так пропало 197 статей.
    """
    runs = [list(r) for r in runs]
    # Прогон из ОДНИХ ПРОБЕЛОВ между двумя одинаковыми — типографский мусор,
    # а не смена начертания: `[.]«лоьро»` `[i]« »` `[.]«и»` это один
    # чеченский перевод «лоьро и могуш хилар сацийра», и если принять пустой
    # курсив всерьёз, перевод разваливается на три куска, из которых два
    # становятся отдельными «переводами» статьи.
    for i in range(1, len(runs) - 1):
        if runs[i][1].strip():
            continue
        left, right = runs[i - 1][0], runs[i + 1][0]
        if left == right and runs[i][0] != left:
            runs[i][0] = left
    for i, (tag, tx) in enumerate(runs):
        if tag != 'b' or not PUNCT_ONLY.match(tx):
            continue
        left = runs[i - 1][0] if i else '.'
        right = runs[i + 1][0] if i + 1 < len(runs) else '.'
        if left != 'b' or right != 'b':
            runs[i][0] = '.'
    out = []
    i = 0
    while i < len(runs):
        tag, tx = runs[i]
        if (out and tag == '.' and PUNCT_ONLY.match(tx)
                and out[-1][0] == 'b'
                and i + 1 < len(runs) and runs[i + 1][0] == 'b'
                and not BLOCK_NO.match(runs[i + 1][1])):
            out[-1][1] += tx
            i += 1
            continue
        if out and out[-1][0] == tag:
            out[-1][1] += tx
        else:
            out.append([tag, tx])
        i += 1
    return out


def _expand_ex(x, problems, idx, headword):
    """Скобки в переводе словосочетания -> дополнительные строки `subs`."""
    vs = bracket_variants(x.get('ce'), problems, idx, headword)
    if len(vs) > 1:
        x['ce'] = vs[0]
        subs = x.setdefault('subs', [])
        have = {s.get('text') for s in subs}
        for v in vs[1:]:
            if v not in have:
                subs.append({'letter': None, 'text': v, 'note': None,
                             'gov': None, 'labels': []})
    elif vs:
        x['ce'] = vs[0]
    for sub in x.get('subs') or []:
        sv = bracket_variants(sub.get('text'), None, idx, headword)
        if sv:
            sub['text'] = sv[0]


def parse_entry(runs, idx, problems):
    """Один абзац -> запись JSONL или None."""
    runs = glue_eq(glue_lost_bold(square_alt_to_round(
        merge_runs(fold_bi(glue_marks(
            [[t, clean(x)] for t, x in runs if x]))))))
    runs = [r for r in runs if r[1]]
    while runs and not runs[0][1].strip():
        runs.pop(0)
    if not runs or runs[0][0] != 'b':
        return None

    headword, stem, hom, block0, head_tail, head_extra = split_head(runs[0][1])
    if not headword:
        return None
    rest = runs[1:]

    variants = [{'form': v, 'star': False} for v in head_extra if v != '~']
    forms = []
    gram, labels, xrefs, idioms = {}, [], [], []
    blocks = {}          # n -> {'n', 'labels', 'senses'}
    senses = {}          # (block_n, sense_n) -> dict
    expansion = pron = None

    cur_block, cur_sense = block0, None
    in_idioms = False
    pending_ru = None
    pending_gov = pending_note = pending_rel = None
    pending_ce = None        # чеченский текст, набранный курсивом посреди перевода
    pending_variant = None   # п. 12: перевод после «~ца ж» относится к ней
    pending_alt = []         # п. 7 на русской стороне: (позиция, замена)
    pending_suffix = '~' in head_extra
    phrase_rel = [None]      # отсылка, стоящая ВНУТРИ словосочетания
    last_bold = [headword, False]   # к чему цеплять «(ся)»; второе — цель отсылки
    ce_open = [False]        # перевод сочетания ещё может продолжиться
    pending_alt_ce = []      # п. 7 в середине перевода: (позиция, замена)
    await_xref_senses = False
    paren = 0
    note_buf = []            # (tag, text) — структура внутри скобок нужна
    last_obj = [None, None]  # куда прицеплять «(или …)» и «а) б)»
    carry = ''

    def sense(bn, sn):
        key = (bn, sn)
        if key not in senses:
            senses[key] = {'n': sn, 'block': bn, 'pos': [], 'labels': [],
                           'glosses': [], 'examples': []}
            if bn is not None:
                blocks.setdefault(bn, {'n': bn, 'labels': [], 'senses': []})
        return senses[key]

    def has_content():
        return any(s['glosses'] or s['examples'] for s in senses.values()) \
            or bool(idioms)

    def head_zone():
        return not has_content() and pending_ru is None and not in_idioms

    def flush_ce():
        nonlocal pending_ce
        t, pending_ce = pending_ce, None
        return t

    def close_ce():
        """Перевод словосочетания дописан. Здесь же собирается вариант по п. 7:
        «кема долу (или лаьтта) меттиг» — скобка меняет слово В СЕРЕДИНЕ, и
        полный второй перевод («кема лаьтта меттиг») известен только сейчас."""
        if not ce_open[0]:
            return
        ce_open[0] = False
        obj, key = last_obj[0], last_obj[1]
        if obj is not None and obj.get(key):
            obj[key] = tidy(obj[key])
        if obj is not None and pending_alt_ce:
            words = (obj.get(key) or '').split()
            # По каждой скобке своя позиция и свой список замен. Комбинации
            # берём все: «нах (или адам) доцуш диса (или хила)» это и
            # «адам доцуш диса», и «нах доцуш хила», и «адам доцуш хила».
            options = [[(i, None)] + [(i, a) for a in alts]
                       for i, alts in pending_alt_ce]
            seen = set()
            for combo in itertools.product(*options):
                if all(a is None for _, a in combo):
                    continue
                w = list(words)
                for i, a in sorted(combo, key=lambda x: -x[0]):
                    if a is not None and i < len(w):
                        w[i:i + 1] = a.split()
                full = tidy(' '.join(w))
                if not full or full in seen:
                    continue
                seen.add(full)
                if len(seen) > 6:
                    break
                if key == 'ce' and 'ru' in obj:
                    obj.setdefault('subs', []).append(
                        {'letter': None, 'text': full, 'note': 'или',
                         'gov': None, 'labels': []})
                else:
                    sense(cur_block, cur_sense)['glosses'].append(
                        {'text': full, 'sep': 'или', 'labels': [],
                         'note': None, 'gov': None})
        pending_alt_ce.clear()

    def add_translation(text, sep_first=None):
        """Светлый (или курсивный чеченский) кусок -> переводы либо перевод
        словосочетания. П. 6: разделитель между синонимами значащий."""
        nonlocal pending_ru, pending_gov, pending_note, pending_variant
        lead = flush_ce()
        raw = (lead + ' ' if lead else '') + (text or '')
        # Продолжение уже начатого перевода: между кусками стоял пустой прогон
        # или скобка. Кончается он на «;», на новом жирном, на номере значения
        # и на «◊» — но не на границе прогона.
        if pending_ru is None and ce_open[0] and last_obj[0] is not None:
            # Перевод у синонима кончается на «,» или «;», у словосочетания —
            # только на «;»: запятая внутри чеченской фразы законна.
            key = last_obj[1]
            m = re.search(r'[;,]' if key == 'text' else r';', raw)
            head = raw[:m.start()] if m else raw
            if _HAS_LETTER.search(head):
                last_obj[0][key] = soft((last_obj[0][key] or '') + ' ' + head)
            if not m:
                return
            if key == 'text':
                sep_first = m.group(0)
            close_ce()
            raw = raw[m.end():]
        text = raw.strip(' ;,:!')
        if not text or not _HAS_LETTER.search(text):
            return
        s = sense(cur_block, cur_sense)
        if pending_ru is None:
            parts = re.split(r'\s*([,;])\s*', text)
            sep = sep_first
            for k, piece in enumerate(parts):
                if k % 2 == 1:
                    sep = piece
                    continue
                piece = piece.strip(' .')
                if not piece or not _HAS_LETTER.search(piece):
                    continue
                g = {'text': tidy(piece), 'sep': sep, 'labels': [],
                     'note': pending_note, 'gov': pending_gov}
                if pending_variant:
                    g['gram'] = {'of': pending_variant}
                s['glosses'].append(g)
                last_obj[:] = [g, 'text']
                ce_open[0] = not raw.rstrip().endswith((';', ','))
                pending_note = pending_gov = None
                sep = None
        else:
            ru = tidy(expand_tilde(pending_ru, headword, stem))
            note = pending_note
            for cut, head_alt in pending_alt:
                # сочетание к моменту скобки было ещё не дописано: приклеиваем
                # хвост, который набрался после неё
                full = tidy(expand_tilde(head_alt + pending_ru[cut:], headword, stem))
                note = ('или ' + full) if not note else note + '; или ' + full
            pending_alt.clear()
            # Запятую на конце не срезаем: перевод может продолжиться, и она
            # окажется внутри фразы («иза дӀавахара, со висира»). Края
            # почистит `close_ce`, когда перевод действительно кончится.
            item = {'ce': soft(raw).strip(' ;:!'), 'ru': ru, 'kind': 'phrase',
                    'labels': [],
                    'note': note, 'note_kind': None, 'gov': pending_gov,
                    'subs': []}
            (idioms if in_idioms else s['examples']).append(item)
            last_obj[:] = [item, 'ce']
            ce_open[0] = not raw.rstrip().endswith(';')
            pending_ru = pending_gov = pending_note = None

    def last_target():
        """Последний записанный перевод — к нему цепляется «(или …)» (п. 7)
        и буквенные значения (п. 4). Порядок не восстанавливается из списков:
        глоссы и словосочетания лежат раздельно, поэтому его надо помнить."""
        return last_obj[0], last_obj[1]

    def alt_words(payload):
        """«или дохор, или къарлур» -> ['дохор', 'къарлур']."""
        return [a.strip(' ,;') for a in re.split(r',\s*или\s+', payload)
                if a.strip(' ,;')]

    def apply_alt(alt):
        """П. 7: «кӀайн ган (или къега)» -> второй перевод «кӀайн къега».
        Скобка заменяет столько слов с конца, сколько в ней самой."""
        nonlocal pending_note
        obj, key = last_target()
        alts = alt_words(alt)
        if not obj or not alts:
            # цели ещё нет — сохраняем как было напечатано, ничего не теряя
            txt = 'или ' + alt if alt else 'или'
            pending_note = txt if pending_note is None else pending_note + '; ' + txt
            return
        # ПРАВИЛО ЗАМЕНЫ. Скобка меняет РОВНО ОДНО последнее слово, а сама
        # может состоять из нескольких: «йоза-дешар цахуург (или хууш воцург)»
        # это «йоза-дешар хууш воцург», а не «хууш воцург». Счёт слов внутри
        # скобки тут не работает — проверено на десяти разборах из книги и на
        # 346 многословных вариантах в корпусе.
        base = (obj.get(key) or '').split()
        if not base:
            return
        idx = len(base) - 1
        if ce_open[0] and obj is last_obj[0]:
            # перевод ещё дописывается: полный вариант соберём на закрытии
            pending_alt_ce.append((idx, alts))
            return
        for a in alts:
            new = ' '.join(base[:idx] + a.split())
            if key == 'ce' and 'ru' in obj:
                obj.setdefault('subs', []).append(
                    {'letter': None, 'text': new, 'note': 'или',
                     'gov': None, 'labels': []})
            else:
                sense(cur_block, cur_sense)['glosses'].append(
                    {'text': new, 'sep': 'или', 'labels': [], 'note': None,
                     'gov': None})

    ILLUSTRATES = {'мас', 'мас.', 'напр', 'напр.', 'масала'}

    def flush_note():
        nonlocal note_buf, pending_note, expansion
        items, note_buf = note_buf, []
        kind, payload = classify_paren(
            items, headword, stem, head_zone(),
            cur_sense is not None or cur_block is not None)
        if not kind:
            return
        if kind == 'ref':
            rel, targets = payload
            for tgt in targets:
                homs = []
                mh = _HOM.search(tgt)
                if mh:
                    homs = [int(x) for x in re.findall(r'[1-9]', mh.group(0))]
                    tgt = tgt[:mh.start()].strip()
                if tgt:
                    xrefs.append({'rel': rel, 'target': tgt,
                                  'homonyms': homs, 'senses': []})
        elif kind == 'suffix':
            base, was_xref = last_bold
            form = (base or headword).strip(' ,;') + payload
            if was_xref and xrefs:
                xrefs.append({'rel': xrefs[-1]['rel'], 'target': form,
                              'homonyms': [], 'senses': []})
            elif pending_ru is not None:
                if ' ' not in pending_ru.strip() and not has_content():
                    form = pending_ru.strip(' ,;') + payload
                    if not any(v['form'] == form for v in variants):
                        variants.append({'form': form, 'star': False})
                # внутри настоящего сочетания оставляем как напечатано
            elif not any(v['form'] == form for v in variants):
                variants.append({'form': form, 'star': False})
        elif kind == 'alt_ru':
            if pending_ru is None:
                apply_alt(expand_tilde(payload, headword, stem))
            else:
                base = pending_ru.split()
                add = payload.split()
                keep = base[:max(0, len(base) - len(add))]
                pending_alt.append((len(pending_ru), ' '.join(keep + add)))
        elif kind == 'alt':
            apply_alt(expand_tilde(payload, headword, stem))
        elif kind == 'forms':
            solo = len(payload) == 1
            for f in payload:
                ce = f.pop('ce', None)
                variants.append(f)
                if ce:
                    g = {'text': ce, 'sep': None, 'labels': [], 'note': None,
                         'gov': None}
                    if solo:
                        # «(ед. ~а ж кало)» — перевод точно этой формы. При двух
                        # формах («~ец м, ~ка ж абазо») он общий, и приписывать
                        # его последней было бы выдумкой.
                        g['gram'] = {'of': f['form']}
                    sense(cur_block, cur_sense)['glosses'].append(g)
        elif kind == 'expansion':
            expansion = payload if expansion is None else expansion
        else:
            txt = expand_tilde(payload, headword, stem)
            pending_note = txt if pending_note is None else pending_note + '; ' + txt

    def _start_phrase(t):
        nonlocal pending_ru
        pending_ru = t if pending_ru is None else pending_ru + t

    def _push_bracket():
        nonlocal pending_ru
        pending_ru += ']'

    def emit_light(t, first=False):
        """Кусок светлого текста вне скобок -> пометы заголовка и переводы."""
        nonlocal carry, cur_block, cur_sense, in_idioms, pron, pending_suffix
        if not t:
            return
        if head_zone():
            m = _PRON.search(t)                              # п. 32
            if m:
                pron = m.group(0)[1:-1]
                t = t[:m.start()] + ' ' + t[m.end():]
            if pending_suffix:
                # «лампа́д||а, ~ка ж»: тильда и её окончание разъехались по
                # прогонам, и вариант остался голой основой.
                m = re.match(r'^\s*([а-яё́̃-]+)', t)
                if m:
                    variants.append({'form': (stem or headword) + m.group(1),
                                     'star': False})
                    t = t[m.end():]
                pending_suffix = False
            m = COMMA_VARIANT.match(t) if first else None    # п. 25, «, сну»
            if m:
                variants.append({'form': m.group(1), 'star': False})
                return
            if 'forms' not in gram:
                m = _ENDINGS.match(t)
                if m and looks_like_endings(m.group(1)):
                    gram['forms'] = m.group(1)
                    t = t[m.end():]
            if t.lstrip().startswith(':'):
                t = t.lstrip()[1:]
                if t.strip():
                    _start_phrase(t)
                    return
        if pending_ru is not None and t.lstrip().startswith(']'):
            _push_bracket()
            t = t.lstrip()[1:]
        if t.rstrip().endswith('[') and t.count('[') > t.count(']'):
            carry = '['
            t = t.rstrip()[:-1]
        if RHOMB in t:
            before, after = t.split(RHOMB, 1)
            add_translation(before)
            close_ce()
            in_idioms = True
            cur_block = cur_sense = None
            t = after
        # П. 3 и 4 — РАЗНЫЕ уровни: жирная «1.» это блок, светлая «1)» значение.
        for k, piece in enumerate(SENSE_NO.split(t)):
            if k % 2 == 1:
                close_ce()
                cur_sense = int(piece)
                continue
            sub = LETTER_NO.split(piece)          # п. 4: «а) … б) …»
            if len(sub) > 1:
                add_translation(sub[0])
                obj, _ = last_target()
                for j in range(1, len(sub), 2):
                    txt = sub[j + 1].strip(' ;,.') if j + 1 < len(sub) else ''
                    if obj is not None and 'ru' in obj and txt:
                        obj.setdefault('subs', []).append(
                            {'letter': sub[j], 'text': txt, 'note': None,
                             'gov': None, 'labels': []})
                    elif txt:
                        add_translation(txt)
                continue
            add_translation(piece)

    if head_tail:
        pending_ru = head_tail

    i = 0
    while i < len(rest):
        tag, text = rest[i]
        nxt = rest[i + 1] if i + 1 < len(rest) else None
        i += 1

        # ---------------- курсив ----------------
        if tag == 'i':
            if paren:
                note_buf.append(('i', text))
                continue
            if pending_rel and text.strip(' .').lower() in ('тж', 'тж.'):
                pending_rel = 'см. тж.'     # п. 28
                continue
            if (xrefs and pending_ru is None and not has_content()
                    and text.strip(' .,').lower() in CONNECTOR):
                # «см. обмени́ть(ся) и обменя́ть(ся)» — союз между целями. Скобка
                # с окончанием между ними не должна обрывать цепочку.
                pending_rel = xrefs[-1]['rel']
                continue
            g, lab, gov, rel, note = split_apparatus(text)
            if (rel and pending_ru is not None and not has_content()
                    and ' ' not in pending_ru.strip()
                    and '~' not in pending_ru):
                form = pending_ru.strip(' ,;')
                if form and form != headword and not any(
                        v['form'] == form for v in variants):
                    variants.append({'form': form, 'star': False})
                pending_ru = None
                pending_rel = rel
                labels.extend(lab)
                continue
            if (rel and pending_ru is not None
                    and (' ' in pending_ru.strip() or '~' in pending_ru)):
                phrase_rel[0] = rel     # «~ая огнева́я то́чка см. дот»
                labels.extend(lab)
                continue
            if rel and pending_ru is None:
                pending_rel = rel
                if head_zone():
                    for gk, gv in g.items():
                        gram.setdefault(gk, gv)
                labels.extend(lab)
                continue
            if head_zone():
                # setdefault, а не update: первая помета относится к заголовку,
                # вторая — к варианту («ареста́нт м, ~ка ж» это статья на «м»).
                for gk, gv in g.items():
                    gram.setdefault(gk, gv)
                labels.extend(lab)
            pending_gov = gov or pending_gov
            if note:
                if (pending_ru is not None or pending_ce is not None
                        or ce_open[0]):
                    # курсивом набран сам чеченский перевод; он не кончается
                    # на границе прогона — «цхьаьнгахьа» + « цабезам хила»
                    pending_ce = note if pending_ce is None else pending_ce + ' ' + note
                elif note.strip(' .,').lower() not in ILLUSTRATES:
                    if pending_note is None:
                        pending_note = note
            continue

        # ---------------- жирный ----------------
        if tag == 'b':
            if not paren:
                close_ce()
            if paren:
                note_buf.append(('b', text))
                continue
            word = text.strip()
            if phrase_rel[0] and pending_ru is not None:
                pending_note = (phrase_rel[0] if pending_note is None
                                else pending_note + '; ' + phrase_rel[0])
                phrase_rel[0] = None
                add_translation(word)
                continue
            if pending_rel is None and await_xref_senses and xrefs and word:
                pending_rel = xrefs[-1]['rel']     # «см. X, Y» — вторая цель
            if pending_rel:
                # `merge_runs` склеивает соседние жирные через запятую, поэтому
                # «см. объе́здить, объе́хать» приходит одним прогоном — это ДВЕ
                # цели. А за «;» или «:» цель уже кончилась и начинается
                # словосочетание: «см. быть; ~, что бу́дет!».
                head, tail = (re.split(r'[;:]', word.strip(' .,;'), 1) + [''])[:2]
                for part in re.split(r'\s*,\s*', head):
                    part = part.strip(' .,;')
                    if not part:
                        continue
                    homs = []
                    mh = _HOM.search(part)
                    if mh:
                        homs = [int(x) for x in re.findall(r'[1-9]', mh.group(0))]
                        part = part[:mh.start()].strip()
                    if part:
                        xrefs.append({'rel': pending_rel, 'target': part,
                                      'homonyms': homs, 'senses': []})
                        last_bold[:] = [part, True]
                pending_rel = None
                await_xref_senses = True
                if tail.strip():
                    pending_ru = tail.strip()
                    await_xref_senses = False
                continue
            if BLOCK_NO.match(text):
                close_ce()
                cur_block, cur_sense = int(BLOCK_NO.match(text).group(1)), None
                blocks.setdefault(cur_block, {'n': cur_block, 'labels': [],
                                              'senses': []})
                continue
            # П. 16 и 12: вторая заглавная форма, а не словосочетание.
            # Опознаётся тем, что это ОДНО слово и рядом стоит грамматика.
            if pending_ru is None and not in_idioms and _WORD.match(word):
                prev_gram = None
                for back in range(i - 2, -1, -1):
                    if rest[back][0] == 'i':
                        prev_gram = split_apparatus(rest[back][1])[0]
                        break
                    if not PUNCT_ONLY.match(rest[back][1]):
                        break
                # «ареста́нт м, ~ка ж уст. см. аресто́ванный»: между `~ка` и родом
                # стоит пустой светлый прогон, и смотреть надо сквозь него.
                nxt_gram, nxt_rel = {}, None
                for fwd in range(i, len(rest)):
                    if rest[fwd][0] == 'i':
                        ap = split_apparatus(rest[fwd][1])
                        nxt_gram, nxt_rel = ap[0], ap[3]
                        break
                    if not PUNCT_ONLY.match(rest[fwd][1]):
                        break
                aspect_pair = (not has_content() and prev_gram
                               and (prev_gram.get('aspect') or prev_gram.get('pos')))
                # П. 25: «башу́, баси́шь и т. д. наст. от баси́ть» — вторая
                # словоформа заголовка. Опознаётся тем, что сразу за ней идёт
                # отсылка: словосочетанию отсылка вместо перевода не нужна.
                form_before_rel = not has_content() and nxt_rel and '~' not in word
                gender_pair = word.startswith('~') and (
                    nxt_gram.get('gender') or nxt_gram.get('aspect')
                    or nxt_gram.get('pos') or nxt_rel)
                if aspect_pair or gender_pair or form_before_rel:
                    form = expand_form(word, headword, stem)
                    if form and form != headword:
                        variants.append({'form': form, 'star': False})
                        last_bold[:] = [form, False]
                        pending_variant = form if has_content() else None
                        continue
            # «ге́лий м гелий» — перевод набран жирным. Опознаётся тем, что он
            # совпадает с заголовком с точностью до ударения: заимствование
            # в чеченском пишется так же, и словосочетанием быть не может.
            if (pending_ru is None and not has_content()
                    and bare(word) == bare(headword)):
                add_translation(word)
                continue
            t_b = carry + text
            carry = ''
            pending_ru = t_b if pending_ru is None else pending_ru + t_b
            last_bold[:] = [word, False]
            continue

        # ---------------- светлый ----------------
        t = text
        if await_xref_senses and not PUNCT_ONLY.match(
                t.replace('(', '').replace(')', '')):
            await_xref_senses = False
            m = XREF_SENSES.match(t)
            if m and xrefs:                                  # п. 16: «см. X 2, 3, 4»
                xrefs[-1]['senses'] = [int(x) for x in re.findall(r'\d+', m.group(1))]
                continue
        first = (i == 1)                    # прогон сразу за заголовком
        # П. 7 изредка набрано квадратными скобками: «бодане [или энжеде] стаг»
        t = re.sub(r'\[(\s*или\s[^\]]*)\]', r'(\1)', t)
        if i >= 2 and rest[i - 2][0] == 'i' and not paren:
            t = re.sub(r'^\s*\.\s*', ' ', t)   # точка от пометы, не начало перевода

        # Текст ВНЕ скобок надо выдать ДО того, как закроется скобка: «(не
        # обидный) халахетар доцу (или ца ден)» — к моменту второй скобки
        # перевод «халахетар доцу» ещё лежал в буфере, и замене по п. 7 не к
        # чему было прицепиться. Поэтому светлый прогон режется на куски по
        # границам скобок, и каждый кусок проходит разбор сразу.
        if paren or '(' in t or ')' in t:
            buf = []
            for ch in t:
                if ch == '(':
                    if not paren:
                        emit_light(''.join(buf), first)
                        buf = []
                    paren += 1
                    continue
                if ch == ')' and paren:
                    paren -= 1
                    if not paren:
                        flush_note()
                    continue
                if paren:
                    if note_buf and note_buf[-1][0] == '.':
                        note_buf[-1] = ('.', note_buf[-1][1] + ch)
                    else:
                        note_buf.append(('.', ch))
                else:
                    buf.append(ch)
            emit_light(''.join(buf), first)
        else:
            emit_light(t, first)

    if pending_ce:
        add_translation('')
    close_ce()
    if pending_ru is not None and pending_ru.strip():
        problems.append((idx, 'словосочетание осталось без перевода',
                         f'{headword}: {pending_ru[:40]}'))
    if paren:
        flush_note()
        problems.append((idx, 'незакрытая круглая скобка', headword))
    # Пояснение, оставшееся без «своего» перевода, относится к предыдущему:
    # «агробиологи (латталелорах … Ӏилма» — скобка в книге не закрыта, и
    # «ге́лий м гелий (хим. элемент, газ)» — скобка последняя в статье.
    # В обоих случаях примечание просто пропадало.
    if pending_note and last_obj[0] is not None:
        obj = last_obj[0]
        obj['note'] = (pending_note if not obj.get('note')
                       else obj['note'] + '; ' + pending_note)
        pending_note = None

    for key in sorted(senses, key=lambda k: ((k[0] or 0), (k[1] or 0))):
        s = senses[key]
        if not (s['glosses'] or s['examples']):
            continue
        bn = s.pop('block')
        if bn is None:
            s['_free'] = True
        else:
            blocks[bn]['senses'].append(s)
    free = [s for k, s in sorted(senses.items(),
                                 key=lambda kv: ((kv[0][0] or 0), (kv[0][1] or 0)))
            if s.pop('_free', False)]
    out_blocks = [b for n, b in sorted(blocks.items()) if b['senses']]

    out = {
        'id': headword if hom is None else f'{headword}-{hom}',
        'headword': headword,
        'homonym': hom,
        'pos': [gram['pos']] if gram.get('pos') else [],
        'labels': sorted(set(labels)),
        'cls_sg': [], 'cls_pl': [], 'forms': forms,
        'variants': variants,
        'gram': {k: v for k, v in gram.items() if k != 'pos'},
        'blocks': out_blocks,
        'senses': free,
        'idioms': idioms,
        'xrefs': xrefs,
        'src_ref': idx,
        'flags': [],
    }
    if expansion:
        out['expansion'] = expansion
    if pron:
        out['pron'] = pron
    # П. 24: статья описывает часть слова. Сторону даёт знак «=», тип —
    # чеченская помета в скобках («…шолгӀа да̃къа» = вторая часть сложных).
    if headword.startswith(_EQ) or headword.endswith(_EQ):
        # Знак «=» — РАЗМЕТКА книги, как `||` и `~`, и в данные он не идёт:
        # иначе `headword_norm` выходит `=градусный`, и поиск по началу слова
        # такую статью не находит никогда. Сторона знака сохраняется в
        # `gram.affix`, а карточка дорисовывает его при показе.
        out['flags'] = ['morpheme']
        out['gram']['affix'] = 'first' if headword.endswith(_EQ) else 'second'
        clean_hw = headword.strip(_EQ)
        out['headword'] = clean_hw
        out['id'] = clean_hw if hom is None else f'{clean_hw}-{hom}'
    # П. 8 применяется к стороне ПЕРЕВОДА (здесь чеченской). Русский заголовок
    # и русские словосочетания остаются как напечатаны.
    for sn in out['senses'] + [x for b in out['blocks'] for x in b['senses']]:
        new_gl = []
        for g in sn['glosses']:
            vs = bracket_variants(g['text'], problems, idx, headword)
            new_gl.append(dict(g, text=vs[0]))
            for v in vs[1:]:
                new_gl.append(dict(g, text=v, sep=',', note=None))
        sn['glosses'] = new_gl
        for x in sn['examples']:
            _expand_ex(x, problems, idx, headword)
    for x in out['idioms']:
        _expand_ex(x, problems, idx, headword)

    all_sn = out['senses'] + [x for b in out['blocks'] for x in b['senses']]
    all_notes = ([g.get('note') for sn in all_sn for g in sn['glosses']]
                 + [x.get('note') for sn in all_sn for x in sn['examples']]
                 + [x.get('note') for x in out['idioms']])
    # «маьӀна долуш» = «в значении…» — это про контекст употребления, а не про
    # часть слова: помечать таким статью как морфему нельзя («ай» — междометие).
    PART_KINDS = {'first', 'second', 'prefix', 'suffix', 'abbr_first', 'part'}
    for n in all_notes:
        m = morph_of(n)
        if m:
            out['gram']['morph'] = m
            if m['kind'] in PART_KINDS and 'morpheme' not in out['flags']:
                out['flags'] = out['flags'] + ['morpheme']
                # «ви́це-», «экс-», «я́рко-» помечены дефисом, а не знаком «=»
                if not out['gram'].get('affix') and out['headword'].endswith('-'):
                    out['gram']['affix'] = 'first'
            break
    return out


# --------------------------------------------------------------------------
# 7. Границы корпуса и проверки
# --------------------------------------------------------------------------

LETTERS = re.compile(r'^[А-ЯЁ]$')


def corpus_range(paras):
    """Корпус начинается с абзаца-буквы «А» и идёт до конца."""
    for i, r in enumerate(paras):
        t = ''.join(x[1] for x in r).strip()
        if LETTERS.match(t) and i > 100:
            return i, len(paras)
    return 0, len(paras)


def all_senses(e):
    for b in e.get('blocks') or []:
        for s in b['senses']:
            yield s
    for s in e.get('senses') or []:
        yield s


def audit(entries, problems):
    seen = Counter(e['headword'] for e in entries)
    idx = Counter()
    for e in entries:
        hw = e['headword']
        n_gl = sum(len(s['glosses']) for s in all_senses(e))
        n_ex = sum(len(s['examples']) for s in all_senses(e))
        if not hw:
            problems.append((e['src_ref'], 'пустой заголовок', ''))
        if not n_gl and not n_ex and not e['idioms'] and not e['xrefs']:
            problems.append((e['src_ref'], 'нет ни перевода, ни отсылки', hw))
        if len(hw) > 45:
            problems.append((e['src_ref'], 'подозрительно длинный заголовок', hw[:60]))
        if '~' in hw or '|' in hw or ',' in hw:
            problems.append((e['src_ref'], 'служебный знак в заголовке', hw))
        if re.search(r'\s\d+\.?$', hw):
            problems.append((e['src_ref'], 'номер прилип к заголовку', hw))
        for s in all_senses(e):
            for x in s['examples']:
                if not x['ce'] or not x['ru']:
                    problems.append((e['src_ref'], 'половина словосочетания пуста',
                                     f"{hw}: {x['ru']} / {x['ce']}"))
        for v in e['variants']:
            if '~' in v['form'] or not v['form']:
                problems.append((e['src_ref'], 'тильда не развёрнута в варианте',
                                 f"{hw}: {v['form']}"))
        if seen[hw] > 1 and e['homonym'] is None:
            idx[hw] += 1
            e['homonym'] = idx[hw]
            e['id'] = f'{hw}-{idx[hw]}'


def main(argv=None):
    ap = argparse.ArgumentParser(description='Карасаев–Мациев 1978 .odt -> JSONL')
    ap.add_argument('--odt', required=True)
    ap.add_argument('--out', default='work')
    ap.add_argument('--limit', type=int, help='разобрать только первые N абзацев')
    args = ap.parse_args(argv)
    os.makedirs(args.out, exist_ok=True)

    paras = paragraphs(args.odt)
    lo, hi = corpus_range(paras)
    if args.limit:
        hi = min(hi, lo + args.limit)
    build_label_vocab(paras, lo, hi)
    print(f'  абзацев всего {len(paras)}, корпус с p{lo}, '
          f'помет в книге {len(LABEL_VOCAB)}')

    problems, entries = [], []
    skipped = 0
    for i in range(lo, hi):
        flat = ''.join(x[1] for x in paras[i]).strip()
        if LETTERS.match(flat):       # «А», «Б», «В» — разделители, не статьи
            continue
        e = parse_entry(paras[i], i, problems)
        if e is None:
            t = ''.join(x[1] for x in paras[i]).strip()
            if t and not LETTERS.match(t):
                skipped += 1
                if len(t) > 3:
                    problems.append((i, 'абзац не разобран', t[:80]))
            continue
        entries.append(e)
    audit(entries, problems)

    path = os.path.join(args.out, 'karasaev1978.jsonl')
    with open(path, 'w', encoding='utf-8') as f:
        for e in entries:
            f.write(json.dumps(e, ensure_ascii=False) + '\n')

    ppath = os.path.join(args.out, 'problems_karasaev.tsv')
    with open(ppath, 'w', encoding='utf-8') as f:
        f.write('абзац\tчто не так\tчто было\n')
        for row in problems:
            f.write('\t'.join(str(x) for x in row) + '\n')

    gl = sum(len(s['glosses']) for e in entries for s in all_senses(e))
    ex = sum(len(s['examples']) for e in entries for s in all_senses(e))
    idi = sum(len(e['idioms']) for e in entries)
    sub = sum(len(x.get('subs') or []) for e in entries
              for s in all_senses(e) for x in s['examples'])
    print(f'\n  статей           {len(entries):>7}')
    print(f'  переводов        {gl:>7}')
    print(f'  словосочетаний   {ex:>7}')
    print(f'  идиом            {idi:>7}')
    print(f'  блоков (п. 3)    {sum(len(e["blocks"]) for e in entries):>7}')
    print(f'  вариантов (п.12/16/25) {sum(len(e["variants"]) for e in entries):>7}')
    print(f'  букв. значений (п. 4)  {sub:>7}')
    print(f'  расшифровок (п. 23)    {sum(1 for e in entries if e.get("expansion")):>7}')
    print(f'  произношений (п. 32)   {sum(1 for e in entries if e.get("pron")):>7}')
    print(f'  отсылок          {sum(len(e["xrefs"]) for e in entries):>7}')
    print(f'  пропущено абзацев {skipped}')
    print(f'\n  {ppath}: {len(problems)} строк')
    for kind, n in Counter(p[1] for p in problems).most_common(10):
        print(f'    {n:>6}  {kind}')
    return 0


if __name__ == '__main__':
    sys.exit(main())

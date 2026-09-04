# -*- coding: utf-8 -*-
"""
Разбор двух малых словарей из .odt в JSONL для build_app_db.py.

    python tools/parse_small_dicts.py --math work/math1997.odt --comp work/comp2017.odt \
                                      --out work/

Даёт четыре файла — по одному на направление — и `problems.tsv` со всем, чего
разобрать не удалось или что выглядит подозрительно.

    math1997_ce.jsonl   чеч->рус, ~1050 статей, с классом и падежной парадигмой
    math1997_ru.jsonl   рус->чеч, ~1290 статей
    comp2017_ru.jsonl   рус->чеч, ~560 статей со словосочетаниями
    comp2017_ce.jsonl   чеч->рус, ~660 статей

ПОЧЕМУ ЭТО ДЕТЕРМИНИРОВАННЫЙ ПАРСЕР, А НЕ МОДЕЛЬ

В отличие от Мациева, у которого на входе был голый OCR, здесь жива
типографская разметка: жирный — заголовок и словосочетания, курсив — грамматика,
светлый — перевод. Границы статей и полей заданы стилями, а не догадкой, поэтому
всё, что нужно, — аккуратно прочитать прогоны и не склеить то, что стоит рядом.

ТРИ МЕСТА, ГДЕ ИСХОДНИК ВРЁТ, И ЧТО МЫ С НИМИ ДЕЛАЕМ

1. БУКВЕННЫЕ ЗАГОЛОВКИ СЪЕДАЮТ НАЧАЛО СЛОВА (только словарь 1997). Буква раздела
   набрана отдельным абзацем и физически вырезана из первого слова: после «А»
   идёт «баде», после «Аь» — «лларг», после «I» — «ад». Восстанавливаем приписью
   и проверяем результат по парадигме из той же статьи: у «абаде» множественное
   «абадеш», у «Ӏад» — «Ӏедаш». Что не сошлось — в problems.tsv.

2. ПАЛОЧКА НАБРАНА ЛАТИНИЦЕЙ (словарь 2017): `I` 2 812 раз, `І` 66, `l` 26.
   Нормализатор спас бы ключи поиска, но не отображаемый текст — в карточке
   стояло бы «хIунда». Чиним, но только там, где рядом кириллица: `opendIct` и
   `shift` — настоящая латиница, их трогать нельзя.

3. КЛАССНЫЙ ПОКАЗАТЕЛЬ ЗАПИСАН ПО-РАЗНОМУ. 1997 пишет копулой («аре ю»),
   2017 — самим показателем парой ед./мн. («ма̃ша (б, д)»). Приводим к спискам
   из `в` `й` `д` `б`, иначе `ю` и `й` разъедутся и каждая пара даст ложное
   расхождение при сшивке.

ЧЕГО МЫ НЕ ДЕЛАЕМ

Не достраиваем диакритику. В словаре 1997 её нет вовсе — ни долгот, ни ударений;
в 2017 есть и то и другое. Долготу можно было бы подтянуть у Мациева по связи,
но это была бы правка текста книги, которой в книге нет. Пусть 1997 остаётся
таким, каким напечатан, а долготу покажет связанная статья Мациева.
"""
import argparse, json, os, re, sys, unicodedata as ud
import zipfile
from collections import Counter
from xml.etree import ElementTree as ET

T = '{urn:oasis:names:tc:opendocument:xmlns:text:1.0}'
S = '{urn:oasis:names:tc:opendocument:xmlns:style:1.0}'
PAL = 'Ӏ'


# --------------------------------------------------------------------------
# 1. Чтение .odt с сохранением жирного и курсива
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
    """-> список абзацев, каждый — список [tag, text], tag ∈ . b i bi."""
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
# 2. Приведение текста
# --------------------------------------------------------------------------

# Весь кириллический блок, а не только А-Я: `І` U+0406 лежит ниже `А` U+0410,
# и по узкому диапазону «могІа» не опознавалось как слово вовсе.
_CYR = re.compile(r'[\u0400-\u04FF]')
_SOFT = '­'          # мягкий перенос из колонок
# Латинские двойники кириллицы. Раньше здесь была только палочка, и этого не
# хватило: `find_homoglyphs.py` нашёл в словаре 2017 года «aьзнийн» и «гӀaлат»
# с латинской `a`. Поиск их находит (нормализатор переводит), а в карточке
# стоит чужая буква.
# ВНИМАНИЕ: цифры `1` здесь быть не должно. В ChechenNormalizer она означает
# палочку — это способ набрать `Ӏ` с обычной раскладки, и для КЛЮЧА ПОИСКА
# верно. Но здесь мы чиним ТЕКСТ КНИГИ, где `1` — номер значения: «тайпа д; 1.»
# превращалось в «тайпа д; Ӏ.», номер переставал опознаваться и уезжал
# в заголовок вместе с грамматикой. Так пропали `тайпа`, `хьаьрк`, `йист`.
_LATIN_PAL = {
    'I': PAL, 'І': PAL, 'l': PAL, 'ӏ': PAL, 'i': PAL, '|': PAL, 'і': PAL,
    'a': 'а', 'c': 'с', 'e': 'е', 'o': 'о', 'p': 'р', 'x': 'х', 'y': 'у', 'k': 'к',
    'A': 'А', 'B': 'В', 'C': 'С', 'E': 'Е', 'H': 'Н', 'K': 'К', 'M': 'М',
    'O': 'О', 'P': 'Р', 'T': 'Т', 'X': 'Х', 'Y': 'У',
}


_LAT = re.compile(r'[A-Za-z]')
_TOKEN = re.compile(r'[A-Za-z\u0400-\u04FF\u0300-\u036f]+')

# Буквы, у которых двойника НЕТ, — по ним и опознаётся алфавит слова.
# `г`, `л`, `т` бывают только кириллицей; `v`, `b`, `d` — только латиницей.
_ONLY_CYR = set('бгджзилмнптфцчшщъыьэюяБГДЖЗИЛМНПФЦЧШЩЪЫЬЭЮЯ') | {PAL, 'ӏ'}
_ONLY_LAT = set('bdfghjlnqrstuvwzDFGIJLNQRSUVWZ') - set(_LATIN_PAL)


def _script_of(token):
    """Какого алфавита слово: 'ce' | 'lat' | 'mixed'.

    Сначала ищем буквы БЕЗ двойника — они решают вопрос сразу: `г`, `л`, `т`
    бывают только кириллицей, `v`, `b`, `d` только латиницей.

    Но слово может целиком состоять из двойников — «хIокху» это х-о-к-х-у плюс
    палочка, и ни одна буква ничего не доказывает. Тогда решаем большинством:
    пять кириллических против одной латинской — слово кириллическое.
    """
    bare = ''.join(c for c in token if ud.category(c) != 'Mn')
    cyr_only = any(c in _ONLY_CYR for c in bare)
    lat_only = any(c in _ONLY_LAT for c in bare)
    if cyr_only and lat_only:
        return 'mixed'          # «терминалаvba» — не наше дело, чинить нечего
    if cyr_only:
        return 'ce'
    if lat_only:
        return 'lat'
    n_cyr = sum(1 for c in bare if _CYR.match(c))
    n_lat = sum(1 for c in bare if _LAT.match(c))
    return 'ce' if n_cyr > n_lat else 'lat'


def fix_palochka(text):
    """Латинский двойник -> кириллица там, где слово опознано как кириллическое.

    Смотрим на СЛОВО, а не на соседей: в «гӀaлат» латинские `I` и `a` стоят
    рядом и по правилу соседа блокировали бы друг друга. Зато `г`, `л`, `т`
    двойников не имеют — значит слово кириллическое, и обе буквы чинятся.

    `opendIct` и `shift` опознаются по `d`, `n`, `s` как латинские и остаются
    как есть; «терминалаvba» — спорное (есть и `н`, и `v`), не трогаем вовсе,
    такие ловит find_homoglyphs.py.

    Одиночная буква раздела `I` двойников не содержит и опознаться не может —
    для неё остаётся прежнее правило соседа.
    """
    if not text:
        return text

    def fix_token(m):
        tok = m.group(0)
        if _script_of(tok) != 'ce':
            return tok
        return ''.join(_LATIN_PAL.get(c, c) for c in tok)

    text = _TOKEN.sub(fix_token, text)

    # то, что не попало ни в одно слово: одиночная `I`, `1`, `|`
    out = list(text)
    for i, ch in enumerate(out):
        if ch not in _LATIN_PAL:
            continue
        left = text[i - 1] if i else ''
        right = text[i + 1] if i + 1 < len(text) else ''
        if left.isalpha() or right.isalpha():
            continue
        out[i] = _LATIN_PAL[ch]
    return ''.join(out)


def clean(text, palochka=True):
    """НЕ обрезает края прогона.

    Граница слова в .odt часто набрана отдельным светлым прогоном из одного
    пробела: `[b]«m модульца n дарже»` `[.]« »` `[b]«~»`. Обрезав края, мы
    склеим соседние прогоны вплотную и получим «даржецадиснарг». Пробелы
    снимаются один раз — там, где собирается готовое поле.
    """
    if text is None:
        return None
    t = ud.normalize('NFC', text).replace(_SOFT, '')
    if palochka:
        t = fix_palochka(t)
    return re.sub(r'\s+', ' ', t)


# Копула -> классный показатель. 1997 пишет «аре ю», 2017 «(й, й)».
_CLS = {'ю': 'й', 'йу': 'й', 'ду': 'д', 'бу': 'б', 'ву': 'в',
        'й': 'й', 'д': 'д', 'б': 'б', 'в': 'в'}


def parse_cls(text):
    """'ю' -> ['й'];  'б, д' -> ['б','д'];  мусор -> []."""
    out = []
    for piece in re.split(r'[,\s/]+', (text or '').strip(' ()')):
        p = piece.strip().lower()
        if p in _CLS:
            out.append(_CLS[p])
    return out


def split_glosses(text):
    """«ослабление; утомление, изнеможение» -> глоссы с разделителем ПЕРЕД.

    Текст перевода лежит в поле `text`, а не `ru`: у словаря рус->чеч перевод
    чеченский, и ключ `ru` был бы враньём, вкомпилированным в формат. Язык
    задаётся направлением словаря (`dicts.lang_tgt`), а не именем поля.

    Внутри скобок не режем: у перевода «хакер (в, б)» запятая разделяет
    классные показатели ед. и мн. числа, а не два перевода.
    """
    out, sep, cur, depth = [], None, [], 0
    text = (text or '').replace('\x00\x00', '\x00')
    for ch in text.strip():
        if ch in '([':
            depth += 1
        elif ch in ')]':
            depth = max(0, depth - 1)
        if ch == '\x00' and depth == 0:
            piece = ''.join(cur).strip(' .')
            if piece:
                out.append({'text': piece, 'sep': sep, 'labels': [],
                            'note': None, 'gov': None})
            sep, cur = None, []
            continue
        if ch in ',;' and depth == 0:
            piece = ''.join(cur).strip(' .')
            if piece:
                out.append({'text': piece, 'sep': sep, 'labels': [],
                            'note': None, 'gov': None})
            sep, cur = ch, []
            continue
        cur.append(ch)
    piece = ''.join(cur).strip(' .')
    if piece:
        out.append({'text': piece, 'sep': sep, 'labels': [], 'note': None, 'gov': None})
    return out


_PAREN_CLS = re.compile(r'\s*\(\s*([вйдбювдуб][вйдбювдуб,\s]*)\)\s*$')


def strip_cls(text):
    """'абрис (ю)' -> ('абрис', ['й']). Показатель перевода, не заголовка."""
    m = _PAREN_CLS.search(text or '')
    if not m:
        return text, []
    cls = parse_cls(m.group(1))
    if not cls:
        return text, []
    return text[:m.start()].strip(), cls


def lower_ce(text):
    """lower(), не трогающий палочку.

    `'Ӏ'.lower()` даёт `ӏ` U+04CF — ровно та ошибка, из-за которой в архивной
    базе 18,5 % статей не находились точным поиском. Заголовок `Ӏад` обязан
    остаться с U+04C0.
    """
    return ''.join(c if c == PAL else c.lower() for c in text or '')


def merge_ws(runs):
    """Светлый прогон из одних пробелов между двумя жирными — часть жирного.

    Иначе «главное ~ интеграла в смысле Коши» распадается на четыре куска:
    в исходнике курсив и жирный чередуются произвольно, а пробел между ними
    набран светлым.
    """
    out = []
    i = 0
    while i < len(runs):
        tag, tx = runs[i]
        if (out and tag == '.' and tx.strip() in ('', '-')
                and out[-1][0].startswith('b')
                and i + 1 < len(runs) and runs[i + 1][0].startswith('b')):
            out[-1][1] += tx
            i += 1
            continue
        if out and out[-1][0] == tag:
            out[-1][1] += tx
        else:
            out.append([tag, tx])
        i += 1
    return out


def expand_tilde(text, full, stem):
    """`~` = заголовок целиком, `~а` = основа до `//` плюс окончание."""
    if '~' not in (text or ''):
        return text
    return re.sub(r'~', stem if stem else full, text)


# --------------------------------------------------------------------------
# 3. Буквенные заголовки словаря 1997
# --------------------------------------------------------------------------

# Буквы разделов — их всего 16, и каждая физически вырезана из первого слова.
LETTER_MAX = 2


def is_letter_heading(runs):
    txt = ''.join(t for _, t in runs).strip()
    if not txt or len(txt) > LETTER_MAX:
        return None
    if not all(_CYR.match(c) or c in 'IІ' for c in txt):
        return None
    return fix_palochka(txt)


# --------------------------------------------------------------------------
# 4. Словарь 1997, раздел чеченско-русский
# --------------------------------------------------------------------------
#   [b] «автомат ю; »
#   [bi] «автоматаш, ю; автоматан, автоматана, автомато, автомате»
#   [.] « автомат;»  [b] «~ийн теори » [.] «теория автоматов»
#
# Грамматический блок по «Построению словаря»: показатель класса ед. ч.;
# именительный мн. ч. с его показателем; род., дат., эрг. и местн. ед. ч.

CASES_1997 = ('gen', 'dat', 'erg', 'all')


def parse_gram_1997(head_text, gram_text):
    """('автомат ю; ', 'автоматаш, ю; автоматан, ...') -> (заголовок, cls, формы)"""
    hw = head_text
    cls_sg = []
    m = re.search(r'\s+([а-яё]{1,2})\s*;\s*$', hw)
    if m:
        c = parse_cls(m.group(1))
        if c:
            cls_sg = c
            hw = hw[:m.start()]
    forms, cls_pl = [], []
    if gram_text:
        chunks = [c.strip() for c in gram_text.split(';')]
        if chunks:
            pl = [x.strip() for x in chunks[0].split(',')]
            if pl and pl[0]:
                forms.append({'form': pl[0], 'num': 'pl', 'case': 'nom'})
            if len(pl) > 1:
                cls_pl = parse_cls(pl[1])
        if len(chunks) > 1:
            obl = [x.strip() for x in chunks[1].split(',') if x.strip()]
            for case, form in zip(CASES_1997, obl):
                forms.append({'form': form, 'num': 'sg', 'case': case})
    return hw.strip(), cls_sg, cls_pl, forms


def parse_math_ce(paras, lo, hi, problems):
    entries, pending_letter, prev_hw = [], None, None
    for i in range(lo, hi):
        runs = merge_ws([[t, clean(x, palochka=True)] for t, x in paras[i] if x])
        runs = [r for r in runs if r[1]]
        while runs and not runs[0][1].strip():
            runs.pop(0)
        if not runs:
            continue
        letter = is_letter_heading(runs)
        if letter is not None:
            pending_letter = letter
            continue
        if not runs[0][0].startswith('b'):
            problems.append(('math1997_ce', i, 'абзац не начинается с жирного',
                             ''.join(t for _, t in runs)[:90]))
            continue

        # «абсцисс» + светлое «//» + «а ю; » — разделитель основы набран светлым
        k, head_parts, gram = 0, [], None
        while k < len(runs):
            tag, text = runs[k]
            if _SEE_ONLY.match(text):
                break            # «хь.» бывает и жирным курсивом — это не грамматика
            if tag == 'bi' and gram is None:
                gram = text
            elif tag.startswith('b'):
                if _ONLY_NO.match(text):     # жирное «1.» — уже тело статьи
                    break
                head_parts.append(text)
            elif tag == '.' and text.strip() in ('//', '/', ''):
                head_parts.append(text.strip() or ' ')
            elif (tag == '.' and gram is None
                  and re.fullmatch(r'\s*[а-яё]{1,2}\s*;\s*', text)):
                head_parts.append(text)      # « ю; » светлым — тоже часть головы
            else:
                break
            k += 1
        head = ''.join(head_parts)
        rest = runs[k:]
        if pending_letter:
            head = lower_ce(pending_letter) + head

        hw, cls_sg, cls_pl, forms = parse_gram_1997(head, gram)
        if pending_letter:
            # Приписку показываем всегда: 16 строк, глазами проверяется за минуту.
            # Автоматически сверить её нельзя — у «га» множественное «генаш»
            # (законный аблаут), и любая проверка «по корню» на этом врёт.
            probe = forms[0]['form'] if forms else '—'
            problems.append(('math1997_ce', i, 'буква приписана (проверить глазами)',
                             f'{pending_letter} + … = {hw}   мн. {probe}'))
            pending_letter = None

        hw, why = decap(hw, prev_hw)
        if why:
            problems.append(('math1997_ce', i, why, hw))
        hw, first_no = strip_head_sense(hw)
        hw, hom = strip_homonym(hw)
        stem, hw = split_stem(hw)
        senses, xrefs = collect_body(rest, hw, stem, src_lang='ce',
                                     first_no=first_no)
        entries.append(mk_entry(hw, senses, xrefs, i, homonym=hom,
                                cls_sg=cls_sg, cls_pl=cls_pl,
                                forms=forms, gram={'stem': stem} if stem else {}))
        prev_hw = hw
    return entries


_HOM = re.compile(r'(?<=[а-яёӀ])([1-9])\s*$')


_HEAD_NO = re.compile(r'\s+(\d)\s*\.\s*$')


def strip_head_sense(head):
    """«единица 1. » -> («единица», 1). Номер первого значения бывает набран
    тем же жирным прогоном, что и заголовок, — и уезжал в заголовок целиком."""
    m = _HEAD_NO.search(head or '')
    if not m:
        return head, None
    return head[:m.start()], int(m.group(1))


def strip_homonym(hw):
    """«могӀа1» -> («могӀа», 1). Номер омонима напечатан цифрой вплотную к слову.

    Оставлять его в заголовке нельзя не только из-за вида: ChechenNormalizer
    считает `1` способом набрать палочку с обычной раскладки, и ключ поиска
    превращается в несуществующее «могӀаӀ» — статья не находится вообще.
    """
    m = _HOM.search(hw or '')
    if not m:
        return hw, None
    return hw[:m.start()].strip(), int(m.group(1))


def split_stem(hw):
    """'абсцисс//а' -> ('абсцисс', 'абсцисса')."""
    if '//' in hw:
        a, b = hw.split('//', 1)
        return a.strip(), (a + b).replace('//', '').strip()
    return None, hw.strip()


# --------------------------------------------------------------------------
# 5. Общее тело статьи: переводы, значения `1.` `2.`, словосочетания, отсылка
# --------------------------------------------------------------------------

_SENSE_NO = re.compile(r'(?:^|(?<=[\s;]))(\d)\s*\.\s*')
_ONLY_NO = re.compile(r'^\s*(\d)\s*\.\s*$')
_ONLY_PUNCT = re.compile(r'^[\s;,.:()\-–—/]*$')
# «хь.» = «хьажа» = «см.». Набрано как придётся: светлым (24 раза), курсивом (3)
# и жирным курсивом (1) — то есть тем же стилем, что и грамматический блок.
_SEE = re.compile(r'^хь\s*\.\s*')
_SEE_ONLY = re.compile(r'^\s*хь\s*\.\s*$')


def strip_edges(text):
    """Снять служебный маркер границы и тире-разделитель с обоих концов.

    `–` разделяет словосочетание и перевод, но по прогонам он попадает то в
    конец жирного, то в начало светлого — зависит от того, как typesetter
    поставил пробел.
    """
    t = (text or '').replace('\x00', ' ')
    # длинное тире — всегда разделитель; обычный дефис — только если он отделён
    # пробелом, иначе снесём половину слова: «n-й степени», «кросс-платформин»
    t = re.sub(r'^\s*(?:[–—]|-(?=\s))\s*', '', t)
    t = re.sub(r'\s*(?:[–—]|(?<=\s)-)\s*$', '', t)
    return re.sub(r'\s+', ' ', t).strip()


def collect_body(rest, hw, stem, src_lang, first_no=None):
    """rest — прогоны после заголовка и грамматики.

    Светлый прогон — текст на языке перевода, жирный — словосочетание на языке
    заголовка. Но три вещи ломают это чередование, и все три встречаются:

      * НОМЕР ЗНАЧЕНИЯ НАБРАН ЖИРНЫМ отдельным прогоном (`["b","1."]`). Принять
        его за словосочетание — значит потерять весь перевод статьи: так
        27 статей словаря 1997 («барам», «дакъа», «терахь» …) остались бы
        вообще без переводов.
      * КЛАССНЫЙ ПОКАЗАТЕЛЬ ПЕРЕВОДА идёт курсивом внутри строки переводов
        («хакер `(в, б)`; 2. къу-программа `(й, й)`»). Возвращаем его в текст
        скобками — дальше его снимет strip_cls, уже поглоссно.
      * ЖИРНЫМИ БЫВАЮТ ПРОБЕЛЫ И `;` между светлыми кусками. Это типографский
        мусор, а не начало словосочетания.
    """
    senses, xrefs = {}, []
    cur_no = first_no
    pending_head = None
    carry = ''      # `~`, оставшийся в конце светлого прогона
    buf = []        # список, а не строка: flush() чистит его на месте

    box = {'carry': '', 'see': False, 'done': False}

    def set_carry(v):
        box['carry'] = v

    def nonlocal_carry():
        return box['carry']

    def sense(n):
        return senses.setdefault(n, {'n': n, 'glosses': [], 'examples': [],
                                     'labels': [], 'pos': []})

    def flush():
        """Накопленный светлый текст — это либо переводы заголовка, либо
        перевод предыдущего словосочетания."""
        nonlocal pending_head, cur_no
        nonlocal_carry()
        text = ''.join(buf).strip()
        del buf[:]
        # «...выражение;~» + жирное « цхьалхе бан » = «~ цхьалхе бан»: тильду
        # набрали светлым, но относится она к следующему словосочетанию
        if text.endswith('~'):
            set_carry('~')
            text = text[:-1].rstrip(' ;')
        if not text:
            return
        if _SEE.match(text):
            tail = _SEE.sub('', text).strip(' ;')
            if pending_head is None:
                # отсылка всей статьи: «ноль хь. нуль». Цель бывает в том же
                # прогоне («хь. саттар»), а бывает следующим жирным («хь.» +
                # «сакхт») — первый случай это 674, второй 110.
                xrefs.append({'rel': 'см.', 'target': tail or None, 'homonyms': []})
                return
            # отсылка ОДНОГО словосочетания к другому: «~ нацело хь. ~ без
            # остатка». Это не отсылка статьи, и делать её такой нельзя —
            # «деление» уехало бы к «~ без остатка». Оставляем текстом, как
            # напечатано, а следующий жирный — это цель, а не новое сочетание.
            if not box['done']:
                box['see'] = box['done'] = True
                buf.extend(['хь. ', tail])
                return
            # второй раз по тому же тексту не заходим: он уже собран целиком
        parts = _SENSE_NO.split(text)
        for k, piece in enumerate(parts):
            if k % 2 == 1:
                cur_no = int(piece)
                continue
            piece = piece.strip(' ;')
            if not piece:
                continue
            if pending_head is None:
                for g in split_glosses(piece):
                    g['text'] = g['text'].replace('\x00', ' ').strip()
                    g['text'], cls = strip_cls(g['text'])
                    g['text'] = re.sub(r'\s+', ' ', g['text']).strip()
                    if cls:
                        g['gram'] = {'cls': cls}
                    if g['text']:
                        sense(cur_no)['glosses'].append(g)
            else:
                coll = expand_tilde(pending_head, hw, stem)
                piece = strip_edges(piece)
                ru, ce = (piece, coll) if src_lang == 'ce' else (coll, piece)
                sense(cur_no)['examples'].append(
                    {'ce': ce, 'ru': ru, 'kind': 'phrase', 'labels': [],
                     'note': None, 'note_kind': None, 'gov': None})
                pending_head = None

    for tag, text in rest:
        if tag == '.':
            buf.append(text)
            continue
        if tag in ('i', 'bi'):
            inner = text.strip(' ()')
            if parse_cls(inner):
                # показатель перевода: «хакер (в, б)» -> граница глосса
                buf.append(f' ({inner})\x00')
            else:
                # курсивом бывает и просто часть текста — «(b, c)» в формуле
                buf.append(' ' + text)
            continue
        # дальше только жирный
        m = _ONLY_NO.match(text)
        if m:
            flush()
            cur_no = int(m.group(1))
            continue
        if _ONLY_PUNCT.match(text):
            buf.append(text)
            continue
        flush()
        if box['see']:
            # флаг поднят внутри flush(): этот жирный — цель отсылки
            # словосочетания, а не начало нового
            box['see'] = False
            buf.append(' ' + expand_tilde(text.strip(' ;'), hw, stem))
            continue
        # Тильду приклеиваем к СЫРОМУ тексту, до обрезки краёв. Пробел вокруг
        # неё — значащий: по предисловию «~» замещает заглавное слово ровно
        # там, где стоит, поэтому «;~» + « матрица » это «~ матрица» ->
        # «цхьааллин матрица» (два слова), а «;~» + «ан функци» это «~ан функци»
        # -> «гайтаман функци» (одно). Обрезав пробел заранее, мы теряем
        # единственный признак, который их различает.
        raw = text
        if box['carry']:
            raw = box['carry'] + raw
            box['carry'] = ''
        head_txt = strip_edges(raw.rstrip().rstrip(';'))
        if xrefs and xrefs[-1]['target'] is None:
            xrefs[-1]['target'] = head_txt
            continue
        pending_head = head_txt
        box['done'] = False
    flush()
    if xrefs and xrefs[-1]['target'] is None:
        xrefs.pop()
    order = sorted(senses, key=lambda n: (n is not None, n or 0))
    return [senses[n] for n in order], xrefs


def mk_entry(hw, senses, xrefs, src_ref, cls_sg=(), cls_pl=(), forms=(), gram=None,
             homonym=None):
    return {
        'id': hw if homonym is None else f'{hw}-{homonym}',
        'headword': hw,
        'homonym': homonym,
        'pos': [],
        'labels': [],
        'cls_sg': list(cls_sg),
        'cls_pl': list(cls_pl),
        'forms': list(forms),
        'senses': senses,
        'idioms': [],
        'xrefs': xrefs,
        'gram': gram or {},
        'src_ref': src_ref,
        'flags': [],
    }


# --------------------------------------------------------------------------
# 6. Словарь 1997, раздел русско-чеченский; словарь 2017, оба раздела
# --------------------------------------------------------------------------

def parse_simple(paras, lo, hi, code, src_lang, problems, letters_eat=False):
    """Заголовок жирным, дальше тело. Голова — все прогоны до первого светлого.

    Собирать голову целиком, а не по одному прогону, приходится потому, что
    скобка показателя бывает разорвана стилями: `гӀулчиг (` жирным, `й, й`
    жирным курсивом, `)` снова жирным. Склеенная строка разбирается однозначно.
    """
    entries, pending_letter, prev_hw = [], None, None
    for i in range(lo, hi):
        runs = merge_ws([[t, clean(x)] for t, x in paras[i] if x])
        runs = [r for r in runs if r[1]]
        while runs and not runs[0][1].strip():
            runs.pop(0)
        if not runs:
            continue
        letter = is_letter_heading(runs)
        if letter is not None:
            pending_letter = letter if letters_eat else None
            continue
        if not runs[0][0].startswith('b'):
            problems.append((code, i, 'абзац не начинается с жирного',
                             ''.join(t for _, t in runs)[:90]))
            continue

        # голова: всё до первого содержательного светлого прогона.
        # `//` набран светлым между жирными — он часть заголовка, а не перевода.
        k = 0
        head_parts = []
        while k < len(runs):
            tag, text = runs[k]
            if tag == '.' and text.strip() not in ('//', '/', ''):
                break
            head_parts.append(text)
            k += 1
        head = ''.join(head_parts)
        rest = runs[k:]

        if pending_letter:
            head = lower_ce(pending_letter) + head
            problems.append((code, i, 'буква приписана (проверить глазами)',
                             f'{pending_letter} + … = {head.strip()[:40]}'))
            pending_letter = None

        head, cls_head = strip_cls(head.strip())
        head, why = decap(head, prev_hw)
        if why:
            problems.append((code, i, why, head))
        head, first_no = strip_head_sense(head)
        head, hom = strip_homonym(head)
        stem, hw = split_stem(head)
        senses, xrefs = collect_body(rest, hw, stem, src_lang=src_lang,
                                     first_no=first_no)
        entries.append(mk_entry(hw, senses, xrefs, i, homonym=hom,
                                cls_sg=cls_head[:1], cls_pl=cls_head[1:2],
                                gram={'stem': stem} if stem else {}))
        prev_hw = hw
    return entries


# Чеченские буквы-диграфы. Нужны, чтобы отличить начало нового раздела от
# обычной прописной: «гуш» и «гӀа» начинаются с РАЗНЫХ букв, хотя первый символ
# у них один.
DIGRAPHS = ('аь', 'гӀ', 'кх', 'къ', 'кӀ', 'оь', 'пӀ', 'тӀ', 'уь',
            'хь', 'хӀ', 'цӀ', 'чӀ', 'юь', 'яь')


def first_letter(word):
    w = lower_ce(word or '')
    for d in DIGRAPHS:
        if w.startswith(d):
            return d
    return w[:1]


def decap(hw, prev):
    """Снять прописную ТОЛЬКО у первой статьи раздела.

    Прописная в этих словарях означает две разные вещи, и путать их нельзя:
    у первой статьи раздела в неё превращена буква раздела («Юкъ» = раздел «Ю»
    плюс «юкъ»), а в середине раздела это настоящее имя собственное —
    «Декартан», «Эрмитан кеп», «Паскалан этмаьӀиг». Отличаем по предыдущей
    статье: у первой статьи раздела буква другая.

    Отдельный случай — буква раздела не заменила первую литеру, а приписалась
    к ней: «Къ» + «къастор» = «Къкъастор». Тогда снимаем дубль.
    """
    if not hw:
        return hw, None
    low = lower_ce(hw[0]) + hw[1:]
    if low == hw:                      # палочка регистра не имеет
        return hw, None
    letter = first_letter(hw)
    if letter and lower_ce(hw)[len(letter):].startswith(letter):
        return lower_ce(hw)[len(letter):], 'снят дубль буквы раздела'
    if prev is not None and first_letter(prev) == letter:
        return hw, None                # середина раздела — имя собственное
    return low, 'снята прописная первой статьи раздела'


# --------------------------------------------------------------------------
# 7. Границы разделов
# --------------------------------------------------------------------------

def end_of_section(paras, start):
    """Словарь кончился там, где подряд идут абзацы не с жирного прогона.

    В конце книги 2017 года лежат подпись к картинке и список литературы —
    два десятка абзацев, которые иначе поехали бы в статьи.
    """
    streak = 0
    for i in range(start, len(paras)):
        runs = [r for r in paras[i] if r[1].strip()]
        if not runs:
            continue
        if runs[0][0].startswith('b'):
            streak = 0
            continue
        streak += 1
        if streak >= 2:
            return i - 1
    return len(paras)


def find_sections(paras, marks):
    """marks — список (подстрока, ключ). Возвращает номера абзацев-заголовков."""
    found = {}
    for i, runs in enumerate(paras):
        t = ''.join(x[1] for x in runs).strip().lower()
        if len(t) > 60:
            continue
        for needle, key in marks:
            if key not in found and needle in t:
                found[key] = i
    return found


# --------------------------------------------------------------------------
# 8. Проверки готового JSONL
# --------------------------------------------------------------------------

def iter_texts(e):
    yield 'headword', e['headword']
    for f in e['forms']:
        yield 'form', f['form']
    for s in e['senses']:
        for g in s['glosses']:
            yield 'gloss', g['text']
        for x in s['examples']:
            yield 'example.ce', x['ce']
            yield 'example.ru', x['ru']


def audit(code, entries, problems):
    seen = Counter()
    for e in entries:
        hw = e['headword']
        seen[hw] += 1
        if not hw:
            problems.append((code, e['src_ref'], 'пустой заголовок', ''))
        if len(hw) > 60:
            problems.append((code, e['src_ref'], 'подозрительно длинный заголовок', hw[:70]))
        if not any(s['glosses'] for s in e['senses']) and not e['xrefs']:
            problems.append((code, e['src_ref'], 'нет ни перевода, ни отсылки', hw))
        for s in e['senses']:
            for ex in s['examples']:
                if not ex['ce'] or not ex['ru']:
                    problems.append((code, e['src_ref'], 'половина словосочетания пуста',
                                     f"{hw}: {ex['ce']} / {ex['ru']}"))
        if 'I' in hw or 'І' in hw:
            problems.append((code, e['src_ref'], 'латиница в заголовке', hw))
        for s_ in e['senses']:
            for x in s_['examples']:
                ce = re.sub(r'\s+', '', x['ce'] or '')
                if hw and ce == re.sub(r'\s+', '', hw) * 2:
                    problems.append((code, e['src_ref'],
                                     'словосочетание = заголовок дважды: в книге '
                                     'часть его набрана светлым',
                                     f"{hw}: {x['ce']} / {x['ru']}"))
        for field, value in iter_texts(e):
            if _SEE.match(value) or re.search(r'(?<=[\s;])хь\s*\.', value):
                problems.append((code, e['src_ref'],
                                 'отсылка «хь.» внутри словосочетания, оставлена текстом',
                                 f'{hw}: {value[:50]}'))
            if '\x00' in value:
                problems.append((code, e['src_ref'], f'служебный маркер в {field}', value[:60]))
            if value != value.strip() or '  ' in value:
                problems.append((code, e['src_ref'], f'лишние пробелы в {field}', repr(value)[:60]))
    for hw, n in seen.items():
        if n > 1:
            problems.append((code, '-', f'заголовок повторяется {n} раз', hw))
    # омонимы: одинаковый заголовок разводим номерами, как у Мациева
    idx = Counter()
    for e in entries:
        hw = e['headword']
        if seen[hw] > 1 and e['homonym'] is None:
            idx[hw] += 1
            e['homonym'] = idx[hw]
            e['id'] = f'{hw}-{idx[hw]}'


def dump(path, entries):
    with open(path, 'w', encoding='utf-8') as f:
        for e in entries:
            f.write(json.dumps(e, ensure_ascii=False) + '\n')
    return len(entries)


def main(argv=None):
    ap = argparse.ArgumentParser(description='1997 и 2017 .odt -> JSONL')
    ap.add_argument('--math', required=True, help='.odt словаря математических терминов')
    ap.add_argument('--comp', required=True, help='.odt словаря компьютерной лексики')
    ap.add_argument('--out', default='work', help='куда класть JSONL')
    args = ap.parse_args(argv)
    os.makedirs(args.out, exist_ok=True)
    problems = []
    stats = {}

    # ---- 1997 -----------------------------------------------------------
    P = paragraphs(args.math)
    s = find_sections(P[80:], [('чеченско - русский', 'ce'), ('русско - чеченский', 'ru')])
    ce_start, ru_start = s['ce'] + 80, s['ru'] + 80
    # приложение «Юхедиллар» в конце чеченско-русского раздела — не статьи
    app = next((i for i in range(ce_start, ru_start)
                if ''.join(x[1] for x in P[i]).strip().lower().startswith('юхедиллар')),
               ru_start)
    stats['math1997_ce'] = dump(os.path.join(args.out, 'math1997_ce.jsonl'),
                                audit_and_return('math1997_ce',
                                                 parse_math_ce(P, ce_start + 1, app, problems),
                                                 problems))
    stats['math1997_ru'] = dump(os.path.join(args.out, 'math1997_ru.jsonl'),
                                audit_and_return('math1997_ru',
                                                 parse_simple(P, ru_start + 1, len(P),
                                                              'math1997_ru', 'ru', problems,
                                                              letters_eat=True),
                                                 problems))

    # ---- 2017 -----------------------------------------------------------
    C = paragraphs(args.comp)
    s = find_sections(C[100:], [('оьрсийн-нохчийн дошам', 'ru'),
                                ('нохчийн-оьрсийн дошам', 'ce')])
    ru_start, ce_start = s['ru'] + 100, s['ce'] + 100
    tail = end_of_section(C, ce_start + 5)
    stats['comp2017_ru'] = dump(os.path.join(args.out, 'comp2017_ru.jsonl'),
                                audit_and_return('comp2017_ru',
                                                 parse_simple(C, ru_start + 2, ce_start,
                                                              'comp2017_ru', 'ru', problems),
                                                 problems))
    stats['comp2017_ce'] = dump(os.path.join(args.out, 'comp2017_ce.jsonl'),
                                audit_and_return('comp2017_ce',
                                                 parse_simple(C, ce_start + 2, tail,
                                                              'comp2017_ce', 'ce', problems),
                                                 problems))

    with open(os.path.join(args.out, 'problems.tsv'), 'w', encoding='utf-8') as f:
        f.write('словарь\tабзац\tчто не так\tчто было\n')
        for row in problems:
            f.write('\t'.join(str(x) for x in row) + '\n')

    print()
    for k, v in stats.items():
        print(f'  {k:<14}{v:>6} статей')
    print(f'\n  problems.tsv  {len(problems)} строк')
    kinds = Counter(p[2] for p in problems)
    for kind, n in kinds.most_common(10):
        print(f'    {n:>5}  {kind}')
    return 0


def audit_and_return(code, entries, problems):
    audit(code, entries, problems)
    return entries


if __name__ == '__main__':
    sys.exit(main())

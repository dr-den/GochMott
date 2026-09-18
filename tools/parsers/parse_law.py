# -*- coding: utf-8 -*-
"""
Абдурашидов Э. Д. «Чеченско-русский, русско-чеченский словарь юридических
терминов» (Грозный, 2008) .odt -> JSONL.

    python tools/parse_law.py --odt work/law2008.odt --out work/

Даёт `law2008_ce.jsonl`, `law2008_ru.jsonl`, `problems_law.tsv`,
`joins_law.tsv`.

ЧЕМ ЭТА КНИГА УДОБНА

Автор сам описал разметку в разделе «Построение словаря», и книга её держит:

    Заглавное слово дается полужирным шрифтом. Перевод дается обычным
    шрифтом. Авторские пояснения — обычным курсивом. […] Термины
    иностранного происхождения […] отмечены в скобках полужирным курсивом.
    […] Внутри словарной статьи не меняющаяся часть заглавного слова
    заменяется знаком тильда (~).

То есть начертание несёт смысл, и колонка одна — никакой двухколоночной
вёрстки, как у Аслаханова. Отсюда разбор идёт по начертанию, а не по
приметам.

Половины размечены ПО-РАЗНОМУ, и это главная ловушка:

    чеч->рус   b  заглавное слово      bi  грамматика     i  пояснение
               .  русский перевод
    рус->чеч   b  русское слово        .   чеченский перевод
               i  класс и пояснение

Самая частая форма статьи — `b bi .` в первой половине (982 из 2037) и
`b . i` во второй (1394 из 2020).

ЧЕМ НЕУДОБНА: ТИЛЬДА В РУССКОЙ ПОЛОВИНЕ

В чеченской половине тильда заменяет слово целиком и подставляется без
размышлений: язык агглютинативный, `~ан барт` — это `авторан барт`.

В русской она стоит за ОСНОВУ: `неустойка` + `уплатить ~у` — это `уплатить
неустойку`, а `нечаянно` + `~ый выстрел` — `нечаянный выстрел`. Сколько букв
отрезать, видно только по списку настоящих словоформ, и список нужен двойной:

    --lexicon <разбор другой книги проекта>   можно повторять
    слова самой этой книги                    берутся всегда, см. `book_words`

Своё окончание подбирается тремя слоями (`fit_ending`, `as_separate_word`,
`by_rule`); последний — догадка по правилу, и он, как и нераскрытая тильда,
всегда оставляет строку в `problems_law.tsv`. Молча склеивать нельзя: так в
базу попадали `администрациярайона` и `покупателейое убийство`.
"""
import argparse, gzip, json, math, os, re, sys, unicodedata
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from parse_karasaev import paragraphs, clean, fix_homoglyphs

# --------------------------------------------------------------------------
# 1. Границы книги
# --------------------------------------------------------------------------

# Начало русско-чеченской половины и конец словарной части.
HALF_RU = re.compile(r'^РУССКО\s*-\s*ЧЕЧЕНСКИЙ')
STOP = re.compile(r'^Заключение\s+автора')

# Буквенная шапка раздела и мусорная строка-разделитель, оставшаяся от
# распознавания: «жжжжжжжжжжжж», «хххххххххх».
# Буквенная шапка. В чеченском алфавите есть двузнаковые буквы — «ГӀ», «КӀ»,
# «ТӀ», «ЦӀ», — и без второго знака они уходили в статьи.
LETTER = re.compile(r'^[А-ЯЁ]Ӏ?$|^Ӏ$')
JUNK = re.compile(r'^([А-Яа-яЁёӀ])\1{3,}$')

# Шапки приложений: сами по себе это проза, а не статьи. Статьи приложений
# идут следом и разбираются наравне с остальными.
HEADING = (
    'Юххедиллар',
    'Приложение',
    'Кхиэлехь дечу къамелийн',
    'Некоторые правовые принципы',
    'НОХЧИЙН', 'ӀЕДАЛЦА', 'ТЕРМИНИЙН',
    'РУССКО', 'ЮРИДИЧЕСКИХ',
)


def is_heading(text):
    return any(text.startswith(h) for h in HEADING)


def zones(paras):
    """Границы: (начало чеч->рус, начало рус->чеч, конец)."""
    ce = ru = end = None
    # Чеченская половина начинается с первой буквенной шапки: до неё только
    # титул и предисловие.
    for i, p in enumerate(paras):
        if LETTER.match(clean(''.join(x[1] for x in p)).strip()):
            ce = i
            break
    for i, p in enumerate(paras):
        t = clean(''.join(x[1] for x in p)).strip()
        if not t:
            continue
        # «РУССКО-ЧЕЧЕНСКИЙ» стоит и на титуле — берём то вхождение, что
        # ПОСЛЕ начала чеченской половины.
        if ru is None and ce is not None and i > ce and HALF_RU.match(t):
            ru = i
        if end is None and STOP.match(t):
            end = i
    return ce, ru, (end if end is not None else len(paras))


# --------------------------------------------------------------------------
# 2. Разметка: что означает начертание
# --------------------------------------------------------------------------

def col(tag):
    """'b' заглавное, 'bi' грамматика, 'i' пояснение, '.' перевод."""
    if tag == 'b':
        return 'head'
    if tag == 'bi':
        return 'gram'
    if tag == 'i':
        return 'note'
    return 'trans'


HAS_LETTER = re.compile(r'[А-Яа-яЁёӀ]')
HEAD_JUNK = re.compile(r'^[\s\t.,;:–—]+|[\s\t.,;:]+$')

# Классный показатель, набранный жирным вместе с заглавным словом: в книге
# так напечатано у трёх десятков статей («бакъо ю», «гоьмаш ю»). Отдельным
# словом из одной буквы чеченский заголовок кончаться не может, так что
# примета однозначная.
HEAD_CLS = re.compile(r'\s+((?:[вйюдб]\s*[,.]?\s*){1,3})$')
# Скобка, открытая в заголовке и закрытая уже в переводе, и прочий сор,
# оставшийся от распознавания: «алкоголизм (», «гражданаш [. ] б».
HEAD_TAIL = re.compile(r'[\s(\[«]+$|[\s)\]»]+$|\s*\[[^\]]*\]\s*'
                       r'|\s*\(\s*\)\s*')
# Непарная скобка по краям перевода.
TRANS_EDGE = re.compile(r'^[\s)\]»]+|[\s(\[«]+$|\s*\(\s*\)\s*')

# Класс существительного. Автор пишет то голую букву, то связку: «ю» — это
# й-класс («ю» = «есть» для й-класса), «в», «д», «б» — как есть.
CLASSES = {'в': 'в', 'ву': 'в', 'й': 'й', 'ю': 'й',
           'д': 'д', 'ду': 'д', 'б': 'б', 'бу': 'б'}

# Язык-источник, помечен в скобках полужирным курсивом.
ORIGIN = {
    'лат': 'латинский', 'фр': 'французский', 'гр': 'греческий',
    'ингал': 'английский', 'ингл': 'английский', 'англ': 'английский',
    'нем': 'немецкий', 'ит': 'итальянский', 'исп': 'испанский',
    'Ӏаьрб': 'арабский', 'араб': 'арабский', 'тюрк': 'тюркский',
}
ORIGIN_RE = re.compile(r'\(\s*(' + '|'.join(map(re.escape, ORIGIN)) + r')\.?\s*\)')

# «дукх.» — дукха, только множественное.
PLURAL_ONLY = re.compile(r'\bдукх\.?')

CASE_ORDER = ('gen', 'dat', 'erg', 'all')     # род., дат., эрг., местн.


# --------------------------------------------------------------------------
# 3. Грамматика чеченско-русской статьи
# --------------------------------------------------------------------------

def dehyphen(word, head):
    """Снять дефис ПЕРЕНОСА, оставив дефис СЛОВА.

    «авантю-ристана» и «бакъ-гӀиллакхаш» на вид одинаковы. Различает их
    заглавное слово: склейка удлиняет общий с ним префикс у первого
    («авантю» -> «авантюрист») и укорачивает у второго.
    """
    if '-' not in word:
        return word
    joined = word.replace('-', '')
    pref = lambda a, b: next((i for i, (x, y) in
                              enumerate(zip(a.lower(), b.lower())) if x != y),
                             min(len(a), len(b)))
    return joined if pref(joined, head) > pref(word, head) else word


VAR_WORD = re.compile(r'[А-Яа-яЁёӀ-]{2,}')
# Помета, а не слово: «уст.», «бус.», «лат.» — их в варианты пускать нельзя.
# Помета области: «бус.» — мусульманское право, «уст.» — устарелое. Она
# говорит, К ЧЕМУ относится слово, и в само слово не входит: искать будут
# «пурба далар», а не «бус.пурба далар».
LABEL_DROP = re.compile(r'^\s*(уст|бус|досл|прим)\b\s*[.,;]?\s*')
# Помета происхождения — другое дело. За «лат.», «фр.», «Ӏаьрб.» у автора
# идёт не второе название, а этимология и толкование («постфактум (лат хирг
# хилла даьлча дерг)»), и вариантом это не бывает. «дукх.» — тоже не вид, а
# грамматика: только множественное.
LABEL_STOP = re.compile(r'^\s*(' + '|'.join(map(re.escape, ORIGIN))
                        + r'|дукх)\b')


def strip_label(text):
    """Снять помету области: «бус.пурба далар» -> «пурба далар».

    Помета из одной себя («уст.») после этого станет пустой строкой и
    вариантом не станет. На помете происхождения возвращаем пусто: за ней
    идёт толкование, а не вид заглавного слова.
    """
    t = (text or '').strip()
    if LABEL_STOP.match(t):
        return ''
    return LABEL_DROP.sub('', t, count=1).strip(' ,.;')


def same_root(a, b, keep=5):
    """Два слова — виды одного и того же: общий корень, разные окончания."""
    if a == b:
        return True
    k = 0
    while k < min(len(a), len(b)) and a[k] == b[k]:
        k += 1
    return k >= keep and k >= min(len(a), len(b)) - 4


def note_parts(note):
    """Пояснение -> отдельные скобки.

    В одной статье их бывает две подряд, и поле `note` их склеивает:
    «ца хууш (ларамза) непреднамеренно (неумышленно, нечаянно)» даёт
    «ларамза) (неумышленно, нечаянно». Режем по закрывающей скобке —
    открывающая местами уезжает в соседнее поле, и опираться на неё нельзя.
    """
    out = []
    for part in (note or '').split(')'):
        p = clean(part).strip().lstrip('(').strip(' ,;')
        if p:
            out.append(p)
    return out


def split_var_cls(text):
    """Отделить классные показатели с хвоста: «гечдар д,д» -> «гечдар», [д,д]."""
    cls, rest = [], text.split()
    while rest:
        letters = [w for w in re.split(r'[,\s]+', rest[-1].strip(' ,.')) if w]
        if not letters or not all(w in CLASSES for w in letters):
            break
        cls = [CLASSES[w] for w in letters] + cls
        rest.pop()
    return ' '.join(rest).strip(' ,.'), cls


def looks_paradigm(text, head):
    """«даредаран, даредарна, даредаро, даредаре» — это парадигма, не вид.

    У статей с потерянным начертанием падежные формы попадают в тот же
    курсив, что и пояснение. Отличить просто: список через запятую, где
    каждое слово — тот же корень, что у заглавного.
    """
    parts = [w.strip() for w in re.split(r'[;,]', text)]
    if len(parts) < 2:
        return False
    # «мани-пуляцина» — дефис переноса; сравнивать корни надо без него.
    hv = [w.replace('-', '') for w in VAR_WORD.findall((head or '').lower())]
    return bool(hv) and all(
        any(same_root(x.replace('-', ''), y) for y in hv) for p in parts
        for x in VAR_WORD.findall(p.lower()))


HEAD_PAREN = re.compile(r'\s*\(([^()]+)\)')


def head_paren_variants(head, ru_half=False, score=None, limit=5):
    """Скобка ВНУТРИ заглавного слова: «государственный (правовой) язык».

    Здесь, в отличие от скобки после заглавного слова, видно и что заменять:
    слово стоит прямо перед скобкой. «чекхдалаза (чекхдаккхаза) лехам-дов» —
    это статья «чекхдалаза лехам-дов» и её вид «чекхдаккхаза лехам-дов»;
    сколько слов в скобке, столько и заменяется слева. Через запятую бывает
    несколько видов сразу. «исполняющий (-щая) обязанности» — обычная
    словарная запись окончания: дефис значит «хвост слова отсюда».

    Скобка из заглавного слова уходит в любом случае: именем статьи она не
    бывает. Если внутри не вид, а помета, определение или чужой язык, её
    содержимое становится пояснением.

    Возвращает (заглавное слово, виды, что отдать в пояснение).
    """
    m = HEAD_PAREN.search(head or '')
    if not m:
        return head, [], None
    inner = m.group(1).strip()
    before, after = head[:m.start()].rstrip(), head[m.end():].lstrip()
    # «условия (гарантии), обеспечивающие…» — за скобкой запятая, и пробел
    # перед ней не нужен.
    glue = (lambda a, b: (a + b if b[:1] in ',;.:' else a + ' ' + b) if b else a)
    base = glue(before, after).strip() or head
    text, cls = split_var_cls(inner)
    if not text or not VAR_WORD.search(text):
        return base, [], inner or None
    # Вид заглавного слова написан на том же языке, что и оно само.
    stripped = strip_label(text)
    wrong_lang = score is not None and (score(text) > 0) == bool(ru_half)
    if not stripped or len(VAR_WORD.findall(stripped)) > limit or wrong_lang:
        return base, [], inner
    text = stripped
    sg, pl = split_plural_cls(cls)
    out = []
    for part in (w.strip(' ,;') for w in text.split(',')):
        if not part:
            continue
        if part.startswith('-'):
            tail = part.lstrip('-').strip()
            w = before.split()
            if not w or not tail:
                continue
            cut = w[-1].rfind(tail[0])
            if cut <= 0:
                continue
            w[-1] = w[-1][:cut] + tail
            form = ' '.join(w)
        else:
            k = len(part.split())
            w = before.split()
            if len(w) < k:
                continue
            form = ' '.join(w[:len(w) - k] + part.split())
        # Хвостовой класс заглавного слова (`… лехам-дов д`) достаётся и
        # виду: чистим форму тем же `clean_head`, что и саму статью.
        form, fcls = clean_head(glue(form, after).strip())
        if not form or form == base:
            continue
        v = {'form': form}
        vsg, vpl = (sg, pl) if (sg or pl) else split_plural_cls(fcls)
        if vsg:
            v['cls'] = vsg
        if vpl:
            v['cls_pl'] = vpl
        out.append(v)
    return base, out, (None if out else inner)


def dedup_variants(variants):
    """Один и тот же вид мог прийти двумя путями — оставляем по одному."""
    out, seen = [], set()
    for v in variants:
        v = v if isinstance(v, dict) else {'form': v}
        key = (v.get('form') or '').strip().lower()
        if not key or key in seen:
            continue
        seen.add(key)
        out.append(v)
    return out


def cut_in(head, form, cut):
    """Вид, стоявший В СЕРЕДИНЕ заглавного слова, заменяет слова перед собой.

    «аьтто боцучу (бегӀийла доцучу) хьоле хӀоттор» — вид не «бегӀийла
    доцучу», а «бегӀийла доцучу хьоле хӀоттор»: скобка стоит на втором
    слове, значит два слова перед ней и заменяет. Скобка в конце заглавного
    слова так не разбирается: там непонятно, заменяет она хвост или всё
    словосочетание, и такие строки уходят в `review`.
    """
    w, v = (head or '').split(), (form or '').split()
    if cut is None or not w or not v or cut >= len(w) or len(v) > cut:
        return form
    return ' '.join(w[:cut - len(v)] + v + w[cut:])


def head_variant(part, score, head='', limit=5):
    """Скобка в чеченско-русской статье: другой вид заглавного слова?

    Системы в скобках у автора нет — в них и синоним заглавного слова
    («амнисти (гечдар д,д)»), и пояснение перевода («айкх (тайный,
    секретный)»), и помета, и целое определение. Но одна примета есть, и
    она механическая: ЯЗЫК. Чеченское в скобке относится к чеченской
    половине статьи, то есть к заглавному слову, русское — к переводу.
    Язык узнаёт та же триграммная модель, что разбирает абзацы с потерянным
    начертанием.

    Второе условие — длина. Вариант это строка, по которой статью будут
    искать; определение на десять слов («возуш хила ца хууш, ца лууш, таро
    йоцург») строкой поиска не бывает и остаётся пояснением.
    """
    text, cls = split_var_cls(part)
    text = strip_label(text)
    if not text or not VAR_WORD.search(text):
        return None
    if len(VAR_WORD.findall(text)) > limit or score is None or score(text) <= 0:
        return None
    if looks_paradigm(text, head):
        return None
    sg, pl = split_plural_cls(cls)
    out = []
    for piece in (strip_label(w) for w in text.split(';')):
        if not piece or not VAR_WORD.search(piece):
            continue
        v = {'form': piece}
        if sg:
            v['cls'] = sg
        if pl:
            v['cls_pl'] = pl
        out.append(v)
    return out or None


def split_plural_cls(cls_sg, cls_pl=()):
    """«в, й, б» у названия лица — мужской и женский, а «б» уже множественное.

    Автор пишет их одной связкой, но это не три класса единственного числа:
    одно и то же слово не бывает разом в-класса и б-класса. В обратной
    половине книги то же самое набрано раздельно («в, ю; авантюристаш б»),
    так что деление проверяемое. Одинокая «б» — настоящий класс б, её
    трогать нельзя.
    """
    cls_pl = list(cls_pl)
    cls_sg = list(cls_sg)
    if len(cls_sg) > 1 and cls_sg[-1] == 'б' and not cls_pl:
        cls_pl = [cls_sg.pop()]
    return cls_sg, cls_pl


def parse_gram(text, head):
    """«(фр.) ю, авантюраш ю; авантюрин, авантюрина, авантюро, авантюре».

    Возвращает `gram` для перевода: язык-источник, классы единственного и
    множественного числа, форму множественного и падежную парадигму.
    """
    gram, rest = {}, clean(text or '')

    m = ORIGIN_RE.search(rest)
    if m:
        gram['origin'] = ORIGIN[m.group(1)]
        rest = rest[:m.start()] + ' ' + rest[m.end():]
    if PLURAL_ONLY.search(rest):
        gram['number'] = 'pl'
        rest = PLURAL_ONLY.sub(' ', rest)
    rest = rest.replace('(', ' ').replace(')', ' ')

    cls_sg, cls_pl, plural, forms = [], [], None, []
    for chunk in re.split(r'[;]', rest):
        # Запятая между формами местами набрана точкой: «адамаллина. адамалло».
        chunk = re.sub(r'\.\s+(?=[А-Яа-яЁёӀ])', ', ', chunk)
        items = [w.strip(' .') for w in chunk.split(',')]
        items = [w for w in items if w]
        if not items:
            continue
        # Голые буквы класса: «в, ю» — мужской и женский у названия лица.
        letters = [CLASSES[w] for w in items if w in CLASSES]
        words = [w for w in items if w not in CLASSES and HAS_LETTER.search(w)]

        if letters and not words:
            (cls_pl if plural else cls_sg).extend(letters)
            continue
        # «авантюраш ю» — форма множественного и её класс.
        if len(words) == 1 and not forms:
            w = words[0].split()
            tail = [CLASSES[t] for t in w[1:] if t in CLASSES]
            if tail or w[0].endswith(('ш', 'й', 'и')):
                plural = dehyphen(w[0], head)
                cls_pl.extend(tail or letters)
                continue
        # Список из нескольких форм — падежная парадигма.
        if len(words) >= 2:
            for n, w in enumerate(words[:len(CASE_ORDER)]):
                # `lang` обязателен: парадигма здесь стоит при ЧЕЧЕНСКОМ
                # заглавном слове, а перевод у статьи русский, и сборщик
                # иначе заведёт форму в русский индекс.
                forms.append({'form': dehyphen(w.split()[0], head),
                              'case': CASE_ORDER[n], 'num': 'sg', 'lang': 'ce'})
            continue
        if letters:
            (cls_pl if plural else cls_sg).extend(letters)

    cls_sg, cls_pl = split_plural_cls(list(dict.fromkeys(cls_sg)),
                                      list(dict.fromkeys(cls_pl)))
    if cls_sg:
        gram['cls'] = cls_sg
    if cls_pl:
        gram['cls_pl'] = cls_pl
    if plural:
        forms.insert(0, {'form': plural, 'num': 'pl', 'lang': 'ce'})
    if forms:
        gram['forms'] = forms
    return gram


# --------------------------------------------------------------------------
# 4. Тильда
# --------------------------------------------------------------------------

TILDE = re.compile(r'[~˜∼]')


RU_WORD = re.compile(r'[а-яё-]{2,}')


def fold_ru(s):
    """Ключ русского слова: без ударений, строчными, ё как е."""
    s = unicodedata.normalize('NFD', s or '')
    s = ''.join(c for c in s if not unicodedata.combining(c))
    return s.lower().replace('ё', 'е')


GLUED_TILDE = re.compile(r'[~˜∼]([А-Яа-яЁё]+)')
SPACED_TILDE = re.compile(r'[~˜∼]\s+([А-Яа-яЁё]{1,5})\b')
FIRST_WORD = re.compile(r'[А-Яа-яЁё][А-Яа-яЁё-]*')


def load_lexicon(paths):
    """Русские словоформы из УЖЕ разобранных книг.

    Нужны, чтобы понять, сколько букв заглавного слова заменяет тильда:
    «неустойка» + «~у» — это «неустойку», а «нечаянно» + «~ый» — «нечаянный».
    Отгадать это правилом нельзя, нужен список настоящих словоформ, и брать
    его нужно СО СТОРОНЫ — из других словарей проекта. Свой собственный
    разбор в список класть нельзя: он занесёт туда собственные же склейки
    («неустойкау») и подтвердит ошибку.

    Длинные слова, встреченные ровно один раз, отбрасываются: такие почти
    всегда чужие склейки, а настоящее редкое слово короче.
    """
    bag = Counter()
    for path in paths or []:
        opener = gzip.open if str(path).endswith('.gz') else open
        with opener(path, 'rt', encoding='utf-8') as f:
            for line in f:
                if not line.strip():
                    continue
                e = json.loads(line)
                texts = [e.get('headword')]
                for s in e.get('senses') or []:
                    for g in s.get('glosses') or []:
                        texts += [g.get('text'), g.get('note')]
                    for x in s.get('examples') or []:
                        texts.append(x.get('ru'))
                for t in texts:
                    bag.update(RU_WORD.findall(fold_ru(t or '')))
    return {w for w, n in bag.items() if n > 1 or len(w) <= 9}


def book_words(paras, ce_at, ru_at, end):
    """Русские словоформы, набранные в САМОЙ книге полностью.

    Книга юридическая, и общие словари проекта её лексику закрывают плохо:
    «преступности», «расследования», «судебное» там просто не встречаются.
    Зато сама книга пишет эти слова целиком — в других статьях, где тильда
    не понадобилась. Всё, что приросло к тильде, из списка выбрасывается:
    подтверждать склейку её же копией нельзя, ради этого и был запрет на
    собственный разбор в `load_lexicon`. Сырой текст книги под запрет не
    подпадает — склеек, кроме опечаток набора, в нём нет.

    Берём только прогоны, русские ПО НАЧЕРТАНИЮ: светлые в чеченской
    половине и полужирные в обратной. Иначе в список русских слов попадёт
    половина чеченского словаря — буквы те же, — и «бехказаллин» сойдёт за
    русскую словоформу.
    """
    bag = set()
    for i in range(ce_at, end):
        rh = i >= ru_at
        for tag, text in paras[i]:
            if tag != ('b' if rh else '.'):
                continue
            t = clean(text)
            t = TILDE.sub(' ', GLUED_TILDE.sub(' ', t))
            bag.update(w for w in RU_WORD.findall(fold_ru(t)) if len(w) > 2)
    return bag


class Lexicon(set):
    """Словоформы для подбора окончаний: чужие книги плюс сама книга.

    Разделение важно для `near_miss`: опечатка набора («дисцинлина») в текст
    книги, конечно, входит, и по общему списку она выглядит настоящим словом.
    Проверять заглавное слово надо по `outside` — по книгам, где опечатки
    этой книги взяться не могли.
    """

    def __init__(self, outside=(), inside=()):
        super().__init__()
        self.outside = set(outside)
        self.update(self.outside)
        self.update(inside)


def head_bases(head):
    """Части заглавного слова, к которым может относиться тильда.

    Обычно это всё заглавное слово, но оно бывает составным и с вариантами
    через запятую, и тогда окончание наращивает первое слово: «следственная
    тактика ~ый эксперимент» — это «следственный эксперимент», а не
    «следственная тактикый».
    """
    out, seen = [], set()
    first = head.split(',')[0].strip()
    word = FIRST_WORD.match(first)
    for cand in (head, first, word.group(0) if word else ''):
        cand = cand.strip(' ,;:()')
        if cand and cand not in seen:
            seen.add(cand)
            out.append(cand)
    return out


def fit_ending(head, frag, lexicon, maxcut=6):
    """«неустойка» + «у» -> «неустойку»: где кончается неизменяемая часть.

    Перебираем, сколько букв заглавного слова оставить, от всех до
    `maxcut` меньше, и берём первое, что даёт известное слово. Начинаем с
    самого длинного: чем меньше отрезано, тем осторожнее догадка.
    """
    if not lexicon:
        return None
    first, sep, rest = frag.partition(' ')
    for base in head_bases(head):
        for k in range(len(base), max(0, len(base) - maxcut) - 1, -1):
            cand = base[:k] + first
            if fold_ru(cand) in lexicon:
                return cand + sep + rest
    return None


def as_separate_word(head, frag, lexicon):
    """«администрация ~района»: конвертер съел пробел, а не окончание.

    Отличить можно по тому, что приросшее — само по себе слово, а не
    окончание: «района» в словаре есть, «ы» и «ое» там быть не может.
    """
    if not lexicon or len(frag) < 4 or fold_ru(frag) not in lexicon:
        return None
    return head + ' ' + frag


RU_TAIL = re.compile(r'(ый|ий|ой|ая|яя|ое|ее|ые|ие|ь|й|[аяоеуюыи])$')


def by_rule(head, frag):
    """Запасной разбор без словаря: окончание вытесняет окончание.

    Русский язык флективный: «неустойка» + «~у» — это «неустойк|у», новое
    окончание встаёт на место именительного. Правило работает для окончаний
    (до трёх букв) и молчаливо врёт на словообразовании («суд» + «~ебное»)
    и на съеденном пробеле, поэтому вызывается последним и всегда идёт в
    `problems`: догадка, а не разбор.
    """
    base = head_bases(head)[-1]
    return (RU_TAIL.sub('', base) or base) + frag


ALPHA_RU = 'абвгдежзийклмнопрстуфхцчшщъыьэюя'


def near_miss(word, lexicon):
    """Заглавное слово с одной опечаткой: «дисцинлина» -> «дисциплина».

    Не для того, чтобы исправить книгу — правка остаётся за человеком, — а
    чтобы объяснить, почему у статьи не раскрылась ни одна тильда: не в
    парсере дело, а в наборе.
    """
    known = getattr(lexicon, 'outside', lexicon)
    w = fold_ru(word)
    if not known or len(w) < 5 or not w.isalpha() or w in known:
        return None
    found = set()
    for i in range(len(w)):
        for c in ALPHA_RU:
            if c != w[i] and w[:i] + c + w[i + 1:] in known:
                found.add(w[:i] + c + w[i + 1:])
    return '/'.join(sorted(found)[:2]) if found else None


GLUED_TILDE_CE = re.compile(r'[~˜∼]([А-Яа-яЁёӀӏ]+)')
CE_VOWELS = set('аеёиоуыэюя')


def merge_ce(head, frag):
    """Прирастить окончание к чеченскому слову.

    Язык агглютинативный, и окончание обычно просто дописывается: «лехам» +
    «~ан» — «лехаман», «квалификаци» + «~н» — «квалификацин». Но два гласных
    подряд в слове не стоят, и если основа кончается гласным, а окончание
    гласным начинается, гласный основы вытесняется: «жоьпалла» + «~ин» —
    «жоьпаллин», «хьукмате» + «~ин» — «хьукматин».

    Двубуквенные гласные «аь», «оь», «уь» в конце заглавного слова в этой
    книге не встречаются, и что с ними делать — не проверено; они попадают
    под общий случай и просто дописываются.
    """
    if head and frag and head[-1].lower() in CE_VOWELS \
            and frag[0].lower() in CE_VOWELS:
        return head[:-1] + frag
    return head + frag


def ce_stem(forms, minlen=3):
    """Основа по парадигме, которую книга печатает сразу за заглавным словом.

    «бакъо» с виду кончается гласным, но парадигма (`бакъонаш, бакъонан,
    бакъонна, бакъоно, бакъоне`) показывает основу «бакъон». Одной форме
    разрешено выпасть из общей приставки: набор не безошибочен («бокъонан»
    вместо «бакъонан»), и портить из-за одной буквы всю проверку нельзя.
    """
    forms = [f for f in forms if f]
    if len(forms) < 2:
        return ''
    need = len(forms) - 1
    for k in range(min(len(f) for f in forms), minlen - 1, -1):
        pre, n = Counter(f[:k] for f in forms).most_common(1)[0]
        if n >= need:
            return pre
    return ''


def expand_tilde(text, head, forms=None, weak=None):
    """«~ан барт» -> «авторан барт», «дегӀах хьажаран ~» -> «… тоьшалла».

    Правило автора: тильда заменяет НЕМЕНЯЮЩУЮСЯ часть заглавного слова и
    стоит в любом месте словосочетания. Целая тильда — это всё заглавное
    слово; приросшая к окончанию наращивает его по `merge_ce`.

    Там, где решение неочевидно — окончание короткое или начинается гласным,
    — результат сверяется с парадигмой из этой же статьи. Расхождение значит,
    что заглавное слово набрано с изъяном («гӀиллак» при парадигме
    «гӀиллакхаш») или что автор написал окончание от другой основы («бакъо» +
    «~ийн», а в парадигме «бакъон»); тогда основу берём из парадигмы, а
    статья уходит в `problems` — правило и книга разошлись, это смотрят
    глазами.
    """
    if not TILDE.search(text or ''):
        return text
    forms = [f for f in (forms or []) if f]
    stem = ce_stem(forms)

    def one(m):
        frag = m.group(1)
        cand = merge_ce(head, frag)
        if stem and (len(frag) <= 3 or frag[0].lower() in CE_VOWELS):
            if not cand.startswith(stem) \
                    and not any(cand.startswith(f) for f in forms):
                alt = merge_ce(stem, frag)
                if weak is not None:
                    weak.append((frag, cand, alt))
                return alt
        return cand

    return TILDE.sub(head, GLUED_TILDE_CE.sub(one, text))


def expand_tilde_ru(text, head, lexicon, weak=None):
    """То же для русской половины, где тильда стоит за ОСНОВУ, а не за слово.

    «неустойка» + «уплатить ~у» — это «уплатить неустойку», а не
    «неустойкау»; «нечаянно» + «~ый выстрел» — «нечаянный выстрел». Сколько
    букв отрезать, видно по списку настоящих словоформ (см. `load_lexicon` и
    `book_words`) — это разбор. Не нашлось в списке — пробуем правило
    `by_rule`, но это уже догадка, и она копится в `weak`, чтобы уйти в
    `problems`. Не вышло и правилом — тильду оставляем как есть: молча
    склеить «~района» с заглавным словом значило бы выдать
    «администрациярайона» за настоящую запись.
    """
    if not TILDE.search(text or ''):
        return text

    def one(m):
        frag = m.group(1)
        got = (fit_ending(head, frag, lexicon)
               or as_separate_word(head, frag, lexicon))
        if got:
            return got
        if len(frag) <= 3:
            got = by_rule(head, frag)
            if weak is not None:
                weak.append((frag, got))
            return got
        return m.group(0)

    out = GLUED_TILDE.sub(one, text)
    # «непоследовательность ~  ный»: конвертер разорвал тильду и окончание
    # пробелом. Склеиваем ТОЛЬКО если получилось слово из словаря — иначе
    # «допрос ~ свидетелей» превратилось бы в «допроссвидетелей».
    out = SPACED_TILDE.sub(
        lambda m: fit_ending(head, m.group(1), lexicon) or m.group(0), out)
    # Целую тильду (за всё слово) подставляем как обычно, а приросшую к
    # окончанию — только если её удалось разобрать выше.
    return re.sub(r'[~˜∼](?![А-Яа-яЁё])', head, out)


# --------------------------------------------------------------------------
# 5. Разбор абзаца на «куски»
# --------------------------------------------------------------------------

NUM_ONLY = re.compile(r'^\s*[1-9]\s*[.\-)]\s*$')
# Прогон из одних классных показателей: «ю», «в, ю, б», «д».
BARE_CLS = re.compile(r'^[\s(]*(?:[вйюдб]\s*[,.]?\s*){1,3}[\s)]*$')
# Классный показатель, к которому прилипла граница вариантов: «д;», «ю;».
CLS_SEMI = re.compile(r'^[\s(]*[вйюдб][\s,.вйюдб]*;\s*$')
# Служебная метка границы вариантов внутри пояснения и грамматики.
SEG = '\x00'
TILDE_TAIL = re.compile(r'[~˜∼]\s*$')

FIELDS = ('head', 'gram', 'note', 'trans')


def regroup(runs):
    """Вернуть одиночной букве начертание соседей.

    Конвертер местами терял курсив на одном-двух знаках, и посреди
    авторского пояснения оказывался светлый кусок: «(оьг» `[. 'Ӏа']`
    «заллин шовкъ)». Палочка уезжала в перевод, а пояснение оставалось
    без буквы. Примета узкая: кусок короче трёх знаков, без пробелов, а
    слева и справа от него — одно и то же чужое начертание.
    """
    out = [list(r) for r in runs]
    idx = [i for i, r in enumerate(out) if r[1].strip()]
    for n in range(1, len(idx) - 1):
        i, before, after = idx[n], idx[n - 1], idx[n + 1]
        text = out[i][1]
        if len(text.strip()) > 2 or ' ' in text.strip() or '\t' in text:
            continue
        if not HAS_LETTER.search(text):
            continue
        left, right = col(out[before][0]), col(out[after][0])
        if left == right and left != col(out[i][0]):
            out[i][0] = out[before][0]
    return out


def presplit(runs):
    """Отделить тильду, прилипшую к хвосту пояснения.

    «~» автор набирает как придётся, и сплошь и рядом она оказывается
    последним знаком курсивного куска: `['i', ' ю; ~'], ['b', 'ское право']`.
    Тильда принадлежит следующему словосочетанию, а не пояснению.
    """
    out = []
    for tag, text in runs:
        m = TILDE_TAIL.search(text or '')
        if m and tag != 'b' and text[:m.start()].strip():
            out.append([tag, text[:m.start()]])
            out.append(['b', text[m.start():]])
        else:
            out.append([tag, text])
    return out


def add(cur, field, text, gap):
    """Приписать кусок, не потеряв пробел, набранный чужим начертанием.

    `['.', 'протокол'], ['i', ' (бакъонийн) '], ['.', 'допроса']` — пробел
    между русскими словами лежит в курсивной вставке. Склеив светлое встык,
    получим «протоколдопроса».
    """
    if (cur and gap and (gap[:1].isspace() or gap[-1:].isspace())
            and not cur[-1:].isspace() and not text[:1].isspace()):
        cur += ' '
    return cur + text


def items(runs, ru_half, score=None):
    """Абзац -> [{head, gram, note, trans}].

    Первый кусок — сама статья, следующие — словосочетания с тильдой.
    Граница куска — жирный прогон ПОСЛЕ того, как перевод уже начался.
    """
    out, cur, seen_trans, seen_gram = [], None, False, False
    last = 'head'
    gap = dict.fromkeys(FIELDS, '')
    # Курсив, попавший в СЕРЕДИНУ заглавного слова: «аьтто боцучу» +
    # [i]«(бегӀийла доцучу)» + «хьоле хӀоттор». Запоминаем, на каком слове
    # он стоял: если заглавное слово после него продолжится, скобка
    # заменяет слова перед собой, а не всё слово целиком.
    mark = [None]

    def flush():
        nonlocal cur, seen_trans, seen_gram, last, gap
        if cur and mark[0] is not None and mark[0] < len(cur['head'].split()):
            cur['head_cut'] = mark[0]
        if cur and (cur['head'].strip() or cur['trans'].strip()):
            out.append(cur)
        cur, seen_trans, seen_gram, last = None, False, False, 'head'
        gap = dict.fromkeys(FIELDS, '')
        mark[0] = None

    # presplit ПЕРЕД regroup: иначе «нилхадоцу; ~» + `[b 'о']` —
    # тильда ещё сидит в светлом прогоне, «о» оказывается между двумя
    # светлыми и уезжает к ним, а должно прирасти к тильде.
    for tag, text in regroup(presplit(runs)):
        if not text:
            continue
        kind = col(tag)
        # Знак препинания сам по себе принадлежит той половине, которая
        # сейчас набирается, а не своему начертанию. Жирная запятая между
        # двумя чеченскими переводами («хьийзор` [b ', '] `нуьцкъала лелор»)
        # и светлая запятая внутри русского заголовка («долг, кредит,
        # обязанность` [. ','] `обязательство») — один и тот же сор.
        # Точка с запятой из этого правила исключена: она не украшение, а
        # граница между вариантами перевода, и место ей в переводе —
        # «шекъхилар` [i 'д'] [. '; '] `дегабаам».
        if (text.strip() and not HAS_LETTER.search(text)
                and not TILDE.search(text) and not re.search(r'[\d;]', text)):
            kind = last
        # «банда гӀера` [i 'ю;'] `талоран гӀера» — тот же разделитель, но
        # слипшийся с классным показателем. Класс остаётся грамматикой,
        # точка с запятой уходит в перевод.
        elif (kind in ('gram', 'note') and seen_trans and CLS_SEMI.match(text)
                and cur is not None):
            cur['gram' if not ru_half else 'note'] += text.replace(';', ' ')
            text, kind = ';', 'trans'
        if kind == 'trans' and ';' in text and cur is not None:
            # Граница вариантов делит не только перевод, но и пояснение с
            # классом: у «подозрение» своя скобка и свой класс у каждого из
            # двух чеченских слов.
            cur['note'] += SEG
            cur['gram'] += SEG
        # Тильда — часть словосочетания, каким бы начертанием ни набрана.
        elif kind != 'head' and TILDE.search(text) and not HAS_LETTER.search(text):
            kind = 'head'
        # Номер значения тоже гуляет по начертаниям: «1.» бывает курсивом.
        elif NUM_ONLY.match(text):
            kind = 'trans'
        elif ru_half and kind == 'gram':
            kind = 'head' if TILDE.search(text) else 'note'
        # Жирным набран один классный показатель — это грамматика, а не
        # заглавное слово: «арбитраж арбитраж ю (фирмийн лехамашца…)».
        elif kind == 'head' and BARE_CLS.match(text):
            kind = 'gram' if not ru_half else 'note'
        # В чеченско-русской половине перевод стоит ПОСЛЕ грамматики, и если
        # он набран жирным, начертание просто потеряно: «аьтто б; аьттонаш
        # б; … удача». Второго заглавного слова в статье быть не может.
        elif (kind == 'head' and not ru_half and seen_gram and not seen_trans
                and HAS_LETTER.search(text)):
            # Русское — значит и вправду перевод. Чеченское жирным после
            # грамматики переводом быть не может: это либо продолжение
            # заглавного слова («… билламаш б (юкъархиларш д)»), либо
            # падежная парадигма, у которой потерялось начертание
            # («баллистика баллистикин, баллистикина, …»).
            if score and score(clean(text)) > 0:
                kind = ('gram' if looks_paradigm(clean(text),
                                                 cur['head'] if cur else '')
                        else 'head')
            else:
                kind = 'trans'
        # «кхиэлъен ~ ю зал заседаний суда» — тильда набрана вместе с
        # переводом, а принадлежит словосочетанию слева от неё.
        if kind == 'trans' and cur and not seen_trans:
            m = re.match(r'\s*[~˜∼]\s*', text)
            if m:
                cur['head'] = add(cur['head'], 'head', '~', gap['head'])
                text = text[m.end():]
                if not text:
                    continue
        # Границу куска открывает только жирное СЛОВО. Жирная запятая
        # («хьийзор» `[b ', ']` «нуьцкъала лелор») — типографский сор, а не
        # новое словосочетание.
        if (kind == 'head' and seen_trans
                and (HAS_LETTER.search(text) or TILDE.search(text))):
            flush()
        # Класс внутри скобки, открытой в заглавном слове, — класс того, что
        # в скобке, а не статьи: «… билламаш б (юкъархиларш д)». Шире этого
        # правило делать нельзя: скобку в заглавном слове автор часто
        # закрывает уже в другом поле, и всё до конца статьи уехало бы в
        # заголовок.
        if (cur is not None and kind in ('gram', 'note') and not seen_trans
                and BARE_CLS.match(text)
                and cur['head'].count('(') > cur['head'].count(')')):
            kind = 'head'
        if cur is None:
            cur = dict.fromkeys(FIELDS, '')
            cur['head_cut'] = None
        if (kind == 'note' and not seen_trans and mark[0] is None
                and cur['head'].strip() and HAS_LETTER.search(text)):
            mark[0] = len(cur['head'].split())
        cur[kind] = add(cur[kind], kind, text, gap[kind])
        for other in FIELDS:
            gap[other] = '' if other == kind else gap[other] + text
        # И «перевод начался» тоже считается по буквам: светлая запятая в
        # «долг, кредит, обязанность` [. ','] `обязательство» переводом не
        # является.
        if HAS_LETTER.search(text):
            last = kind
        if kind == 'trans' and HAS_LETTER.search(text):
            seen_trans = True
        elif kind == 'gram' and text.strip():
            seen_gram = True
    flush()
    return [splice_paren(c) for c in out]


# Скобка открылась в поле, а её содержимое набрано курсивом и потому
# уехало в пояснение: «человечное (» + [i]«цивилизованное)» + «право».
# Курсив здесь не пояснение, а синоним ВНУТРИ словосочетания, и место
# ему на месте скобки.
OPEN_PAREN = re.compile(r'\([^()]*$')


def splice_paren(chunk):
    """Вернуть содержимое скобки туда, где скобка открылась.

    Условий много, и все нужны: скобка открыта в поле и не закрыта;
    текст есть с обеих сторон от неё («( … ) воинские преступления» —
    настоящее пояснение перед переводом, его трогать нельзя); пояснение
    эту скобку закрывает; внутри не начинается ещё одна; и вставляемое
    коротко — синоним в два-три слова, а не фраза. Длинное пояснение
    внутри скобок («досл. „При желании…“») стоит в книге ПОСЛЕ текста, а
    не на месте скобки, и вставлять его туда значит переставить слова.
    """
    note = (chunk.get('note') or '')
    if SEG in note or ')' not in note:
        return chunk
    inner, _, rest = note.partition(')')
    inner = inner.strip(' ,;')
    if '(' in inner or not HAS_LETTER.search(inner) or len(inner.split()) > 4:
        return chunk
    for field in ('head', 'trans'):
        text = chunk.get(field) or ''
        m = OPEN_PAREN.search(text)
        if not m:
            continue
        before, after = text[:m.start()], text[m.start() + 1:]
        if not HAS_LETTER.search(before) or not HAS_LETTER.search(after):
            continue
        if before and not before[-1].isspace():
            before += ' '
        spliced = re.sub(r'\s+([,;.)])', r'\1',
                         re.sub(r'\(\s+', '(', f'{before}({inner}) {after}'))
        return dict(chunk, note=rest, **{field: spliced})
    return chunk


# «задержанный (~ая)» — женская пара заглавного слова. Тильда здесь стоит не
# за всё слово, а за его неизменяемую часть: «задержанн» + «ая». Мест таких в
# книге ровно два, и оба этой формы — прилагательное-причастие на -ый/-ий/-ой
# и окончание в скобке.
GENDER_PAIR = re.compile(
    r'^(.*?[А-Яа-яЁёӀ]{2,}?)(ый|ий|ой)\s*\(\s*[~˜∼]\s*([а-яё]{1,3})\s*\)?\s*$',
    re.I)


def gender_pair(text):
    """«задержанный (~ая)» -> ('задержанный', ['задержанная'])."""
    m = GENDER_PAIR.match(text or '')
    if not m:
        return text, []
    stem, tail, other = m.group(1), m.group(2), m.group(3)
    return stem + tail, [stem + other]


def promote_note(note):
    """Курсив, поднятый в перевод -> (перевод, остаток пояснения).

    Скобка впереди — всё-таки пояснение: «(исполнитель судебного решения)
    чалтач» переводится словом «чалтач», а скобка остаётся при нём.
    """
    t = clean(note or '').strip()
    kept = []
    while True:
        m = re.match(r'^\s*\([^)]*\)\s*', t)
        if not m:
            break
        kept.append(m.group(0).strip())
        t = t[m.end():]
    return HEAD_JUNK.sub('', t), ('; '.join(kept) or None)


def clean_head(text):
    """Заглавное слово -> (слово, классы, снятые с его хвоста).

    Класс автор местами набрал жирным заодно с самим словом, и без этого
    «бакъо ю» уезжает в заголовок целиком — а по нему потом и ищут.
    """
    t = HEAD_JUNK.sub('', clean(text or ''))
    cls = []
    # Сор и класс снимаются по очереди, пока снимается: скобка, открытая в
    # заголовке, прячет за собой класс — «…къепе ю (» и «…къастамзалла ю ( )».
    for _ in range(4):
        before = t
        t = HEAD_JUNK.sub('', clean(HEAD_TAIL.sub(' ', t)))
        m = HEAD_CLS.search(t)
        if m:
            cls += re.findall(r'[вйюдб]', m.group(1).lower())
            t = t[:m.start()]
        if t == before:
            break
    return (HEAD_JUNK.sub('', clean(t)),
            [CLASSES[c] for c in dict.fromkeys(cls)])


def split_outside(text, sep):
    """Разрезать по знаку, но только вне скобок.

    «хьажаза (таллаза, дешаза) гӀуллакх» — запятая внутри скобки не делит
    перевод на варианты, она часть вставленного синонима.
    """
    out, cur, depth = [], '', 0
    for c in text or '':
        depth += (c == '(') - (c == ')')
        if c == sep and depth <= 0:
            out.append(cur)
            cur = ''
        else:
            cur += c
    out.append(cur)
    return out


def split_variants(text):
    """Перевод -> [(разделитель, вариант)].

    Точку с запятой автор ставит РОВНО между вариантами перевода: «питана;
    харцо», «описка; ошибка». Все 58 таких мест в книге проверены глазами,
    ни одного разорванного оборота.

    Запятая так не годится. «хьийзор, нуьцкъала лелор» — два перевода, а
    «Ӏедал кхочушдаран а, куьйгалла даран а гӀуллакх» — один оборот с общей
    вершиной, и таких в книге 54. Поэтому по запятой режем только заведомо
    безопасное: список коротких синонимов, где каждая часть в одно-два
    слова и ни в одной нет союза «а» или «я».
    """
    out = []
    for k, chunk in enumerate(split_outside(clean(text or ''), ';')):
        # «лицензи ю; ханна елла бакъо» — класс стоит при первом варианте, и
        # виден он только после того, как варианты разведены.
        chunk = HEAD_CLS.sub('', chunk.strip(' ,;')).strip(' ,;')
        if not chunk:
            continue
        out.append((';' if out else None, chunk, k))

    final = []
    for sep, chunk, k in out:
        parts = [p.strip() for p in split_outside(chunk, ',')]
        ok = (len(parts) > 1 and all(p and len(p.split()) <= 2 for p in parts)
              and not any(w in ('а', 'я') for p in parts for w in p.split()))
        if ok:
            final += [(sep if n == 0 else ',', p, k)
                      for n, p in enumerate(parts)]
        else:
            final.append((sep, chunk, k))
    return final or [(None, clean(text or '').strip(), 0)]


def unwrap(note):
    """Снять скобки, которыми книга помечает пояснение.

    В книге скобка — это разметка: она и означает «дальше пояснение». В
    JSONL для пояснения есть своё поле, и скобка в нём — уже мусор, к тому
    же чужой: у Карасаева 17 026 пояснений без скобок и одно со скобкой, и
    приложение показывает поле одинаково для всех источников.

    Снимаем только внешнюю пару, и только если она обнимает всю строку:
    «условия (гарантии), обеспечивающие…» — это текст, а не разметка.
    Непарную скобку с краю («наиболее подходящий)») тоже убираем — её пара
    осталась в соседнем поле.
    """
    t = (note or '').strip()
    while True:
        if t.startswith('(') and t.count('(') == t.count(')') + 1:
            t = t[1:].strip()
            continue
        if t.endswith(')') and t.count(')') == t.count('(') + 1:
            t = t[:-1].strip()
            continue
        if t.startswith('(') and t.endswith(')') and depth_ok(t[1:-1]):
            t = t[1:-1].strip()
            continue
        break
    # Два пояснения подряд, каждое в своих скобках: «(амалт в,ю,б)
    # (заложница)» — это не одна пара скобок, а две, и снимать их надо
    # порознь, а сами пояснения развести точкой с запятой, как в остальных
    # словарях проекта.
    groups = paren_groups(t)
    if groups:
        t = '; '.join(groups)
    # Точку не трогаем: «уст.», «бус.», «досл.» — это пометы.
    return t.strip(' ,;') or None


def paren_groups(text):
    """Строка целиком состоит из скобочных групп -> их содержимое."""
    out, cur, depth = [], '', 0
    for c in text:
        if c == '(' and depth == 0:
            if cur.strip():
                return None          # текст вне скобок — не тот случай
            cur, depth = '', 1
            continue
        if c == ')' and depth == 1:
            depth = 0
            if cur.strip():
                out.append(cur.strip(' ,;'))
            cur = ''
            continue
        depth += (c == '(') - (c == ')')
        cur += c
    if depth or cur.strip():
        return None
    return out if len(out) > 1 else None


def depth_ok(text):
    """Скобки внутри строки не расходятся: «(а) и (б)» — это не одна пара."""
    d = 0
    for c in text:
        d += (c == '(') - (c == ')')
        if d < 0:
            return False
    return d == 0


def gloss_of(text, note, gram, sep=None):
    g = {'text': text, 'sep': sep, 'labels': [], 'gov': None,
         'note': unwrap(note)}
    if gram:
        g['gram'] = gram
    return g


# Голый классный показатель, уехавший в перевод: «ю стороны». Букву «в»
# сюда включать нельзя — с неё начинается половина русских переводов
# («в рамках закона»), поэтому требуем, чтобы в связке была хоть одна из
# однозначных букв.
LEAD_CLS = re.compile(r'^\s*((?:[вйюдб]\s*[,.]\s*|[вйюдб]\s+){1,3})(?=[А-Яа-яЁёӀ])')
SENSE_NO = re.compile(r'(?:^|(?<=[;.]))\s*([1-9])\s*[.\-)]\s*')
DIGITS_ONLY = re.compile(r'^[\d\s.,;()-]*$')


def strip_lead_class(text):
    """Снять с перевода классный показатель, потерявший начертание."""
    m = LEAD_CLS.match(text or '')
    if m and re.search(r'[йюдб]', m.group(1), re.I):
        return text[m.end():], [CLASSES[c] for c in re.findall(r'[вйюдб]',
                                                               m.group(1).lower())]
    return text, []


def split_senses(text):
    """«1.свидетельство; 2. доверенность; 3.показание» -> три значения."""
    parts = SENSE_NO.split(text or '')
    if len(parts) < 3:
        return [(None, (text or '').strip(' ;,'))]
    out, head = [], parts[0].strip(' ;,')
    if head:
        out.append((None, head))
    for n, body in zip(parts[1::2], parts[2::2]):
        body = body.strip(' ;,')
        if body:
            out.append((int(n), body))
    return out or [(None, (text or '').strip(' ;,'))]


# Класс в русско-чеченской половине: «в,ю,б», «ю», «д». Стоит отдельным
# куском курсива, часто в скобках вместе с чеченским синонимом.
RU_CLS = re.compile(r'^[\s(]*((?:[вйюдб]\s*[.,]?\s*){1,3})[\s)]*$', re.I)


# Класс в начале или в конце пояснения: «ю ( гӀумакхе гӀуллакх д)»,
# «(кхоллархо, хӀотторхо в,ю,б)», «… воздаяние равным) д».
RU_CLS_EDGE = re.compile(r'^\s*((?:[вйюдб]\s*[,.]?\s*){1,3})(?![А-Яа-яЁёӀ])'
                         r'|(?<![А-Яа-яЁёӀ])((?:[вйюдб]\s*[,.]?\s*){1,3})\s*$')


def split_ru_note(note):
    """Курсив русско-чеченской статьи: отделить классы от пояснения."""
    cls, rest = [], []
    for chunk in re.split(r'[;]', clean(note or '')):
        c = chunk.strip()
        if not c:
            continue
        m = RU_CLS.match(c)
        if m:
            cls += re.findall(r'[вйюдб]', m.group(1).lower())
            continue
        # Класс приклеен к пояснению с краю — срезаем его, остальное текст.
        while True:
            m = RU_CLS_EDGE.search(c)
            if not m:
                break
            cls += re.findall(r'[вйюдб]', (m.group(1) or m.group(2)).lower())
            c = (c[:m.start()] + ' ' + c[m.end():]).strip(' .,')
        if c:
            rest.append(c)
    return ([CLASSES[x] for x in dict.fromkeys(cls)],
            '; '.join(rest) or None)


# --------------------------------------------------------------------------
# 6. Статья
# --------------------------------------------------------------------------

def parse_entry(chunks, idx, ru_half, problems, prev_head=None,
                lexicon=None, score=None, review=None):
    """Куски одного абзаца -> статья со словосочетаниями в examples."""
    first, rest = chunks[0], chunks[1:]
    # Окончания, подобранные правилом, а не словарём: догадки, и каждая
    # должна попасть в `problems`, даже если разбор в остальном удался.
    weak, unresolved, restem = [], [], []
    review = review if review is not None else []
    raw_head, variants = gender_pair(clean(first['head']))
    # Скобку из заглавного слова разбираем ДО чистки: `clean_head` срезает
    # хвостовой класс, а класс внутри скобки принадлежит не статье, а виду.
    raw_head, paren_vars, paren_note = head_paren_variants(
        raw_head, ru_half, score)
    variants.extend(paren_vars)
    head, head_cls = clean_head(raw_head)
    if not head:
        return None
    # Книга построена по гнездовой системе, и словосочетание гнезда иногда
    # начинает собственный абзац: «~ бух болуш хилар». Тильда ссылается на
    # заглавное слово предыдущей статьи.
    if TILDE.search(head) and prev_head:
        head = clean_head(clean(
            expand_tilde_ru(head, prev_head, lexicon, weak) if ru_half
            else expand_tilde(head, prev_head)))[0]
    trans = HEAD_JUNK.sub('', clean(first['trans']))

    # Пояснение и грамматика разбиты на отрезки по границам вариантов
    # перевода; первый отрезок принадлежит заглавному переводу, остальные —
    # своим вариантам.
    note_segs = (first['note'] or '').split(SEG)
    gram_segs = (first['gram'] or '').split(SEG)
    first = dict(first, note=note_segs[0], gram=gram_segs[0])

    # Класс автор ставит то полужирным курсивом, то обычным, то вовсе
    # забывает начертание — собираем его отовсюду.
    head_cut = first.get('head_cut')
    note_cls, note = split_ru_note(first['note'])
    if paren_note:
        note = f'{note}; {paren_note}' if note else paren_note
    if ru_half:
        # «в,ю,б» у названия лица — мужской, женский и множественное; в
        # чеченско-русской половине то же самое записано раздельно
        # («в, ю; авантюристаш б»), так что деление проверяемое.
        gram = {}
        sg, pl = split_plural_cls(note_cls)
        if sg:
            gram['cls'] = sg
        if pl:
            gram['cls_pl'] = pl
    else:
        gram = parse_gram(first['gram'], head)
        if note_cls and not gram.get('cls'):
            gram['cls'], pl = split_plural_cls(note_cls)
            if pl:
                gram.setdefault('cls_pl', pl)
    if note and DIGITS_ONLY.match(note):
        note = None
    # «цаларарна ден таӀзар д (цалараран таӀзар д)» — в скобках не пояснение,
    # а второй вид той же чеченской фразы, со своим классом.
    if not ru_half and note:
        keep = []
        for part in note_parts(note):
            var = head_variant(part, score, head)
            if var:
                var = [dict(v, form=cut_in(head, v['form'], head_cut))
                       for v in var]
                variants.extend(var)
                # Вариант короче многословного заглавного и корнем с ним не
                # сходится: автор мог иметь в виду замену одного слова («ца
                # хууш яьлла топ (тапча)» — это «… яьлла тапча»), а мог и
                # всю фразу («ца хууш (ларамза)»). Это смысл, а не разметка,
                # и решает его человек — строка уходит в `review`.
                hv = VAR_WORD.findall(head.lower())
                for v in var:
                    vw = VAR_WORD.findall(v['form'].lower())
                    if len(hv) > 1 and len(vw) < len(hv) and not all(
                            any(same_root(x, y) for y in hv) for x in vw):
                        review.append((idx, head, v['form'],
                                       ','.join(v.get('cls') or []) or '—'))
            else:
                keep.append(part)
        note = '; '.join(keep) or None

    # Язык-источник автор ставит полужирным курсивом, но местами начертание
    # теряет, и «(лат.)» оказывается в переводе: «аффект (лат.)».
    m = ORIGIN_RE.search(trans)
    if m:
        gram.setdefault('origin', ORIGIN[m.group(1)])
        trans = trans[:m.start()] + ' ' + trans[m.end():]
    # Скобка, открытая в пояснении и закрытая уже в переводе, оставляет
    # висячий знак: «) аффект».
    trans = TRANS_EDGE.sub(' ', trans)
    trans, lead_cls = strip_lead_class(trans)
    trans = HEAD_JUNK.sub('', clean(trans))
    for found in (head_cls, lead_cls):
        if found and not gram.get('cls'):
            gram['cls'] = found

    # «помощник гӀоьнча в, ю, б» — перевод набран курсивом, как пояснение.
    # В русско-чеченской половине это одно и то же слово по-чеченски, так что
    # поднять пояснение в перевод безопаснее, чем потерять статью целиком.
    # В обратной половине так делать НЕЛЬЗЯ: там перевод русский, а курсив
    # чеченский, и подмена была бы враньём.
    if not trans and ru_half and note:
        trans, note = promote_note(note)

    if not trans:
        problems.append((idx, 'нет перевода', head[:60]))
        return None

    head_forms = [f.get('form') for f in gram.get('forms') or []]
    examples, variant_glosses = [], []
    for c in rest:
        ce = clean_head(clean(c['head']))[0]
        ru = HEAD_JUNK.sub('', clean(c['trans']))
        if not ru and ru_half:
            # То же, что у заглавной статьи: перевод словосочетания бывает
            # набран курсивом («~ (исполнитель судебного решения) чалтач»).
            ru = promote_note(split_ru_note(c['note'])[1])[0]
        if not ce or not ru:
            if ce or ru:
                problems.append((idx, 'словосочетание без половины',
                                 f'{ce[:40]} | {ru[:40]}'))
            continue
        # Чеченская половина: язык агглютинативный, окончание просто
        # прирастает к заглавному слову, и подстановка целиком верна.
        # Русская: тильда стоит за ОСНОВУ, и длину основы надо подбирать.
        ce = (expand_tilde_ru(ce, head, lexicon, weak) if ru_half
              else expand_tilde(ce, head, head_forms, restem))
        ce = HEAD_JUNK.sub('', clean(ce))
        # Классный показатель, потерявший начертание, уезжает в перевод и
        # здесь тоже: «кхиэлъен ~ ю зал заседаний суда».
        ru = HEAD_JUNK.sub('', strip_lead_class(ru)[0])
        ce = HEAD_JUNK.sub('', strip_lead_class(ce)[0]) if ru_half else ce
        if not ce or not ru:
            continue
        if TILDE.search(ce):
            unresolved.append(ce[:50])
        # Словосочетание, совпавшее с заглавным словом, — не словосочетание,
        # а второй перевод со своим пояснением: «наехать (без умысла)
        # тӀекхета; ~ (умышленно) тӀетоха».
        if clean(ce).strip() == clean(head).strip():
            cls2, note2 = split_ru_note(c['note'])
            variant_glosses.append(gloss_of(
                HEAD_JUNK.sub('', clean(strip_lead_class(ru)[0])),
                promote_note(note2)[1] or note2,
                {'cls': cls2} if cls2 else {}, ';'))
            continue
        if ru_half:
            ce, ru = ru, ce          # в этой половине слева русский
        examples.append({
            'ce': ce, 'ru': ru, 'note': None, 'gov': None, 'is_idiom': False,
        })

    if ru_half:
        headword, text = head, trans
    else:
        headword, text = head, trans

    if len(headword) > 90:
        problems.append((idx, 'подозрительно длинный заголовок', headword[:70]))
    if TILDE.search(headword):
        unresolved.append(headword[:60])
    for frag, got in weak:
        problems.append((idx, 'окончание подобрано правилом',
                         f'{headword[:40]}: ~{frag} -> {got[:40]}'))
    for frag, cand, alt in restem:
        problems.append((idx, 'основа взята из парадигмы',
                         f'{headword[:40]}: ~{frag} -> {alt[:30]}, '
                         f'а по заглавному слову {cand[:30]}'))
    for txt in unresolved:
        problems.append((idx, 'тильда не раскрыта', f'{headword[:40]}: {txt}'))
    # Тильды не раскрылись или раскрылись правилом, а заглавного слова нет
    # ни в одном словаре: обычно это опечатка набора, и чинить её в парсере
    # нечем — зато видно, что чинить в книге.
    if (unresolved or weak) and (near := near_miss(head_bases(headword)[-1],
                                                   lexicon)):
        problems.append((idx, 'опечатка в заглавном слове?',
                         f'{headword[:40]} -> {near}'))

    forms_gram = {k: v for k, v in gram.items() if k == 'forms'}
    def extra(seg):
        """Пояснение и класс варианта, кроме первого."""
        cls, txt = split_ru_note(seg if ru_half else '')
        if not ru_half:
            g = parse_gram(seg, head)
            cls, txt = g.get('cls', []), None
        return cls, txt

    senses = []
    for n, (num, body) in enumerate(split_senses(text)):
        parts = split_variants(body)
        glosses = []
        for k, (sep, part, seg) in enumerate(parts):
            head_gloss = n == 0 and k == 0
            if head_gloss:
                g = gloss_of(part, note, forms_gram, sep)
            else:
                cls, txt = extra(note_segs[seg] if seg < len(note_segs) else '')
                g = gloss_of(part, txt, {'cls': cls} if cls else {}, sep)
            glosses.append(g)
        if n == 0:
            glosses += variant_glosses
        senses.append({
            'n': num, 'pos': [], 'labels': [],
            'glosses': glosses,
            'examples': examples if n == 0 else [],
        })

    return {
        'id': headword,
        'headword': headword,
        'homonym': None,
        'pos': [], 'labels': [],
        'cls_sg': gram.get('cls', []), 'cls_pl': gram.get('cls_pl', []),
        'forms': [],
        'variants': dedup_variants(variants),
        'gram': {k: v for k, v in gram.items() if k in ('origin', 'number')},
        'blocks': [],
        'senses': senses,
        'idioms': [], 'xrefs': [],
        'src_ref': idx, 'flags': [],
    }


# --------------------------------------------------------------------------
# 7. Сбор абзацев в статьи
# --------------------------------------------------------------------------

# --------------------------------------------------------------------------
# 7a. Абзацы с потерянным начертанием
# --------------------------------------------------------------------------

TRI_WORD = re.compile(r'[^А-Яа-яЁёӀ]+')
# Буквенная шапка: у чеченского алфавита она бывает в две буквы («АЬ», «КЪ»,
# «Хь»), и `LETTER`, которым ищутся границы половин, их не ловит.
HEAD_LETTER = re.compile(r'[А-ЯЁӀ][А-ЯЁа-яёӀьъ]?$')


def trigrams(text):
    t = ' ' + re.sub(r' +', ' ', TRI_WORD.sub(' ', (text or '').lower())) + ' '
    return [t[i:i + 3] for i in range(len(t) - 2)]


def language_model(paras, ce_at, ru_at, end):
    """Триграммы чеченского и русского — по начертанию самой книги.

    Учить не на чём-то стороннем: в чеченско-русской половине полужирное
    чеченское, а светлое русское, в обратной наоборот, и это даёт по
    несколько тысяч строк на язык. Курсив в обучение не берём — там мешаются
    пометы обоих языков.
    """
    ce, ru = Counter(), Counter()
    for i in range(ce_at, end):
        rh = i >= ru_at
        for tag, text in paras[i]:
            if not HAS_LETTER.search(text):
                continue
            if tag == 'b':
                (ru if rh else ce).update(trigrams(text))
            elif tag == '.':
                (ce if rh else ru).update(trigrams(text))
    return ce, ru


def scorer(model):
    """Логарифм отношения правдоподобий: > 0 — чеченский кусок, < 0 — русский.

    На прогонах самой книги (9 787 штук) такая модель угадывает язык в 95,8 %
    случаев, а на целых словосочетаниях — тем более: ошибается она на
    коротких кусках, где и решать нечего.
    """
    ce, ru = model
    nce, nru = sum(ce.values()), sum(ru.values())
    v = len(set(ce) | set(ru)) + 1

    def score(text):
        s = 0.0
        for g in trigrams(text):
            s += (math.log((ce[g] + 0.5) / (nce + 0.5 * v))
                  - math.log((ru[g] + 0.5) / (nru + 0.5 * v)))
        return s
    return score


# Русское согласованное определение: словосочетание на нём кончиться не
# может, значит граница языков взята на слово раньше — «…документов к
# уголовному | делу тешаман хӀуманаш…». Модель языка тут бессильна: «делу»
# по буквам чеченское слово («дела», «делан»), и решает не она, а русский
# синтаксис.
RU_DANGLING = re.compile(r'\b(?:[а-яё]{3,}(?:ому|ему|ым|им|ого|его|ой|ый|ий|'
                         r'ая|яя|ые|ими|ыми)|[вкосу]|на|по|при|для|из|от)$')


def ru_head_complete(raw, pos, limit=2):
    """Дотянуть русское заглавное слово до существительного (не больше двух слов)."""
    for _ in range(limit):
        head = clean(raw[:pos]).strip(' .,;')
        if not RU_DANGLING.search(head.lower()):
            break
        nxt = re.compile(r'\S+\s*').match(raw, pos)
        if not nxt or not HAS_LETTER.search(clean(raw[nxt.end():])):
            break
        pos = nxt.end()
    return pos


def split_lost_bold(runs, ru_half, score):
    """Абзац без единого полужирного прогона: статья или перенос строки?

    Вёрстка местами теряет полужирное начертание целиком, и статья
    («бакъ-пачхьалкхана ямарт хилар д измена правовому государству») с виду
    ничем не отличается от хвоста предыдущей строки. Отличие одно: у статьи
    внутри есть граница языков — чеченская часть, потом русская (в обратной
    половине наоборот), — а у переноса весь текст на одном языке.

    Границу ищем моделью языка: перебираем все места между словами и берём
    то, где начало сильнее всего похоже на язык заглавного слова, а хвост на
    язык перевода. Возвращаем прогоны с восстановленным начертанием либо
    None, если абзац и вправду перенос.
    """
    raw = ''.join(t for _, t in runs)
    words = list(re.finditer(r'\S+', raw))
    if not words or any(tag in ('b', 'bi') for tag, t in runs if t.strip()):
        return None
    flat = clean(raw).strip()
    # Шапка алфавита и продолжение скобки — точно не статья.
    if HEAD_LETTER.fullmatch(flat) or flat[:1] in '()':
        return None

    sign = -1 if ru_half else 1
    best = None
    for k in range(1, len(words) + 1):
        pos = words[k].start() if k < len(words) else len(raw)
        head, tail = clean(raw[:pos]), clean(raw[pos:])
        if not HAS_LETTER.search(head):
            continue
        s = sign * (score(head) - score(tail))
        if best is None or s > best[0]:
            best = (s, pos, k, sign * score(head),
                    sign * score(tail) if HAS_LETTER.search(tail) else None)
    if not best:
        return None
    _, pos, k, sh, st = best
    if sh <= 0 or (st is not None and st >= 0):
        return None
    # Одно слово и никакого перевода — это почти всегда перенос перевода
    # предыдущей статьи («регистрация», «эвакуация»), а не статья без
    # перевода. Одно слово С переводом — статья: «зулам-девнехь в уголовном
    # процессе».
    if k == 1 and st is None:
        return None
    if ru_half:
        pos = ru_head_complete(raw, pos)

    out, at = [], 0
    for tag, text in runs:
        end = at + len(text)
        if tag == '.' and at < pos:
            if end <= pos:
                out.append(['b', text])
            else:
                out.append(['b', text[:pos - at]])
                out.append(['.', text[pos - at:]])
        else:
            out.append([tag, text])
        at = end
    return out


def starts_entry(runs):
    """Статья начинается там, где ПЕРВЫЙ прогон с буквами — жирный.

    Колонка одна, так что гадать не о чем: у продолжения строки первым
    буквенным прогоном идёт перевод или грамматика, а не заглавное слово.
    """
    for tag, text in runs:
        if HAS_LETTER.search(text):
            return tag == 'b'
    return False


def collect(paras, lo, hi, ru_half=False, score=None):
    """Абзацы [lo, hi) -> [(номер абзаца, прогоны)] с учётом переносов."""
    out, cur, joins = [], None, []
    for i in range(lo, hi):
        runs = paras[i]
        text = clean(''.join(x[1] for x in runs)).strip()
        # Шапку раздела нельзя ни приклеить к предыдущей статье, ни сделать
        # своей: «Уь» — это оглавление буквы, а не слово. `LETTER` ловит
        # только односимвольные да «ГӀ»-образные, и семь чеченских шапок в
        # две буквы («АЬ», «КХ», «КЪ», «Оь», «Уь», «Хь», «Юь») уезжали в
        # перевод предыдущей статьи: «оптимальный ( Уь».
        if not text or HEAD_LETTER.fullmatch(text) or JUNK.match(text):
            continue
        if is_heading(text) or re.fullmatch(r'[\d\s.]+', text):
            continue
        fixed = split_lost_bold(runs, ru_half, score) if score else None
        if starts_entry(runs) or fixed is not None:
            if cur:
                out.append(cur)
            cur = {'start': i,
                   'runs': fixed if fixed is not None else [list(r) for r in runs]}
            if fixed is not None:
                joins.append((i, 'потеряно начертание — разобрано как статья',
                              clean(''.join(x[1] for x in fixed
                                            if x[0] == 'b'))[:60], text[:60]))
        elif cur:
            joins.append((i, 'продолжение строки',
                          clean(''.join(x[1] for x in cur['runs']))[:60],
                          text[:60]))
            cur['runs'].append(['.', ' '])
            cur['runs'].extend([list(r) for r in runs])
        else:
            joins.append((i, 'до первой статьи — пропущено', '', text[:60]))
    if cur:
        out.append(cur)
    return out, joins


def audit(entries, problems):
    """Одинаковые заголовки нумеруются как омонимы."""
    seen = Counter(e['headword'] for e in entries)
    n = Counter()
    for e in entries:
        hw = e['headword']
        if seen[hw] > 1:
            n[hw] += 1
            e['homonym'] = n[hw]
            e['id'] = f'{hw}-{n[hw]}'


def build(paras, lo, hi, ru_half, problems, lexicon=None, score=None,
          review=None):
    got, joins = collect(paras, lo, hi, ru_half, score)
    entries = []
    prev_head = None
    for it in got:
        chunks = items(it['runs'], ru_half, score)
        if not chunks:
            continue
        e = parse_entry(chunks, it['start'], ru_half, problems,
                        prev_head, lexicon, score, review)
        if e:
            entries.append(e)
            if not TILDE.search(e['headword']):
                prev_head = e['headword']
    audit(entries, problems)
    return entries, joins


# --------------------------------------------------------------------------
# 8. Точка входа
# --------------------------------------------------------------------------

def main(argv=None):
    ap = argparse.ArgumentParser(
        description='Абдурашидов 2008 .odt -> JSONL (две половины)')
    ap.add_argument('--odt', required=True)
    ap.add_argument('--out', default='work')
    ap.add_argument('--lexicon', action='append', metavar='JSONL',
                    help='разбор ДРУГОЙ книги проекта: русские словоформы '
                         'оттуда нужны, чтобы понять, сколько букв заменяет '
                         'тильда в русской половине. Можно повторять. Свой '
                         'собственный разбор сюда класть нельзя — он занесёт '
                         'в список собственные же склейки; слова самой книги '
                         'парсер берёт сам, прямо из её текста.')
    args = ap.parse_args(argv)
    os.makedirs(args.out, exist_ok=True)

    paras = paragraphs(args.odt)
    ce_at, ru_at, end = zones(paras)
    if ce_at is None or ru_at is None:
        raise SystemExit('не нашёл границы половин — проверьте книгу')
    print(f'  абзацев всего {len(paras)}')
    print(f'  чеченско-русская половина  {ce_at}..{ru_at}')
    print(f'  русско-чеченская половина  {ru_at}..{end}')

    problems = []
    outside = load_lexicon(args.lexicon)
    inside = book_words(paras, ce_at, ru_at, end)
    lexicon = Lexicon(outside, inside)
    print(f'  словарь русских форм: {len(lexicon)} слов '
          f'({len(outside)} из {len(args.lexicon or [])} книг проекта, '
          f'{len(inside - outside)} своих)')
    if not args.lexicon:
        print('  --lexicon не задан: окончания подбираются только по '
              'словам самой книги, ошибок будет больше')
    score = scorer(language_model(paras, ce_at, ru_at, end))
    review = []
    ce, jce = build(paras, ce_at, ru_at, False, problems, score=score,
                    review=review)
    ru, jru = build(paras, ru_at, end, True, problems, lexicon, score)

    for code, entries in (('law2008_ce', ce), ('law2008_ru', ru)):
        path = os.path.join(args.out, f'{code}.jsonl')
        with open(path, 'w', encoding='utf-8') as f:
            for e in entries:
                f.write(json.dumps(e, ensure_ascii=False) + '\n')

    ppath = os.path.join(args.out, 'problems_law.tsv')
    with open(ppath, 'w', encoding='utf-8') as f:
        f.write('абзац\tчто не так\tчто было\n')
        # Одна и та же тильда повторяется в статье десяток раз («~ы» у
        # «дисцинлина»); чинить её всё равно один раз, и строка нужна одна.
        for row in sorted(set(problems), key=lambda r: (r[1], r[2], r[0])):
            f.write('\t'.join(str(x) for x in row) + '\n')
    def source_line(i):
        """Абзац книги одной строкой — чтобы не искать статью глазами."""
        t = clean(''.join(x[1] for x in paras[i]))
        return re.sub(r'\s+', ' ', t).strip()

    rpath = os.path.join(args.out, 'review_law.tsv')
    with open(rpath, 'w', encoding='utf-8') as f:
        f.write('абзац\tзаглавное слово\tвариант\tкласс\tстатья в книге\n')
        for row in sorted(set(review), key=lambda r: (r[1], r[0])):
            f.write('\t'.join(str(x) for x in row + (source_line(row[0]),)) + '\n')
    jpath = os.path.join(args.out, 'joins_law.tsv')
    with open(jpath, 'w', encoding='utf-8') as f:
        f.write('абзац\tпочему приклеено\tк чему\tчто приклеено\n')
        for row in sorted(jce + jru, key=lambda r: (r[1], r[2], r[0])):
            f.write('\t'.join(str(x) for x in row) + '\n')

    def stat(entries, name):
        ex = sum(len(s['examples']) for e in entries for s in e['senses'])
        fm = sum(len((g.get('gram') or {}).get('forms') or [])
                 for e in entries for s in e['senses'] for g in s['glosses'])
        cls = sum(1 for e in entries if e['cls_sg'] or e['cls_pl'])
        org = sum(1 for e in entries if e['gram'].get('origin'))
        note = sum(1 for e in entries for s in e['senses'] for g in s['glosses']
                   if g.get('note'))
        print(f'\n  {name}')
        print(f'    статей          {len(entries):>6}')
        print(f'    словосочетаний  {ex:>6}')
        print(f'    падежных форм   {fm:>6}')
        print(f'    с классом       {cls:>6}')
        print(f'    язык-источник   {org:>6}')
        print(f'    с примечанием   {note:>6}')

    stat(ce, 'law2008_ce   чеченско-русская')
    stat(ru, 'law2008_ru   русско-чеченская')
    print(f'\n  {rpath}: {len(set(review))} строк — вариант короче '
          f'заглавного, смотреть глазами')
    print(f'  {jpath}: {len(jce) + len(jru)} строк')
    print(f'  {ppath}: {len(set(problems))} строк')
    for kind, n in Counter(p[1] for p in set(problems)).most_common(8):
        print(f'    {n:>6}  {kind}')


if __name__ == '__main__':
    main()

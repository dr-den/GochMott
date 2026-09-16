# -*- coding: utf-8 -*-
"""
Аслаханов С.-А. М. Русско-чеченский словарь спортивных терминов (2012) -> JSONL.

    python tools/parse_aslakhanov.py --odt work/aslakhanov2012.odt --out work/

Даёт `aslakhanov2012.jsonl` и `problems_aslakhanov.tsv`.

ЧЕМ ЭТА КНИГА ОТЛИЧАЕТСЯ ОТ ПРЕДЫДУЩИХ

Она свёрстана В ДВЕ КОЛОНКИ, и вёрстка пережила выгрузку в .odt. Отсюда три
особенности, которых не было ни у Мациева, ни у Карасаева:

1. СТАТЬЯ РАЗОРВАНА ПО АБЗАЦАМ. Одна статья это столько абзацев, сколько строк
   она занимала в колонке. Начало статьи видно по жирному ЗАГОЛОВКУ; абзацы
   без жирного текста — продолжение предыдущей. Таких продолжений 1275.

2. СЛОВА РАЗОРВАНЫ ДЕФИСОМ на границе колонки: «ам-» и «плитудана» это
   «амплитудана». Дефисов-переносов 463, и все они на конце абзаца.

3. БУКВА РАЗДЕЛА — КОЛОНТИТУЛ, а не заголовок: «А» встречается 13 раз, «Б» и
   «В» столько же. Принимать их за границы разделов нельзя.

Зато разметка простая: жирный — русский заголовок, светлый — чеченский
перевод, между колонками табуляция. Курсива почти нет.

ЧТО ТАКОЕ РАЗДЕЛ И ПОЧЕМУ ОН ВАЖЕН

Словарь разбит на 15 разделов по видам спорта («БАСКЕТБОЛ», «БОКС», …), и
термины повторяются: «нападающий удар» есть в восьми разделах, «бросок» в
шести. Всего 343 повторяющихся заголовка на 804 статьи. Без пометы вида спорта
они превращаются в неразличимые омонимы, поэтому раздел пишется в `labels` и
участвует в нумерации омонимов.

ПАРАДИГМА В СКОБКАХ

При чеченском переводе стоит его склонение:

    амплитуда (амплитудан, амплитудана, амплитудо, амплитуде, й;
               мн. амплитудаш, й)
                └ род.  └ дат.   └ эрг.  └ местн. └ класс

Порядок падежей постоянный: родительный, дательный, эргативный, местный;
дальше классный показатель, дальше через `;` множественное число со своим
показателем. Парсер раскладывает это в `gram` глоссы, а текст перевода
оставляет чистым: в карточке «амплитуда», а не «амплитуда (амплитудан…)».

Скобка бывает и не парадигмой — «масд.», «прич.», глагольные формы,
одиночный синоним. Всё, что не легло в схему падежей, остаётся примечанием
как напечатано; выдумывать разбор там, где автор его не дал, нельзя.
"""
import argparse, json, math, os, re, sys
from collections import Counter

# Слой чтения .odt общий с парсером Карасаева: там же живут канонизация
# палочки и разбор латинских двойников, и держать их в двух копиях — значит
# однажды починить в одной и забыть про другую.
try:
    from parse_karasaev import paragraphs, clean, fix_homoglyphs
except ImportError:
    sys.exit('нужен parse_karasaev.py рядом: из него берётся чтение .odt')


# --------------------------------------------------------------------------
# 1. Границы: что в книге не статья
# --------------------------------------------------------------------------

CORPUS_FROM = 'ОБЩЕСПОРТИВНАЯ'     # первый заголовок раздела
CORPUS_TO = 'РУССКО - ЧЕЧЕНСКИЙ'   # выходные данные в конце книги

LETTER = re.compile(r'^[А-ЯЁ]$')
# Заголовок раздела: обе колонки заглавными. `(ДВБ)` и `(Ӏ,2,3,4)` тоже
# заглавные, но это уточнения внутри статей — у них нет ни одной буквы.
SECTION_LINE = re.compile(r'^[^a-zа-яё]{5,}$')


def is_section(text):
    t = (text or '').strip()
    return bool(t and SECTION_LINE.match(t) and re.search(r'[А-ЯЁ]{3}', t)
                and 'ISBN' not in t)


def has_bold_text(runs):
    """Начало статьи — жирный прогон С БУКВАМИ.

    Именно с буквами: продолжения строк тоже начинаются с жирного прогона, но
    в нём одна табуляция — остаток отступа колонки.
    """
    return any(t.startswith('b') and re.search(r'[А-Яа-яЁёA-Za-z]', x)
               for t, x in runs)


# Скобка с парадигмой занимает строку-две: «(духаран, духарна, духаро,
# духаре, д; мн. духарш, д)» — восемьдесят знаков. Если от незакрытой скобки
# до конца текста ушло больше, скобка не открыта, а потеряна: в книге у
# статьи «спорт» закрывающая просто не набрана, и без этого ограничения она
# утаскивала за собой два десятка следующих статей — «спортивный врач»,
# «стадион», «старт» и так до конца буквы.
PAREN_REACH = 60


def open_paren_at(text):
    """Позиция последней незакрытой «(» или -1."""
    stack = []
    for i, ch in enumerate(text or ''):
        if ch == '(':
            stack.append(i)
        elif ch == ')' and stack:
            stack.pop()
    return stack[-1] if stack else -1


def unbalanced(text, reach=PAREN_REACH):
    """Открытая скобка без закрывающей — статья точно не дописана."""
    pos = open_paren_at(text)
    return pos >= 0 and len(text) - pos <= reach


# Незаконченная русская строка. Именная группа не может кончаться предлогом
# или союзом: «жим штанги лежа на» — это половина «…на гимнастической
# скамейке», а не заголовок.
_PREP = (r'в|во|на|над|надо|под|подо|при|с|со|из|изо|от|ото|до|по|за|к|ко|о'
         r'|об|обо|у|для|через|между|меж|около|после|перед|передо|без|про'
         r'|ради|вокруг|вдоль|из-за|из-под|против|среди|сквозь|внутри|поверх')
_CONJ = r'и|или|а|но|да|либо|чем|нежели'
TAIL_OPEN = re.compile(rf'(?:^|\s)(?:{_PREP}|{_CONJ})$', re.I)

# Прилагательное в косвенном падеже без существительного: «увеличение
# мышечной» — половина «…мышечной выносливости». Окончание ненадёжно само по
# себе («удар головой» и «жесты судей» — законченные заголовки), поэтому
# примета работает только вместе с нарушением алфавитного порядка.
OBLIQUE_TAIL = re.compile(
    r'\w{4,}(ого|его|ому|ему|ыми|ими|ую|юю|ой|ей|ым|им|ых|их)$', re.I)


ORDINAL = re.compile(r'^(перв|втор|трет|четв[её]рт|пят|шест|седьм|восьм|девят'
                     r'|десят)(ый|ий|ой|ая|ья|ье|ое|ые|ьи)\b', re.I)


def series(a, b):
    """Два заголовка — соседи по серии, а не статья с оторванным хвостом.

    Книга местами идёт не по алфавиту, а по величине, и тогда подряд стоят
    «первый полусредний вес (до 68кг)» и «второй полусредний вес (до 74кг)».
    Для алфавитного теста это выброс, хотя обе строки — полноценные статьи.

    Две приметы серии, каждой достаточно:
      * оба заголовка начинаются с порядкового числительного;
      * заголовки отличаются только первым словом («барьер у ямы с водой» и
        «препятствие у ямы с водой»).

    У настоящего оторванного хвоста ни того, ни другого не бывает: общих
    слов с предыдущим заголовком у него нет вовсе.
    """
    ha, hb = HEAD_JUNK.sub('', a or ''), HEAD_JUNK.sub('', b or '')
    if ORDINAL.match(ha) and ORDINAL.match(hb):
        return True
    ta = sort_key(' '.join(ha.split()[1:]))
    tb = sort_key(' '.join(hb.split()[1:]))
    return bool(ta) and ta == tb


def closes_paren(text):
    """Строка закрывает скобок больше, чем открывает, — значит, доканчивает
    парадигму предыдущей статьи: «…стероидашка) дегӀан»."""
    return (text or '').count(')') > (text or '').count('(')


def out_of_order(prev_ru, ru):
    """Строка стоит в алфавите ПЕРЕД предыдущей — заголовком быть не может."""
    key = sort_key(HEAD_JUNK.sub('', ru))
    prev_key = sort_key(HEAD_JUNK.sub('', prev_ru))
    return bool(key and prev_key and key < prev_key)


def unrelated(prev_ru, ru):
    """У двух строк разное первое слово.

    Оторванный хвост никогда не повторяет первое слово заголовка: «средняя
    часть дельтовидной» продолжается словом «мышцы». А вот соседние статьи
    в книге идут гнёздами и первое слово делят: «бег со скакалкой» и «бег с
    крестными ногами», «передача пяткой» и «передача низом».
    """
    first = lambda t: (sort_key(HEAD_JUNK.sub('', t)).split() or [''])[0]
    a, b = first(prev_ru), first(ru)
    return bool(a) and bool(b) and a != b


def common_prefix(a, b):
    n = 0
    for x, y in zip(a, b):
        if x != y:
            break
        n += 1
    return n


def review_key(text):
    """Ключ сортировки отчётов: без кавычек и скобок, строчными, ё как е.

    От `sort_key` отличается тем, что НЕ выбрасывает цифры и латиницу —
    иначе «(до 67кг)» и «АИБА» схлопнутся в пустую строку и уедут в начало.
    """
    t = (text or '').lower().replace('ё', 'е')
    return (re.sub(r'^[\s«»"\'(\[.,;:–—-]+', '', t), t)


def sort_key(head):
    """Ключ алфавитного порядка книги: строчные, без ё и без пунктуации."""
    return re.sub(r'[^а-я -]', '', (head or '').lower().replace('ё', 'е')).strip()


# --------------------------------------------------------------------------
# 2. Склейка строк колонки в одну статью
# --------------------------------------------------------------------------

BREAK = '\x00'      # служебная метка границы абзаца внутри статьи


# Перенос слова на конце строки. Между буквой и дефисом местами затесался
# мягкий перенос U+00AD — «четкость, состояние внеш\u00ad-»; его пропускаем.
HYPHEN_END = re.compile(r'[А-Яа-яЁёӀA-Za-z]\u00ad?[-\u2010\u2011]$')

# Пометка на прогоне-разделителе, который `stitch` ставит на стыке строк.
# Нужна, чтобы `split_columns` не приняла его за пробел внутри слова и не
# перекинула в соседнюю колонку: «увеличение мышечной» + «выносливости»
# склеивалось в «увеличение мышечнойвыносливости» именно так.
SEP = '\x01'

# Пометка на прогоне, который присоединяется к предыдущему БЕЗ пробела —
# вторая половина перенесённого слова. Между «йис-» и «тан» в исходнике
# стоит пустой жирный прогон (отступ колонки), и без этой пометки он сойдёт
# за зазор с пробелом: «йис тан» вместо «йистан».
GLUE = '\x02'

# Вторая половина перенесённого слова начинается со строчной буквы.
WORD_TAIL = re.compile(r'[а-яёa-zӀ]')


def column_of(tag):
    return 'b' if tag.startswith('b') else '.'


def stitch(runs):
    """Строки колонки -> сплошной текст.

    На границе абзаца два случая. Обычный перенос строки — вставляем пробел.
    Перенос СЛОВА (строка кончилась дефисом) — дефис убираем и склеиваем без
    пробела, а у следующей строки срезаем отступ колонки, иначе табуляция
    превратится в пробел и «ам-плитудана» станет «ам плитудана».

    Решение принимается для каждой колонки ОТДЕЛЬНО: перенос в русской
    половине и перенос в чеченской случаются независимо, и сплошь и рядом
    в одной строке одна колонка переносит слово, а другая — нет
    («грудиноключичнососце-» + «видная мышца» против «дегӀан» + «ницкъ»).
    """
    out = []
    glue = {'b': False, '.': False}
    for tag, text in runs:
        if tag == BREAK:
            for col in ('b', '.'):
                last, blanks = None, []
                for r in reversed(out):
                    if r[0].endswith(SEP) or column_of(r[0]) != col:
                        continue
                    if not r[1].strip():
                        # Пустой прогон своей колонки — остаток отступа.
                        # Слово кончилось раньше, смотрим дальше назад.
                        blanks.append(r)
                        continue
                    last = r
                    break
                if last is not None and HYPHEN_END.search(last[1].rstrip()):
                    last[1] = last[1].rstrip()[:-1]
                    for r in blanks:      # иначе «вер-» + «тушка» склеится
                        r[1] = ''         # в «вер тушка»
                    glue[col] = True
                else:
                    # Пробел на стыке строк нужен ОБЕИМ колонкам: «нападающий
                    # удар» + «с высокой передачи» склеится в «ударс», если
                    # положить разделитель только в светлую половину.
                    out.append([col + SEP, ' '])
            continue
        col = column_of(tag)
        if glue[col]:
            text = text.lstrip()
            if not text:
                continue
            glue[col] = False
            tag += GLUE
        out.append([tag, text])
    return [r for r in out if r[1]]


def split_columns(runs):
    """Жирное — русский заголовок, светлое — чеченский перевод.

    Пробел МЕЖДУ ДВУМЯ ЖИРНЫМИ словами набран отдельным прогоном, и начертание
    у него бывает светлое. Разложив колонки как есть, пробел уедет в чеченскую
    половину, а русская склеится: «в организме нежелаемые» -> «ворганизме
    нежелаемые». Поэтому прогон из одних пробелов сначала прибивается к
    соседям, если они одного начертания. Табуляцию не трогаем — это граница
    колонок, а не пробел внутри слова.
    """
    runs = [list(r) for r in runs]
    for i in range(1, len(runs) - 1):
        tag, tx = runs[i]
        if tag.endswith(SEP):          # разделитель строк — он уже в своей
            continue                   # колонке, перекладывать нельзя
        if tx.strip() or '\t' in tx or not tx:
            continue
        left, right = column_of(runs[i - 1][0]), column_of(runs[i + 1][0])
        if left == right:
            runs[i][0] = left
    return (clean(join_column(runs, 'b')).strip(' \t'),
            clean(join_column(runs, '.')).strip(' \t'))


def join_column(runs, col):
    """Собрать колонку, не потеряв пробел, набранный в чужой половине.

    «нападающий удар» и «с длинной передачи» — два жирных прогона подряд, но
    в исходнике они не соседи: между ними лежит светлый прогон
    '\t\tеха дӀаяларехь тӀелатарца  ', и пробел, разделяющий жирные слова,
    набран его хвостом. Склеив жирное встык, получим «нападающий ударс
    длинной передачи». Поэтому: если у пропущенного куска пробел по краю —
    ставим пробел и здесь.
    """
    acc, gap = '', ''
    for tag, text in runs:
        if column_of(tag) != col:
            # Разделитель ЧУЖОЙ колонки в зазор не идёт: иначе он сойдёт за
            # пробел и разорвёт перенесённое слово — «ам-плитудана» снова
            # станет «ам плитудана».
            if not tag.endswith(SEP):
                gap += text
            continue
        if (acc and gap and HYPHEN_END.search(acc.rstrip())
                and WORD_TAIL.match(text.lstrip())):
            # Перенос слова ВНУТРИ одного абзаца. Конвертер сплющил две
            # строки книги в один абзац, и половинки слова лежат подряд,
            # разделённые прогоном чужой колонки: «сте-» … «роидаша».
            acc = acc.rstrip()[:-1] + text.lstrip()
            gap = ''
            continue
        if (acc and gap and not tag.endswith(GLUE)
                and (gap[:1].isspace() or gap[-1:].isspace())
                and not acc[-1:].isspace() and not text[:1].isspace()):
            acc += ' '
        acc += text
        gap = ''
    return acc


# --------------------------------------------------------------------------
# 3. Парадигма
# --------------------------------------------------------------------------

CASE_ORDER = ('gen', 'dat', 'erg', 'all')      # род., дат., эрг., местн.
CLS = set('вйдб')
# `мн.`, `мн`, `масд.`, `прич.` — служебные слова внутри скобки.
PLURAL = re.compile(r'^мн\b\.?$', re.I)
MASD = re.compile(r'^масд\b\.?', re.I)
PTCP = re.compile(r'^прич\b\.?', re.I)


def parse_paradigm(inner):
    """«амплитудан, амплитудана, амплитудо, амплитуде, й; мн. амплитудаш, й»
    -> {'forms': [...], 'cls': ['й'], 'cls_pl': ['й'], 'plural': ['амплитудаш']}

    Возвращает None, если это не парадигма: тогда скобка идёт примечанием как
    напечатана. Признак парадигмы — минимум три падежные формы подряд.
    """
    # «Ӏалам – Ӏаламан, Ӏаламна, Ӏаламо, Ӏаламе, д»: перед тире стоит само
    # слово, которое склоняется, а не первая падежная форма. Не отрезав его,
    # вся парадигма съезжает на падеж влево.
    base = None
    m = re.match(r'\s*([^\s,;()–—-]+)\s*[–—-]\s+(.+)$', inner)
    if m and ',' in m.group(2):
        base, inner = m.group(1), m.group(2)

    sg, pl = inner, ''
    if ';' in inner:
        sg, pl = inner.split(';', 1)

    def items(s):
        return [x.strip() for x in re.split(r'\s*,\s*', s) if x.strip()]

    def take(chunk):
        """-> (словоформы, классные показатели, было ли «мн.»)"""
        words, cls, plural = [], [], False
        for it in items(chunk):
            head = it.split()[0] if it.split() else ''
            if PLURAL.match(head):
                plural = True
                it = it[len(head):].strip()
                if not it:
                    continue
            if MASD.match(it) or PTCP.match(it):
                it = re.sub(r'^(масд|прич)\b\.?\s*', '', it, flags=re.I)
                if not it:
                    continue
            if it.lower() in CLS:
                cls.append(it.lower())
            elif re.fullmatch(r'[^\s,;()]+', it):
                words.append(it)
        return words, cls, plural

    sg_words, sg_cls, sg_plural = take(sg)
    pl_words, pl_cls, _ = take(pl) if pl else ([], [], False)

    # «мн.» могло стоять в первой половине — тогда это она и есть множественное
    if sg_plural and not pl_words:
        sg_words, pl_words = [], sg_words
        sg_cls, pl_cls = [], sg_cls

    if len(sg_words) < 3 and len(pl_words) < 1:
        return None
    if len(sg_words) > len(CASE_ORDER) + 1:
        return None                       # длинный перечень — не склонение

    out = {}
    forms = [{'form': w, 'case': c, 'num': 'sg'}
             for w, c in zip(sg_words, CASE_ORDER)]
    forms += [{'form': w, 'num': 'pl'} for w in pl_words]
    if forms:
        out['forms'] = forms
    if base:
        out['of'] = base
    if sg_cls:
        out['cls'] = sg_cls
    if pl_cls:
        out['cls_pl'] = pl_cls
    return out or None


PAREN = re.compile(r'\s*\(([^)]*)\)')


def split_translation(ce, problems, idx, headword):
    """Перевод -> (чистый текст, gram, примечания)."""
    gram, notes = {}, []

    def eat(m):
        inner = m.group(1).strip()
        if not inner:
            return ''
        p = parse_paradigm(inner)
        if p:
            for k, v in p.items():
                if k in gram:
                    gram[k] = gram[k] + v if isinstance(v, list) else v
                else:
                    gram[k] = v
            return ''
        notes.append(inner)
        return ''

    text = PAREN.sub(eat, ce)
    text = re.sub(r'\s+', ' ', text).strip(' ,;')
    if not text and notes:
        # весь перевод оказался в скобке — вернём как есть, иначе статья пуста
        text = notes.pop(0)
    return text, gram, notes


# --------------------------------------------------------------------------
# 4. Сборка статей
# --------------------------------------------------------------------------

def find_sections(paras):
    """Номер первого абзаца каждого раздела -> его название.

    Заголовок раздела занимает одну или две строки, потому что набран в две
    колонки и длинные названия переносятся: «АТЛЕТИЧЕСКАЯ ЗОЬРТАЛЛИН» +
    «ГИМНАСТИКА ГИМНАСТИКА» — это ОДИН заголовок «атлетическая гимнастика».
    Разбирая строки по одной, вторую половину легко принять за начало раздела
    «гимнастика», который на самом деле начинается на шестьсот абзацев позже.
    Поэтому соседние заглавные строки сначала склеиваются.
    """
    caps = [(i, clean(''.join(x[1] for x in p)).strip())
            for i, p in enumerate(paras)]
    caps = [(i, t) for i, t in caps if is_section(t)]
    merged = []
    for i, t in caps:
        if merged and i - merged[-1][1] <= 2:
            merged[-1] = (merged[-1][0], i, merged[-1][2] + ' ' + t)
        else:
            merged.append((i, i, t))
    out = {}
    for start, _end, text in merged:
        name = section_name(text)
        if name:
            out[start] = name
    return out


def continues(cur, runs):
    """Жирная строка, которая на самом деле продолжает предыдущую статью.

    Возвращает причину (строку) или None. Русская колонка переносится так же,
    как чеченская, и типографски продолжение неотличимо от заголовка: у части
    терминов автор даёт толкование («анаболические стероиды — сложные по
    составу допинги, вызывающие в организме нежелаемые процессы»), и его
    строки набраны тем же жирным. Поэтому опираемся только на то, что видно
    наверняка:

      * у строки нет чеченской половины — продолжать переводить нечего;
      * строка начинается с предлога или союза — заголовком это быть не может;
      * у предыдущей статьи открыта скобка — парадигма не дописана;
      * предыдущая строка кончилась переносом слова;
      * предыдущая строка кончилась предлогом или союзом.

    Шестая примета — прилагательное в косвенном падеже на конце предыдущей
    строки — сама по себе врёт («удар головой», «жесты судей» — законченные
    заголовки), поэтому засчитывается только вместе с нарушением алфавитного
    порядка.

    Остальное — в `join_outliers`, а что не вытянуло и там, уходит в
    `problems`: пусть решает человек.
    """
    if cur is None:
        return None
    ru, ce = split_columns(stitch([list(r) for r in runs]))
    if NEW_ENTRY.match(ru):
        return None
    if not ce.strip():
        return 'нет чеченской половины'
    if FUNCTION_WORD.match(HEAD_JUNK.sub('', ru)):
        return 'начинается со служебного слова'
    if HEAD_JUNK.sub('', ru).startswith('('):
        return 'начинается со скобки'
    prev_ru, prev_ce = split_columns(stitch(cur['runs']))
    if unbalanced(prev_ce) and (closes_paren(ce) or out_of_order(prev_ru, ru)):
        # Скобка, которая «открыта» уже сотню знаков, — это потерянная
        # закрывающая, а не продолжение парадигмы (у статьи «спорт» её
        # просто не набрали, и без ограничения она утаскивала за собой
        # «спортивный врач», «стадион», «старт» — до конца буквы).
        # Поэтому мало того, что скобка близко (`unbalanced`): строка должна
        # либо САМА эту скобку закрыть, либо сломать алфавитный порядок.
        return 'у предыдущей открыта скобка'
    if (HYPHEN_END.search(prev_ru.rstrip())
            or HYPHEN_END.search(prev_ce.rstrip())):
        return 'перенос слова'
    tail = HEAD_JUNK.sub('', prev_ru).strip()
    if TAIL_OPEN.search(tail):
        return 'предыдущая кончается предлогом или союзом'
    if (OBLIQUE_TAIL.search(tail) and out_of_order(tail, ru)
            and unrelated(tail, ru)):
        # Ещё и «чужая» строка: у «бег со скакалкой» и «бег с крестными
        # ногами» общее начало, и обе — полноценные заголовки, хотя вторая
        # по алфавиту стоит раньше первой, а первая кончается на «-ой».
        # У настоящего хвоста с предыдущим заголовком общего начала нет.
        return 'предыдущая кончается косвенным прилагательным'
    return None


def join_outliers(items, max_words=24):
    """Заголовок-выброс: стоит ниже И левого соседа, И правого.

    Внутри раздела книга идёт по алфавиту. Оторванный хвост («национальная
    сборная» / «команда» / «национальная федерация») ломает порядок, а стоит
    его убрать — порядок восстанавливается: следующий заголовок продолжает с
    того места, где кончил предыдущий. Настоящий сбой порядка в книге так
    себя не ведёт: «антидопинговый контроль» и «аннулировать» переставлены
    местами, и убрать один, чтобы другой встал на место, не выйдет.

    Две страховки от цепной реакции — один неразобранный кусок толкования
    иначе утащит за собой десяток настоящих статей:

      * у выброса и у предыдущего заголовка не совпадает даже первая буква
        (переставленные соседи по алфавиту всегда начинаются одинаково);
      * к заголовку длиннее `max_words` слов ничего не приклеивается —
        предел щедрый, потому что в книге есть настоящие статьи-толкования
        на два десятка слов, но он обрывает цепную реакцию.
    """
    rows = []
    for it in items:
        ru = split_columns(stitch(it['runs']))[0]
        row = dict(it)
        row['_ru'] = ru
        # Ключ первой строки, а не склеенной статьи: по алфавиту книга
        # ставит статью именно по её началу.
        row['_key'] = sort_key(HEAD_JUNK.sub('', ru))
        rows.append(row)

    out, joined = [], []
    for n, it in enumerate(rows):
        prev = out[-1] if out else None
        nxt = rows[n + 1] if n + 1 < len(rows) else None
        if (prev is not None and nxt is not None
                and not NEW_ENTRY.match(it['_ru'])
                and not series(prev['_ru'], it['_ru'])
                and prev['section'] == it['section'] == nxt['section']
                and it['_key'] and prev['_key'] and nxt['_key']
                # выброс ломает порядок — слева, справа или с обеих сторон
                and (it['_key'] < prev['_key'] or it['_key'] > nxt['_key'])
                # и не родня ни левому соседу, ни правому: у переставленных
                # по ошибке автора заголовков первая буква всегда общая
                and common_prefix(prev['_key'], it['_key']) == 0
                and common_prefix(it['_key'], nxt['_key']) == 0
                # а без него порядок восстанавливается
                and nxt['_key'] >= prev['_key']
                and len(prev['_ru'].split()) < max_words):
            joined.append((it['start'], prev['_ru'], it['_ru']))
            prev['runs'].append([BREAK, ''])
            prev['runs'].extend(it['runs'])
            prev['_ru'] = split_columns(stitch(prev['runs']))[0]
            continue
        out.append(it)

    for it in out:
        it.pop('_ru', None)
        it.pop('_key', None)
    return out, joined


def repair_lost_bold(paras):
    """Вернуть в русскую колонку хвост перенесённого слова.

    Конвертер местами терял жирное начертание у второй половины слова, и
    хвост уезжал в чеченскую половину: «индивидуальная так-» и отдельной
    строкой светлое «тическая игра». Признак железный: жирная половина
    кончилась переносом слова, а в следующем абзаце жирного нет вовсе —
    в книге такого быть не может, перенос обязан чем-то продолжиться.

    Берём хвост до первой табуляции (дальше начинается чеченская колонка),
    а если табуляции нет — прогон целиком, пока он короткий. Таких мест в
    книге десять, и каждое чинится насквозь: «вызываю-» + «щее временное
    перепол-» + «нение» — цепочка из трёх строк.
    """
    out = [[list(r) for r in p] for p in paras]
    for i, runs in enumerate(out):
        ru = split_columns(stitch([list(r) for r in runs]))[0]
        if not HYPHEN_END.search(ru.rstrip()):
            continue
        j = i + 1
        while j < len(out) and not clean(''.join(x[1] for x in out[j])).strip():
            j += 1
        if j >= len(out) or has_bold_text(out[j]):
            continue
        nxt = out[j]
        k = next((n for n, r in enumerate(nxt) if r[1].strip()), None)
        if k is None:
            continue
        tag, text = nxt[k]
        if '\t' in text:
            head, tail = text.split('\t', 1)
            tail = '\t' + tail
        elif len(text.split()) <= 4:
            head, tail = text, ''
        else:
            head, _, tail = text.partition(' ')
            tail = ' ' + tail
        nxt[k:k + 1] = [['b', head]] + ([[tag, tail]] if tail else [])
    return out


# --------------------------------------------------------------------------
# 4a. Какого языка строка: модель на триграммах из самой книги
# --------------------------------------------------------------------------

TRI_WORD = re.compile(r'[^а-яёӀ ]', re.I)


def trigrams(text):
    t = ' ' + re.sub(r' +', ' ', TRI_WORD.sub(' ', (text or '').lower())) + ' '
    return [t[i:i + 3] for i in range(len(t) - 2)]


def language_model(paras, sections):
    """Триграммы русской и чеченской колонок — по строкам, где обе на месте.

    Учить не на чём-то стороннем, а на самой книге: двухколоночных строк
    четыре с половиной тысячи, и в них начертание разделило колонки
    надёжно. На отложенной десятой части такая модель узнаёт язык куска
    из одного-трёх слов в 98 случаях из 100.
    """
    ru, ce, started = Counter(), Counter(), False
    for i, p in enumerate(paras):
        if i in sections:
            started = True
            continue
        if not started:
            continue
        if clean(''.join(x[1] for x in p)).strip().startswith(CORPUS_TO):
            break
        if not has_bold_text(p):
            continue
        a, b = split_columns(stitch([list(r) for r in p]))
        if a.strip() and b.strip():
            ru.update(trigrams(a))
            ce.update(trigrams(b))
    return ru, ce


def looks_russian(model, text):
    """Логарифм отношения правдоподобий: > 0 — русская строка."""
    ru, ce = model
    nru, nce = sum(ru.values()), sum(ce.values())
    if not nru or not nce:
        return False
    v = len(set(ru) | set(ce)) + 1
    score = 0.0
    for g in trigrams(text):
        score += (math.log((ru[g] + 0.5) / (nru + 0.5 * v))
                  - math.log((ce[g] + 0.5) / (nce + 0.5 * v)))
    return score > 0


# Отсылочная статья: перевода у неё нет, вместо него «см.» и другой русский
# заголовок («гандбол см. ручной мяч»). Такая строка тоже светлая и тоже у
# левого края, но это НАЧАЛО статьи, а не хвост предыдущей. Отличается тем,
# что «см.» стоит не в начале строки: со «см.» начинается как раз перенос
# отсылки («остановка матча,» / «см. остановка игры»).
# Отсылка внутри самого заголовка: «копьеметатель, см. метатель копья».
SEE_IN_HEAD = re.compile(r'^(.*?)[,;]?\s*\(?\s*см\s*\.\s*(.+?)\)?\s*$')

XREF_HEAD = re.compile(r'[А-Яа-яЁё][^.]{0,90}?\bсм\s*\.')
XREF_TAIL = re.compile(r'^[\s(«"]*см\s*\.')


def split_off_bold(runs, new_entry=False):
    """Отрезать от первого прогона русскую голову — до первой табуляции."""
    k = next((n for n, r in enumerate(runs) if r[1].strip()), None)
    if k is None:
        return
    tag, text = runs[k]
    if '\t' in text:
        head, tail = text.split('\t', 1)
        tail = '\t' + tail
    else:
        head, tail = text, ''
    if new_entry:
        head = '\u200b' + head
    runs[k:k + 1] = [['b', head]] + ([[tag, tail]] if tail else [])


def repair_flush_left(paras, sections):
    """Строка без жирного, прижатая к левому краю, — русская, а не чеченская.

    Вторая половина переноса теряет начертание не только на разрыве слова:
    «атака согласованным» / «перемещением» — слово целое, дефиса нет, и
    хвост уходит в чеченскую колонку, склеивая две статьи в одну.

    Русская колонка стоит у левого края (4466 жирных прогонов из 4534
    начинаются с нулевой позиции), чеченская — с отступом. Но отступ бывает
    и у чеченских продолжений, и без него: одного положения мало. Поэтому
    к геометрии добавлен язык — строку читает модель, обученная на колонках
    этой же книги.
    """
    model = language_model(paras, sections)
    out, started = [[list(r) for r in p] for p in paras], False
    for i, runs in enumerate(out):
        if i in sections:
            started = True
            continue
        if not started:
            continue
        text = clean(''.join(x[1] for x in runs)).strip()
        if not text or LETTER.match(text) or is_section(text):
            continue
        if text.startswith(CORPUS_TO):
            break
        if has_bold_text(runs) or not runs or not runs[0][1].strip():
            continue
        if runs[0][1][:1].isspace():      # с отступом — чеченская колонка
            continue
        if not looks_russian(model, text):
            continue
        j = i + 1
        while j < len(out) and not clean(''.join(x[1] for x in out[j])).strip():
            j += 1
        nxt = (clean(''.join(x[1] for x in out[j])).strip()
               if j < len(out) else '')
        xref = (XREF_HEAD.search(text) and not XREF_TAIL.match(text)
                or XREF_TAIL.match(nxt))
        split_off_bold(runs, new_entry=bool(xref))
    return out


def collect(paras, outlier_join=True):
    """Абзацы книги -> ([статьи], [журнал склеек])."""
    sections = find_sections(paras)
    paras = repair_flush_left(repair_lost_bold(paras), sections)
    out, cur, section, joins = [], None, None, []
    started = False
    for i, p in enumerate(paras):
        flat = clean(''.join(x[1] for x in p)).strip()
        if i in sections:
            section = sections[i]
            started = True
            if cur:
                out.append(cur)
                cur = None
            continue
        if not started:
            continue
        if flat.startswith(CORPUS_TO):
            break
        if not flat or LETTER.match(flat) or is_section(flat):
            continue
        why = continues(cur, p) if has_bold_text(p) else None
        if has_bold_text(p) and not why:
            if cur:
                out.append(cur)
            cur = {'start': i, 'section': section, 'runs': [list(r) for r in p]}
        elif cur:
            if why:
                joins.append((i, why,
                              split_columns(stitch(cur['runs']))[0],
                              split_columns(stitch([list(r) for r in p]))[0]))
            cur['runs'].append([BREAK, ''])
            cur['runs'].extend([list(r) for r in p])
    if cur:
        out.append(cur)
    if outlier_join:
        out, outliers = join_outliers(out)
        joins.extend((i, 'выброс в алфавитном порядке', a, b)
                     for i, a, b in outliers)
    return out, joins


# Русская половина заголовка раздела: до того места, где начинается чеченская.
SECTION_RU = {
    'ОБЩЕСПОРТИВНАЯ': 'общеспортивная', 'АТЛЕТИЧЕСКАЯ': 'атлетическая гимнастика',
    'БАСКЕТБОЛ': 'баскетбол', 'БОКС': 'бокс', 'БОРЬБА': 'борьба',
    'ВОЛЕЙБОЛ': 'волейбол', 'ГИМНАСТИКА': 'гимнастика', 'ДЗЮДО': 'дзюдо',
    'ЛЕГКАЯ': 'лёгкая атлетика', 'НАРОДНЫЕ': 'народные танцы',
    'ПОДВИЖНЫЕ': 'подвижные игры', 'РУКОПАШНЫЙ': 'рукопашный бой',
    'РУЧНОЙ': 'ручной мяч', 'ТЯЖЕЛАЯ': 'тяжёлая атлетика', 'ФУТБОЛ': 'футбол',
}


def section_name(text):
    """Заголовок раздела набран в две колонки, русская первая."""
    first = (text.split() or [''])[0].strip(' ,')
    for word in text.split():
        name = SECTION_RU.get(word.strip(' ,'))
        if name:
            return name
    return SECTION_RU.get(first)


# --------------------------------------------------------------------------
# 5. Статья
# --------------------------------------------------------------------------

HEAD_JUNK = re.compile(r'^[\s\t.,;:–—§\u200b\ufeff-]+|[\s\t.,;:\u200b\ufeff]+$')

# Ручная пометка в самом словаре: «это НАЧАЛО статьи, ни к чему не клеить».
# Ставится в начало русской половины строки. Нужна там, где книга нарушает
# собственный алфавит по делу — весовые категории идут не по алфавиту, а по
# весу («первый полусредний вес», «второй полусредний вес»), и приметы,
# которые ловят оторванный хвост, принимают такую пару за одну статью.
#   U+200B — нулевой пробел, в документе не виден (в LibreOffice:
#            Вставка -> Специальный символ, найти ZERO WIDTH SPACE);
#   U+FEFF — то же самое, если вставилось из другого редактора;
#   §      — видимый вариант, если возиться с невидимыми не хочется.
NEW_ENTRY = re.compile(r'^[\s\t]*[\u200b\ufeff§]')
HAS_LETTER = re.compile(r'[А-Яа-яЁёӀ]')

# Русская словарная статья не начинается с предлога или союза. Строка вида
# «от груди», «к груди сидя», «со штангой на спине» — это хвост толкования
# предыдущей статьи, набранный тем же жирным («передача двумя руками от
# груди»). Таких строк 110, и все проверенные оказались продолжениями.
FUNCTION_WORD = re.compile(
    r'^(в|во|на|над|под|при|с|со|из|от|до|по|за|к|ко|о|об|у|и|или|а|но|для'
    r'|через|между|около|после|перед|без)\s', re.I)


def parse_entry(item, problems):
    idx, section = item['start'], item['section']
    runs = stitch(item['runs'])
    ru, ce = split_columns(runs)
    ru = HEAD_JUNK.sub('', ru)
    if not ru:
        problems.append((idx, 'пустой заголовок', ce[:60]))
        return None

    # «гандбол см. ручной мяч» — отсылочная статья: перевода у неё нет по
    # определению, вместо него другой русский заголовок. Режем на заголовок
    # и цель; такие статьи в книге есть и с переводом («игра, см. матч»).
    xrefs = []
    m = SEE_IN_HEAD.match(ru)
    if m:
        head = m.group(1).strip(' .,;:()«»"')
        target = m.group(2).strip(' .,;:()«»"')
        if head and target:
            ru, xrefs = head, [{'rel': 'см.', 'target': target,
                                'homonyms': [], 'senses': []}]

    if not ce:
        if not xrefs:
            problems.append((idx, 'нет перевода', ru[:60]))
            return None
        return record(ru, section, [], xrefs, idx)

    text, gram, notes = split_translation(ce, problems, idx, ru)
    if not text or not HAS_LETTER.search(text):
        problems.append((idx, 'перевод пуст или из одних знаков', f'{ru[:40]}: {text!r}'))
        return None

    gloss = {'text': text, 'sep': None, 'labels': [], 'gov': None,
             'note': '; '.join(notes) or None}
    if gram:
        gloss['gram'] = gram

    if len(ru) > 80:
        problems.append((idx, 'подозрительно длинный заголовок', ru[:70]))
    if re.search(r'[А-ЯЁ]{4}', ru):
        problems.append((idx, 'заголовок заглавными — возможно, шапка', ru[:60]))

    return record(ru, section, [gloss], xrefs, idx)


def record(ru, section, glosses, xrefs, idx):
    return {
        'id': ru,
        'headword': ru,
        'homonym': None,
        'pos': [], 'labels': [section] if section else [],
        'cls_sg': [], 'cls_pl': [], 'forms': [], 'variants': [],
        'gram': {}, 'blocks': [],
        'senses': [{'n': None, 'pos': [], 'labels': [],
                    'glosses': glosses, 'examples': []}],
        'idioms': [], 'xrefs': xrefs,
        'src_ref': idx, 'flags': [],
    }


def audit(entries, problems):
    """Одинаковые заголовки в разных разделах — законные омонимы.

    «нападающий удар» есть в восьми видах спорта, и это восемь разных статей,
    а не восемь дублей. Номер омонима идёт по порядку следования в книге,
    раздел уже лежит в `labels`.
    """
    # Книга внутри раздела идёт по алфавиту. Заголовок, который сортируется
    # ПЕРЕД предыдущим, — почти наверняка не заголовок, а строка толкования,
    # набранная тем же жирным («…вызывающие в организме нежелаемые процессы»).
    # Сливать их автоматически нельзя: в книге есть и настоящие сбои порядка
    # («антидопинговый контроль» стоит перед «аннулировать»), поэтому только
    # помечаем.
    prev_key, prev_sec = '', None
    for e in entries:
        key = sort_key(e['headword'])
        sec = e['labels'][0] if e['labels'] else None
        if sec != prev_sec:
            prev_key, prev_sec = '', sec
        if key and prev_key and key < prev_key:
            problems.append((e['src_ref'], 'нарушен алфавитный порядок — возможно,'
                             ' это продолжение предыдущей статьи',
                             f'{prev_key} -> {e["headword"]}'))
        prev_key = key

    seen = Counter(e['headword'] for e in entries)
    n = Counter()
    for e in entries:
        hw = e['headword']
        if seen[hw] > 1:
            n[hw] += 1
            e['homonym'] = n[hw]
            e['id'] = f'{hw}-{n[hw]}'
        if not e['senses'][0]['glosses'] and not e['xrefs']:
            problems.append((e['src_ref'], 'статья без перевода', hw))
        if re.search(r'[А-Яа-яЁёӀ][-\u2010\u2011]\s', hw):
            problems.append((e['src_ref'], 'в заголовке остался знак переноса', hw))


def main(argv=None):
    ap = argparse.ArgumentParser(description='Аслаханов 2012 .odt -> JSONL')
    ap.add_argument('--odt', required=True)
    ap.add_argument('--out', default='work')
    ap.add_argument('--limit', type=int)
    ap.add_argument('--no-alpha-join', action='store_true',
                    help='не склеивать по алфавитному выбросу — только по '
                         'приметам, которые видны наверняка')
    args = ap.parse_args(argv)
    os.makedirs(args.out, exist_ok=True)

    paras = paragraphs(args.odt)
    items, joins = collect(paras, outlier_join=not args.no_alpha_join)
    if args.limit:
        items = items[:args.limit]
    print(f'  абзацев всего {len(paras)}, статей найдено {len(items)}')
    print(f'  строк приклеено к предыдущей статье: {len(joins)}')
    for why, n in Counter(r[1] for r in joins).most_common():
        print(f'    {n:>6}  {why}')

    problems, entries = [], []
    for it in items:
        e = parse_entry(it, problems)
        if e:
            entries.append(e)
    audit(entries, problems)

    path = os.path.join(args.out, 'aslakhanov2012.jsonl')
    with open(path, 'w', encoding='utf-8') as f:
        for e in entries:
            f.write(json.dumps(e, ensure_ascii=False) + '\n')
    ppath = os.path.join(args.out, 'problems_aslakhanov.tsv')
    with open(ppath, 'w', encoding='utf-8') as f:
        f.write('абзац\tчто не так\tчто было\n')
        for row in sorted(problems, key=lambda r: (r[1], review_key(r[2]), r[0])):
            f.write('\t'.join(str(x) for x in row) + '\n')

    jpath = os.path.join(args.out, 'joins_aslakhanov.tsv')
    with open(jpath, 'w', encoding='utf-8') as f:
        f.write('абзац\tпочему приклеено\tк чему\tчто приклеено\n')
        # Сортировка для чтения глазами: сначала все склейки одной приметы
        # подряд, внутри приметы — по алфавиту. Так проверяется примета
        # целиком, а не вперемешку с остальными по порядку страниц.
        for i, why, a, b in sorted(joins, key=lambda r: (r[1], review_key(r[2]),
                                                         review_key(r[3]), r[0])):
            f.write(f'{i}\t{why}\t{a}\t{b}\n')

    gl = sum(len(s['glosses']) for e in entries for s in e['senses'])
    forms = sum(len(g.get('gram', {}).get('forms', []))
                for e in entries for s in e['senses'] for g in s['glosses'])
    withcls = sum(1 for e in entries for s in e['senses'] for g in s['glosses']
                  if g.get('gram', {}).get('cls'))
    notes = sum(1 for e in entries for s in e['senses'] for g in s['glosses']
                if g.get('note'))
    print(f'\n  статей            {len(entries):>7}')
    print(f'  переводов         {gl:>7}')
    print(f'  падежных форм     {forms:>7}')
    print(f'  с классом         {withcls:>7}')
    print(f'  с примечанием     {notes:>7}')
    print(f'  омонимов          {sum(1 for e in entries if e["homonym"]):>7}')
    by_sec = Counter(l for e in entries for l in e['labels'])
    print('\n  по разделам: ' + ', '.join(f'{k} {v}' for k, v in by_sec.most_common()))
    print(f'\n  {jpath}: {len(joins)} строк')
    print(f'  {ppath}: {len(problems)} строк')
    for kind, n in Counter(p[1] for p in problems).most_common(8):
        print(f'    {n:>6}  {kind}')
    return 0


if __name__ == '__main__':
    sys.exit(main())

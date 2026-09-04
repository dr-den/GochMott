#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Собирает вводную часть словарей 1978, 1997 и 2017 в assets/books/.

Мациев собирается отдельно (`extract_about.py` -> `assets/about.json`): у него
есть ещё список сокращений и алфавит, а у этих трёх книг их нет.

На выходе:

    app/src/main/assets/books/index.json          список книг для экрана «О словарях»
    app/src/main/assets/books/karasaev1978.json   вводная часть 1978
    app/src/main/assets/books/math1997.json       вводная часть 1997
    app/src/main/assets/books/comp2017.json       вводная часть 2017

Формат разделов тот же, что у `about.json`: абзац — список отрезков, отрезок без
начертаний лежит строкой, с начертаниями — объектом `{t, b, i, s}`. Абзац, который
начинается с жирного отрезка, приложение рисует плашкой примера.

Паспорт книги (авторы, год, число статей) берётся ИЗ БАЗЫ, а не пишется руками:
иначе список на экране разойдётся с тем, что в ней лежит.

    python tools/build_books_json.py
"""

import json
import os
import re
import sqlite3
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, 'rawSources')
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'books')
DB = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'dict.db')

NS = {
    'office': 'urn:oasis:names:tc:opendocument:xmlns:office:1.0',
    'style': 'urn:oasis:names:tc:opendocument:xmlns:style:1.0',
    'text': 'urn:oasis:names:tc:opendocument:xmlns:text:1.0',
    'fo': 'urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0',
}
Q = {k: '{%s}' % v for k, v in NS.items()}


# --------------------------------------------------------------------------
# 1. Чтение .odt: абзацы с начертаниями
# --------------------------------------------------------------------------

def text_styles(root):
    """{имя стиля: (bold, italic, superscript)} из automatic-styles."""
    out = {}
    for st in root.iter(Q['style'] + 'style'):
        if st.get(Q['style'] + 'family') != 'text':
            continue
        props = st.find(Q['style'] + 'text-properties')
        if props is None:
            continue
        pos = props.get(Q['style'] + 'text-position') or ''
        out[st.get(Q['style'] + 'name')] = (
            (props.get(Q['fo'] + 'font-weight') or '') == 'bold',
            (props.get(Q['fo'] + 'font-style') or '') == 'italic',
            pos.startswith('super') or bool(re.match(r'\s*[1-9]', pos)),
        )
    return out


def runs_of(para, styles, inherited=(False, False, False)):
    """Абзац -> список (текст, bold, italic, superscript), пробелы схлопнуты."""
    out = []

    def push(txt, fmt):
        if not txt:
            return
        if out and out[-1][1:] == fmt:
            out[-1] = (out[-1][0] + txt,) + fmt
        else:
            out.append((txt,) + fmt)

    def walk(node, fmt):
        push(node.text or '', fmt)
        for child in node:
            tag = child.tag
            if tag == Q['text'] + 'span':
                walk(child, styles.get(child.get(Q['text'] + 'style-name'), fmt))
            elif tag == Q['text'] + 's':
                n = int(child.get(Q['text'] + 'c') or 1)
                push(' ' * n, fmt)
            elif tag in (Q['text'] + 'tab', Q['text'] + 'line-break'):
                push(' ', fmt)
            else:
                walk(child, fmt)
            push(child.tail or '', fmt)

    walk(para, inherited)
    return out


# --------------------------------------------------------------------------
# 1a. Палочка. Во вводной части 2017 она набрана латинской «I» 65 раз плюс
# украинская «І» — ровно та беда, про которую пишет find_homoglyphs.py: ключи
# спасёт нормализатор, а ОТОБРАЖАЕМЫЙ текст нет, и на экране «О словарях» будет
# «хIунда» рядом с «хӀунда» в карточке статьи. Чиним здесь, а не в приложении:
# в assets должен лежать уже правильный текст.
# --------------------------------------------------------------------------

PAL = '\u04c0'  # Ӏ
def _is_cyr(ch):
    return bool(ch) and ('Ѐ' <= ch <= 'ӿ')


def repair_pal(text):
    """Латинская I между кириллицей -> Ӏ. «ISBN» и «IP-адрес» не трогаются."""
    text = text.replace('І', PAL).replace('і', PAL).replace('ӏ', PAL)
    out = list(text)
    for i, ch in enumerate(out):
        if ch != 'I':
            continue
        prev = out[i - 1] if i else ''
        nxt = text[i + 1] if i + 1 < len(text) else ''
        if _is_cyr(prev) or _is_cyr(nxt):
            out[i] = PAL
    return ''.join(out)


def read_odt(path):
    """-> список абзацев; абзац это список (текст, b, i, s)."""
    with zipfile.ZipFile(path) as z:
        root = ET.fromstring(z.read('content.xml'))
    styles = text_styles(root)
    body = root.find(Q['office'] + 'body')
    return [[(repair_pal(t), b, i, sup) for t, b, i, sup in runs_of(p, styles)]
            for p in body.iter(Q['text'] + 'p')]


def to_json(runs):
    """Отрезки -> формат about.json: без начертаний просто строка."""
    out = []
    for text, b, i, s in runs:
        if not text.strip() and not out:
            continue
        if not (b or i or s):
            out.append(text)
        else:
            item = {'t': text}
            if b:
                item['b'] = True
            if i:
                item['i'] = True
            if s:
                item['s'] = True
            out.append(item)
    return out


def section(paras, rng):
    """Абзацы диапазона -> список абзацев JSON, пустые выброшены."""
    out = []
    for i in rng:
        block = to_json(paras[i])
        if any((x if isinstance(x, str) else x['t']).strip() for x in block):
            out.append(block)
    return out


# --------------------------------------------------------------------------
# 2. Что где лежит в трёх книгах
# --------------------------------------------------------------------------
# Номера абзацев в content.xml. У 1997 и 2017 разделов по два — предисловие и
# построение словаря, — но 1997 печатает предисловие только по-чеченски, а
# построение только по-русски. Пустая сторона намеренная: приложение показывает
# ту, что есть, а не пустой экран.
#
# У Карасаева–Мациева вводная часть только русская: книга русско-чеченская, и
# по-чеченски в ней набран лишь титул. Предисловия нет вовсе — есть издательская
# аннотация и «О пользовании словарем» на 30 пунктов.

KARASAEV = {
    'code': 'karasaev1978',
    'odt': 'Карасаев А.Т., Мациев А.Г. Русско-чеченский словарь 1978.odt',
    'title_ce': 'Оьрсийн-нохчийн словарь',
    'sections': [
        {'id': 'annotation', 'title': {'ru': 'Аннотация', 'ce': ''},
         'ru': range(126, 129), 'ce': range(0, 0)},
        {'id': 'structure', 'title': {'ru': 'О пользовании словарём', 'ce': ''},
         'ru': range(136, 340), 'ce': range(0, 0)},
    ],
}

MATH = {
    'code': 'math1997',
    'odt': 'Чеченско-русский русско-чеченский словарь математических терминов 1997.odt',
    'title_ce': '',
    'sections': [
        {'id': 'preface', 'title': {'ru': 'Предисловие', 'ce': 'Дешхьалхе'},
         'ru': range(0, 0), 'ce': range(57, 64)},
        {'id': 'structure', 'title': {'ru': 'Построение словаря', 'ce': ''},
         'ru': range(66, 80), 'ce': range(0, 0)},
    ],
}

COMP = {
    'code': 'comp2017',
    'odt': 'Умархаджиев С.М. и др. Русско-чеченский чеченско-русский словарь '
           'компьютерной лексики 2017.odt',
    'title_ce': 'Оьрсийн-нохчийн, нохчийн-оьрсийн компьютерийн лексикин дошам',
    'sections': [
        {'id': 'preface', 'title': {'ru': 'Предисловие', 'ce': 'Дешхьалхе'},
         'ru': range(117, 124), 'ce': range(88, 95)},
        {'id': 'structure', 'title': {'ru': 'Построение словаря', 'ce': 'Дошаман дӀахӀоттам'},
         'ru': range(125, 145), 'ce': range(96, 116)},
    ],
}

# Раздел от приложения, а не от книги: он объясняет, чем текст на экране
# отличается от напечатанного. Пишется по-русски; чеченской стороны нет, и
# приложение подставит русскую — выдумывать за авторов перевод нельзя.
NOTES = {
    'karasaev1978': [
        'Книга идёт с русского на чеченский: заглавное слово в ней русское, '
        'чеченское стоит переводом. Поэтому чеченское слово находится в её '
        'статьях по переводу — в карточке оно будет не заголовком статьи, '
        'а внутри неё.',
        'В книге заглавное слово разделено двумя чертами на неизменяемую часть '
        'и окончание, а внутри статьи эта часть заменена тильдой: '
        '«белоку́р||ый … ~ые во́лосы». В приложении сочетания собраны целиком — '
        '«белокурые волосы», — поэтому их можно искать и копировать как обычные '
        'слова.',
        'Ударение проставлено на всех русских словах, кроме односложных, — так '
        'напечатано в книге. Долгота чеченского гласного показана чёрточкой над '
        'буквой: «да̃къа». И то и другое снимается переключателями «Ударения в '
        'переводах» и «Долгота гласных» в боковом меню; ударение здесь пропадает '
        'и в заглавном слове, потому что заглавное слово русское.',
    ],
    'math1997': [
        'В книге часть заглавного слова, повторяющаяся во всех производных, '
        'отделена двумя косыми линиями (//), а внутри статьи заменена тильдой (~): '
        '«абсцисс//а … ~ийн сема». В приложении слова собраны целиком — заглавное '
        'слово «абсцисса», сочетание «абсциссийн сема», — поэтому их можно искать '
        'и копировать как обычные слова.',
        'Знаков долготы гласного этот словарь не печатает, поэтому переключатель '
        '«Долгота гласных» на его статьи не влияет.',
        'Падежные формы чеченских существительных книга даёт прямо в статье, '
        'подряд. В приложении они разложены по падежам и числам в разделе '
        '«Формы слова».',
        'Классный показатель существительного стоит так же, как в книге: '
        'в чеченско-русской части — при заглавном слове, в русско-чеченской — '
        'при переводе.',
    ],
    'comp2017': [
        'Долгота гласного обозначена тильдой над буквой: «ма̃ша». Как объясняет '
        'раздел «Построение словаря», знак стоит при заглавном слове; '
        'в словосочетаниях книга его не повторяет, поэтому в примерах то же слово '
        'напечатано без тильды — «компьютерийн маша». Приложение показывает текст '
        'так, как он набран в книге, и ничего не дописывает.',
        'Убрать тильду совсем можно переключателем «Долгота гласных» в боковом '
        'меню — он снимает знак и в заглавных словах.',
        'Классные показатели чеченских слов приведены в скобках после слова — '
        '«адаптер (й, й)»: первый для единственного числа, второй для '
        'множественного.',
        'Ударение в русских заглавных словах проставлено, кроме односложных слов '
        'и слов с буквой «ё». Оно снимается переключателем «Ударения в переводах» '
        'в том же меню.',
    ],
}


# --------------------------------------------------------------------------
# 3. Паспорт книги из базы
# --------------------------------------------------------------------------

def books_from_db():
    """{book: {authors, year, citation, n_lemmas, title}} — по КНИГЕ, не направлению."""
    db = sqlite3.connect(DB)
    out = {}
    for book, title, authors, year, citation, n in db.execute(
            'SELECT book, title, authors, year, citation, SUM(n_lemmas) '
            'FROM dicts GROUP BY book ORDER BY MIN(priority)'):
        out[book] = {'title': title, 'authors': authors, 'year': year,
                     'citation': citation, 'entries': n}
    db.close()
    return out


def build_book(spec, paras, meta):
    sections = []
    for s in spec['sections']:
        sections.append({
            'id': s['id'],
            'title': s['title'],
            'appText': False,
            'ru': section(paras, s['ru']),
            'ce': section(paras, s['ce']),
        })
    sections.append({
        # appText: текст написало приложение, а не книга. Приложение по этому
        # признаку не пишет «раздела нет по-чеченски» — раздела нет и в книге,
        # потому что книга про расхождения с собой не пишет.
        'id': 'app_note',
        'title': {'ru': 'О тексте в приложении', 'ce': ''},
        'appText': True,
        'ru': [[p] for p in NOTES[spec['code']]],
        'ce': [],
    })
    return {
        'title': {'ru': meta['title'], 'ce': spec['title_ce']},
        'source': meta['citation'],
        'sections': sections,
    }


def main():
    if not os.path.exists(DB):
        sys.exit('нет %s — сначала соберите базу' % DB)
    meta = books_from_db()
    os.makedirs(OUT, exist_ok=True)

    index = [{
        'code': 'maciev1961',
        'title': {'ru': meta['maciev1961']['title'], 'ce': 'Нохчийн-оьрсийн словарь'},
        'authors': meta['maciev1961']['authors'],
        'year': meta['maciev1961']['year'],
        'entries': meta['maciev1961']['entries'],
        'asset': 'about.json',
    }]

    for spec in (KARASAEV, MATH, COMP):
        code = spec['code']
        if code not in meta:
            sys.exit('словаря %s нет в базе' % code)
        paras = read_odt(os.path.join(RAW, spec['odt']))
        book = build_book(spec, paras, meta[code])
        path = os.path.join(OUT, code + '.json')
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(book, f, ensure_ascii=False, indent=1)
        print('%-40s %2d разделов  %6d байт' % (
            os.path.relpath(path, ROOT), len(book['sections']), os.path.getsize(path)))
        index.append({
            'code': code,
            'title': book['title'],
            'authors': meta[code]['authors'],
            'year': meta[code]['year'],
            'entries': meta[code]['entries'],
            'asset': 'books/%s.json' % code,
        })

    path = os.path.join(OUT, 'index.json')
    with open(path, 'w', encoding='utf-8') as f:
        json.dump({'books': index}, f, ensure_ascii=False, indent=1)
    print('%-40s %2d книги' % (os.path.relpath(path, ROOT), len(index)))


if __name__ == '__main__':
    main()

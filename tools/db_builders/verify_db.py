# -*- coding: utf-8 -*-
"""
Сверка пересобранной базы: dict.db v4 против старой v3.

    python tools/verify_db.py new.db --old old_v3.db --dict maciev1961
    python tools/verify_db.py new.db                      # только автономные проверки

Смысл: v4 отличается от v3 только раскладкой (dict_id, lang, переименования).
На ОДНОМ Мациеве содержимое обязано совпасть до строчки. Всё, что разошлось, —
ошибка миграции, а не улучшение. Скрипт сравнивает не счётчики (они сходятся и
при перепутанных строках), а множества самих значений, привязанные к `slug`.

Выход: 0 — сошлось, 1 — есть расхождения. Годится для CI.
"""
import argparse, json, re, sqlite3, sys
from collections import defaultdict

# v3 -> v4: имя таблицы и колонок, которые переименованы
RENAMED = [
    ('word_forms', 'forms', 'таблица словоформ'),
    ('ru_index', 'trans_index', 'обратный индекс'),
    ('glosses.ru', 'glosses.text', 'текст перевода'),
    ('glosses.ru_norm', 'glosses.text_norm', 'ключ перевода'),
    ('subs.ru', 'subs.text', 'подпункт примера'),
]

EXPECT = 6     # держать синхронно с DB_USER_VERSION в сборщике

OK, BAD = '  ok  ', '  РАЗОШЛОСЬ  '


class Report:
    def __init__(self):
        self.fail = 0
        self.lines = []

    def check(self, name, ok, detail=''):
        if not ok:
            self.fail += 1
        self.lines.append(f'{BAD if not ok else OK}{name}'
                          + (f'\n        {detail}' if detail and not ok else ''))

    def note(self, text):
        self.lines.append(f'        {text}')

    def dump(self):
        print('\n'.join(self.lines))
        return self.fail


def q(db, sql, args=()):
    return db.execute(sql, args).fetchall()


def one(db, sql, args=()):
    r = db.execute(sql, args).fetchone()
    return r[0] if r else None


def diff_sets(a, b, limit=8):
    """Что есть только слева / только справа, с примерами."""
    only_a, only_b = a - b, b - a
    ex = []
    for tag, s in (('только в старой', only_a), ('только в новой', only_b)):
        for item in list(sorted(s, key=str))[:limit]:
            ex.append(f'{tag}: {item}')
    return len(only_a), len(only_b), '\n        '.join(ex)


# --------------------------------------------------------------------------
# Автономные проверки новой базы — работают и без старой
# --------------------------------------------------------------------------

def check_standalone(new, rep):
    ver = one(new, 'PRAGMA user_version')
    rep.check(f'PRAGMA user_version = {ver} (ожидалось {EXPECT})', ver == EXPECT)

    tables = {r[0] for r in q(new, "SELECT name FROM sqlite_master WHERE type='table'")}
    need = {'dicts', 'lemmas', 'forms', 'senses', 'glosses', 'examples', 'subs',
            'cross_refs', 'trans_index', 'lemma_links', 'label_uses', 'meta'}
    rep.check('все таблицы схемы на месте', need <= tables,
              f'нет: {sorted(need - tables)}')

    gone = {'word_forms', 'ru_index'} & tables
    rep.check('старых имён таблиц не осталось', not gone, f'найдены: {sorted(gone)}')

    n_d = one(new, 'SELECT COUNT(*) FROM dicts')
    rep.check(f'словарей в базе: {n_d}', n_d >= 1)
    for code, ls, lt, n, prio in q(new, 'SELECT code,lang_src,lang_tgt,n_lemmas,priority'
                                        ' FROM dicts ORDER BY priority'):
        rep.note(f'{code:<14} {ls}->{lt}  статей {n:>6}  priority {prio}')

    # dict_id нигде не NULL
    for t in ('lemmas', 'forms', 'senses', 'glosses', 'examples', 'subs',
              'cross_refs', 'trans_index', 'blocks', 'lemma_class'):
        n = one(new, f'SELECT COUNT(*) FROM {t} WHERE dict_id IS NULL')
        rep.check(f'{t}: dict_id проставлен везде', n == 0, f'{n} строк без dict_id')

    # ссылки не пересекают границу словаря
    n = one(new, """SELECT COUNT(*) FROM cross_refs x JOIN lemmas l ON l.id=x.to_lemma_id
                    WHERE l.dict_id <> x.dict_id""")
    rep.check('отсылки не выходят за пределы своей книги', n == 0, f'{n} нарушений')

    # каждая лемма находится по своему заголовку
    n = one(new, """SELECT COUNT(*) FROM lemmas l WHERE NOT EXISTS(
                      SELECT 1 FROM forms f WHERE f.lemma_id=l.id
                        AND f.form_norm=l.headword_norm)""")
    rep.check('каждая статья находится по своему заголовку', n == 0, f'{n} не находятся')

    # язык согласован с паспортом
    n = one(new, """SELECT COUNT(*) FROM forms f JOIN dicts d ON d.id=f.dict_id
                    WHERE f.lang <> d.lang_src""")
    rep.check('forms.lang = dicts.lang_src', n == 0, f'{n} строк')
    n = one(new, """SELECT COUNT(*) FROM glosses g JOIN dicts d ON d.id=g.dict_id
                    WHERE g.lang <> d.lang_tgt""")
    rep.check('glosses.lang = dicts.lang_tgt', n == 0, f'{n} строк')

    # строчная палочка не должна попадать в ключи
    n = one(new, "SELECT COUNT(*) FROM forms WHERE lang='ce'"
                 " AND form_norm LIKE '%'||char(1231)||'%'")
    rep.check('в ключах нет строчной палочки U+04CF', n == 0, f'{n} строк')

    # ударение/долгота не должны попадать в ключи
    for col, tbl in (('headword_norm', 'lemmas'), ('form_norm', 'forms'),
                     ('text_norm', 'glosses')):
        n = one(new, f"SELECT COUNT(*) FROM {tbl} WHERE {col} LIKE '%'||char(769)||'%'"
                     f"   OR {col} LIKE '%'||char(771)||'%'")
        rep.check(f'{tbl}.{col} без комбинирующих знаков', n == 0, f'{n} строк')

    # но в отображаемом тексте они должны БЫТЬ (иначе потеряли диакритику)
    n = one(new, "SELECT COUNT(*) FROM lemmas WHERE headword LIKE '%'||char(771)||'%'")
    rep.note(f'заголовков со знаком долготы: {n}')
    n = one(new, "SELECT COUNT(*) FROM glosses WHERE lang='ru'"
                 " AND text LIKE '%'||char(769)||'%'")
    rep.note(f'русских переводов с ударением: {n}')

    # FTS
    has_fts = one(new, "SELECT COUNT(*) FROM sqlite_master WHERE name='forms_trgm'")
    rep.note(f'forms_trgm (FTS5): {"есть" if has_fts else "нет — будет фолбэк на LIKE"}')

    # связи
    n = one(new, 'SELECT COUNT(*) FROM lemma_links')
    if n:
        c = one(new, "SELECT COUNT(*) FROM lemma_links WHERE conflict <> '[]'")
        rep.note(f'связей между словарями: {n}, из них с расхождениями: {c}')
        n2 = one(new, 'SELECT COUNT(*) FROM lemma_links WHERE a_dict_id = b_dict_id')
        rep.check('связи соединяют разные словари', n2 == 0, f'{n2} внутри одного')
        r = one(new, 'SELECT COUNT(*) FROM lemma_links WHERE reviewed=1')
        rep.note(f'связей, просмотренных человеком: {r}')
        n3 = one(new, "SELECT COUNT(*) FROM lemma_links WHERE conflict <> '[]' AND reviewed=0")
        rep.note(f'расхождений без решения: {n3} — они в conflicts.tsv')


# --------------------------------------------------------------------------
# Сверка со старой базой (v3), по одному словарю
# --------------------------------------------------------------------------

def resolve_code(new, code, rep):
    """Код словаря, а не путь.

    У сборщика флаг `--dict` берёт `CODE=PATH`, у сверки — только `CODE`.
    Одинаковое имя при разном смысле — ошибка в моём CLI, поэтому здесь мы
    прощаем очевидную путаницу: из `rawSources/maciev1961=work/x.jsonl`
    вытаскиваем `maciev1961`, но говорим об этом вслух.
    """
    known = [r[0] for r in q(new, 'SELECT code FROM dicts ORDER BY priority')]
    if code in known:
        return code
    guess = re.split(r'[\\/]', code.split('=')[0])[-1].strip()
    if guess in known:
        rep.note(f'принял код {guess!r}: флаг --code ждёт КОД словаря, а не путь '
                 f'(это у сборщика --dict CODE=PATH)')
        return guess
    rep.check(f'словарь {code!r} есть в новой базе', False,
              'СВЕРКА СОДЕРЖИМОГО НЕ ВЫПОЛНЕНА. Флаг --code ждёт код словаря;\n'
              '        в базе есть: ' + ', '.join(known))
    return None


def check_against_old(old, new, code, rep, sample=12):
    code = resolve_code(new, code, rep)
    if code is None:
        return
    did = one(new, 'SELECT id FROM dicts WHERE code=?', (code,))

    ver = one(old, 'PRAGMA user_version')
    rep.note(f'старая база: user_version = {ver}')

    # ---- счётчики
    pairs = [('lemmas', 'lemmas'), ('word_forms', 'forms'), ('senses', 'senses'),
             ('glosses', 'glosses'), ('examples', 'examples'), ('subs', 'subs'),
             ('cross_refs', 'cross_refs'), ('ru_index', 'trans_index'),
             ('blocks', 'blocks'), ('lemma_class', 'lemma_class')]
    for told, tnew in pairs:
        a = one(old, f'SELECT COUNT(*) FROM {told}')
        b = one(new, f'SELECT COUNT(*) FROM {tnew} WHERE dict_id=?', (did,))
        rep.check(f'{told:<12} -> {tnew:<12} {a} / {b}', a == b,
                  f'разница {b - a:+d}')

    # ---- леммы: сверяем ЗНАЧЕНИЯ по slug, а не количество
    a = {(r[0], r[1], r[2], r[3], r[4], r[5]) for r in q(
        old, 'SELECT slug,headword,headword_norm,headword_fold,homonym,class_star'
             ' FROM lemmas')}
    b = {(r[0], r[1], r[2], r[3], r[4], r[5]) for r in q(
        new, 'SELECT slug,headword,headword_norm,headword_fold,homonym,class_star'
             ' FROM lemmas WHERE dict_id=?', (did,))}
    na, nb, ex = diff_sets(a, b, sample)
    rep.check(f'заголовки и ключи лемм совпадают ({len(a)})', na == 0 and nb == 0,
              f'{na} пропало, {nb} появилось\n        {ex}')

    # ---- формы: (slug, form_norm, kind, source)
    a = {tuple(r) for r in q(old, """
        SELECT l.slug, w.form_norm, w.kind, w.source
        FROM word_forms w JOIN lemmas l ON l.id = w.lemma_id""")}
    b = {tuple(r) for r in q(new, """
        SELECT l.slug, f.form_norm, f.kind, f.source
        FROM forms f JOIN lemmas l ON l.id = f.lemma_id WHERE f.dict_id=?""", (did,))}
    na, nb, ex = diff_sets(a, b, sample)
    rep.check(f'ключи прямого поиска совпадают ({len(a)})', na == 0 and nb == 0,
              f'{na} пропало, {nb} появилось\n        {ex}')

    # ---- глоссы: (slug, idx, текст, ключ)
    a = {tuple(r) for r in q(old, """
        SELECT l.slug, s.block_n, s.ordering, g.idx, g.ru, g.ru_norm, g.sep, g.note, g.gov
        FROM glosses g JOIN lemmas l ON l.id = g.lemma_id
        JOIN senses s ON s.id = g.sense_id""")}
    b = {tuple(r) for r in q(new, """
        SELECT l.slug, s.block_n, s.ordering, g.idx, g.text, g.text_norm, g.sep, g.note, g.gov
        FROM glosses g JOIN lemmas l ON l.id = g.lemma_id
        JOIN senses s ON s.id = g.sense_id WHERE g.dict_id=?""", (did,))}
    na, nb, ex = diff_sets(a, b, sample)
    rep.check(f'переводы совпадают ({len(a)})', na == 0 and nb == 0,
              f'{na} пропало, {nb} появилось\n        {ex}')

    # ---- примеры
    a = {tuple(r) for r in q(old, """
        SELECT l.slug, e.idx, e.is_idiom, e.ce, e.ru
        FROM examples e JOIN lemmas l ON l.id = e.lemma_id""")}
    b = {tuple(r) for r in q(new, """
        SELECT l.slug, e.idx, e.is_idiom, e.ce, e.ru
        FROM examples e JOIN lemmas l ON l.id = e.lemma_id WHERE e.dict_id=?""", (did,))}
    na, nb, ex = diff_sets(a, b, sample)
    rep.check(f'примеры и идиомы совпадают ({len(a)})', na == 0 and nb == 0,
              f'{na} пропало, {nb} появилось\n        {ex}')

    # ---- обратный индекс: (slug, word, src)
    a = {tuple(r) for r in q(old, """
        SELECT l.slug, r.word, r.stem, r.src, COALESCE(g.ru, e.ce, '')
        FROM ru_index r JOIN lemmas l ON l.id = r.lemma_id
        LEFT JOIN glosses  g ON g.id = r.target_id AND r.src IN (0,3)
        LEFT JOIN examples e ON e.id = r.target_id AND r.src IN (1,2)""")}
    b = {tuple(r) for r in q(new, """
        SELECT l.slug, t.word, t.stem, t.src, COALESCE(g.text, e.ce, '')
        FROM trans_index t JOIN lemmas l ON l.id = t.lemma_id
        LEFT JOIN glosses  g ON g.id = t.target_id AND t.src IN (0,3)
        LEFT JOIN examples e ON e.id = t.target_id AND t.src IN (1,2)
        WHERE t.dict_id=?""", (did,))}
    na, nb, ex = diff_sets(a, b, sample)
    rep.check(f'обратный индекс совпадает ({len(a)})', na == 0 and nb == 0,
              f'{na} пропало, {nb} появилось\n        {ex}')

    # ---- разрешённые отсылки
    a = one(old, 'SELECT COUNT(*) FROM cross_refs WHERE to_lemma_id IS NOT NULL')
    b = one(new, 'SELECT COUNT(*) FROM cross_refs WHERE to_lemma_id IS NOT NULL'
                 ' AND dict_id=?', (did,))
    rep.check(f'отсылок разрешено: было {a}, стало {b}', a == b)

    # ---- выдача поиска: топ-10 по контрольным словам должен совпасть
    probes_ru = ['утомление', 'палка', 'каждый', 'ошибки', 'поголовно', 'рука', 'вода']
    for w in probes_ru:
        ra = [r[0] for r in q(old, """
            SELECT l.slug FROM ru_index r JOIN lemmas l ON l.id=r.lemma_id
            WHERE r.word=? ORDER BY r.src, l.ordering LIMIT 10""", (w,))]
        rb = [r[0] for r in q(new, """
            SELECT l.slug FROM trans_index t JOIN lemmas l ON l.id=t.lemma_id
            WHERE t.word=? AND t.dict_id=? ORDER BY t.src, l.ordering LIMIT 10""",
                              (w, did))]
        rep.check(f'выдача рус→чеч «{w}» ({len(ra)} строк)', ra == rb,
                  f'было {ra}\n        стало {rb}')

    probes_ce = [r[0] for r in q(old, 'SELECT headword_norm FROM lemmas'
                                      ' ORDER BY ordering LIMIT 5')]
    probes_ce += [r[0] for r in q(old, "SELECT form_norm FROM word_forms"
                                       " WHERE kind='paradigm' LIMIT 5")]
    for w in probes_ce:
        ra = [r[0] for r in q(old, """
            SELECT l.slug FROM word_forms f JOIN lemmas l ON l.id=f.lemma_id
            WHERE f.form_norm=? GROUP BY l.id ORDER BY l.ordering LIMIT 10""", (w,))]
        rb = [r[0] for r in q(new, """
            SELECT l.slug FROM forms f JOIN lemmas l ON l.id=f.lemma_id
            WHERE f.form_norm=? AND f.dict_id=? GROUP BY l.id
            ORDER BY l.ordering LIMIT 10""", (w, did))]
        rep.check(f'выдача чеч→рус «{w}»', ra == rb,
                  f'было {ra}\n        стало {rb}')


def main(argv=None):
    ap = argparse.ArgumentParser(description='сверка dict.db v4 со старой v3')
    ap.add_argument('new', help='новая база')
    ap.add_argument('--old', help='старая база v3 (если есть — идёт полная сверка)')
    ap.add_argument('--code', '--dict', dest='code', default='maciev1961',
                    metavar='CODE',
                    help='КОД словаря в новой базе, с которым сверять старую '
                         '(не путь: у сборщика --dict CODE=PATH, здесь только CODE)')
    ap.add_argument('--sample', type=int, default=12, help='сколько примеров расхождений')
    args = ap.parse_args(argv)

    new = sqlite3.connect(f'file:{args.new}?mode=ro', uri=True)
    rep = Report()

    print(f'\n=== автономные проверки: {args.new}')
    check_standalone(new, rep)

    if args.old:
        print(f'\n=== сверка со старой базой: {args.old} (словарь {args.code})')
        old = sqlite3.connect(f'file:{args.old}?mode=ro', uri=True)
        check_against_old(old, new, args.code, rep, args.sample)
        old.close()
    else:
        rep.note('старая база не указана — сверка содержимого пропущена')

    fail = rep.dump()
    print()
    if fail:
        print(f'РАСХОЖДЕНИЙ: {fail}. Базу выкладывать нельзя.')
    else:
        print('Всё сошлось.')
    print('\nПереименования, которые надо отразить в Kotlin:')
    for a, b, what in RENAMED:
        print(f'  {a:<20} -> {b:<20} {what}')
    new.close()
    return 1 if fail else 0


if __name__ == '__main__':
    sys.exit(main())

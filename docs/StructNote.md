# Запросы к `dict.db`

Рабочие запросы приложения (схема v4). Схема — в `SCHEMA.md`, реализация —
в `DictRepository.kt`.

Общее для всех запросов выдачи:

```sql
-- LEMMA_COLUMNS
l.id, l.headword, l.homonym, p.name_ru AS pos, l.class_star,
l.pluralia_tantum, l.labels, l.obj_num, l.subj_num

-- BOOK_ORDER
d.priority, l.richness DESC, l.ordering
```

Джойн `JOIN dicts d` есть везде. `d.priority` обязателен **перед** `l.ordering`:
в v4 `ordering` считается внутри словаря, и без приоритета выдача из нескольких
книг пойдёт чересполосицей. Колонки читаются по **имени**, а не по позиции: набор
у слоёв разный (`only_gen`, `best_src`, `matched`).

## Язык запроса не угадываем

Направление задаёт UI, и ключ считается нормализатором **своего** языка:

```kotlin
val keyCe = ChechenNormalizer.normalize(input)   // -> lang = 'ce'
val keyRu = RuNormalizer.normalize(input)        // -> lang = 'ru'
```

Гнать русский ввод через `ChechenNormalizer` нельзя: там `1`, `i`, `l`, `|`
становятся палочкой (это способ набрать `Ӏ` с обычной раскладки), и «1-й ряд»
превратится в «Ӏ-й ряд».

Таблицы названы по роли, а не по языку, поэтому один и тот же язык живёт в двух
местах: чеченское слово бывает и заголовком книги чеч→рус, и словом внутри перевода
книги рус→чеч. Оба входа опрашиваются всегда — это два промаха по индексу, а не
эвристика, которая однажды ошибётся. Пока словарь в базе один, «чужая» ветка
просто пуста.

---

## A. Точное совпадение со стороной ЗАГОЛОВКА

`forms` — единственный вход: заголовок, варианты, падежи, времена и сгенерированные
классные формы лежат там вместе.

```sql
SELECT <LEMMA_COLUMNS>,
       MAX(f.is_headword) AS exact_headword,
       MIN(CASE WHEN f.source <> 'dict' THEN 1 ELSE 0 END) AS only_gen
FROM forms f
JOIN lemmas l   ON l.id = f.lemma_id
JOIN dicts  d   ON d.id = f.dict_id
LEFT JOIN pos p ON p.id = l.pos_id
WHERE f.form_norm = ? AND f.lang = ?          -- keyCe/'ce' либо keyRu/'ru'
GROUP BY l.id
ORDER BY only_gen, exact_headword DESC, <BOOK_ORDER>;
```

`only_gen` опускает вниз статьи, найденные только по ненапечатанной форме. В v4
таких категорий две: `gen` — форма собрана заменой классного показателя, `linked` —
ключ пришёл из другой книги. Обе это ключи, а не слово из книги, поэтому условие
пишется `source <> 'dict'`, а не `= 'gen'`.

Любая косвенная форма (`куьйго`, `аьхна`, `ваьккхира`) находит свою лемму — они
перечислены в словаре.

## B1. Вся фраза совпала с переводом

Отдельной таблицы фраз нет — перевод целиком лежит в `glosses.text_norm`, по нему
индекс `(text_norm, lang)`.

```sql
SELECT <LEMMA_COLUMNS>, 0 AS exact_headword, g.text AS matched
FROM glosses g
JOIN lemmas l   ON l.id = g.lemma_id
JOIN dicts  d   ON d.id = g.dict_id
LEFT JOIN pos p ON p.id = l.pos_id
WHERE g.text_norm = ? AND g.lang = ?          -- «каждый раз» -> хӀоразза
GROUP BY l.id
ORDER BY <BOOK_ORDER> LIMIT 100;
```

## B2 / A2. Все слова запроса есть в переводе

`HAVING COUNT(DISTINCT …)` даёт пересечение, поэтому «ошибки находить» находит
`гӀа̃латашда̃ха`, а не всё подряд про ошибки.

```sql
SELECT <LEMMA_COLUMNS>, 0 AS exact_headword,
       MIN(t.src) AS best_src, g.text AS matched
FROM trans_index t
JOIN lemmas l       ON l.id = t.lemma_id
JOIN dicts  d       ON d.id = t.dict_id
LEFT JOIN pos p     ON p.id = l.pos_id
LEFT JOIN glosses g ON g.id = t.target_id AND t.src IN (0, 3)
WHERE t.word IN (?, ?, …) AND t.lang = ?
GROUP BY l.id
HAVING COUNT(DISTINCT t.word) = :n
ORDER BY best_src, <BOOK_ORDER>
LIMIT 100;
```

Один и тот же запрос обслуживает оба направления:

* `lang='ru'` — **B2**, русское слово в переводах книги чеч→рус;
* `lang='ce'` — **A2**, чеченское слово внутри переводов книги рус→чеч. `бехк` не
  заголовок у Карасаева, но встречается в 158 его статей.

Две тонкости.

`MIN(t.src)` рядом с «голой» `g.text` — документированное поведение SQLite: при
`min()`/`max()` неагрегированная колонка берётся из строки, давшей экстремум. Значит
показанный перевод принадлежит **сильнейшему** совпадению, а не случайному.

`src` — откуда взято совпадение: `0` перевод значения, `1` пример, `2` идиома,
`3` протянуто по отсылке. Поэтому «поголовно» находит `гӀаж` через идиому
«гӀаж такхолла мел верг», но ниже прямых переводов.

## B3. Запасной слой — та же таблица по `stem`

Колонка одна, а содержимое зависит от языка: **основа Snowball для русского, скелет
`FuzzyKey` для чеченского**. Значит и ключ подавать разный — `RuStem.stem(w)` либо
`FuzzyKey.chechenFromNormalized(w)`.

Для русского это именно запасной слой: у «пол» основа общая с «поле», «полено»,
«полый», и как самостоятельный путь это мусор в выдаче. Для чеченского — слой
опечаток. Включается, только когда точные слои не дали ничего.

## C. Примерный поиск чеч→рус (опечатки, части слова)

Идёт всегда параллельно точному: «куг»/«кюг» вместо «куьг» не находятся ни точным
поиском, ни подстрокой — отличаются сами буквы. Ловит скелет `FuzzyKey`.

Скелеты заголовков считает сборщик, при старте их достаточно прочитать. Заголовок
в v4 бывает и русским, поэтому строки разводятся по `lemmas.lang`: иначе русские
заголовки попадут в чеченский примерный поиск.

```sql
SELECT id, lang, headword_fold, headword_norm FROM lemmas;
```

Русские подсказки собираются из слов перевода плюс русских заголовков:

```sql
SELECT DISTINCT word FROM trans_index WHERE lang = 'ru';
```

Подстрока по словоформам — через FTS5, если она есть. Таблица `forms_trgm` общая
для всех языков, поэтому фильтр по `lang` нужен и здесь:

```sql
SELECT DISTINCT l.id, l.headword_fold
FROM forms_trgm x
JOIN forms  f ON f.id = x.rowid
JOIN lemmas l ON l.id = f.lemma_id
WHERE forms_trgm MATCH ? AND f.lang = ? LIMIT 100;
```

Запасной вариант без FTS5: `WHERE f.form_norm LIKE '%' || ? || '%' AND f.lang = ?`.

## Ранжирование

Порядок ключей сортировки, от сильного к слабому:

1. точность совпадения (`exact_headword`, затем `only_gen`);
2. `src` обратного индекса (0 перевод значения, 1 пример, 2 идиома, 3 отсылка);
3. `d.priority` — паспортный приоритет словаря;
4. `l.richness` — подробность статьи;
5. `l.ordering` — алфавит внутри своей книги.

---

## Карточка статьи

**Формы.** Только напечатанные в книге — `source <> 'dict'` показывать нельзя.
Заголовочную форму репозиторий тоже не отдаёт: она уже стоит в шапке карточки.

```sql
SELECT f.form, f.is_headword, ct.abbr_ru, ct.name_ru,
       nt.code AS number, vt.name_ru AS tam, f.source
FROM forms f
LEFT JOIN case_type   ct ON ct.id = f.case_id
LEFT JOIN number_type nt ON nt.id = f.number_id
LEFT JOIN verb_tam    vt ON vt.id = f.tam_id
WHERE f.lemma_id = ? AND f.source = 'dict'
  AND f.kind NOT IN ('variant', 'headword')
ORDER BY f.is_headword DESC, f.number_id, ct.ordering, f.ordering;
```

**Значения и переводы.** Значение — это несколько глоссов, склеенных своим же
разделителем из книги (`sep`). Колонка называется `text`, а не `ru`: у словаря
рус→чеч глосс чеченский.

```sql
SELECT s.id, s.sense_no, s.block_n, p.name_ru AS pos, s.labels
FROM senses s LEFT JOIN pos p ON p.id = s.pos_id
WHERE s.lemma_id = ? ORDER BY s.block_n, s.ordering;

SELECT g.sense_id, g.text, g.sep, g.note, g.gov, g.labels
FROM glosses g WHERE g.lemma_id = ? ORDER BY g.sense_id, g.idx;
```

`block_n` — блок `1.`/`2.`: одна статья, разные части речи
(`хе̃наза`: 1. прил. преждевременный, 2. нареч. преждевременно).

**Примеры.** Привязаны к значению и показываются под ним. Идиомы за «◊» относятся к
статье целиком — у них `sense_id IS NULL`, `is_idiom = 1`. `examples.ce`/`examples.ru`
названы по языку, а не по роли, и в v4 не переименованы.

```sql
SELECT e.id, e.sense_id, e.is_idiom, e.ce, e.ru, e.kind,
       e.note, e.note_kind, e.gov, e.labels
FROM examples e WHERE e.lemma_id = ? ORDER BY e.is_idiom, e.sense_id, e.idx;

SELECT s.example_id, s.letter, s.text, s.note, s.gov
FROM subs s JOIN examples e ON e.id = s.example_id
WHERE e.lemma_id = ? ORDER BY s.example_id, s.idx;
```

**Классы и отсылки.** `rel` уже человекочитаем — переводить коды не нужно:

```sql
SELECT marker, number FROM lemma_class WHERE lemma_id = ?
ORDER BY number DESC, ordering;

SELECT rel, to_headword, to_lemma_id FROM cross_refs
WHERE from_lemma_id = ? ORDER BY id;
```

**Откуда статья.** Готовая строка — `dicts.citation`, склеивать её в UI не нужно;
она же уходит в буфер по долгому тапу.

```sql
SELECT d.code, d.title, d.authors, d.year, d.citation
FROM lemmas l JOIN dicts d ON d.id = l.dict_id WHERE l.id = ?;
```

**Где ещё это слово.** Пара в `lemma_links` хранится один раз и без направления,
поэтому «другая» статья — та сторона, которая не равна нашей:

```sql
SELECT o.id, o.headword, o.homonym, od.title,
       k.method, k.confidence, k.conflict
FROM lemma_links k
JOIN lemmas o  ON o.id = CASE WHEN k.a_lemma_id = :id THEN k.b_lemma_id
                              ELSE k.a_lemma_id END
JOIN dicts  od ON od.id = o.dict_id
WHERE k.a_lemma_id = :id OR k.b_lemma_id = :id
ORDER BY k.confidence DESC, od.priority;
```

`conflict` непустой — значит книги расходятся в классе или части речи. Это не
ошибка: показываем оба варианта с указанием источника, а не выбираем победителя.
Пока словарь в базе один, запрос всегда возвращает пусто и секция не рисуется.

---

## Про диакритику

Всё, что идёт в UI, приходит из базы **со знаками**: `ха̃дадала`, `ка́ждый раз`.
Снимает их `Marks` (`ui/Marks.kt`) при отрисовке — по одному проходу на строку,
отдельно для долготы и для ударения, двумя переключателями в боковом меню. Обратной
операции нет и не нужно: размеченная строка и есть источник, а ключи поиска (`*_norm`)
лежат в базе уже очищенными.

Долгий тап по слову или фразе кладёт её в буфер обмена — всегда без знаков,
через `Diacritics.plain`. Поэтому скопированное можно сразу вставить обратно в строку
поиска: `гӀожо̃` → `гӀожо` → находит `гӀаж`.

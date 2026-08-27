# Запросы к `dict.db`

Рабочие запросы приложения. Схема — в `SCHEMA.md`, реализация — в `DictRepository.kt`.

---

## A. Чеченский → русский (основной)

Подаём `val key = ChechenNormalizer.normalize(input)`. `word_forms` — единственный вход:
заголовок, варианты, падежи, времена и сгенерированные классные формы лежат там вместе.

```sql
SELECT l.id, l.headword, l.homonym, p.name_ru AS pos, l.class_star,
       l.pluralia_tantum, l.labels, l.obj_num, l.subj_num,
       MAX(wf.is_headword) AS exact_headword,
       MIN(CASE WHEN wf.source = 'gen' THEN 1 ELSE 0 END) AS only_gen
FROM word_forms wf
JOIN lemmas l   ON l.id = wf.lemma_id
LEFT JOIN pos p ON p.id = l.pos_id
WHERE wf.form_norm = ?                        -- key
GROUP BY l.id
ORDER BY only_gen, exact_headword DESC, l.ordering;
```

`only_gen` опускает вниз статьи, найденные только по сгенерированной форме: ключ верный,
но орфография восстановлена алгоритмом. Любая косвенная форма (`куьйго`, `аьхна`,
`ваьккхира`) находит свою лемму — они перечислены в словаре.

## B. Русский → чеченский

Три слоя, от точного к приблизительному. Первый непустой выигрывает.

**B1. Вся фраза совпала с переводом.** Отдельной таблицы фраз нет — перевод целиком
лежит в `glosses.ru_norm`, по нему индекс.

```sql
SELECT …, 0 AS exact_headword, g.ru AS matched
FROM glosses g
JOIN lemmas l   ON l.id = g.lemma_id
LEFT JOIN pos p ON p.id = l.pos_id
WHERE g.ru_norm = ?                           -- «каждый раз» -> хӀоразза
GROUP BY l.id ORDER BY l.ordering LIMIT 100;
```

**B2. Все слова запроса есть в переводе.** `HAVING COUNT(DISTINCT …)` даёт пересечение,
поэтому «ошибки находить» находит `гӀа̃латашда̃ха`, а не всё подряд про ошибки.

```sql
SELECT …, 0 AS exact_headword,
       MIN(r.src) AS best_src, g.ru AS matched
FROM ru_index r
JOIN lemmas l       ON l.id = r.lemma_id
LEFT JOIN pos p     ON p.id = l.pos_id
LEFT JOIN glosses g ON g.id = r.target_id AND r.src IN (0, 3)
WHERE r.word IN (?, ?, …)
GROUP BY l.id
HAVING COUNT(DISTINCT r.word) = :n
ORDER BY best_src, l.ordering
LIMIT 100;
```

Две тонкости.

`MIN(r.src)` рядом с «голой» `g.ru` — документированное поведение SQLite: при
`min()`/`max()` неагрегированная колонка берётся из строки, давшей экстремум. Значит
показанный перевод принадлежит **сильнейшему** совпадению, а не случайному.

`src` — откуда взято совпадение: `0` перевод значения, `1` пример, `2` идиома,
`3` протянуто по отсылке. Поэтому «поголовно» находит `гӀаж` через идиому
«гӀаж такхолла мел верг», но ниже прямых переводов.

**B3. Запасной слой — основы Snowball.** Тот же запрос по `r.stem` с
`RuStem.stem(слово)`. Именно запасной: у «пол» основа общая с «поле», «полено»,
«полый», и как самостоятельный путь это мусор в выдаче.

## C. Примерный поиск чеч→рус (опечатки, части слова)

Идёт всегда параллельно точному: «куг»/«кюг» вместо «куьг» не находятся ни точным
поиском, ни подстрокой — отличаются сами буквы. Ловит скелет `FuzzyKey`.

Скелеты заголовков теперь считает сборщик, при старте их достаточно прочитать:

```sql
SELECT id, headword_fold FROM lemmas;
```

Подстрока по словоформам — через FTS5, если она есть:

```sql
SELECT DISTINCT l.id, l.headword_fold
FROM forms_trgm f
JOIN word_forms wf ON wf.id = f.rowid
JOIN lemmas l      ON l.id = wf.lemma_id
WHERE forms_trgm MATCH ? LIMIT 100;
```

Запасной вариант: `WHERE wf.form_norm LIKE '%' || ? || '%'`.

---

## Карточка статьи

**Формы.** Только напечатанные в книге — `source='gen'` показывать нельзя:

```sql
SELECT wf.form, wf.is_headword, ct.abbr_ru, ct.name_ru,
       nt.code AS number, vt.name_ru AS tam, wf.source
FROM word_forms wf
LEFT JOIN case_type   ct ON ct.id = wf.case_id
LEFT JOIN number_type nt ON nt.id = wf.number_id
LEFT JOIN verb_tam    vt ON vt.id = wf.tam_id
WHERE wf.lemma_id = ? AND wf.source = 'dict' AND wf.kind <> 'variant'
ORDER BY wf.is_headword DESC, nt.code, ct.ordering, wf.ordering;
```

**Значения и переводы.** Значение — это несколько глоссов, склеенных своим же
разделителем из книги (`sep`):

```sql
SELECT s.id, s.sense_no, s.block_n, p.name_ru AS pos, s.labels
FROM senses s LEFT JOIN pos p ON p.id = s.pos_id
WHERE s.lemma_id = ? ORDER BY s.block_n, s.ordering;

SELECT g.sense_id, g.ru, g.sep, g.note, g.gov, g.labels
FROM glosses g WHERE g.lemma_id = ? ORDER BY g.sense_id, g.idx;
```

`block_n` — блок `1.`/`2.`: одна статья, разные части речи
(`хе̃наза`: 1. прил. преждевременный, 2. нареч. преждевременно).

**Примеры.** Привязаны к значению и показываются под ним. Идиомы за «◊» относятся к
статье целиком — у них `sense_id IS NULL`, `is_idiom = 1`:

```sql
SELECT e.id, e.sense_id, e.is_idiom, e.ce, e.ru, e.kind,
       e.note, e.note_kind, e.gov, e.labels
FROM examples e WHERE e.lemma_id = ? ORDER BY e.is_idiom, e.sense_id, e.idx;

SELECT s.example_id, s.letter, s.ru, s.note, s.gov
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

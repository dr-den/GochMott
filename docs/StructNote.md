

---





### A. Чеченский → русский (основной). Точное совпадение нормализованной формы.
Подаём `val key = ChechenNormalizer.normalize(input)`.
```sql
SELECT l.id, l.headword, l.homograph_n, p.code AS pos,
       l.is_class_agreeing, l.pluralia_tantum, l.indeclinable, l.gram_note,
       MAX(wf.is_headword) AS exact_headword
FROM word_forms wf
JOIN lemmas l   ON l.id = wf.lemma_id
LEFT JOIN pos p ON p.id = l.pos_id
WHERE wf.form_norm = ?                       -- key
GROUP BY l.id
ORDER BY exact_headword DESC, l.homograph_n; -- точные заголовки выше
```
Любая косвенная форма (`мостагӀо`, `аьхна`, `ваьккхира`) найдёт свою лемму, т.к. формы перечислены в словаре. Дальше по `l.id` - значения и примеры (см. «Карточка статьи»).

### B. Русский → чеченский (обратный). По основе слова.
Подаём `val st = RuStem.stem(word)`.
```sql
SELECT DISTINCT l.id, l.headword, l.homograph_n, p.code AS pos,
       s.sense_no, s.gloss_ru
FROM ru_index ri
JOIN senses s   ON s.id = ri.sense_id
JOIN lemmas l   ON l.id = s.lemma_id
LEFT JOIN pos p ON p.id = l.pos_id
WHERE ri.stem = ?                            -- st
ORDER BY l.headword
LIMIT 100;
```
Так «врага», «врагов», «врагу» одинаково находят перевод, записанный как «враг».
Подстраховка, если у БД ещё не прогнан `lemmatize_ru.py` (stem пустой):
`WHERE ri.word = ? OR ri.word LIKE ? || '%'` (точное слово или префикс).

### C. Нечёткий/по части слова (опционально, FTS5). Для опечаток и ввода куска.
Подаём `key = ChechenNormalizer.normalize(input)` (длиной ≥3).
```sql
SELECT DISTINCT l.id, l.headword
FROM forms_trgm f
JOIN word_forms wf ON wf.id = f.rowid
JOIN lemmas l      ON l.id = wf.lemma_id
WHERE forms_trgm MATCH ?                      -- key
LIMIT 50;
```
Запасной вариант без FTS5: `WHERE wf.form_norm LIKE '%' || ? || '%'` (медленнее, но ок).

## Карточка статьи (детальный экран по l.id)
Формы с подписями падежей/времён:
```sql
SELECT wf.form, wf.is_headword, ct.abbr_ru AS case_abbr, ct.name_ru AS case_name,
       nt.code AS number, vt.name_ru AS tam, wf.source
FROM word_forms wf
LEFT JOIN case_type   ct ON ct.id = wf.case_id
LEFT JOIN number_type nt ON nt.id = wf.number_id
LEFT JOIN verb_tam    vt ON vt.id = wf.tam_id
WHERE wf.lemma_id = ?
ORDER BY wf.is_headword DESC, nt.code, ct.ordering;
```
Классы (показатели согласования) по числам:
```sql
SELECT gc.marker, nt.code AS number
FROM lemma_class lc
JOIN gram_class   gc ON gc.id = lc.class_id
JOIN number_type  nt ON nt.id = lc.number_id
WHERE lc.lemma_id = ?;
```
Значения с пометами:
```sql
SELECT s.id, s.sense_no, s.gloss_ru, s.domain
FROM senses s WHERE s.lemma_id = ? ORDER BY s.sense_no;
```
Примеры и идиомы (kind: example | idiom | proverb | saying):
```sql
SELECT ce_text, ru_text, kind FROM examples
WHERE lemma_id = ? ORDER BY (kind='idiom') DESC, id;
```
Перекрёстные ссылки (см./ср.) — `to_lemma_id` кликабелен, если не NULL:
```sql
SELECT rel_type, to_headword_raw, to_lemma_id
FROM cross_refs WHERE from_lemma_id = ?;
```

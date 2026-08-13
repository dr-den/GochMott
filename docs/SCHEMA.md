# SCHEMA.md — структура `dict.db`

Готовая SQLite-база Чеченско-русского словаря Мациева для офлайн-переводчика.
Только чтение. Не пересобирать. Кодировка ключей поиска — `normalize_ce.py`
(в приложении ему соответствует `ChechenNormalizer.kt`).

## Версия базы — `PRAGMA user_version`

Сейчас **2**. База целиком read-only, пользовательских данных в ней нет, поэтому на
устройстве она не мигрируется, а перезаписывается копией из assets. Признак «пора
перезаписать» — расхождение `PRAGMA user_version` локальной копии с константой
`DatabaseHelper.EXPECTED_DB_VERSION`.

**При каждой пересборке dict.db поднимайте оба числа сразу:** `PRAGMA user_version = N`
в самой БД и `EXPECTED_DB_VERSION = N` в коде. Иначе у тех, кто ставил приложение раньше,
останется старая копия и запросы к новой схеме упадут. Расхождение ловит юнит-тест
`DbVersionTest`.

| версия | что изменилось |
|---:|---|
| 2 | `ru_index`: `sense_id` → `lemma_id`, PK `(word, lemma_id)`, индекс по `lemma_id` |
| 1 (или 0) | первая версия словаря |

## Объёмы
| таблица | строк | что внутри |
|---|---:|---|
| `lemmas` | 20 423 | статьи (заголовочные слова) |
| `senses` | 22 452 | значения/переводы |
| `word_forms` | 65 998 | все словоформы (ядро прямого поиска) |
| `examples` | 5 655 | примеры, идиомы, пословицы |
| `cross_refs` | 1 503 | связи статей (см./ср./видовые пары) |
| `ru_index` | 57 799 | обратный индекс русских слов (рус→чеч, **на уровне статьи**) |
| `lemma_class` | 9 550 | классы в/й/д/б по числам |
| `sense_labels` | 5 339 | пометы значений (M:N) |
| `labels` | 83 | справочник всех помет словаря |

## Модель
```
lemma (статья) 1─┬─< sense        (значение/перевод)
                 ├─< word_form    (падежи, числа, формы глагола)  ← прямой поиск
                 ├─< lemma_class  (классы в/й/д/б, раздельно ед./мн.)
                 ├─< example      (примеры/идиомы)
                 └─< ru_index     (русские слова перевода → статья)  ← обратный поиск
sense M──N labels (через sense_labels)
cross_ref : lemma ↔ lemma
```

---

## Справочники (контролируемые словари)
Подставляй человекочитаемые имена из этих таблиц, в UI не показывай коды.

**`pos`** (id, code, name_ru) — части речи. Коды: `noun, verb, adj, adv, pron, num, postp, conj, part, interj, phrase`.

**`gram_class`** (id, marker, descr_ru) — классные показатели: `в, й, д, б`.

**`case_type`** (id, code, name_ru, abbr_ru, ordering) — падежи в порядке Мациева:
`nom`(им.), `gen`(род.), `dat`(дат.), `erg`(эрг.), `all`(местн.), `instr`(твор.), `cmp`(сравн.).

**`number_type`** (id, code, name_ru) — число: `sg`, `pl`.

**`verb_tam`** (id, code, name_ru, ordering) — время/вид/форма глагола:
`masdar`(словарная форма), `inf`, `pres`(наст.), `past_wit`(прош. очев.), `perf`(прош. сов.),
`imperf`, `fut`(буд.), `imp`(повел.), `cvb`(дееприч.), `ptcp`(прич.).

**`labels`** (id, code, kind, name_ru) — все 83 пометы словаря. `kind` ∈
`pos | case | gram_form | domain | register | ref` (напр. `перен`/register, `бот`/domain, `см`/ref).

---

## Ядро

### `lemmas` — статья
| колонка | смысл |
|---|---|
| `id` | PK |
| `headword` | как напечатано (уже очищено от латинских гомоглифов; у слот-глаголов может быть `*`) |
| `headword_norm` | нормализованный ключ; для слот-глаголов — база без показателя (`аха`) |
| `homograph_n` | 1,2,3 — номер омографа (мостагӀ¹/мостагӀ²) |
| `pos_id` | → `pos` |
| `is_class_agreeing` | 1 у слов, меняющих первую букву по классу |
| `class_slot` | символ-заполнитель (`*`), если есть |
| `pluralia_tantum` | 1 = «только мн.» |
| `indeclinable` | 1 = «нескл.» |
| `gram_note` | грам. примечание («субъект в ед.») |
| `raw_entry` | полная исходная строка статьи (для отладки/правок) |
| `confidence`, `needs_review` | качество разбора; `needs_review=1` — кандидат на ручную проверку (~6.6%) |
| `source_page`, `stress`, `notes` | провенанс/доп. (часто NULL) |

Уникальность: `(headword_norm, homograph_n)`.

### `senses` — значение
`id, lemma_id→lemmas, sense_no (1,2,3…), gloss_ru (чистый перевод), gloss_norm, domain (денорм. помета для показа), notes`.
Примеры из перевода вынесены в `examples`, поэтому `gloss_ru` — это именно перевод.

### `word_forms` — все словоформы (главный путь поиска)
| колонка | смысл |
|---|---|
| `id`, `lemma_id`→lemmas | |
| `form` | как напечатано (очищено) |
| `form_norm` | **ключ поиска** (`normalize_ce`); по нему индекс |
| `case_id`→case_type | для именных форм |
| `number_id`→number_type | |
| `class_id`→gram_class | класс согласования (мн. класс; классный префикс глагола) |
| `tam_id`→verb_tam | для глагольных форм (у заголовка глагола = `masdar`) |
| `is_headword` | 1 = заголовочная (словарная) форма |
| `source` | `dict` | `gen` (сгенерирована по классам) | `manual` |
| `form_label`, `confidence`, `raw` | подпись/качество |

Подпись падежа/времени для UI бери джойном: `case_type.abbr_ru` или `verb_tam.name_ru`.

### `lemma_class` — классы раздельно по числу
`id, lemma_id, class_id→gram_class, number_id→number_type, sense_id (обычно NULL), ordering`.
Пример: мостагӀ = {в,й} в ед. и {б} во мн. → четыре строки.

### `examples` — примеры/идиомы
`id, lemma_id, sense_id, ce_text (чеч.), ce_norm, ru_text (перевод), kind, notes`.
`kind` ∈ `example | collocation | idiom | proverb | saying` (идиомы — те, что в словаре за «◊»).
`notes='auto-split…'` (~39 шт.) — граница чеч/рус определена эвристикой, возможна неточность.

### `cross_refs` — связи статей
`id, from_lemma_id→lemmas, to_lemma_id→lemmas (NULL если не разрешено), to_headword_raw (сырой текст цели), rel_type, notes`.
`rel_type` ∈ `see | compare | same_as | variant | plural_of | aspect_pair | …`.
Если `to_lemma_id` не NULL — ссылку можно сделать кликабельной (переход на статью);
иначе ищи по `to_headword_raw`.

### `sense_labels` — пометы значений (M:N)
`(sense_id, label_id)` → таблица `labels`. Несколько помет на значение (перен.+бот. и т.п.).

---

## Поисковые надстройки

### `ru_index` — обратный индекс рус→чеч (портативный, без FTS5)

```sql
CREATE TABLE ru_index (
  word     TEXT    NOT NULL,   -- нормализ. русское слово (lower, ё->е, без пунктуации)
  stem     TEXT,               -- основа Snowball (lemmatize_ru.py / add_ru_stems.py)
  lemma_id INTEGER NOT NULL REFERENCES lemmas(id) ON DELETE CASCADE,
  PRIMARY KEY (word, lemma_id)
) WITHOUT ROWID;
CREATE INDEX idx_ru_index_word  ON ru_index(word);
CREATE INDEX idx_ru_index_stem  ON ru_index(stem);
CREATE INDEX idx_ru_index_lemma ON ru_index(lemma_id);
```

**Связь идёт напрямую на статью (`lemmas.id`), а не на значение.** Колонки `sense_id` больше нет.
Раньше индекс указывал на `senses.id`; при миграции `lemma_id` подставлен как `senses.lemma_id`,
дубли `(word, lemma_id)` схлопнуты (58 098 → 57 799 строк).

Запрос: `WHERE stem = RuStem.stem(запрос)` (после прогона скрипта основ) либо фолбэк
`WHERE word = ? OR word LIKE ?||'%'`. Джойн результата — сразу на `lemmas`:

```sql
SELECT DISTINCT l.id, l.headword, l.homograph_n
FROM ru_index r JOIN lemmas l ON l.id = r.lemma_id
WHERE r.stem = ?;
```

Значения для карточки статьи тянутся отдельно: `SELECT * FROM senses WHERE lemma_id = ? ORDER BY sense_no`.
Какое именно значение дало совпадение, индекс больше не хранит — если нужна подсветка
конкретного `gloss_ru`, ищи вхождение слова в `senses.gloss_norm` или используй `senses_fts`.

### `senses_fts` (FTS5) — полнотекст по `gloss_ru`
Виртуальная, синхронизируется триггерами. Альтернатива `ru_index` для рус→чеч,
если на устройстве доступен FTS5: `WHERE senses_fts MATCH ?`.
Это единственный путь, который отдаёт совпадение на уровне **значения** (`senses.id`).

### `forms_trgm` (FTS5 trigram) — нечёткий/подстрочный поиск по `form_norm`
Для опечаток и ввода части слова: `WHERE forms_trgm MATCH ?`.
⚠ Требует FTS5 в системном SQLite (есть не на всех старых Android — см. AGENT_PROMPT.md).

---

## Что важно помнить
- **Прямой поиск чеч→рус** (`word_forms.form_norm`) и **обратный рус→чеч** (`ru_index`)
  работают на штатном SQLite любого Android. FTS5 нужен только для нечёткого пути.
- **Обратный поиск возвращает статьи, а не значения.** Результат `ru_index` — это `lemma_id`;
  дедупликация по статье уже сделана на уровне PK, дополнительный `DISTINCT`/`GROUP BY` по
  `sense_id` в коде больше не нужен.
- Чеченский ввод нормализуй `ChechenNormalizer.normalize()`, русский — `RuStem.stem()`.
  Это те же алгоритмы, которыми построены ключи в БД; иначе матч не сойдётся.
- `gloss_ru` уже без примеров; примеры/идиомы — в `examples`; русские основы для
  поиска — в `ru_index.stem` (прогнать `lemmatize_ru.py`).

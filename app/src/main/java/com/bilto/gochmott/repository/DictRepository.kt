package com.bilto.gochmott.repository

import android.database.Cursor
import com.bilto.gochmott.db.DatabaseHelper
import com.bilto.gochmott.model.EntryDetail
import com.bilto.gochmott.model.Example
import com.bilto.gochmott.model.Form
import com.bilto.gochmott.model.Gloss
import com.bilto.gochmott.model.GramClass
import com.bilto.gochmott.model.LemmaHit
import com.bilto.gochmott.model.Marks
import com.bilto.gochmott.model.Ref
import com.bilto.gochmott.model.Sense
import com.bilto.gochmott.model.Sub
import com.bilto.gochmott.search.ChechenNormalizer
import com.bilto.gochmott.search.FuzzyKey
import com.bilto.gochmott.search.RuStem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DictRepository @Inject constructor(private val dbHelper: DatabaseHelper) {

    private companion object {
        const val MIN_FUZZY_LEN = 2        // короче — совпадёт пол-словаря
        const val FUZZY_LIMIT = 30         // сколько «похожих» чеченских статей отдаём в UI
        const val RU_SUGGESTION_LIMIT = 10 // сколько похожих русских слов предлагаем
        const val RU_HITS_LIMIT = 100      // потолок статей на одно русское слово

        /** Общий список колонок статьи: порядок жёстко завязан на [cursorToHit]. */
        const val LEMMA_COLUMNS =
            "l.id, l.headword, l.homonym, p.name_ru AS pos, l.class_star, " +
            "l.pluralia_tantum, l.labels, l.obj_num, l.subj_num"

        val RU_WORD = Regex("[а-яё]+")

        /** Ключ ведра для примеров, не привязанных к значению (идиомы за «◊»). */
        const val IDIOM_KEY = -1L
    }

    // Path A: Chechen → Russian (exact normalized form match).
    //
    // word_forms — единственный вход: заголовок, варианты, падежная и глагольная
    // парадигма и сгенерированные классные формы лежат там вместе. Сгенерированные
    // (source='gen') опускаем в конец выдачи: это верные ключи поиска, но их
    // орфография восстановлена алгоритмом, а не взята из книги.
    suspend fun searchChechen(input: String): List<LemmaHit> = withContext(Dispatchers.IO) {
        val key = ChechenNormalizer.normalize(input)
        if (key.isEmpty()) return@withContext emptyList()

        val sql = """
            SELECT $LEMMA_COLUMNS,
                   MAX(wf.is_headword) AS exact_headword,
                   MIN(CASE WHEN wf.source = 'gen' THEN 1 ELSE 0 END) AS only_gen
            FROM word_forms wf
            JOIN lemmas l   ON l.id = wf.lemma_id
            LEFT JOIN pos p ON p.id = l.pos_id
            WHERE wf.form_norm = ?
            GROUP BY l.id
            ORDER BY only_gen, exact_headword DESC, l.ordering
        """.trimIndent()

        dbHelper.database.rawQuery(sql, arrayOf(key)).use { buildHits(it) }
    }

    /**
     * Path B: Russian → Chechen.
     *
     * Обратный индекс пословный, а перевод целиком лежит в `glosses.ru_norm`,
     * поэтому запрос проходит тремя слоями, от точного к приблизительному:
     *
     *  1. вся фраза совпала с переводом    «ка́ждый раз»      -> хӀоразза
     *  2. все слова запроса есть в переводе «ошибки находить» -> гӀа̃латашда̃ха
     *  3. совпали основы Snowball          «утомления»        -> гӀелдар, хьахар¹
     *
     * Основы — именно запасной слой, а не отдельный путь: у «пол» основа общая
     * с «поле», «полено», «полый», и как самостоятельная выдача это мусор.
     */
    suspend fun searchRussian(input: String): List<LemmaHit> = withContext(Dispatchers.IO) {
        val phrase = normalizeRussian(input)
        if (phrase.isEmpty()) return@withContext emptyList()
        val words = RU_WORD.findAll(phrase).map { it.value }.toList()
        if (words.isEmpty()) return@withContext emptyList()

        val byPhrase = hitsForPhrase(phrase)
        val byWords = hitsForRussianWords(words, "word")
        val exact = (byPhrase + byWords).distinctBy { it.id }
        if (exact.isNotEmpty()) return@withContext exact

        val stems = words.map { RuStem.stem(it) }.filter { it.isNotEmpty() }
        if (stems.isEmpty()) return@withContext emptyList()
        hitsForRussianWords(stems, "stem")
    }

    /** Так же, как нормализованы ключи `ru_index.word` при сборке БД. */
    private fun normalizeRussian(input: String): String =
        input.trim().lowercase().replace('ё', 'е')
            .filterNot { it == Marks.STRESS }   // вставленный текст бывает с ударениями
            .replace(Regex("\\s+"), " ")

    // Path C: примерный поиск чеч→рус. Идёт ВСЕГДА, параллельно точному, — потому что
    // «куг»/«кюг» вместо «куьг» не находятся ни точным поиском, ни подстрокой:
    // отличие в самих буквах. Ловит их скелет FuzzyKey (ь/ъ/палочка выброшены, ю→у…).
    suspend fun searchChechenFuzzy(
        input: String,
        exclude: Set<Long> = emptySet()
    ): List<LemmaHit> = withContext(Dispatchers.IO) {
        val skeleton = FuzzyKey.chechen(input)
        if (skeleton.length < MIN_FUZZY_LEN) return@withContext emptyList()
        val maxEdits = FuzzyKey.maxEdits(skeleton.length)
        val maxRank = FuzzyKey.maxRank(skeleton.length)

        val candidates = HashMap<Long, FuzzyCandidate>()
        fun offer(lemmaId: Long, candidateSkeleton: String) {
            if (lemmaId in exclude) return
            val scored = scoreCandidate(candidateSkeleton, skeleton, maxEdits)
            if (scored.rank > maxRank) return
            val prev = candidates[lemmaId]
            if (prev == null || scored < prev) candidates[lemmaId] = scored
        }

        // 1) скелеты заголовков: куг/кюг → куьг, мостаг → мостагӀ, хума → хӀума
        val index = fuzzyIndex()
        for (i in index.ceLemmaId.indices) offer(index.ceLemmaId[i], index.ceSkeleton[i])

        // 2) подстрока по всем словоформам: части слова и падежные/глагольные формы
        substringFormMatches(ChechenNormalizer.normalize(input)).forEach { (lemmaId, skeleton2) ->
            offer(lemmaId, skeleton2)
        }

        val order = candidates.entries
            .sortedWith(compareBy({ it.value }, { it.key }))
            .take(FUZZY_LIMIT).map { it.key }

        val byId = loadHits(order).associateBy { it.id }
        order.mapNotNull { byId[it] }
    }

    /**
     * Path D: похожие РУССКИЕ СЛОВА — подсказки на случай, когда точного слова в индексе
     * нет. Возвращает слова, а не статьи: пользователь сначала выбирает слово, и только
     * потом видит его переводы. Смешивать статьи разных слов в одну выдачу нельзя —
     * непонятно, что чему перевод.
     */
    suspend fun suggestRussianWords(
        input: String,
        limit: Int = RU_SUGGESTION_LIMIT
    ): List<String> = withContext(Dispatchers.IO) {
        val skeleton = FuzzyKey.russian(input.trim())
        if (skeleton.length < MIN_FUZZY_LEN) return@withContext emptyList()
        val maxEdits = FuzzyKey.maxEdits(skeleton.length)
        val maxRank = FuzzyKey.maxRank(skeleton.length)

        val index = fuzzyIndex()
        val scored = ArrayList<Pair<String, FuzzyCandidate>>()
        for (i in index.ruWord.indices) {
            val candidate = scoreCandidate(index.ruSkeleton[i], skeleton, maxEdits)
            if (candidate.rank > maxRank) continue
            scored.add(index.ruWord[i] to candidate)
        }

        scored.sortedWith(compareBy({ it.second }, { it.first }))
            .map { it.first }.distinct().take(limit)
    }

    private data class FuzzyCandidate(
        val rank: Int,
        /** Длина общего начала с запросом: опечатка обычно не в первой букве. */
        val commonPrefix: Int,
        /** Насколько кандидат отличается длиной от запроса. */
        val lengthDelta: Int,
        val length: Int
    ) : Comparable<FuzzyCandidate> {
        override fun compareTo(other: FuzzyCandidate): Int = COMPARATOR.compare(this, other)

        companion object {
            private val COMPARATOR = compareBy<FuzzyCandidate>(
                { it.rank }, { -it.commonPrefix }, { it.lengthDelta }, { it.length }
            )
        }
    }

    private fun scoreCandidate(candidateSkeleton: String, query: String, maxEdits: Int) =
        FuzzyCandidate(
            rank = FuzzyKey.rank(candidateSkeleton, query, maxEdits),
            commonPrefix = candidateSkeleton.commonPrefixWith(query).length,
            lengthDelta = kotlin.math.abs(candidateSkeleton.length - query.length),
            length = candidateSkeleton.length
        )

    /** Словоформы, содержащие ключ как подстроку (FTS5-триграммы + LIKE). */
    private fun substringFormMatches(key: String): List<Pair<Long, String>> {
        if (key.length < MIN_FUZZY_LEN) return emptyList()
        val found = LinkedHashMap<Long, String>()

        if (dbHelper.hasFts5) {
            val sqlFts = """
                SELECT DISTINCT l.id, l.headword_fold
                FROM forms_trgm f
                JOIN word_forms wf ON wf.id = f.rowid
                JOIN lemmas l      ON l.id = wf.lemma_id
                WHERE forms_trgm MATCH ?
                LIMIT 100
            """.trimIndent()
            try {
                dbHelper.database.rawQuery(sqlFts, arrayOf(key)).use { cursor ->
                    while (cursor.moveToNext()) found[cursor.getLong(0)] = cursor.getString(1) ?: ""
                }
            } catch (_: Exception) { }
        }

        // полный ключ + без первого символа + без последнего
        val patterns = buildList {
            add(key)
            if (key.length > 2) add(key.drop(1))
            if (key.length > 2) add(key.dropLast(1))
        }.distinct()

        val sqlLike = """
            SELECT DISTINCT l.id, l.headword_fold
            FROM word_forms wf
            JOIN lemmas l ON l.id = wf.lemma_id
            WHERE wf.form_norm LIKE ?
            LIMIT 100
        """.trimIndent()
        patterns.forEach { pattern ->
            dbHelper.database.rawQuery(sqlLike, arrayOf("%$pattern%")).use { cursor ->
                while (cursor.moveToNext()) found[cursor.getLong(0)] = cursor.getString(1) ?: ""
            }
        }
        return found.map { it.key to it.value }
    }

    /** Весь перевод целиком совпал с запросом — самое сильное попадание рус→чеч. */
    private fun hitsForPhrase(phrase: String): List<LemmaHit> {
        val sql = """
            SELECT $LEMMA_COLUMNS, 0 AS exact_headword, g.ru AS matched
            FROM glosses g
            JOIN lemmas l   ON l.id = g.lemma_id
            LEFT JOIN pos p ON p.id = l.pos_id
            WHERE g.ru_norm = ?
            GROUP BY l.id
            ORDER BY l.ordering
            LIMIT $RU_HITS_LIMIT
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(phrase)).use { buildHits(it, matched = 10) }
    }

    /**
     * Статьи, у которых в переводе есть ВСЕ переданные слова (или основы).
     *
     * `src` в индексе задаёт, откуда взято совпадение: 0 — перевод значения,
     * 1 — перевод примера, 2 — идиома, 3 — протянуто по отсылке («понуд. от»).
     * Сортировка по `MIN(src)` поднимает словарные значения над примерами.
     */
    private fun hitsForRussianWords(keys: List<String>, column: String): List<LemmaHit> {
        if (keys.isEmpty()) return emptyList()
        val distinct = keys.distinct()
        val placeholders = distinct.joinToString(",") { "?" }
        // MIN(r.src) рядом с «голой» g.ru — документированное поведение SQLite:
        // неагрегированная колонка берётся из той строки, что дала минимум.
        // Значит показанный перевод — это перевод сильнейшего совпадения.
        val sql = """
            SELECT $LEMMA_COLUMNS, 0 AS exact_headword,
                   MIN(r.src) AS best_src, g.ru AS matched
            FROM ru_index r
            JOIN lemmas l          ON l.id = r.lemma_id
            LEFT JOIN pos p        ON p.id = l.pos_id
            LEFT JOIN glosses g    ON g.id = r.target_id AND r.src IN (0, 3)
            WHERE r.$column IN ($placeholders)
            GROUP BY l.id
            HAVING COUNT(DISTINCT r.$column) = ${distinct.size}
            ORDER BY best_src, l.ordering
            LIMIT $RU_HITS_LIMIT
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, distinct.toTypedArray())
            .use { buildHits(it, matched = 11) }
    }

    private fun loadHits(ids: List<Long>): List<LemmaHit> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = """
            SELECT $LEMMA_COLUMNS, 0 AS exact_headword
            FROM lemmas l
            LEFT JOIN pos p ON p.id = l.pos_id
            WHERE l.id IN ($placeholders)
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, ids.map { it.toString() }.toTypedArray())
            .use { buildHits(it) }
    }

    // ---- индекс примерного поиска ----
    // Скелеты чеченских заголовков теперь считает сборщик БД (`headword_fold`,
    // тот же порт FuzzyKey), поэтому при старте их достаточно прочитать.
    // Русские скелеты считаются на месте: в БД лежат только сами слова.

    private class FuzzyIndex(
        val ceLemmaId: LongArray,
        val ceSkeleton: Array<String>,
        val ruWord: Array<String>,
        val ruSkeleton: Array<String>
    )

    @Volatile private var cachedIndex: FuzzyIndex? = null
    private val indexMutex = Mutex()

    /** Строит индекс заранее, чтобы первый примерный поиск не ждал. */
    suspend fun warmUpFuzzyIndex() { fuzzyIndex() }

    private suspend fun fuzzyIndex(): FuzzyIndex =
        cachedIndex ?: indexMutex.withLock {
            cachedIndex ?: withContext(Dispatchers.IO) { buildFuzzyIndex() }.also { cachedIndex = it }
        }

    private fun buildFuzzyIndex(): FuzzyIndex {
        val ceIds = ArrayList<Long>(21_000)
        val ceKeys = ArrayList<String>(21_000)
        dbHelper.database.rawQuery("SELECT id, headword_fold FROM lemmas", null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getStringOrNull(1) ?: continue
                if (key.length < MIN_FUZZY_LEN) continue
                ceIds.add(cursor.getLong(0))
                ceKeys.add(key)
            }
        }

        val ruWords = ArrayList<String>(29_000)
        val ruKeys = ArrayList<String>(29_000)
        dbHelper.database.rawQuery("SELECT DISTINCT word FROM ru_index", null).use { cursor ->
            while (cursor.moveToNext()) {
                val word = cursor.getStringOrNull(0) ?: continue
                val key = FuzzyKey.russian(word)
                if (key.length < MIN_FUZZY_LEN) continue
                ruWords.add(word)
                ruKeys.add(key)
            }
        }

        return FuzzyIndex(
            ceLemmaId = ceIds.toLongArray(),
            ceSkeleton = ceKeys.toTypedArray(),
            ruWord = ruWords.toTypedArray(),
            ruSkeleton = ruKeys.toTypedArray()
        )
    }

    // Full article detail by lemma id
    suspend fun getEntryDetail(lemmaId: Long): EntryDetail = withContext(Dispatchers.IO) {
        val lemma = getLemmaHit(lemmaId)
        val examplesBySense = getExamples(lemmaId)
        EntryDetail(
            lemma = lemma.copy(classes = getClasses(lemmaId)),
            forms = getForms(lemmaId),
            senses = getSenses(lemmaId, examplesBySense),
            idioms = examplesBySense[IDIOM_KEY].orEmpty(),
            refs = getRefs(lemmaId)
        )
    }

    private fun getLemmaHit(lemmaId: Long): LemmaHit {
        val sql = """
            SELECT $LEMMA_COLUMNS, 1 AS exact_headword
            FROM lemmas l
            LEFT JOIN pos p ON p.id = l.pos_id
            WHERE l.id = ?
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursorToHit(cursor) else LemmaHit(
                id = lemmaId, headword = "?", homographN = 0, pos = null,
                isClassAgreeing = false, pluraliaTantum = false, labels = emptyList(),
                objNum = null, subjNum = null, exactHeadword = false
            )
        }
    }

    /**
     * Формы для карточки — только напечатанные в книге.
     *
     * `source='gen'` отфильтрован сознательно: замена классного показателя даёт
     * верный ключ поиска, но не всегда принятую орфографию (у `даа` й-класс
     * пишется `яа`, а генератор выдаёт `йаа`). Искать по ним можно, показывать
     * как форму слова — нет.
     *
     * Заголовочная форма тоже не возвращается: она уже стоит в шапке карточки.
     * Если её оставить, у статьи без парадигмы список окажется непустым, таблица
     * отфильтрует единственную строку и покажет пустую рамку.
     */
    private fun getForms(lemmaId: Long): List<Form> {
        val sql = """
            SELECT wf.form, wf.is_headword,
                   ct.abbr_ru AS case_abbr, ct.name_ru AS case_name,
                   nt.code AS number, vt.name_ru AS tam, wf.source
            FROM word_forms wf
            LEFT JOIN case_type   ct ON ct.id = wf.case_id
            LEFT JOIN number_type nt ON nt.id = wf.number_id
            LEFT JOIN verb_tam    vt ON vt.id = wf.tam_id
            WHERE wf.lemma_id = ? AND wf.source = 'dict'
              AND wf.kind NOT IN ('variant', 'headword')
            ORDER BY wf.is_headword DESC, wf.number_id, ct.ordering, wf.ordering
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Form(
                        form = Marks.ce(cursor.getString(0)),
                        isHeadword = cursor.getInt(1) == 1,
                        caseAbbr = cursor.getStringOrNull(2),
                        caseName = cursor.getStringOrNull(3),
                        number = cursor.getStringOrNull(4),
                        tam = cursor.getStringOrNull(5),
                        source = cursor.getStringOrNull(6)
                    ))
                }
            }
        }
    }

    private fun getSenses(lemmaId: Long, examples: Map<Long, List<Example>>): List<Sense> {
        val glosses = getGlosses(lemmaId)
        val sql = """
            SELECT s.id, s.sense_no, s.block_n, p.name_ru AS pos, s.labels
            FROM senses s
            LEFT JOIN pos p ON p.id = s.pos_id
            WHERE s.lemma_id = ?
            ORDER BY s.block_n, s.ordering
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    add(Sense(
                        id = id,
                        senseNo = if (cursor.isNull(1)) null else cursor.getInt(1),
                        blockN = if (cursor.isNull(2)) null else cursor.getInt(2),
                        pos = cursor.getStringOrNull(3),
                        labels = jsonList(cursor.getStringOrNull(4)),
                        glosses = glosses[id].orEmpty(),
                        examples = examples[id].orEmpty()
                    ))
                }
            }
        }
    }

    private fun getGlosses(lemmaId: Long): Map<Long, List<Gloss>> {
        val sql = """
            SELECT g.sense_id, g.ru, g.sep, g.note, g.gov, g.labels
            FROM glosses g
            WHERE g.lemma_id = ?
            ORDER BY g.sense_id, g.idx
        """.trimIndent()
        val out = LinkedHashMap<Long, MutableList<Gloss>>()
        dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                out.getOrPut(cursor.getLong(0)) { mutableListOf() }.add(Gloss(
                    ru = Marks.ru(cursor.getString(1)) ?: "",
                    sep = cursor.getStringOrNull(2),
                    note = Marks.ru(cursor.getStringOrNull(3)),
                    gov = cursor.getStringOrNull(4),
                    labels = jsonList(cursor.getStringOrNull(5))
                ))
            }
        }
        return out
    }

    /** Примеры, разложенные по значениям. Идиомы статьи лежат под [IDIOM_KEY]. */
    private fun getExamples(lemmaId: Long): Map<Long, List<Example>> {
        val subs = getSubs(lemmaId)
        val sql = """
            SELECT e.id, e.sense_id, e.is_idiom, e.ce, e.ru, e.kind,
                   e.note, e.note_kind, e.gov, e.labels
            FROM examples e
            WHERE e.lemma_id = ?
            ORDER BY e.is_idiom, e.sense_id, e.idx
        """.trimIndent()
        val out = LinkedHashMap<Long, MutableList<Example>>()
        dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val isIdiom = cursor.getInt(2) == 1
                val bucket = if (isIdiom || cursor.isNull(1)) IDIOM_KEY else cursor.getLong(1)
                out.getOrPut(bucket) { mutableListOf() }.add(Example(
                    ceText = Marks.ce(cursor.getString(3)),
                    ruText = Marks.ru(cursor.getStringOrNull(4)),
                    kind = cursor.getStringOrNull(5),
                    isIdiom = isIdiom,
                    note = Marks.ru(cursor.getStringOrNull(6)),
                    noteKind = cursor.getStringOrNull(7),
                    gov = cursor.getStringOrNull(8),
                    labels = jsonList(cursor.getStringOrNull(9)),
                    subs = subs[id].orEmpty()
                ))
            }
        }
        return out
    }

    private fun getSubs(lemmaId: Long): Map<Long, List<Sub>> {
        val sql = """
            SELECT s.example_id, s.letter, s.ru, s.note, s.gov
            FROM subs s
            JOIN examples e ON e.id = s.example_id
            WHERE e.lemma_id = ?
            ORDER BY s.example_id, s.idx
        """.trimIndent()
        val out = LinkedHashMap<Long, MutableList<Sub>>()
        dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                out.getOrPut(cursor.getLong(0)) { mutableListOf() }.add(Sub(
                    letter = cursor.getStringOrNull(1),
                    ru = Marks.ru(cursor.getString(2)) ?: "",
                    note = Marks.ru(cursor.getStringOrNull(3)),
                    gov = cursor.getStringOrNull(4)
                ))
            }
        }
        return out
    }

    private fun getRefs(lemmaId: Long): List<Ref> {
        val sql = """
            SELECT rel, to_headword, to_lemma_id
            FROM cross_refs
            WHERE from_lemma_id = ?
            ORDER BY id
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Ref(
                        rel = cursor.getString(0) ?: "",
                        toHeadword = Marks.ce(cursor.getString(1)),
                        toLemmaId = if (cursor.isNull(2)) null else cursor.getLong(2)
                    ))
                }
            }
        }
    }

    private fun getClasses(lemmaId: Long): List<GramClass> {
        val sql = """
            SELECT marker, number FROM lemma_class
            WHERE lemma_id = ? ORDER BY number DESC, ordering
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(GramClass(cursor.getString(0) ?: "", cursor.getString(1) ?: ""))
                }
            }
        }
    }

    /**
     * Довешивает на карточки выдачи первые два значения и классы.
     *
     * Значение теперь состоит из нескольких переводов («ослабле́ние; утомле́ние»),
     * поэтому глоссы одного значения склеиваются своим же разделителем из книги.
     */
    suspend fun enrichHits(hits: List<LemmaHit>): List<LemmaHit> = withContext(Dispatchers.IO) {
        if (hits.isEmpty()) return@withContext emptyList()
        val ids = hits.map { it.id }
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()

        val sensesMap = mutableMapOf<Long, MutableList<StringBuilder>>()
        val senseOrder = mutableMapOf<Long, MutableMap<Long, Int>>()
        dbHelper.database.rawQuery(
            """SELECT g.lemma_id, g.sense_id, g.ru, g.sep
               FROM glosses g
               JOIN senses s ON s.id = g.sense_id
               WHERE g.lemma_id IN ($placeholders)
               ORDER BY g.lemma_id, s.block_n, s.ordering, g.idx""",
            args
        ).use { c ->
            while (c.moveToNext()) {
                val lid = c.getLong(0)
                val sid = c.getLong(1)
                val ru = Marks.ru(c.getString(2)) ?: continue
                val order = senseOrder.getOrPut(lid) { mutableMapOf() }
                val lines = sensesMap.getOrPut(lid) { mutableListOf() }
                val slot = order[sid]
                if (slot == null) {
                    if (lines.size >= 2) continue          // в карточку идут первые два
                    order[sid] = lines.size
                    lines.add(StringBuilder(ru))
                } else {
                    lines[slot].append(c.getStringOrNull(3) ?: ",").append(' ').append(ru)
                }
            }
        }

        val classesMap = mutableMapOf<Long, MutableList<GramClass>>()
        dbHelper.database.rawQuery(
            """SELECT lemma_id, marker, number FROM lemma_class
               WHERE lemma_id IN ($placeholders) ORDER BY number DESC, ordering""",
            args
        ).use { c ->
            while (c.moveToNext()) {
                classesMap.getOrPut(c.getLong(0)) { mutableListOf() }
                    .add(GramClass(c.getString(1) ?: "", c.getString(2) ?: ""))
            }
        }

        hits.map { hit ->
            hit.copy(
                firstSenses = sensesMap[hit.id]?.map { it.toString() } ?: emptyList(),
                classes = classesMap[hit.id] ?: emptyList()
            )
        }
    }

    private fun buildHits(cursor: Cursor, matched: Int = -1): List<LemmaHit> = buildList {
        while (cursor.moveToNext()) add(cursorToHit(cursor, matched))
    }

    private fun cursorToHit(cursor: Cursor, matched: Int = -1) = LemmaHit(
        id = cursor.getLong(0),
        headword = Marks.ce(cursor.getString(1)),
        // homonym в БД NULL у неомонимов — в модели это 0, надстрочный номер не рисуем
        homographN = if (cursor.isNull(2)) 0 else cursor.getInt(2),
        pos = cursor.getStringOrNull(3),
        isClassAgreeing = cursor.getInt(4) == 1,
        pluraliaTantum = cursor.getInt(5) == 1,
        labels = jsonList(cursor.getStringOrNull(6)),
        objNum = cursor.getStringOrNull(7),
        subjNum = cursor.getStringOrNull(8),
        exactHeadword = cursor.getInt(9) == 1,
        matchedGloss = if (matched >= 0 && matched < cursor.columnCount)
            Marks.ru(cursor.getStringOrNull(matched)) else null
    )

    /** Пометы и классы лежат JSON-массивами: в SQLite нет типа «массив». */
    private fun jsonList(raw: String?): List<String> {
        if (raw.isNullOrEmpty() || raw == "[]") return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList { for (i in 0 until array.length()) add(array.optString(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)
}

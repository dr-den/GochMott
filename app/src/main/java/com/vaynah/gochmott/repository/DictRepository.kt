package com.vaynah.gochmott.repository

import android.database.Cursor
import com.vaynah.gochmott.db.DatabaseHelper
import com.vaynah.gochmott.model.EntryDetail
import com.vaynah.gochmott.model.Example
import com.vaynah.gochmott.model.Form
import com.vaynah.gochmott.model.GramClass
import com.vaynah.gochmott.model.LemmaHit
import com.vaynah.gochmott.model.Ref
import com.vaynah.gochmott.model.Sense
import com.vaynah.gochmott.search.ChechenNormalizer
import com.vaynah.gochmott.search.FuzzyKey
import com.vaynah.gochmott.search.RuStem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DictRepository @Inject constructor(private val dbHelper: DatabaseHelper) {

    private companion object {
        const val MIN_FUZZY_LEN = 2        // короче — совпадёт пол-словаря
        const val FUZZY_LIMIT = 30         // сколько «похожих» чеченских статей отдаём в UI
        const val RU_SUGGESTION_LIMIT = 10 // сколько похожих русских слов предлагаем
        const val RU_HITS_LIMIT = 100      // потолок статей на одно русское слово
    }

    // Path A: Chechen → Russian (exact normalized form match)
    suspend fun searchChechen(input: String): List<LemmaHit> = withContext(Dispatchers.IO) {
        val key = ChechenNormalizer.normalize(input)
        if (key.isEmpty()) return@withContext emptyList()

        val sql = """
            SELECT l.id, l.headword, l.homograph_n, p.name_ru AS pos,
                   l.is_class_agreeing, l.pluralia_tantum, l.indeclinable, l.gram_note,
                   MAX(wf.is_headword) AS exact_headword
            FROM word_forms wf
            JOIN lemmas l   ON l.id = wf.lemma_id
            LEFT JOIN pos p ON p.id = l.pos_id
            WHERE wf.form_norm = ?
            GROUP BY l.id
            ORDER BY exact_headword DESC, l.homograph_n
        """.trimIndent()

        dbHelper.database.rawQuery(sql, arrayOf(key)).use { cursor ->
            buildHits(cursor)
        }
    }

    // Path B: Russian → Chechen (reverse index).
    // ru_index ссылается прямо на lemmas.id — джойн через senses не нужен, а какое именно
    // значение дало совпадение, индекс не хранит: глоссы навешивает enrichHits.
    //
    // Сначала ТОЧНОЕ слово: если «пол» есть в индексе, отдаём ровно его статьи и ничего
    // больше не подмешиваем. Поиск по основе Snowball — только запасной путь для форм,
    // которых в индексе нет: основа у «пол» общая с «поле», «полено», «полый» (8 статей
    // против 30), у «вода» — с «водить». Как отдельный путь это мусор в выдаче.
    suspend fun searchRussian(input: String): List<LemmaHit> = withContext(Dispatchers.IO) {
        val word = normalizeRussian(input)
        if (word.isEmpty()) return@withContext emptyList()

        val exact = hitsForRussianWord(word)
        if (exact.isNotEmpty()) return@withContext exact

        val stem = RuStem.stem(word)
        if (stem.isEmpty()) return@withContext emptyList()

        // DISTINCT нужен: разные словоформы («рука», «руки») имеют одну основу
        // и ведут в одну статью.
        val sqlByStem = """
            SELECT DISTINCT l.id, l.headword, l.homograph_n, p.name_ru AS pos,
                   l.is_class_agreeing, l.pluralia_tantum, l.indeclinable, l.gram_note,
                   0 AS exact_headword
            FROM ru_index ri
            JOIN lemmas l   ON l.id = ri.lemma_id
            LEFT JOIN pos p ON p.id = l.pos_id
            WHERE ri.stem = ?
            ORDER BY l.headword
            LIMIT $RU_HITS_LIMIT
        """.trimIndent()

        dbHelper.database.rawQuery(sqlByStem, arrayOf(stem)).use { buildHits(it) }
    }

    /** Так же, как нормализованы ключи `ru_index.word` при сборке БД. */
    private fun normalizeRussian(input: String): String =
        input.trim().lowercase().replace('ё', 'е')

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
        substringFormMatches(ChechenNormalizer.normalize(input)).forEach { (lemmaId, headword) ->
            offer(lemmaId, FuzzyKey.chechen(headword))
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
                SELECT DISTINCT l.id, l.headword
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
            SELECT DISTINCT l.id, l.headword
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

    /**
     * Все статьи, связанные с одним русским словом. PK `(word, lemma_id)` гарантирует,
     * что дублей не будет. Потолок нужен из-за служебных «слов», просочившихся в индекс
     * при разборе словаря («мн» — 655 статей, «прич» — 391).
     */
    private fun hitsForRussianWord(word: String): List<LemmaHit> {
        val sql = """
            SELECT l.id, l.headword, l.homograph_n, p.name_ru AS pos,
                   l.is_class_agreeing, l.pluralia_tantum, l.indeclinable, l.gram_note,
                   0 AS exact_headword
            FROM ru_index ri
            JOIN lemmas l   ON l.id = ri.lemma_id
            LEFT JOIN pos p ON p.id = l.pos_id
            WHERE ri.word = ?
            ORDER BY l.headword
            LIMIT $RU_HITS_LIMIT
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(word)).use { buildHits(it) }
    }

    private fun loadHits(ids: List<Long>): List<LemmaHit> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = """
            SELECT l.id, l.headword, l.homograph_n, p.name_ru AS pos,
                   l.is_class_agreeing, l.pluralia_tantum, l.indeclinable, l.gram_note,
                   0 AS exact_headword
            FROM lemmas l
            LEFT JOIN pos p ON p.id = l.pos_id
            WHERE l.id IN ($placeholders)
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, ids.map { it.toString() }.toTypedArray())
            .use { buildHits(it) }
    }

    // ---- индекс примерного поиска ----
    // Скелетов нет в БД (её нельзя пересобирать), поэтому держим их в памяти:
    // 20 423 заголовка + 28 700 русских слов ≈ 3 МБ. Прогон запроса по всему массиву с
    // отсечкой по Левенштейну — единицы миллисекунд, так что индекс плоский, без хеш-карт.

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
        dbHelper.database.rawQuery("SELECT id, headword_norm FROM lemmas", null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = FuzzyKey.chechenFromNormalized(cursor.getStringOrNull(1))
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
        val forms = getForms(lemmaId)
        val senses = getSenses(lemmaId)
        val examples = getExamples(lemmaId)
        val refs = getRefs(lemmaId)
        val classes = getClasses(lemmaId)

        EntryDetail(
            lemma = lemma.copy(classes = classes),
            forms = forms,
            senses = senses,
            examples = examples,
            refs = refs
        )
    }

    private fun getLemmaHit(lemmaId: Long): LemmaHit {
        val sql = """
            SELECT l.id, l.headword, l.homograph_n, p.name_ru AS pos,
                   l.is_class_agreeing, l.pluralia_tantum, l.indeclinable, l.gram_note,
                   1 AS exact_headword
            FROM lemmas l
            LEFT JOIN pos p ON p.id = l.pos_id
            WHERE l.id = ?
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            if (cursor.moveToFirst()) cursorToHit(cursor) else LemmaHit(
                id = lemmaId, headword = "?", homographN = 1, pos = null,
                isClassAgreeing = false, pluraliaTantum = false, indeclinable = false,
                gramNote = null, exactHeadword = false
            )
        }
    }

    private fun getForms(lemmaId: Long): List<Form> {
        val sql = """
            SELECT wf.form, wf.is_headword,
                   ct.abbr_ru AS case_abbr, ct.name_ru AS case_name,
                   nt.code AS number, vt.name_ru AS tam, wf.source
            FROM word_forms wf
            LEFT JOIN case_type   ct ON ct.id = wf.case_id
            LEFT JOIN number_type nt ON nt.id = wf.number_id
            LEFT JOIN verb_tam    vt ON vt.id = wf.tam_id
            WHERE wf.lemma_id = ?
            ORDER BY wf.is_headword DESC, nt.code, ct.ordering
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Form(
                        form = cursor.getString(0) ?: "",
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

    private fun getSenses(lemmaId: Long): List<Sense> {
        val sql = """
            SELECT s.id, s.sense_no, s.gloss_ru, s.domain
            FROM senses s
            WHERE s.lemma_id = ?
            ORDER BY s.sense_no
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Sense(
                        id = cursor.getLong(0),
                        senseNo = cursor.getInt(1),
                        glossRu = cursor.getString(2) ?: "",
                        domain = cursor.getStringOrNull(3)
                    ))
                }
            }
        }
    }

    private fun getExamples(lemmaId: Long): List<Example> {
        val sql = """
            SELECT ce_text, ru_text, kind
            FROM examples
            WHERE lemma_id = ?
            ORDER BY (kind='idiom') DESC, id
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Example(
                        ceText = cursor.getString(0) ?: "",
                        ruText = cursor.getStringOrNull(1),
                        kind = cursor.getString(2) ?: "example"
                    ))
                }
            }
        }
    }

    private fun getRefs(lemmaId: Long): List<Ref> {
        val sql = """
            SELECT rel_type, to_headword_raw, to_lemma_id
            FROM cross_refs
            WHERE from_lemma_id = ?
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Ref(
                        relType = cursor.getString(0) ?: "",
                        toHeadwordRaw = cursor.getString(1) ?: "",
                        toLemmaId = if (cursor.isNull(2)) null else cursor.getLong(2)
                    ))
                }
            }
        }
    }

    private fun getClasses(lemmaId: Long): List<GramClass> {
        val sql = """
            SELECT gc.marker, nt.code AS number
            FROM lemma_class lc
            JOIN gram_class   gc ON gc.id = lc.class_id
            JOIN number_type  nt ON nt.id = lc.number_id
            WHERE lc.lemma_id = ?
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(GramClass(
                        marker = cursor.getString(0) ?: "",
                        number = cursor.getString(1) ?: ""
                    ))
                }
            }
        }
    }

    // Enrich hits with first 2 senses and gram classes (single batch query per type)
    suspend fun enrichHits(hits: List<LemmaHit>): List<LemmaHit> = withContext(Dispatchers.IO) {
        if (hits.isEmpty()) return@withContext emptyList()
        val ids = hits.map { it.id }
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()

        val sensesMap = mutableMapOf<Long, MutableList<String>>()
        dbHelper.database.rawQuery(
            "SELECT lemma_id, gloss_ru FROM senses WHERE lemma_id IN ($placeholders) ORDER BY lemma_id, sense_no",
            args
        ).use { c ->
            while (c.moveToNext()) {
                val lid = c.getLong(0)
                val g = c.getString(1) ?: continue
                sensesMap.getOrPut(lid) { mutableListOf() }.also { if (it.size < 2) it.add(g) }
            }
        }

        val classesMap = mutableMapOf<Long, MutableList<GramClass>>()
        dbHelper.database.rawQuery(
            """SELECT lc.lemma_id, gc.marker, nt.code
               FROM lemma_class lc
               JOIN gram_class gc ON gc.id = lc.class_id
               JOIN number_type nt ON nt.id = lc.number_id
               WHERE lc.lemma_id IN ($placeholders)""",
            args
        ).use { c ->
            while (c.moveToNext()) {
                val lid = c.getLong(0)
                classesMap.getOrPut(lid) { mutableListOf() }
                    .add(GramClass(c.getString(1) ?: "", c.getString(2) ?: ""))
            }
        }

        hits.map { hit ->
            hit.copy(
                firstSenses = sensesMap[hit.id] ?: emptyList(),
                classes = classesMap[hit.id] ?: emptyList()
            )
        }
    }

    private fun buildHits(cursor: Cursor): List<LemmaHit> = buildList {
        while (cursor.moveToNext()) add(cursorToHit(cursor))
    }

    private fun cursorToHit(cursor: Cursor) = LemmaHit(
        id = cursor.getLong(0),
        headword = cursor.getString(1) ?: "",
        homographN = cursor.getInt(2),
        pos = cursor.getStringOrNull(3),
        isClassAgreeing = cursor.getInt(4) == 1,
        pluraliaTantum = cursor.getInt(5) == 1,
        indeclinable = cursor.getInt(6) == 1,
        gramNote = cursor.getStringOrNull(7),
        exactHeadword = cursor.getInt(8) == 1
    )

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)
}

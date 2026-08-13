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
        const val MIN_FUZZY_LEN = 2   // короче — совпадёт пол-словаря
        const val FUZZY_LIMIT = 30    // сколько «похожих» отдаём в UI
        const val RU_FUZZY_WORDS = 8  // сколько похожих русских слов раскрываем в статьи
    }

    // Path A: Chechen → Russian (exact normalized form match)
    suspend fun searchChechen(input: String): List<LemmaHit> = withContext(Dispatchers.IO) {
        val key = ChechenNormalizer.normalize(input)
        if (key.isEmpty()) return@withContext emptyList()

        val sql = """
            SELECT l.id, l.headword, l.homograph_n, p.code AS pos,
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

    // Path B: Russian → Chechen (reverse index by stem)
    suspend fun searchRussian(input: String): List<LemmaHit> = withContext(Dispatchers.IO) {
        val word = input.trim()
        if (word.isEmpty()) return@withContext emptyList()

        val stem = RuStem.stem(word)

        // Primary: search by stem
        val sqlByStem = """
            SELECT DISTINCT l.id, l.headword, l.homograph_n, p.code AS pos,
                   l.is_class_agreeing, l.pluralia_tantum, l.indeclinable, l.gram_note,
                   0 AS exact_headword, s.gloss_ru
            FROM ru_index ri
            JOIN senses s   ON s.id = ri.sense_id
            JOIN lemmas l   ON l.id = s.lemma_id
            LEFT JOIN pos p ON p.id = l.pos_id
            WHERE ri.stem = ?
            ORDER BY l.headword
            LIMIT 100
        """.trimIndent()

        var results = dbHelper.database.rawQuery(sqlByStem, arrayOf(stem)).use { cursor ->
            buildHitsWithGloss(cursor)
        }

        // Fallback: exact word or prefix match if stem search returns nothing
        if (results.isEmpty()) {
            val normalized = word.lowercase().replace('ё', 'е')
            val sqlFallback = """
                SELECT DISTINCT l.id, l.headword, l.homograph_n, p.code AS pos,
                       l.is_class_agreeing, l.pluralia_tantum, l.indeclinable, l.gram_note,
                       0 AS exact_headword, s.gloss_ru
                FROM ru_index ri
                JOIN senses s   ON s.id = ri.sense_id
                JOIN lemmas l   ON l.id = s.lemma_id
                LEFT JOIN pos p ON p.id = l.pos_id
                WHERE ri.word = ? OR ri.word LIKE ?
                ORDER BY l.headword
                LIMIT 100
            """.trimIndent()
            results = dbHelper.database.rawQuery(
                sqlFallback,
                arrayOf(normalized, "$normalized%")
            ).use { cursor -> buildHitsWithGloss(cursor) }
        }

        results
    }

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
            val rank = FuzzyKey.rank(candidateSkeleton, skeleton, maxEdits)
            if (rank > maxRank) return
            val prev = candidates[lemmaId]
            if (prev == null || rank < prev.rank) {
                candidates[lemmaId] = FuzzyCandidate(
                    rank = rank,
                    // при равном ранге вперёд идут слова с более длинным общим началом:
                    // опечатка обычно не в первой букве
                    commonPrefix = candidateSkeleton.commonPrefixWith(skeleton).length,
                    length = candidateSkeleton.length
                )
            }
        }

        // 1) скелеты заголовков: куг/кюг → куьг, мостаг → мостагӀ, хума → хӀума
        val index = fuzzyIndex()
        for (i in index.ceLemmaId.indices) offer(index.ceLemmaId[i], index.ceSkeleton[i])

        // 2) подстрока по всем словоформам: части слова и падежные/глагольные формы
        substringFormMatches(ChechenNormalizer.normalize(input)).forEach { (lemmaId, headword) ->
            offer(lemmaId, FuzzyKey.chechen(headword))
        }

        val order = candidates.entries.sortedWith(
            compareBy({ it.value.rank }, { -it.value.commonPrefix }, { it.value.length }, { it.key })
        ).take(FUZZY_LIMIT).map { it.key }

        val byId = loadHits(order).associateBy { it.id }
        order.mapNotNull { byId[it] }
    }

    // Path D: примерный поиск рус→чеч — опечатки и другая форма русского слова.
    suspend fun searchRussianFuzzy(
        input: String,
        exclude: Set<Long> = emptySet()
    ): List<LemmaHit> = withContext(Dispatchers.IO) {
        val skeleton = FuzzyKey.russian(input.trim())
        if (skeleton.length < MIN_FUZZY_LEN) return@withContext emptyList()
        val maxEdits = FuzzyKey.maxEdits(skeleton.length)
        val maxRank = FuzzyKey.maxRank(skeleton.length)

        val index = fuzzyIndex()
        val words = ArrayList<Pair<String, FuzzyCandidate>>()
        for (i in index.ruWord.indices) {
            val candidateSkeleton = index.ruSkeleton[i]
            val rank = FuzzyKey.rank(candidateSkeleton, skeleton, maxEdits)
            if (rank > maxRank) continue
            words.add(
                index.ruWord[i] to FuzzyCandidate(
                    rank = rank,
                    commonPrefix = candidateSkeleton.commonPrefixWith(skeleton).length,
                    length = candidateSkeleton.length
                )
            )
        }
        if (words.isEmpty()) return@withContext emptyList()

        val hits = LinkedHashMap<Long, LemmaHit>()
        words.sortedWith(
            compareBy({ it.second.rank }, { -it.second.commonPrefix }, { it.second.length }, { it.first })
        ).take(RU_FUZZY_WORDS).forEach { (word, _) ->
            if (hits.size < FUZZY_LIMIT) {
                hitsForRussianWord(word).forEach { hit ->
                    if (hit.id !in exclude && !hits.containsKey(hit.id)) hits[hit.id] = hit
                }
            }
        }
        hits.values.take(FUZZY_LIMIT)
    }

    private data class FuzzyCandidate(val rank: Int, val commonPrefix: Int, val length: Int)

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

    private fun hitsForRussianWord(word: String): List<LemmaHit> {
        val sql = """
            SELECT DISTINCT l.id, l.headword, l.homograph_n, p.code AS pos,
                   l.is_class_agreeing, l.pluralia_tantum, l.indeclinable, l.gram_note,
                   0 AS exact_headword, s.gloss_ru
            FROM ru_index ri
            JOIN senses s   ON s.id = ri.sense_id
            JOIN lemmas l   ON l.id = s.lemma_id
            LEFT JOIN pos p ON p.id = l.pos_id
            WHERE ri.word = ?
            ORDER BY l.headword
            LIMIT 40
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(word)).use { buildHitsWithGloss(it) }
    }

    private fun loadHits(ids: List<Long>): List<LemmaHit> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = """
            SELECT l.id, l.headword, l.homograph_n, p.code AS pos,
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
            SELECT l.id, l.headword, l.homograph_n, p.code AS pos,
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

    private fun buildHitsWithGloss(cursor: Cursor): List<LemmaHit> {
        // Deduplicate by lemma id, collect first 2 senses inline
        val map = linkedMapOf<Long, MutableList<String>>()
        val hitMap = linkedMapOf<Long, LemmaHit>()
        while (cursor.moveToNext()) {
            val hit = cursorToHit(cursor)
            if (!hitMap.containsKey(hit.id)) {
                hitMap[hit.id] = hit
                map[hit.id] = mutableListOf()
            }
            val gloss = cursor.getStringOrNull(9)
            if (gloss != null && (map[hit.id]?.size ?: 0) < 2) {
                map[hit.id]?.add(gloss)
            }
        }
        return hitMap.values.map { it.copy(firstSenses = map[it.id] ?: emptyList()) }
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

package com.bilto.gochmott.repository

import android.database.Cursor
import com.bilto.gochmott.db.DatabaseHelper
import com.bilto.gochmott.model.DictSource
import com.bilto.gochmott.model.EntryDetail
import com.bilto.gochmott.model.Example
import com.bilto.gochmott.model.Form
import com.bilto.gochmott.model.Gloss
import com.bilto.gochmott.model.GramClass
import com.bilto.gochmott.model.Lang
import com.bilto.gochmott.model.LemmaHit
import com.bilto.gochmott.model.LinkedEntry
import com.bilto.gochmott.model.Ref
import com.bilto.gochmott.model.Sense
import com.bilto.gochmott.model.Sub
import com.bilto.gochmott.search.ChechenNormalizer
import com.bilto.gochmott.search.FuzzyKey
import com.bilto.gochmott.search.RuNormalizer
import com.bilto.gochmott.search.RuStem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Доступ к `dict.db` (схема v5, многословарная).
 *
 * Таблицы названы по РОЛИ, а не по языку: `forms` — сторона заголовка,
 * `trans_index` — сторона перевода, и у обеих есть колонка `lang`. У словаря чеч→рус
 * заголовки чеченские, переводы русские; у словаря рус→чеч — наоборот. Поэтому
 * «искать чеченское слово» — это не «искать в forms», а «искать в обеих таблицах там,
 * где `lang='ce'`». Язык запроса при этом не угадывается: направление задаёт UI,
 * а пустая ветка просто ничего не возвращает.
 */
@Singleton
class DictRepository @Inject constructor(private val dbHelper: DatabaseHelper) {

    private companion object {
        const val MIN_FUZZY_LEN = 2        // короче — совпадёт пол-словаря
        const val FUZZY_LIMIT = 30         // сколько «похожих» чеченских статей отдаём в UI
        const val RU_SUGGESTION_LIMIT = 10 // сколько похожих русских слов предлагаем
        const val HITS_LIMIT = 100         // потолок статей на один поисковый слой

        const val CE = Lang.CE
        const val RU = Lang.RU

        /**
         * Общий список колонок статьи. Читается по ИМЕНАМ (см. [cursorToHit]), поэтому
         * слои поиска вольны дописывать свои колонки в любом порядке.
         */
        const val LEMMA_COLUMNS =
            "l.id, l.headword, l.lang, l.homonym, p.name_ru AS pos, l.class_star, " +
            "l.pluralia_tantum, l.labels, l.obj_num, l.subj_num, " +
            "d.book AS dict_book, d.year AS dict_year"

        /**
         * Ранжирование внутри слоя, от сильного к слабому: паспортный приоритет книги,
         * подробность статьи, алфавит внутри своей книги.
         *
         * `d.priority` обязателен ПЕРЕД `l.ordering`: `ordering` считается внутри
         * словаря, и без приоритета выдача из нескольких книг пойдёт чересполосицей.
         * `richness` — выведенная мера подробности статьи (значения + глоссы +
         * 2×примеры + напечатанные формы парадигмы); она поднимает полную статью
         * Мациева над голым «термин → термин» отраслевого словаря. В книге такого
         * числа нет, поэтому оно только сортирует и никогда не показывается.
         */
        const val BOOK_ORDER = "d.priority, l.richness DESC, l.ordering"

        /**
         * Слова русской фразы для обратного индекса — тот же набор символов, что
         * у `WORD_RE['ru']` в сборщике. Чеченского разбиения здесь нет: по словам
         * ищется только сторона перевода, а она у нас всегда русская (см.
         * [searchChechen]). Если он понадобится, помните про палочку U+04C0 —
         * в диапазон `а-я` она не входит, и «жамӀ» без неё распадётся.
         */
        val RU_WORD = Regex("[а-яё]+")

        /** Ключ ведра для примеров, не привязанных к значению (идиомы за «◊»). */
        const val IDIOM_KEY = -1L
    }

    // ---------------------------------------------------------------- поиск

    /**
     * Поиск по чеченскому вводу — только по стороне ЗАГОЛОВКА (`forms.lang='ce'`):
     * заголовки, варианты, падежная и глагольная парадигма и сгенерированные
     * классные формы книг чеч→рус.
     *
     * Обратный индекс здесь НЕ опрашивается, хотя `trans_index.lang='ce'` и есть.
     * Его строки принадлежат словарям рус→чеч, то есть статьям с РУССКИМ
     * заголовком: на «хаамаш» оттуда приходят `да́нные`, `сеть`, `за́пись`,
     * `защи́та`. В выдаче «чеченский → русский» это читается как мусор, а по
     * большей части им и является — 11 210 из 13 673 таких строк имеют `src=1`,
     * то есть слово попало в индекс из примера («хаамаш дӀалун маша»), а не из
     * перевода. Симметрии с [searchRussian] здесь нет и не должно быть: там обе
     * ветки ведут к чеченскому слову, ради которого запрос и сделан, а здесь
     * вторая ветка уводит к русскому.
     */
    suspend fun searchChechen(input: String): List<LemmaHit> = withContext(Dispatchers.IO) {
        val key = ChechenNormalizer.normalize(input)
        if (key.isEmpty()) return@withContext emptyList()
        hitsForForms(key, CE)
    }

    /**
     * Поиск по русскому вводу.
     *
     * Слои, от точного к приблизительному; первый непустой выигрывает:
     *
     *  0. русский ЗАГОЛОВОК книги рус→чеч (`forms.lang='ru'`) — 1 847 статей 1997/2017;
     *  1. вся фраза совпала с переводом     «ка́ждый раз»      -> хӀоразза
     *  2. все слова запроса есть в переводе «ошибки находить» -> гӀа̃латашда̃ха
     *  3. совпали основы Snowball           «утомления»       -> гӀелдар, хьахар¹
     *
     * Обратный индекс тут, в отличие от [searchChechen], нужен обязательно: слои
     * 1–3 дают чеченские заголовки Мациева, и без них словарь на 19 075 статей
     * по-русски не искался бы вовсе. Слой 0 даёт русские заголовки книг 1997 и
     * 2017 — тоже ответ на «переведи на чеченский», просто с другой стороны.
     *
     * Основы — именно запасной слой, а не отдельный путь: у «пол» основа общая
     * с «поле», «полено», «полый», и как самостоятельная выдача это мусор.
     */
    suspend fun searchRussian(input: String): List<LemmaHit> = withContext(Dispatchers.IO) {
        val phrase = RuNormalizer.normalize(input)
        if (phrase.isEmpty()) return@withContext emptyList()
        val words = wordsOf(phrase)

        val exact = (hitsForForms(phrase, RU) +
                hitsForPhrase(phrase, RU) +
                hitsForTranslationWords(words, "word", RU)).distinctBy { it.id }
        if (exact.isNotEmpty()) return@withContext exact

        val stems = words.map { RuStem.stem(it) }.filter { it.isNotEmpty() }
        hitsForTranslationWords(stems, "stem", RU)
    }

    /** Слова русской фразы — те же, что индексирует сборщик (`WORD_RE['ru']`). */
    private fun wordsOf(normalized: String): List<String> =
        RU_WORD.findAll(normalized).map { it.value }.toList()

    /**
     * Запрос A: точное совпадение со стороной ЗАГОЛОВКА.
     *
     * `only_gen` опускает вниз статьи, найденные только по ненапечатанной форме.
     * Таких категорий две: `gen` — форма собрана заменой классного показателя,
     * `linked` — ключ пришёл из другой книги. Обе это ключи поиска, а не слово из
     * книги, поэтому условие пишется как `source <> 'dict'`, а не `= 'gen'`.
     */
    private fun hitsForForms(key: String, lang: String): List<LemmaHit> {
        val sql = """
            SELECT $LEMMA_COLUMNS,
                   MAX(f.is_headword) AS exact_headword,
                   MIN(CASE WHEN f.source <> 'dict' THEN 1 ELSE 0 END) AS only_gen
            FROM forms f
            JOIN lemmas l   ON l.id = f.lemma_id
            JOIN dicts  d   ON d.id = f.dict_id
            LEFT JOIN pos p ON p.id = l.pos_id
            WHERE f.form_norm = ? AND f.lang = ?
            GROUP BY l.id
            ORDER BY only_gen, exact_headword DESC, $BOOK_ORDER
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(key, lang)).use { buildHits(it) }
    }

    /** Запрос B1: весь перевод целиком совпал с запросом — сильнейшее обратное попадание. */
    private fun hitsForPhrase(phrase: String, lang: String): List<LemmaHit> {
        val sql = """
            SELECT $LEMMA_COLUMNS, 0 AS exact_headword, g.text AS matched
            FROM glosses g
            JOIN lemmas l   ON l.id = g.lemma_id
            JOIN dicts  d   ON d.id = g.dict_id
            LEFT JOIN pos p ON p.id = l.pos_id
            WHERE g.text_norm = ? AND g.lang = ?
            GROUP BY l.id
            ORDER BY $BOOK_ORDER
            LIMIT $HITS_LIMIT
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(phrase, lang)).use { buildHits(it) }
    }

    /**
     * Запросы A2/B2/B3: статьи, у которых в переводе есть ВСЕ переданные слова.
     *
     * `src` в обратном индексе задаёт, откуда взято совпадение: 0 — перевод значения,
     * 1 — перевод примера, 2 — идиома, 3 — протянуто по отсылке («понуд. от»).
     * Сортировка по `MIN(src)` поднимает словарные значения над примерами.
     *
     * [column] — `word` (точный слой) либо `stem` (запасной). Колонка `stem` одна, а
     * содержимое зависит от языка: основа Snowball для русского, скелет FuzzyKey для
     * чеченского — значит и ключ подавать нужно разный.
     */
    private fun hitsForTranslationWords(
        keys: List<String>,
        column: String,
        lang: String
    ): List<LemmaHit> {
        if (keys.isEmpty()) return emptyList()
        val distinct = keys.distinct()
        val placeholders = distinct.joinToString(",") { "?" }
        // MIN(t.src) рядом с «голой» g.text — документированное поведение SQLite:
        // неагрегированная колонка берётся из той строки, что дала минимум.
        // Значит показанный перевод — это перевод сильнейшего совпадения.
        val sql = """
            SELECT $LEMMA_COLUMNS, 0 AS exact_headword,
                   MIN(t.src) AS best_src, g.text AS matched
            FROM trans_index t
            JOIN lemmas l       ON l.id = t.lemma_id
            JOIN dicts  d       ON d.id = t.dict_id
            LEFT JOIN pos p     ON p.id = l.pos_id
            LEFT JOIN glosses g ON g.id = t.target_id AND t.src IN (0, 3)
            WHERE t.$column IN ($placeholders) AND t.lang = ?
            GROUP BY l.id
            HAVING COUNT(DISTINCT t.$column) = ${distinct.size}
            ORDER BY best_src, $BOOK_ORDER
            LIMIT $HITS_LIMIT
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, (distinct + lang).toTypedArray())
            .use { buildHits(it) }
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
            val scored = scoreCandidate(candidateSkeleton, skeleton, maxEdits)
            if (scored.rank > maxRank) return
            val prev = candidates[lemmaId]
            if (prev == null || scored < prev) candidates[lemmaId] = scored
        }

        // 1) скелеты заголовков: куг/кюг → куьг, мостаг → мостагӀ, хума → хӀума
        val index = fuzzyIndex()
        for (i in index.ceLemmaId.indices) offer(index.ceLemmaId[i], index.ceSkeleton[i])

        // 2) подстрока по всем словоформам: части слова и падежные/глагольные формы
        substringFormMatches(ChechenNormalizer.normalize(input), CE)
            .forEach { (lemmaId, skeleton2) -> offer(lemmaId, skeleton2) }

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

    /** Словоформы нужного языка, содержащие ключ как подстроку (FTS5-триграммы + LIKE). */
    private fun substringFormMatches(key: String, lang: String): List<Pair<Long, String>> {
        if (key.length < MIN_FUZZY_LEN) return emptyList()
        val found = LinkedHashMap<Long, String>()

        if (dbHelper.hasFts5) {
            val sqlFts = """
                SELECT DISTINCT l.id, l.headword_fold
                FROM forms_trgm x
                JOIN forms  f ON f.id = x.rowid
                JOIN lemmas l ON l.id = f.lemma_id
                WHERE forms_trgm MATCH ? AND f.lang = ?
                LIMIT $HITS_LIMIT
            """.trimIndent()
            try {
                dbHelper.database.rawQuery(sqlFts, arrayOf(key, lang)).use { cursor ->
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
            FROM forms f
            JOIN lemmas l ON l.id = f.lemma_id
            WHERE f.form_norm LIKE ? AND f.lang = ?
            LIMIT $HITS_LIMIT
        """.trimIndent()
        patterns.forEach { pattern ->
            dbHelper.database.rawQuery(sqlLike, arrayOf("%$pattern%", lang)).use { cursor ->
                while (cursor.moveToNext()) found[cursor.getLong(0)] = cursor.getString(1) ?: ""
            }
        }
        return found.map { it.key to it.value }
    }

    private fun loadHits(ids: List<Long>): List<LemmaHit> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = """
            SELECT $LEMMA_COLUMNS, 0 AS exact_headword
            FROM lemmas l
            JOIN dicts  d   ON d.id = l.dict_id
            LEFT JOIN pos p ON p.id = l.pos_id
            WHERE l.id IN ($placeholders)
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, ids.map { it.toString() }.toTypedArray())
            .use { buildHits(it) }
    }

    // ---- индекс примерного поиска ----
    // Скелеты ЗАГОЛОВКОВ считает сборщик БД (`headword_fold`, тот же порт FuzzyKey),
    // поэтому при старте их достаточно прочитать. В v4 заголовок бывает и русским,
    // так что строки разводятся по `lemmas.lang`: иначе русские заголовки попадут
    // в чеченский примерный поиск. Скелеты русских слов считаются на месте —
    // в `trans_index` лежат только сами слова.

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
        val ruWords = LinkedHashSet<String>(32_000)

        dbHelper.database.rawQuery(
            "SELECT id, lang, headword_fold, headword_norm FROM lemmas", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                when (cursor.getStringOrNull(1)) {
                    CE -> {
                        val key = cursor.getStringOrNull(2) ?: continue
                        if (key.length < MIN_FUZZY_LEN) continue
                        ceIds.add(cursor.getLong(0))
                        ceKeys.add(key)
                    }
                    // Русский заголовок книги рус→чеч — такой же кандидат в подсказки,
                    // как слово из перевода.
                    RU -> cursor.getStringOrNull(3)?.let { ruWords.add(it) }
                }
            }
        }

        dbHelper.database.rawQuery(
            "SELECT DISTINCT word FROM trans_index WHERE lang = ?", arrayOf(RU)
        ).use { cursor ->
            while (cursor.moveToNext()) cursor.getStringOrNull(0)?.let { ruWords.add(it) }
        }

        val ruList = ArrayList<String>(ruWords.size)
        val ruKeys = ArrayList<String>(ruWords.size)
        ruWords.forEach { word ->
            val key = FuzzyKey.russian(word)
            if (key.length < MIN_FUZZY_LEN) return@forEach
            ruList.add(word)
            ruKeys.add(key)
        }

        return FuzzyIndex(
            ceLemmaId = ceIds.toLongArray(),
            ceSkeleton = ceKeys.toTypedArray(),
            ruWord = ruList.toTypedArray(),
            ruSkeleton = ruKeys.toTypedArray()
        )
    }

    // ------------------------------------------------------------- карточка

    suspend fun getEntryDetail(lemmaId: Long): EntryDetail = withContext(Dispatchers.IO) {
        val lemma = getLemmaHit(lemmaId)
        val examplesBySense = getExamples(lemmaId)
        EntryDetail(
            lemma = lemma.copy(classes = getClasses(lemmaId)),
            forms = getForms(lemmaId),
            senses = getSenses(lemmaId, examplesBySense),
            idioms = examplesBySense[IDIOM_KEY].orEmpty(),
            refs = getRefs(lemmaId),
            source = getSource(lemmaId),
            related = getRelated(lemmaId)
        )
    }

    private fun getLemmaHit(lemmaId: Long): LemmaHit {
        val sql = """
            SELECT $LEMMA_COLUMNS, 1 AS exact_headword
            FROM lemmas l
            JOIN dicts  d   ON d.id = l.dict_id
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

    /** Паспорт книги, из которой статья: подпись под карточкой и строка для «поделиться». */
    private fun getSource(lemmaId: Long): DictSource? {
        val sql = """
            SELECT d.code, d.title, d.authors, d.year, d.citation
            FROM lemmas l JOIN dicts d ON d.id = l.dict_id
            WHERE l.id = ?
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            if (!cursor.moveToFirst()) null else DictSource(
                code = cursor.getString(0) ?: "",
                title = cursor.getString(1) ?: "",
                authors = cursor.getString(2) ?: "",
                year = if (cursor.isNull(3)) null else cursor.getInt(3),
                citation = cursor.getString(4) ?: ""
            )
        }
    }

    /**
     * То же слово в других книгах (`lemma_links`). Пара хранится один раз и без
     * направления, поэтому «другая» статья — та сторона, которая не равна нашей.
     *
     * Поля книг не сливаются никогда: связь говорит лишь «это одно слово», а
     * `conflict` перечисляет, в чём книги расходятся (класс, часть речи). Непустой
     * `conflict` — не ошибка сборки, а разночтение источников: показываем оба
     * варианта с указанием книги, а не выбираем победителя.
     */
    private fun getRelated(lemmaId: Long): List<LinkedEntry> {
        val sql = """
            SELECT o.id, o.headword, o.lang, o.homonym, od.title,
                   k.method, k.confidence, k.conflict, k.reviewed, k.note
            FROM lemma_links k
            JOIN lemmas o  ON o.id = CASE WHEN k.a_lemma_id = ? THEN k.b_lemma_id
                                          ELSE k.a_lemma_id END
            JOIN dicts  od ON od.id = o.dict_id
            WHERE k.a_lemma_id = ? OR k.b_lemma_id = ?
            ORDER BY k.confidence DESC, od.priority
        """.trimIndent()
        val id = lemmaId.toString()
        return dbHelper.database.rawQuery(sql, arrayOf(id, id, id)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(LinkedEntry(
                        lemmaId = cursor.getLong(0),
                        headword = cursor.getString(1) ?: "",
                        lang = cursor.getStringOrNull(2) ?: CE,
                        homographN = if (cursor.isNull(3)) 0 else cursor.getInt(3),
                        dictTitle = cursor.getString(4) ?: "",
                        method = cursor.getString(5) ?: "",
                        confidence = cursor.getDouble(6),
                        conflict = jsonList(cursor.getStringOrNull(7)),
                        reviewed = cursor.getInt(8) == 1,
                        note = cursor.getStringOrNull(9)
                    ))
                }
            }
        }
    }

    /**
     * Формы для карточки — только напечатанные в книге.
     *
     * `source <> 'dict'` отфильтрован сознательно. Замена классного показателя (`gen`)
     * даёт верный ключ поиска, но не всегда принятую орфографию: у `даа` й-класс
     * пишется `яа`, а генератор выдаёт `йаа`. Ключ, протянутый из другой книги
     * (`linked`), формой этой статьи не является тем более. Искать по ним можно,
     * показывать как форму слова — нет.
     *
     * Заголовочная форма тоже не возвращается: она уже стоит в шапке карточки.
     * Если её оставить, у статьи без парадигмы список окажется непустым, таблица
     * отфильтрует единственную строку и покажет пустую рамку.
     */
    private fun getForms(lemmaId: Long): List<Form> {
        val sql = """
            SELECT f.form, f.is_headword,
                   ct.abbr_ru AS case_abbr, ct.name_ru AS case_name,
                   nt.code AS number, vt.name_ru AS tam, f.source
            FROM forms f
            LEFT JOIN case_type   ct ON ct.id = f.case_id
            LEFT JOIN number_type nt ON nt.id = f.number_id
            LEFT JOIN verb_tam    vt ON vt.id = f.tam_id
            WHERE f.lemma_id = ? AND f.source = 'dict'
              AND f.kind NOT IN ('variant', 'headword')
            ORDER BY f.is_headword DESC, f.number_id, ct.ordering, f.ordering
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
        // `glosses.ru` в v4 называется `text`: у словаря рус→чеч глосс чеченский,
        // и имя `ru` было бы враньём прямо в схеме.
        val sql = """
            SELECT g.sense_id, g.text, g.lang, g.sep, g.note, g.gov, g.labels, g.gram
            FROM glosses g
            WHERE g.lemma_id = ?
            ORDER BY g.sense_id, g.idx
        """.trimIndent()
        val out = LinkedHashMap<Long, MutableList<Gloss>>()
        dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                out.getOrPut(cursor.getLong(0)) { mutableListOf() }.add(Gloss(
                    text = cursor.getString(1) ?: "",
                    lang = cursor.getStringOrNull(2) ?: RU,
                    cls = jsonStringList(cursor.getStringOrNull(7), "cls"),
                    sep = cursor.getStringOrNull(3),
                    note = cursor.getStringOrNull(4),
                    gov = cursor.getStringOrNull(5),
                    labels = jsonList(cursor.getStringOrNull(6))
                ))
            }
        }
        return out
    }

    /** Примеры, разложенные по значениям. Идиомы статьи лежат под [IDIOM_KEY]. */
    private fun getExamples(lemmaId: Long): Map<Long, List<Example>> {
        val subs = getSubs(lemmaId)
        // `examples.ce` / `examples.ru` названы по ЯЗЫКУ, а не по роли, и в v4 НЕ
        // переименованы: у словаря рус→чеч `ru` — русское сочетание, `ce` — его перевод.
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
                    ceText = cursor.getString(3) ?: "",
                    ruText = cursor.getStringOrNull(4),
                    kind = cursor.getStringOrNull(5),
                    isIdiom = isIdiom,
                    note = cursor.getStringOrNull(6),
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
            SELECT s.example_id, s.letter, s.text, s.lang, s.note, s.gov
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
                    text = cursor.getString(2) ?: "",
                    lang = cursor.getStringOrNull(3) ?: RU,
                    note = cursor.getStringOrNull(4),
                    gov = cursor.getStringOrNull(5)
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
                        toHeadword = cursor.getString(1) ?: "",
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
     * Значение состоит из нескольких переводов («ослабле́ние; утомле́ние»), поэтому
     * глоссы одного значения склеиваются своим же разделителем из книги.
     */
    suspend fun enrichHits(hits: List<LemmaHit>): List<LemmaHit> = withContext(Dispatchers.IO) {
        if (hits.isEmpty()) return@withContext emptyList()
        val ids = hits.map { it.id }
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()

        val sensesMap = mutableMapOf<Long, MutableList<StringBuilder>>()
        val senseOrder = mutableMapOf<Long, MutableMap<Long, Int>>()
        dbHelper.database.rawQuery(
            """SELECT g.lemma_id, g.sense_id, g.text, g.sep
               FROM glosses g
               JOIN senses s ON s.id = g.sense_id
               WHERE g.lemma_id IN ($placeholders)
               ORDER BY g.lemma_id, s.block_n, s.ordering, g.idx""",
            args
        ).use { c ->
            while (c.moveToNext()) {
                val lid = c.getLong(0)
                val sid = c.getLong(1)
                val text = c.getString(2) ?: continue
                val order = senseOrder.getOrPut(lid) { mutableMapOf() }
                val lines = sensesMap.getOrPut(lid) { mutableListOf() }
                val slot = order[sid]
                if (slot == null) {
                    if (lines.size >= 2) continue          // в карточку идут первые два
                    order[sid] = lines.size
                    lines.add(StringBuilder(text))
                } else {
                    lines[slot].append(c.getStringOrNull(3) ?: ",").append(' ').append(text)
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

    private fun buildHits(cursor: Cursor): List<LemmaHit> = buildList {
        while (cursor.moveToNext()) add(cursorToHit(cursor))
    }

    /**
     * Колонки читаются по ИМЕНИ, а не по позиции: набор колонок у слоёв поиска разный
     * (`only_gen`, `best_src`, `matched`), и позиционное чтение приходилось
     * подпирать магическими индексами.
     */
    private fun cursorToHit(cursor: Cursor) = LemmaHit(
        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
        headword = cursor.string("headword").orEmpty(),
        lang = cursor.string("lang") ?: CE,
        dictBook = cursor.string("dict_book").orEmpty(),
        dictYear = cursor.int("dict_year"),
        // homonym в БД NULL у неомонимов — в модели это 0, надстрочный номер не рисуем
        homographN = cursor.int("homonym") ?: 0,
        pos = cursor.string("pos"),
        isClassAgreeing = cursor.int("class_star") == 1,
        pluraliaTantum = cursor.int("pluralia_tantum") == 1,
        labels = jsonList(cursor.string("labels")),
        objNum = cursor.string("obj_num"),
        subjNum = cursor.string("subj_num"),
        exactHeadword = cursor.int("exact_headword") == 1,
        matchedGloss = cursor.string("matched")
    )

    /** Массив строк внутри JSON-объекта: `glosses.gram` -> `{"cls": ["б", "д"]}`. */
    private fun jsonStringList(raw: String?, key: String): List<String> {
        if (raw.isNullOrEmpty() || raw == "{}") return emptyList()
        return try {
            val array = JSONObject(raw).optJSONArray(key) ?: return emptyList()
            buildList { for (i in 0 until array.length()) add(array.optString(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

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

    /** Значение колонки по имени; null — колонки в этом слое нет либо в ней NULL. */
    private fun Cursor.string(name: String): String? {
        val i = getColumnIndex(name)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.int(name: String): Int? {
        val i = getColumnIndex(name)
        return if (i < 0 || isNull(i)) null else getInt(i)
    }
}

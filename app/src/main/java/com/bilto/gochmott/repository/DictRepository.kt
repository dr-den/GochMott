package com.bilto.gochmott.repository

import android.database.Cursor
import com.bilto.gochmott.db.DatabaseHelper
import com.bilto.gochmott.model.DictSource
import com.bilto.gochmott.model.DictStats
import com.bilto.gochmott.model.EntryDetail
import com.bilto.gochmott.model.Example
import com.bilto.gochmott.model.Form
import com.bilto.gochmott.model.Gloss
import com.bilto.gochmott.model.GramClass
import com.bilto.gochmott.model.Lang
import com.bilto.gochmott.model.LemmaHit
import com.bilto.gochmott.model.LinkedEntry
import com.bilto.gochmott.model.ClassDifference
import com.bilto.gochmott.model.ClassNote
import com.bilto.gochmott.model.MergedRef
import com.bilto.gochmott.model.Ref
import com.bilto.gochmott.model.Sense
import com.bilto.gochmott.model.Sub
import com.bilto.gochmott.model.Usage
import com.bilto.gochmott.model.UsageEntry
import com.bilto.gochmott.search.ChechenNormalizer
import com.bilto.gochmott.search.Diacritics
import com.bilto.gochmott.search.FuzzyKey
import com.bilto.gochmott.search.RuNormalizer
import com.bilto.gochmott.search.RuStem
import com.bilto.gochmott.search.RuStopWords
import com.bilto.gochmott.settingsrepo.SettingKeys
import com.bilto.gochmott.settingsrepo.SettingsRepository
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
class DictRepository @Inject constructor(
    private val dbHelper: DatabaseHelper,
    private val settings: SettingsRepository
) {

    private companion object {
        const val MIN_FUZZY_LEN = 2        // короче — совпадёт пол-словаря
        const val FUZZY_LIMIT = 30         // сколько «похожих» чеченских статей отдаём в UI
        const val RU_SUGGESTION_LIMIT = 10 // сколько похожих русских слов предлагаем
        const val HITS_LIMIT = 100         // потолок статей на один поисковый слой
        const val USAGE_LIMIT = 200        // потолок употреблений слова без своей статьи
        const val MAX_REF_HOPS = 3         // глубина цепочки отсылок при показе перевода
        const val MAX_LINK_HOPS = 4       // группы `lemma_links` не длиннее шести статей
        const val MIN_MERGE_CONFIDENCE = 0.9  // ниже — это разные написания, не одно слово
        const val MIRROR_LIMIT = 20           // зеркальных статей на одну статью
        const val MERGED_SENSE_PREVIEW = 3    // строк значений в слитой карточке выдачи
        const val STATS_SEP = '|'          // разделитель полей в кэше статистики
        const val FIELD = ''         // разделители внутри ключа значения:
        const val GLOSS = ''         // в самом тексте словаря их быть не может

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

    /**
     * Слова русской фразы — РОВНО те, что сборщик положил в обратный индекс.
     *
     * Служебные приходится отбрасывать и здесь: их в индексе нет, а
     * `HAVING COUNT(DISTINCT word) = :n` требует все слова запроса. Пока фильтра
     * не было, ни один запрос со служебным словом не находился — «на пятый день»
     * не выводил `цӀаста`, хотя его перевод именно такой.
     */
    private fun wordsOf(normalized: String): List<String> =
        RuStopWords.significant(RU_WORD.findAll(normalized).map { it.value }.toList())

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


    /**
     * Чеченское слово, у которого нет своей статьи, — где оно встречается.
     *
     * Дополняет [searchChechen] ровно там, где тот пуст. 2 212 чеченских слов
     * стоят в базе только внутри переводов словарей рус→чеч и заголовком нигде не
     * значатся: 446 из них — настоящий перевод русской статьи (`да́нные` = `хаамаш`),
     * остальные 1 766 живут внутри словосочетаний (`координатийн куп` —
     * «координатная окрестность»). Это в основном словоформы и определения,
     * у которых своей статьи и не должно быть.
     *
     * Показывать их РУССКИМИ заголовками нельзя — выдача «чеченский → русский»
     * превращается в список русских слов. Поэтому наружу отдаётся чеченское слово,
     * а русские статьи становятся его употреблениями (см. `UsagesScreen`).
     *
     * Запрос по индексу `(word, lang)`; идёт только на промахе и только на
     * однословном вводе — в `trans_index` лежат отдельные слова.
     */
    suspend fun chechenUsages(input: String): UsageEntry? = withContext(Dispatchers.IO) {
        val key = ChechenNormalizer.normalize(input)
        if (key.length < MIN_FUZZY_LEN || key.contains(' ')) return@withContext null

        // target_id указывает в glosses ЛИБО в examples — таблицы разные, а id у них
        // независимые и пересекаются. Поэтому джойн разводится по `src`, иначе
        // словосочетание подхватит чужой глосс с тем же номером.
        val sql = """
            SELECT t.src, l.id, l.headword, d.book AS dict_book, d.year AS dict_year,
                   g.text AS gloss, e.ce AS phrase_ce, e.ru AS phrase_ru
            FROM trans_index t
            JOIN lemmas l        ON l.id = t.lemma_id
            JOIN dicts  d        ON d.id = t.dict_id
            LEFT JOIN glosses  g ON g.id = t.target_id AND t.src IN (0, 3)
            LEFT JOIN examples e ON e.id = t.target_id AND t.src IN (1, 2)
            WHERE t.word = ? AND t.lang = ?
            ORDER BY t.src, d.priority, l.ordering
            LIMIT $USAGE_LIMIT
        """.trimIndent()

        val usages = dbHelper.database.rawQuery(sql, arrayOf(key, CE)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Usage(
                        src = cursor.getInt(0),
                        lemmaId = cursor.getLong(1),
                        ruHeadword = cursor.getString(2) ?: "",
                        dictBook = cursor.getString(3) ?: "",
                        dictYear = if (cursor.isNull(4)) null else cursor.getInt(4),
                        gloss = cursor.getStringOrNull(5),
                        phraseCe = cursor.getStringOrNull(6),
                        phraseRu = cursor.getStringOrNull(7)
                    ))
                }
            }
        }
        if (usages.isEmpty()) null
        else UsageEntry(word = displayForm(key, usages), key = key, usages = usages)
    }

    /**
     * Написание слова со знаками долготы.
     *
     * В `trans_index.word` ключи очищены, а показывать надо как в книге — `ма̃ша`,
     * а не `маша`. Восстанавливаем из того же текста, откуда ключ и взят: ищем
     * в переводе или словосочетании токен, чья нормализация совпала с ключом.
     * Не нашли — показываем ключ: хуже, но не враньё.
     */
    private fun displayForm(key: String, usages: List<Usage>): String {
        for (usage in usages) {
            val source = if (usage.isGloss) usage.gloss else usage.phraseCe
            for (token in tokens(source.orEmpty())) {
                if (ChechenNormalizer.normalize(token) == key) return token
            }
        }
        return key
    }

    /** Слова строки вместе с надстрочными знаками; знаки — часть слова, не разделитель. */
    private fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        for (ch in text) {
            if (ch.isLetter() || ch.category == CharCategory.NON_SPACING_MARK) {
                word.append(ch)
            } else if (word.isNotEmpty()) {
                out.add(word.toString()); word.clear()
            }
        }
        if (word.isNotEmpty()) out.add(word.toString())
        return out
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

    // ------------------------------------------------------------- статистика

    @Volatile private var cachedStats: DictStats? = null
    private val statsMutex = Mutex()

    /**
     * Что показать на пустом экране поиска.
     *
     * Считается ОДИН РАЗ на версию словаря и кладётся в `common.db`: запрос по
     * русской стороне пробегает весь обратный индекс (93 467 строк), и делать
     * это при каждом старте — заставлять слабое устройство работать впустую.
     *
     * Признак годности кэша — `PRAGMA user_version` установленной копии. Словарь
     * read-only и целиком заменяется файлом, а версия при каждой пересборке
     * поднимается (это стережёт `DbVersionTest`), так что другого признака не
     * нужно: совпала версия — цифры те же.
     *
     * В памяти держится ещё и [cachedStats]: за время сессии направление поиска
     * переключают много раз, и ходить за этим в Room каждый раз незачем.
     */
    suspend fun stats(): DictStats {
        cachedStats?.let { return it }
        return statsMutex.withLock {
            cachedStats ?: withContext(Dispatchers.IO) {
                val version = dbHelper.installedVersion
                readCachedStats(version) ?: computeStats().also { saveStats(version, it) }
            }.also { cachedStats = it }
        }
    }

    /** Тяжёлая часть: три запроса по самой словарной базе. */
    private fun computeStats(): DictStats {
        fun count(sql: String, vararg args: String): Int =
            dbHelper.database.rawQuery(sql, args).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        return DictStats(
            // Книг, а не направлений: math1997_ce и math1997_ru — одна книга.
            books = count("SELECT COUNT(DISTINCT book) FROM dicts"),
            // Чеченская сторона: заголовки, по ним и идёт прямой поиск.
            chechenWords = count("SELECT COUNT(*) FROM lemmas WHERE lang = ?", CE),
            // Русская сторона: все слова, по которым вообще можно спросить, —
            // заголовки книг рус→чеч плюс словарь обратного индекса.
            russianWords = count(
                "SELECT COUNT(*) FROM (" +
                    "SELECT word FROM trans_index WHERE lang = ? " +
                    "UNION SELECT headword_norm FROM lemmas WHERE lang = ?)",
                RU, RU
            )
        )
    }

    /** `версия|книг|чеченских|русских`; чужая версия или мусор -> null, пересчитаем. */
    private suspend fun readCachedStats(version: Int): DictStats? {
        val raw = settings.getOrNull(SettingKeys.dictStats).orEmpty()
        val parts = raw.split(STATS_SEP)
        if (parts.size != 4) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers[0] != version) return null
        return DictStats(
            books = numbers[1],
            chechenWords = numbers[2],
            russianWords = numbers[3]
        )
    }

    private fun saveStats(version: Int, stats: DictStats) {
        settings.set(
            SettingKeys.dictStats,
            listOf(version, stats.books, stats.chechenWords, stats.russianWords)
                .joinToString(STATS_SEP.toString())
        )
    }

    // ------------------------------------------------------------- карточка

    suspend fun getEntryDetail(lemmaId: Long): EntryDetail = withContext(Dispatchers.IO) {
        val lemma = getLemmaHit(lemmaId)
        val examplesBySense = getExamples(lemmaId)
        // Книги, где та же статья: их примеры вливаются в значения эталона,
        // а значения, которых у него нет, встают отдельной строкой с плашкой.
        val siblings = mergedSiblings(lemmaId)
        val (combined, idioms) = combineWithSiblings(
            lemmaId,
            getSenses(lemmaId, examplesBySense),
            examplesBySense[IDIOM_KEY].orEmpty(),
            siblings
        )
        // Книги, где наше слово стоит переводом, а заголовком — его русский
        // эквивалент. Поиск ЧЕ→РУ их не видит, а пара настоящая.
        val senses = collapseTwins(
            addMirrors(combined, lemma.lang, mirrorEntries(lemmaId), headwordMateGlosses(lemmaId))
        )
        EntryDetail(
            lemma = lemma.copy(classes = getClasses(lemmaId)),
            forms = getForms(lemmaId),
            senses = senses,
            idioms = idioms,
            refs = getRefs(lemmaId, withTargetSenses = senses.isEmpty()),
            source = getSource(lemmaId),
            related = getRelated(lemmaId),
            classNotes = classNotesFor(lemmaId, siblings)
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

    /**
     * Отсылки статьи.
     *
     * [withTargetSenses] — подтянуть переводы цели. Нужно, когда своих значений
     * у статьи нет вовсе: таких 5 451 из 22 500, и вся их суть в отсылке
     * («ба̃тӀо̃ см. да̃тӀо̃», «понуд. от», «мн. от»). Без переводов цели карточка
     * выглядит пустой, и читатель решает, что перевода в словаре нет.
     *
     * У статей со своими значениями отсылка второстепенна, и лишний запрос за
     * переводами чужой статьи там не нужен — их 17 049 из 22 500.
     */
    private fun getRefs(lemmaId: Long, withTargetSenses: Boolean = false): List<Ref> {
        val sql = """
            SELECT rel, to_headword, to_lemma_id
            FROM cross_refs
            WHERE from_lemma_id = ?
            ORDER BY id
        """.trimIndent()
        val refs = dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
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
        if (!withTargetSenses) return refs

        // 43 отсылки из 6 037 ведут в статью, которой в книге нет: у них
        // to_lemma_id пуст, и подтягивать нечего — покажем одну ссылку.
        return refs.map { ref ->
            val target = ref.toLemmaId ?: return@map ref
            ref.copy(targetSenses = sensesThroughRefs(target))
        }
    }

    /**
     * Переводы статьи, а если своих у неё нет — переводы того, на что она сама
     * ссылается.
     *
     * Отсылки выстраиваются в цепочку: `а̃кхада̃ладала` → `а̃кхадала` → `а̃кха`
     * («отомсти́ть»). Из 5 427 статей без своих значений перевод виден сразу
     * у 4 572, ещё 843 требуют второго шага и 12 — третьего; тупиков нет.
     * Поэтому [MAX_REF_HOPS] = 3: этого хватает на весь словарь.
     *
     * Показываем при этом ссылку на ПРЯМУЮ цель, а не на конец цепочки: читателю
     * важно, от какого слова образовано это, а смысл по цепочке и так наследуется.
     */
    private fun sensesThroughRefs(startId: Long): List<String> {
        var frontier = listOf(startId)
        val seen = mutableSetOf(startId)
        repeat(MAX_REF_HOPS) {
            val senses = firstSensesOf(frontier)
            frontier.forEach { id ->
                senses[id]?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            val next = refTargetsOf(frontier).filter { seen.add(it) }
            if (next.isEmpty()) return emptyList()
            frontier = next
        }
        return emptyList()
    }

    /** Куда ссылаются эти статьи. Неразрешённые отсылки пропускаются. */
    private fun refTargetsOf(ids: List<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val sql = """
            SELECT DISTINCT to_lemma_id FROM cross_refs
            WHERE from_lemma_id IN ($placeholders) AND to_lemma_id IS NOT NULL
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, ids.map { it.toString() }.toTypedArray())
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
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

        val sensesMap = firstSensesOf(ids)

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

        mergeLinked(
            hits.map { hit ->
                hit.copy(
                    firstSenses = sensesMap[hit.id].orEmpty(),
                    classes = classesMap[hit.id] ?: emptyList()
                )
            }
        )
    }

    /**
     * Первые значения статей одной строкой — для карточки в выдаче и для превью
     * отсылки.
     *
     * Значение состоит из нескольких переводов («ослабле́ние; утомле́ние»), поэтому
     * глоссы одного значения склеиваются своим же разделителем из книги (`sep`),
     * а разные значения остаются разными строками.
     */
    private fun firstSensesOf(ids: List<Long>, limit: Int = 2): Map<Long, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        val lines = mutableMapOf<Long, MutableList<StringBuilder>>()
        val senseOrder = mutableMapOf<Long, MutableMap<Long, Int>>()
        dbHelper.database.rawQuery(
            """SELECT g.lemma_id, g.sense_id, g.text, g.sep
               FROM glosses g
               JOIN senses s ON s.id = g.sense_id
               WHERE g.lemma_id IN ($placeholders)
               ORDER BY g.lemma_id, s.block_n, s.ordering, g.idx""",
            ids.map { it.toString() }.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val lemmaId = c.getLong(0)
                val senseId = c.getLong(1)
                val text = c.getString(2) ?: continue
                val order = senseOrder.getOrPut(lemmaId) { mutableMapOf() }
                val bucket = lines.getOrPut(lemmaId) { mutableListOf() }
                val slot = order[senseId]
                if (slot == null) {
                    if (bucket.size >= limit) continue
                    order[senseId] = bucket.size
                    bucket.add(StringBuilder(text))
                } else {
                    bucket[slot].append(c.getStringOrNull(3) ?: ",").append(' ').append(text)
                }
            }
        }
        return lines.mapValues { (_, v) -> v.map { it.toString() } }
    }

    // --------------------------------------------------- одно слово в трёх книгах

    /**
     * Книги, повторяющие эту статью слово в слово, — для комбинированной карточки.
     *
     * Заполняется только у ЭТАЛОНА группы (наименьший `dicts.priority`). Если
     * открыли статью младшей книги — из «В других словарях» например, — она
     * показывает своё: подмешивать туда чужое значило бы прятать, чем эта книга
     * от эталона отличается, а ради этого её и открыли.
     */
    private fun mergedSiblings(lemmaId: Long): List<MergedRef> {
        val group = linkClosure(lemmaId)
        if (group.size < 2) return emptyList()
        val priority = dictPriority(group)
        val ordered = group.sortedBy { priority[it] ?: Int.MAX_VALUE }
        if (ordered.first() != lemmaId) return emptyList()

        val rest = ordered.drop(1)
        val books = dictRefs(rest)
        return rest.mapNotNull { books[it] }
    }

    /** Вся группа связанных статей: `lemma_links` попарны, транзитивность добираем сами. */
    private fun linkClosure(lemmaId: Long): List<Long> {
        var frontier = listOf(lemmaId)
        val seen = linkedSetOf(lemmaId)
        repeat(MAX_LINK_HOPS) {
            val placeholders = frontier.joinToString(",") { "?" }
            val args = frontier.map { it.toString() }.toTypedArray()
            val next = mutableListOf<Long>()
            dbHelper.database.rawQuery(
                "SELECT a_lemma_id, b_lemma_id FROM lemma_links " +
                    "WHERE (a_lemma_id IN ($placeholders) OR b_lemma_id IN ($placeholders)) " +
                    "AND confidence >= $MIN_MERGE_CONFIDENCE",
                args + args
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    listOf(cursor.getLong(0), cursor.getLong(1))
                        .forEach { if (seen.add(it)) next.add(it) }
                }
            }
            if (next.isEmpty()) return seen.toList()
            frontier = next
        }
        return seen.toList()
    }

    private fun dictRefs(ids: List<Long>): Map<Long, MergedRef> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        val out = HashMap<Long, MergedRef>()
        dbHelper.database.rawQuery(
            "SELECT l.id, d.book, d.year FROM lemmas l JOIN dicts d ON d.id = l.dict_id " +
                "WHERE l.id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out[cursor.getLong(0)] = MergedRef(
                    lemmaId = cursor.getLong(0),
                    dictBook = cursor.getString(1) ?: "",
                    dictYear = if (cursor.isNull(2)) null else cursor.getInt(2)
                )
            }
        }
        return out
    }

    /**
     * Чем книги расходятся по классу. Показатели у всех приведены к одной букве
     * (`в` `й` `б` `д`), поэтому сравнение честное: `ю` словаря 1997 и `й` словаря
     * 2017 — это один и тот же класс, а не расхождение.
     */
    private fun classNotesFor(lemmaId: Long, siblings: List<MergedRef>): List<ClassNote> {
        if (siblings.isEmpty()) return emptyList()
        val mine = classesByNumber(lemmaId)
        // Ключ — весь набор расхождений книги, поэтому книги с одинаковым
        // разночтением собираются в одну помету, а не в две-четыре.
        val bySignature = LinkedHashMap<List<ClassDifference>, MutableList<MergedRef>>()
        siblings.forEach { sibling ->
            val diffs = classesByNumber(sibling.lemmaId)
                .filter { (number, markers) -> markers != mine[number] }
                .map { (number, markers) -> ClassDifference(number, markers) }
                // Сначала единственное, потом множественное — как в шапке карточки.
                .sortedBy { if (it.number == "sg") 0 else 1 }
            if (diffs.isNotEmpty()) bySignature.getOrPut(diffs) { mutableListOf() }.add(sibling)
        }
        return bySignature.map { (diffs, books) -> ClassNote(diffs, books) }
    }

    private fun classesByNumber(lemmaId: Long): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        dbHelper.database.rawQuery(
            "SELECT number, marker FROM lemma_class WHERE lemma_id = ? ORDER BY number DESC, ordering",
            arrayOf(lemmaId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.getOrPut(cursor.getString(0) ?: "") { mutableListOf() }
                    .add(cursor.getString(1) ?: "")
            }
        }
        return out
    }

    /**
     * Статьи, стоящие к нашей ЗЕРКАЛЬНО: у них наше слово — весь перевод.
     *
     * `харцо` заголовком стоит только у Мациева; математический словарь держит
     * его в половине рус→чеч как перевод статьи «ложь». Поиск ЧЕ→РУ идёт по
     * заголовкам и такую пару не видит, а она настоящая: весь перевод равен
     * запросу, это не обрывок словосочетания.
     *
     * Берём только ДРУГИЕ книги. Половинки одной книги (`math1997_ce` и
     * `math1997_ru`) зеркалят друг друга сплошь — 1 558 пар из 2 627, — и
     * показывать их незачем: чеченская половина и так ищется напрямую.
     */
    private fun mirrorEntries(lemmaId: Long): List<MirrorEntry> {
        val self = dbHelper.database.rawQuery(
            "SELECT l.headword_norm, l.lang, d.book FROM lemmas l " +
                "JOIN dicts d ON d.id = l.dict_id WHERE l.id = ?",
            arrayOf(lemmaId.toString())
        ).use { c ->
            if (c.moveToFirst()) Triple(c.getString(0), c.getString(1), c.getString(2)) else null
        } ?: return emptyList()
        val (headwordNorm, lang, book) = self
        if (headwordNorm.isNullOrEmpty()) return emptyList()

        val sql = """
            SELECT o.id, o.headword, o.headword_norm, d.book, d.year
            FROM glosses g
            JOIN lemmas o ON o.id = g.lemma_id
            JOIN dicts  d ON d.id = g.dict_id
            WHERE g.text_norm = ? AND g.lang = ? AND d.book <> ?
            GROUP BY o.id
            ORDER BY d.priority, o.ordering
            LIMIT $MIRROR_LIMIT
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(headwordNorm, lang, book)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    add(MirrorEntry(
                        book = MergedRef(
                            lemmaId = id,
                            dictBook = cursor.getString(3) ?: "",
                            dictYear = if (cursor.isNull(4)) null else cursor.getInt(4)
                        ),
                        headword = cursor.getString(1) ?: "",
                        headwordNorm = cursor.getString(2) ?: "",
                        examples = mirrorExamples(id, lang, headwordNorm)
                    ))
                }
            }
        }
    }

    /**
     * Примеры зеркальной статьи, в которых НАШЕ слово действительно есть.
     *
     * Статья «ложь» иллюстрирует русское слово, и её чеченская сторона не обязана
     * содержать `харцо`: 28 % таких примеров нашего слова не содержат вовсе, и под
     * нашей статьёй они были бы не к месту.
     */
    private fun mirrorExamples(lemmaId: Long, lang: String, headwordNorm: String): List<Example> =
        getExamples(lemmaId).values.flatten().filter { example ->
            val side = if (lang == RU) example.ruText else example.ceText
            val norm = if (lang == RU) RuNormalizer.normalize(side)
            else ChechenNormalizer.normalize(side)
            norm.contains(headwordNorm)
        }

    /**
     * Переводы статей с ТЕМ ЖЕ заголовком, кроме нашей.
     *
     * Зеркало ищется по написанию и само не знает, какому омониму принадлежит.
     * Если перевода у нас нет, а у однофамильца есть — он его: у `харцо̃` «ложь»
     * относится к первому омониму, а не ко второму («опроки́нуть, обвали́ть»).
     */
    private fun headwordMateGlosses(lemmaId: Long): Set<String> {
        val sql = """
            SELECT DISTINCT sg.text_norm
            FROM lemmas me
            JOIN lemmas sib ON sib.headword_norm = me.headword_norm
                           AND sib.lang = me.lang AND sib.id <> me.id
            JOIN glosses sg ON sg.lemma_id = sib.id
            WHERE me.id = ?
        """.trimIndent()
        return dbHelper.database.rawQuery(sql, arrayOf(lemmaId.toString())).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0) ?: "") }
        }
    }

    private class MirrorEntry(
        val book: MergedRef,
        val headword: String,
        val headwordNorm: String,
        val examples: List<Example>
    )

    /**
     * Вливает статьи книг-двойников в статью эталона.
     *
     * Схлопывание идёт по ЗНАЧЕНИЯМ, а не по книгам. Иначе у `хьаьрк` карточка
     * выходит списком «диакритический знак, знак, цифра, знак, цифра»: 1997 даёт
     * «знак; цифра», 2017 — то же самое, и обе книги печатают свою строку, хотя
     * нового во второй нет.
     *
     * Поэтому ведём набор уже показанных переводов (нормализованных):
     *
     *  * значение младшей книги не добавляет ничего нового — своей строки не
     *    получает, его примеры дописываются к подходящему значению эталона
     *    с плашкой книги;
     *  * добавляет — показываем ТОЛЬКО новые переводы, отдельной строкой
     *    с плашкой. Книга, повторившая это значение следом, дописывается
     *    в ту же плашку.
     *
     * Идиомы за «◊» относятся к статье целиком и просто дописываются в общий
     * список.
     */
    private fun combineWithSiblings(
        lemmaId: Long,
        own: List<Sense>,
        ownIdioms: List<Example>,
        siblings: List<MergedRef>
    ): Pair<List<Sense>, List<Example>> {
        if (siblings.isEmpty()) return own to ownIdioms

        class Bucket(
            var sense: Sense,
            val norms: MutableSet<String>,
            val added: MutableList<Example> = mutableListOf(),
            val books: MutableList<MergedRef> = mutableListOf()
        )

        val buckets = own.mapTo(mutableListOf()) { sense ->
            Bucket(sense, sense.glosses.mapTo(mutableSetOf()) { normOf(it) })
        }
        val shown = buckets.flatMapTo(mutableSetOf()) { it.norms }
        val idioms = ownIdioms.toMutableList()

        siblings.forEach { book ->
            val examples = getExamples(book.lemmaId)
            idioms += examples[IDIOM_KEY].orEmpty().map { it.from(book) }
            getSenses(book.lemmaId, examples).forEach { sense ->
                val keys = sense.glosses.map { normOf(it) }.toSet()
                val fresh = keys - shown
                if (fresh.isEmpty()) {
                    // Книга ничего не добавляет: примеры к самому близкому значению,
                    // а плашку — к тем строкам, которые она тоже подтверждает.
                    buckets.filter { it.norms.isNotEmpty() && keys.containsAll(it.norms) }
                        .forEach { if (book !in it.books && it.books.isNotEmpty()) it.books += book }
                    val host = buckets
                        .maxByOrNull { it.norms.intersect(keys).size }
                        ?.takeIf { it.norms.intersect(keys).isNotEmpty() }
                    if (host != null) host.added += sense.examples.map { it.from(book) }
                    else if (sense.examples.isNotEmpty()) {
                        buckets.firstOrNull()?.added?.addAll(sense.examples.map { it.from(book) })
                    }
                } else {
                    val onlyNew = sense.glosses.filter { normOf(it) in fresh }
                    buckets += Bucket(
                        sense = sense.copy(glosses = onlyNew, fromBooks = listOf(book)),
                        norms = fresh.toMutableSet(),
                        books = mutableListOf(book)
                    )
                    shown += fresh
                }
            }
        }

        val senses = buckets.map { bucket ->
            bucket.sense.copy(
                examples = bucket.sense.examples + bucket.added,
                fromBooks = bucket.books.toList()
            )
        }
        return senses to idioms
    }

    /**
     * Схлопывает значения, которые на экране неотличимы одно от другого.
     *
     * У `класс` Мациев печатает два значения с одним и тем же переводом «класс»
     * и без единого пояснения. На экране получается «1. класс» и «2. класс» —
     * читатель видит повтор, а не два значения. Примеры при этом разные, поэтому
     * значения не выбрасываются, а сливаются в одно со всеми своими примерами.
     *
     * Различает хоть что-нибудь показываемое — пояснение, помета, плашка книги —
     * значит, это не повтор, и значения остаются раздельными: у `дом` это «цӀа»
     * и «(учреждение) цӀа».
     */
    private fun collapseTwins(senses: List<Sense>): List<Sense> {
        if (senses.size < 2) return senses
        val out = mutableListOf<Sense>()
        val seen = HashMap<String, Int>()
        senses.forEach { sense ->
            val at = seen[senseSignature(sense)]
            if (at == null) {
                seen[senseSignature(sense)] = out.size
                out += sense
            } else {
                out[at] = out[at].copy(examples = out[at].examples + sense.examples)
            }
        }
        return out
    }

    /**
     * Всё, что видно у значения на экране, одной строкой — ключ для [collapseTwins].
     *
     * `MergedRef` сравнивается по книге, а не целиком: `lemmaId` внутри у каждой
     * книги свой, а на экране от плашки видно только название.
     */
    private fun senseSignature(sense: Sense): String = buildString {
        append(sense.blockN).append(FIELD).append(sense.pos).append(FIELD)
        append(sense.labels.joinToString(",")).append(FIELD)
        sense.fromBooks.forEach { append(it.dictBook).append(',') }
        sense.glosses.forEach { gloss ->
            append(GLOSS)
            append(gloss.text).append(FIELD).append(gloss.lang).append(FIELD)
            append(gloss.sep).append(FIELD).append(gloss.note).append(FIELD)
            append(gloss.gov).append(FIELD).append(gloss.cls.joinToString(",")).append(FIELD)
            append(gloss.labels.joinToString(",")).append(FIELD)
            gloss.fromBooks.forEach { append(it.dictBook).append(',') }
        }
    }

    /**
     * Дописывает в статью то, что говорят зеркальные книги (см. [mirrorEntries]).
     *
     * Их заголовок — это перевод НАШЕГО слова, поэтому:
     *
     *  * перевод у нас уже есть — новой строки не будет, книга помечает сам
     *    перевод: «непра́вда, ложь (Матем, 1997), неуда́ча». Отдельная строка
     *    «2. ложь» рядом с «1. …ложь…» была бы дублем;
     *  * перевода нет — он встаёт своим значением с плашкой, как у книг-двойников.
     *
     * Примеры зеркальной статьи идут туда же, куда её заголовок.
     */
    private fun addMirrors(
        senses: List<Sense>,
        lang: String,
        mirrors: List<MirrorEntry>,
        mateGlosses: Set<String>
    ): List<Sense> {
        if (mirrors.isEmpty()) return senses

        val booksByGloss = HashMap<String, MutableList<MergedRef>>()
        val addedExamples = HashMap<Long, MutableList<Example>>()
        val fresh = LinkedHashMap<String, MutableList<MirrorEntry>>()

        mirrors.forEach { mirror ->
            val host = senses.firstOrNull { sense ->
                sense.glosses.any { normOf(it) == mirror.headwordNorm }
            }
            if (host == null) {
                // Перевода у нас нет. Если он есть у однофамильца — это его
                // зеркало, а не наше; своей строкой оно тут не встанет.
                if (mirror.headwordNorm !in mateGlosses) {
                    fresh.getOrPut(mirror.headwordNorm) { mutableListOf() } += mirror
                }
            } else {
                booksByGloss.getOrPut(mirror.headwordNorm) { mutableListOf() } += mirror.book
                if (mirror.examples.isNotEmpty()) {
                    addedExamples.getOrPut(host.id) { mutableListOf() } +=
                        mirror.examples.map { it.from(mirror.book) }
                }
            }
        }

        val marked = senses.map { sense ->
            sense.copy(
                glosses = sense.glosses.map { gloss ->
                    val books = booksByGloss[normOf(gloss)] ?: return@map gloss
                    gloss.copy(fromBooks = books.toList())
                },
                examples = sense.examples + addedExamples[sense.id].orEmpty()
            )
        }

        // Книги с одним и тем же новым переводом собираются в одну плашку.
        val extra = fresh.values.map { group ->
            val first = group.first()
            Sense(
                // Своего значения в базе у этой строки нет: она собрана из чужого
                // заголовка. Отрицательный id, чтобы не столкнуться с настоящими.
                id = -first.book.lemmaId,
                fromBooks = group.map { it.book },
                senseNo = null,
                blockN = null,
                pos = null,
                labels = emptyList(),
                glosses = listOf(
                    Gloss(
                        text = first.headword,
                        lang = Lang.other(lang),
                        cls = emptyList(),
                        sep = null,
                        note = null,
                        gov = null,
                        labels = emptyList()
                    )
                ),
                examples = group.flatMap { it.examples }
            )
        }
        return marked + extra
    }

    private fun Example.from(book: MergedRef) =
        copy(dictBook = book.dictBook, dictYear = book.dictYear)

    /**
     * Ключ перевода — тот же, что лежит в `glosses.text_norm`.
     *
     * Считаем на месте, а не читаем из базы: перевод у нас уже в руках, а лишний
     * запрос на каждую статью-двойник — на ровном месте.
     */
    private fun normOf(gloss: Gloss): String =
        if (gloss.lang == RU) RuNormalizer.normalize(gloss.text)
        else ChechenNormalizer.normalize(gloss.text)


    /**
     * Схлопывает статьи, которые разные книги повторяют слово в слово.
     *
     * `тӀадам` стоит заголовком у Мациева, в математическом 1997 и в компьютерном
     * 2017, и переводы у всех троих те же — три строки выдачи на одно слово.
     * Схлопываем их в одну; какая книга главная, решает `dicts.priority`, то есть
     * Мациев — эталон.
     *
     * **Условие слияния — связь в `lemma_links` с уверенностью от 0.9.** Значения
     * при этом сравнивать не нужно: то, чего у эталона нет, карточка показывает
     * отдельной строкой с плашкой книги (`Sense.dictBook`), так что слияние ничего
     * не прячет. У `хьаьрк` Мациев даёт «диакритический знак; знак», а 1997 и 2017
     * добавляют «цифра» — слово одно, и строка выдачи должна быть одна.
     *
     * Отброшены 15 связей с уверенностью 0.7: это не одно слово в двух книгах,
     * а два НАПИСАНИЯ одного — `ворхӀбӀе̃` и `ворхӀ бӀе`, `цхьа` и `цхьаъ`.
     * Показать оба честнее, чем выдать одно за другое.
     *
     * Сливаем только среди тех статей, что реально попали в выдачу: если форма
     * младшей книги запросу не отвечала, её там быть и не должно.
     */
    private fun mergeLinked(hits: List<LemmaHit>): List<LemmaHit> {
        if (hits.size < 2) return hits
        val byId = hits.associateBy { it.id }
        val groups = linkGroupsWithin(byId.keys)
        if (groups.isEmpty()) return hits

        val priority = dictPriority(byId.keys.toList())
        // Кого в какую строку убрать; строку ведёт статья с наименьшим priority.
        val absorbedBy = HashMap<Long, Long>()
        for (group in groups) {
            val ordered = group.sortedBy { priority[it] ?: Int.MAX_VALUE }
            ordered.drop(1).forEach { absorbedBy[it] = ordered.first() }
        }
        if (absorbedBy.isEmpty()) return hits

        val merged = HashMap<Long, MutableList<LemmaHit>>()
        hits.forEach { hit ->
            val primary = absorbedBy[hit.id] ?: return@forEach
            merged.getOrPut(primary) { mutableListOf() }.add(hit)
        }
        return hits.mapNotNull { hit ->
            if (hit.id in absorbedBy) return@mapNotNull null
            val absorbed = merged[hit.id] ?: return@mapNotNull hit
            hit.copy(
                alsoIn = absorbed.map { MergedRef(it.id, it.dictBook, it.dictYear) },
                // Значения младших книг дописываем в превью: у `абаде̃` Мациев даёт
                // «вечность без конца», а 1997 — «бесконечность», и увидеть это
                // хочется в выдаче, а не после тапа. Повторы отсеиваем по СМЫСЛУ,
                // а не по строке: `то́чка` Мациева и `точка` словаря 1997 — одно
                // и то же значение, и «капля, точка, точка» в строке недопустимо.
                firstSenses = (hit.firstSenses + absorbed.flatMap { it.firstSenses })
                    .distinctBy { Diacritics.plain(it).lowercase() }
                    .take(MERGED_SENSE_PREVIEW),
                // Совпасть мог перевод младшей книги — тогда строка «почему нашлось»
                // принадлежит ей, и терять её нельзя.
                matchedGloss = hit.matchedGloss ?: absorbed.firstNotNullOfOrNull { it.matchedGloss }
            )
        }
    }

    /** Группы связанных статей ВНУТРИ переданного множества; одиночки опущены. */
    private fun linkGroupsWithin(ids: Set<Long>): List<List<Long>> {
        if (ids.size < 2) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()
        val sql = """
            SELECT a_lemma_id, b_lemma_id FROM lemma_links
            WHERE a_lemma_id IN ($placeholders) AND b_lemma_id IN ($placeholders)
              AND confidence >= $MIN_MERGE_CONFIDENCE
        """.trimIndent()
        val parent = HashMap<Long, Long>()
        fun find(x: Long): Long {
            var cur = x
            while (parent[cur] != cur) {
                parent[cur] = parent[parent[cur]]!!
                cur = parent[cur]!!
            }
            return cur
        }
        dbHelper.database.rawQuery(sql, args + args).use { cursor ->
            while (cursor.moveToNext()) {
                val a = cursor.getLong(0)
                val b = cursor.getLong(1)
                parent.getOrPut(a) { a }
                parent.getOrPut(b) { b }
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[ra] = rb
            }
        }
        return parent.keys.groupBy { find(it) }.values.filter { it.size > 1 }.map { it.sorted() }
    }

    private fun dictPriority(ids: List<Long>): Map<Long, Int> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        val out = HashMap<Long, Int>()
        dbHelper.database.rawQuery(
            "SELECT l.id, d.priority FROM lemmas l JOIN dicts d ON d.id = l.dict_id " +
                "WHERE l.id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()
        ).use { cursor ->
            while (cursor.moveToNext()) out[cursor.getLong(0)] = cursor.getInt(1)
        }
        return out
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

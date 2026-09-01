package com.bilto.gochmott.repository

import android.content.Context
import com.bilto.gochmott.model.Abbreviation
import com.bilto.gochmott.model.AlphabetLetter
import com.bilto.gochmott.model.Bilingual
import com.bilto.gochmott.model.BookParagraph
import com.bilto.gochmott.model.BookRef
import com.bilto.gochmott.model.BookSection
import com.bilto.gochmott.model.DictionaryBook
import com.bilto.gochmott.model.TextRun
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Вводные части книг: предисловия, правила построения словаря, у Мациева ещё
 * сокращения и алфавит.
 *
 * Файлов несколько, по одному на книгу: `about.json` (Мациев, ~100 КБ, собирает
 * `tools/extract_about.py`) и `books/<код>.json` для словарей 1997 и 2017 (собирает
 * `tools/build_books_json.py` прямо из `.odt` и `dicts`). Править руками не
 * нужно ни один.
 *
 * Список книг лежит отдельным файлом `books/index.json`, а не выводится из
 * `dicts`: там строка на НАПРАВЛЕНИЕ, а на экране нужна строка на КНИГУ, и у
 * каждой книги свой файл вводной части, которого в базе нет.
 *
 * Кэш пофайловый: читатель ходит между книгами, и перечитывать сотню килобайт
 * при каждом возврате незачем.
 */
@Singleton
class BookRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val INDEX_ASSET = "books/index.json"
    }

    @Volatile private var cachedIndex: List<BookRef>? = null
    private val cachedBooks = mutableMapOf<String, DictionaryBook>()
    private val mutex = Mutex()

    /** Список книг для экрана «О словарях», в порядке приоритета из базы. */
    suspend fun index(): List<BookRef> =
        cachedIndex ?: mutex.withLock {
            cachedIndex ?: withContext(Dispatchers.IO) { parseIndex(readAsset(INDEX_ASSET)) }
                .also { cachedIndex = it }
        }

    /**
     * Вводная часть книги по её коду (`dicts.book`).
     *
     * Список читается ДО взятия замка: `Mutex` не реентрантный, и вызов `index()`
     * из-под него повесил бы экран намертво.
     */
    suspend fun load(code: String): DictionaryBook {
        cachedBooks[code]?.let { return it }
        val ref = index().firstOrNull { it.code == code }
            ?: error("нет книги с кодом $code в $INDEX_ASSET")
        return mutex.withLock {
            cachedBooks[code] ?: withContext(Dispatchers.IO) { parse(ref.code, readAsset(ref.asset)) }
                .also { cachedBooks[code] = it }
        }
    }

    private fun readAsset(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun parseIndex(json: String): List<BookRef> =
        JSONObject(json).getJSONArray("books").map { obj ->
            BookRef(
                code = obj.getString("code"),
                title = obj.getJSONObject("title").bilingual(),
                authors = obj.optString("authors"),
                year = obj.optInt("year").takeIf { it > 0 },
                entries = obj.optInt("entries"),
                asset = obj.getString("asset")
            )
        }

    private fun parse(code: String, json: String): DictionaryBook {
        val root = JSONObject(json)

        val sections = root.getJSONArray("sections").map { obj ->
            BookSection(
                id = obj.getString("id"),
                title = obj.getJSONObject("title").bilingual(),
                ru = obj.getJSONArray("ru").paragraphs(),
                ce = obj.getJSONArray("ce").paragraphs()
            )
        }

        // Сокращений и алфавита нет ни у 1997, ни у 2017 — книги их не печатают.
        val abbr = root.optJSONObject("abbreviations")
        val alphabet = root.optJSONObject("alphabet")

        return DictionaryBook(
            code = code,
            title = root.getJSONObject("title").bilingual(),
            source = root.optString("source"),
            sections = sections,
            abbreviationsTitle = abbr?.getJSONObject("title")?.bilingual() ?: Bilingual("", ""),
            abbreviations = abbr?.getJSONArray("items")?.map {
                Abbreviation(
                    short = it.getString("short"),
                    expansion = Bilingual(it.optString("ru"), it.optString("ce"))
                )
            }.orEmpty(),
            alphabetTitle = alphabet?.getJSONObject("title")?.bilingual() ?: Bilingual("", ""),
            alphabet = alphabet?.getJSONArray("items")?.map {
                AlphabetLetter(it.getString("letter"), it.optString("name"))
            }.orEmpty()
        )
    }

    private fun JSONObject.bilingual() = Bilingual(optString("ru"), optString("ce"))

    /**
     * Отрезок без начертаний лежит в JSON просто строкой, с начертаниями —
     * объектом `{t, b, i, s}`. Так файл вдвое короче: выделено меньше процента.
     */
    private fun JSONArray.paragraphs(): List<BookParagraph> =
        (0 until length()).map { i ->
            val runs = getJSONArray(i)
            (0 until runs.length()).mapNotNull { j ->
                when (val item = runs.get(j)) {
                    is String -> TextRun(item)
                    is JSONObject -> TextRun(
                        text = item.optString("t"),
                        bold = item.optBoolean("b"),
                        italic = item.optBoolean("i"),
                        superscript = item.optBoolean("s")
                    )
                    else -> null
                }
            }
        }

    private fun <T> JSONArray.map(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { transform(getJSONObject(it)) }
}

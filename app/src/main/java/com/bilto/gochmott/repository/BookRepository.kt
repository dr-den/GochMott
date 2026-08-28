package com.bilto.gochmott.repository

import android.content.Context
import com.bilto.gochmott.model.Abbreviation
import com.bilto.gochmott.model.AlphabetLetter
import com.bilto.gochmott.model.Bilingual
import com.bilto.gochmott.model.BookParagraph
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
 * Читает `assets/about.json` — вводную часть словаря Мациева.
 *
 * Файл собирает `tools/extract_about.py` из `docs/О словаре.odt`; править руками
 * его не нужно. Около 100 КБ, поэтому читается целиком и один раз, а дальше
 * держится в памяти: экраны листают его туда-сюда.
 */
@Singleton
class BookRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val ASSET = "about.json"
    }

    @Volatile private var cached: DictionaryBook? = null
    private val mutex = Mutex()

    suspend fun load(): DictionaryBook =
        cached ?: mutex.withLock {
            cached ?: withContext(Dispatchers.IO) { parse(readAsset()) }.also { cached = it }
        }

    private fun readAsset(): String =
        context.assets.open(ASSET).bufferedReader().use { it.readText() }

    private fun parse(json: String): DictionaryBook {
        val root = JSONObject(json)

        val sections = root.getJSONArray("sections").map { obj ->
            BookSection(
                id = obj.getString("id"),
                title = obj.getJSONObject("title").bilingual(),
                ru = obj.getJSONArray("ru").paragraphs(),
                ce = obj.getJSONArray("ce").paragraphs()
            )
        }

        val abbr = root.getJSONObject("abbreviations")
        val alphabet = root.getJSONObject("alphabet")

        return DictionaryBook(
            title = root.getJSONObject("title").bilingual(),
            source = root.optString("source"),
            sections = sections,
            abbreviationsTitle = abbr.getJSONObject("title").bilingual(),
            abbreviations = abbr.getJSONArray("items").map {
                Abbreviation(
                    short = it.getString("short"),
                    expansion = Bilingual(it.optString("ru"), it.optString("ce"))
                )
            },
            alphabetTitle = alphabet.getJSONObject("title").bilingual(),
            alphabet = alphabet.getJSONArray("items").map {
                AlphabetLetter(it.getString("letter"), it.optString("name"))
            }
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

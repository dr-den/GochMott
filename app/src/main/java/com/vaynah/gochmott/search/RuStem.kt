package com.vaynah.gochmott.search

import org.tartarus.snowball.ext.RussianStemmer

/**
 * Стемминг РУССКОГО запроса для поиска рус→чеч. Должен давать ТУ ЖЕ основу, что и
 * lemmatize_ru.py при сборке БД (там snowballstemmer). Класс russianStemmer —
 * это Snowball Russian, сгенерированный из того же исходника, поэтому вывод
 * совпадает символ-в-символ с Python `snowballstemmer.stemmer("russian")`.
 *
 * ЗАВИСИМОСТЬ (выберите одно):
 *   • лёгкий путь: положить в проект файлы рантайма Snowball из snowballstem.org —
 *     пакет org.tartarus.snowball: классы SnowballProgram, Among и
 *     ext/russianStemmer (несколько маленьких .java, public domain/BSD); либо
 *   • Gradle-зависимость Lucene, где этот класс уже есть:
 *       implementation("org.apache.lucene:lucene-analysis-common:9.11.1")
 *
 * Нормализация ПЕРЕД стеммингом: lower + ё→е (как normalize_ru на сборке).
 */
object RuStem {

    private val stemmer = RussianStemmer()

    /** Готовит слово запроса к сравнению с ru_index.stem. */
    @Synchronized
    fun stem(word: String): String {
        val w = word.lowercase().replace('ё', 'е').trim()
        if (w.isEmpty()) return ""
        stemmer.current = w
        stemmer.stem()
        return stemmer.current
    }

    /** Разбивает русскую фразу на слова и стеммит каждое (для многословного ввода). */
    fun stemWords(query: String): List<String> =
        Regex("[а-яёА-ЯЁ]+").findAll(query)
            .map { stem(it.value) }
            .filter { it.isNotEmpty() }
            .toList()
}

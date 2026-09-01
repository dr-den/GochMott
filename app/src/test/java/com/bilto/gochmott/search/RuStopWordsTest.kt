package com.bilto.gochmott.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Отбор слов запроса обязан совпадать с `index_words` сборщика: он не кладёт
 * служебные слова в обратный индекс, и запрос, который их требует, не сойдётся.
 */
class RuStopWordsTest {

    @Test
    fun `служебные выбрасываются`() {
        assertEquals(
            listOf("пятый", "день"),
            RuStopWords.significant(listOf("на", "пятый", "день"))
        )
    }

    @Test
    fun `однобуквенные выбрасываются как у сборщика`() {
        assertEquals(listOf("день"), RuStopWords.significant(listOf("к", "день")))
    }

    /**
     * Оговорка сборщика: у союза `а` перевод — ровно «и», у `бу` — «есть».
     * Выбросить их значило бы сделать эти статьи ненаходимыми.
     */
    @Test
    fun `если ничего не осталось, берём всё`() {
        assertEquals(listOf("и"), RuStopWords.significant(listOf("и")))
        assertEquals(listOf("это", "же"), RuStopWords.significant(listOf("это", "же")))
    }

    @Test
    fun `обычная фраза не трогается`() {
        assertEquals(
            listOf("каждый", "раз"),
            RuStopWords.significant(listOf("каждый", "раз"))
        )
    }
}

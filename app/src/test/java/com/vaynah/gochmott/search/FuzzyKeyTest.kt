package com.vaynah.gochmott.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyKeyTest {

    /** Написания, которые пользователь путает, должны давать один скелет. */
    @Test
    fun `variant spellings collapse to one skeleton`() {
        assertSameSkeleton("куьг", "куг", "кюг", "КУЬГ", "кyг" /* латинская y */)
        assertSameSkeleton("мостагӀ", "мостаг", "мостаг1", "мостагI")
        assertSameSkeleton("хӀума", "хума", "х1ума")
        assertSameSkeleton("аьрзу", "арзу", "ярзу")
        assertSameSkeleton("тӀулг", "тулг", "ттулг")
    }

    @Test
    fun `already normalized input gives the same skeleton`() {
        val word = "мостагӀалла"
        assertEquals(
            FuzzyKey.chechen(word),
            FuzzyKey.chechenFromNormalized(ChechenNormalizer.normalize(word))
        )
    }

    @Test
    fun `unrelated words keep different skeletons`() {
        assertTrue(FuzzyKey.chechen("куьг") != FuzzyKey.chechen("ког"))
        assertTrue(FuzzyKey.chechen("дог") != FuzzyKey.chechen("дуог"))
    }

    @Test
    fun `russian skeleton folds yo and soft signs`() {
        assertEquals(FuzzyKey.russian("ёлка"), FuzzyKey.russian("елка"))
        assertEquals(FuzzyKey.russian("конь"), FuzzyKey.russian("кон"))
        assertEquals("рука", FuzzyKey.russian("Рука"))
    }

    /** Ранги: точный скелет → начало слова → подстрока → опечатка. */
    @Test
    fun `rank orders exact before prefix before substring before typo`() {
        val query = FuzzyKey.chechen("куг")
        val exact = FuzzyKey.rank(FuzzyKey.chechen("куьг"), query)
        val prefix = FuzzyKey.rank(FuzzyKey.chechen("куьгбехке"), query)
        val substring = FuzzyKey.rank(FuzzyKey.chechen("бӀаьркуьг"), query)
        val typo = FuzzyKey.rank(FuzzyKey.chechen("ког"), query)

        assertEquals(0, exact)
        assertTrue("$exact < $prefix", exact < prefix)
        assertTrue("$prefix < $substring", prefix < substring)
        assertTrue("$substring < $typo", substring < typo)
        assertTrue("typo $typo в пределах порога", typo <= FuzzyKey.maxRank(query.length))
    }

    @Test
    fun `rank cuts off words that are too far`() {
        val query = FuzzyKey.chechen("куг")
        val far = FuzzyKey.rank(FuzzyKey.chechen("мостагӀалла"), query)
        assertTrue(far > FuzzyKey.maxRank(query.length))
    }

    @Test
    fun `levenshtein cutoff matches full distance below the limit`() {
        assertEquals(0, Levenshtein.atMost("куг", "куг", 2))
        assertEquals(1, Levenshtein.atMost("куг", "ког", 2))
        assertEquals(Levenshtein.distance("дица", "дийца"), Levenshtein.atMost("дица", "дийца", 2))
        assertEquals(3, Levenshtein.atMost("куг", "мостаг", 2)) // отсечка: max + 1
    }

    private fun assertSameSkeleton(vararg words: String) {
        val expected = FuzzyKey.chechen(words.first())
        assertTrue("скелет пуст для ${words.first()}", expected.isNotEmpty())
        for (word in words) {
            assertEquals("«$word» ожидался скелет «$expected»", expected, FuzzyKey.chechen(word))
        }
    }
}
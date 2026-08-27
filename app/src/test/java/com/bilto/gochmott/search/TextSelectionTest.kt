package com.bilto.gochmott.search

import com.bilto.gochmott.model.SearchDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSelectionTest {

    @Test
    fun `single word selection is taken as is`() {
        val parsed = TextSelection.parse("куьг")
        assertEquals("куьг", parsed?.word)
        assertFalse(parsed!!.hadMoreWords)
    }

    @Test
    fun `only the first word of a phrase is translated`() {
        val parsed = TextSelection.parse("боль в глазах")
        assertEquals("боль", parsed?.word)
        assertTrue(parsed!!.hadMoreWords)
    }

    @Test
    fun `punctuation and spaces around the word are dropped`() {
        assertEquals("глазах", TextSelection.parse("  «глазах», ")?.word)
        assertEquals("мостагӀ", TextSelection.parse("(мостагӀ)")?.word)
        assertFalse(TextSelection.parse("  «глазах». ")!!.hadMoreWords)
    }

    /** Палочка в «народных» написаниях — часть слова, а не мусор на конце. */
    @Test
    fun `folk palochka spellings stay in the word`() {
        assertEquals("мостаг1", TextSelection.parse("мостаг1")?.word)
        assertEquals("х1ума", TextSelection.parse("х1ума аьлла")?.word)
        assertEquals("мостаг|", TextSelection.parse("мостаг|")?.word)
    }

    @Test
    fun `tokens without letters are skipped`() {
        assertEquals("правка", TextSelection.parse("1 правка")?.word)
        assertEquals("правка", TextSelection.parse("[5] — правка")?.word)
    }

    @Test
    fun `selection without words gives null`() {
        assertNull(TextSelection.parse(null))
        assertNull(TextSelection.parse("   "))
        assertNull(TextSelection.parse("[1][5][6]"))
    }

    @Test
    fun `chechen markers switch the direction`() {
        listOf("куьг", "мостагӀ", "мостаг1", "хьаькам", "кхо", "къа").forEach { word ->
            assertEquals(
                "$word должно считаться чеченским",
                SearchDirection.CE_TO_RU,
                LanguageGuess.directionFor(word)
            )
        }
    }

    @Test
    fun `plain russian words go to the reverse direction`() {
        listOf("глазах", "молоко", "отходы", "объезд", "съезд").forEach { word ->
            assertEquals(
                "$word должно считаться русским",
                SearchDirection.RU_TO_CE,
                LanguageGuess.directionFor(word)
            )
        }
    }
}

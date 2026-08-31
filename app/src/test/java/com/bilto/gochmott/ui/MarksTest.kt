package com.bilto.gochmott.ui

import com.bilto.gochmott.search.ChechenNormalizer
import com.bilto.gochmott.search.Diacritics
import com.bilto.gochmott.search.RuStem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Показ надстрочных знаков и ключи поиска.
 *
 * В `dict.db` текст лежит со знаками, ключи (`*_norm`, `trans_index.stem`) — без.
 * Оба конца считаются одним кодом: ключи в базе построил `tools/build_app_db_v4.py`
 * портами этих же алгоритмов. Если тест упал — либо изменили алгоритм и не
 * пересобрали базу, либо наоборот.
 */
class MarksTest {

    @After
    fun restoreDefaults() {
        Marks.showLength = true
        Marks.showStress = true
    }

    @Test
    fun `по умолчанию показаны оба знака`() {
        assertEquals("ха̃дадала", Marks.ce("ха̃дадала"))
        assertEquals("ка́ждый раз", Marks.ru("ка́ждый раз"))
    }

    @Test
    fun `переключатели независимы`() {
        Marks.showLength = false
        Marks.showStress = true
        assertEquals("хададала", Marks.ce("ха̃дадала"))
        assertEquals("ка́ждый раз", Marks.ru("ка́ждый раз"))

        Marks.showLength = true
        Marks.showStress = false
        assertEquals("ха̃дадала", Marks.ce("ха̃дадала"))
        assertEquals("каждый раз", Marks.ru("ка́ждый раз"))
    }

    @Test
    fun `снятие знаков не трогает буквы`() {
        // й и ё — самостоятельные кодпойнты, а не буква с диакритикой;
        // й в чеченском несёт долготу (ий, уьй, юьй) и пропасть не должен.
        Marks.showLength = false
        assertEquals("куьйган", Marks.ce("куьйган"))
        assertEquals("мостагӀ", Marks.ce("мостагӀ"))
        Marks.showStress = false
        assertEquals("ёлка", Marks.ru("ёлка"))
    }

    @Test
    fun `null проходит насквозь`() {
        assertEquals("", Marks.ce(null))
        assertEquals(null, Marks.ru(null))
        Marks.showStress = false
        assertEquals(null, Marks.ru(null))
    }

    @Test
    fun `показанный текст и ключ поиска сходятся при любом переключателе`() {
        val expected = ChechenNormalizer.normalize("ха̃дадала")
        for (length in listOf(true, false)) {
            Marks.showLength = length
            assertEquals(
                "показ долготы не должен влиять на ключ поиска",
                expected,
                ChechenNormalizer.normalize(Marks.ce("ха̃дадала"))
            )
        }
    }

    @Test
    fun `нормализатор приложения совпадает с ключами базы`() {
        assertTrue("ChechenNormalizer.selfTest() провалился", ChechenNormalizer.selfTest())
        // Палочка обязана канонизироваться в Ӏ U+04C0. Строчная ӏ U+04CF — та самая
        // ошибка, из-за которой архивная сборка теряла 18,5 % статей.
        assertEquals("мостагӀ", ChechenNormalizer.normalize("мостагӏ"))
    }

    @Test
    fun `основы совпадают с теми, что записаны в trans_index`() {
        // Значения сняты с собранной базы: расхождение = Lucene поехал,
        // и обратный поиск перестанет находить словоформы.
        val expected = mapOf(
            "утомление" to "утомлен",
            "утомления" to "утомлен",
            "находить" to "наход",
            "ошибки" to "ошибк",
            "каждый" to "кажд",
            "поголовно" to "поголовн",
            "ль" to "ль"
        )
        expected.forEach { (word, stem) -> assertEquals(word, stem, RuStem.stem(word)) }
    }

    @Test
    fun `ударение снимается и до стемминга`() {
        // Пользователь может вставить в поиск текст, скопированный из другого
        // словаря — уже с ударением. Ключ должен получиться тот же.
        assertEquals(RuStem.stem("утомление"), RuStem.stem(Diacritics.withoutStress("утомле́ние")))
    }
}

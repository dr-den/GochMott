package com.bilto.gochmott.model

import com.bilto.gochmott.search.ChechenNormalizer
import com.bilto.gochmott.search.RuStem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Диакритика и ключи поиска.
 *
 * В `dict.db` текст лежит со знаками, а ключи (`*_norm`, `ru_index.stem`) — без.
 * Оба конца считаются одним и тем же кодом: ключи в базе построил
 * `tools/build_app_db.py`, портами этих же алгоритмов. Если тест упал —
 * либо изменили алгоритм и не пересобрали базу, либо наоборот.
 */
class MarksTest {

    @After
    fun restoreDefaults() {
        Marks.showLength = true
        Marks.showStress = true
    }

    @Test
    fun `умолчание приложения — оба знака показаны`() {
        Marks.showLength = true
        Marks.showStress = true
        assertEquals("ха̃дадала", Marks.ce("ха̃дадала"))
        assertEquals("ка́ждый раз", Marks.ru("ка́ждый раз"))
    }

    @Test
    fun `долгота снимается, когда выключена`() {
        Marks.showLength = false
        assertEquals("хададала", Marks.ce("ха̃дадала"))
    }

    @Test
    fun `переключатели независимы`() {
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
        Marks.showStress = false
        assertEquals("ёлка", Marks.ru("ёлка"))
    }

    @Test
    fun `null проходит насквозь`() {
        assertEquals("", Marks.ce(null))
        assertEquals(null, Marks.ru(null))
    }

    @Test
    fun `показанный текст и ключ поиска сходятся`() {
        // Что видит пользователь -> что уходит в WHERE form_norm = ?
        val shown = Marks.ce("ха̃дадала")
        assertEquals("хададала", ChechenNormalizer.normalize(shown))
        // и то же самое, если знаки показаны
        Marks.showLength = true
        assertEquals("хададала", ChechenNormalizer.normalize(Marks.ce("ха̃дадала")))
    }

    @Test
    fun `нормализатор приложения совпадает с ключами базы`() {
        assertTrue("ChechenNormalizer.selfTest() провалился", ChechenNormalizer.selfTest())
        // Палочка обязана канонизироваться в Ӏ U+04C0. Строчная ӏ U+04CF — та самая
        // ошибка, из-за которой архивная сборка теряла 18,5 % статей.
        assertEquals("мостагӀ", ChechenNormalizer.normalize("мостагӏ"))
    }

    @Test
    fun `основы совпадают с теми, что записаны в ru_index`() {
        // Значения снятые с собранной базы: расхождение = Lucene поехал,
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
}

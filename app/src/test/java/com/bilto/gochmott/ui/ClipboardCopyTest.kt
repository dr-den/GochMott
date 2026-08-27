package com.bilto.gochmott.ui

import com.bilto.gochmott.search.ChechenNormalizer
import com.bilto.gochmott.search.Diacritics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Что уходит в буфер обмена.
 *
 * Правило одно: на экран знаки идут, в буфер — нет, и это не зависит от
 * переключателей в меню. Подготовка строки живёт в `ClipboardCopy.kt` внутри
 * Context-расширения, поэтому здесь проверяется само преобразование —
 * `Diacritics.plain(...).trim()`, ровно как там.
 */
class ClipboardCopyTest {

    private fun forClipboard(text: String) = Diacritics.plain(text).trim()

    @After
    fun restoreDefaults() {
        Marks.showLength = true
        Marks.showStress = true
    }

    @Test
    fun `долгота в буфер не попадает`() {
        assertEquals("хададала", forClipboard("ха̃дадала"))
        assertEquals("гӀалаташдаха", forClipboard("гӀа̃латашда̃ха"))
        assertEquals("гӀоже", forClipboard("гӀоже̃"))
    }

    @Test
    fun `ударение в буфер не попадает`() {
        assertEquals("каждый раз", forClipboard("ка́ждый раз"))
        assertEquals("рука", forClipboard("рука́"))
        assertEquals("ослабление; утомление", forClipboard("ослабле́ние; утомле́ние"))
    }

    @Test
    fun `буфер не зависит от переключателей показа`() {
        Marks.showLength = true
        Marks.showStress = true
        assertEquals("хададала", forClipboard(Marks.ce("ха̃дадала")))
        assertEquals("каждый раз", forClipboard(Marks.ru("ка́ждый раз").orEmpty()))
    }

    @Test
    fun `палочка и мягкий знак не трогаются`() {
        // Ӏ U+04C0 и ь — буквы, а не диакритика: без них слово перестаёт быть собой
        assertEquals("куьйгаш", forClipboard("куьйгаш"))
        assertEquals("мостагӀ", forClipboard("мостагӀ"))
        assertEquals("ёлка", forClipboard("ёлка"))
    }

    @Test
    fun `скопированное находится поиском`() {
        // Главная причина чистить копию: вставленное обратно в строку поиска
        // должно давать тот же ключ, что и заголовок статьи.
        val copied = forClipboard("ха̃дадала")
        assertEquals(ChechenNormalizer.normalize("ха̃дадала"), ChechenNormalizer.normalize(copied))
        assertFalse(copied.contains(Diacritics.LENGTH))
        assertFalse(copied.contains(Diacritics.STRESS))
    }

    @Test
    fun `пробелы по краям срезаются`() {
        assertEquals("палка", forClipboard("  палка  "))
    }
}

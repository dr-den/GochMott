package com.bilto.gochmott.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Русский нормализатор — порт `normalize_ru` из `tools/build_app_db_v4.py`.
 * Этим кодом собраны ключи `glosses.text_norm` и `trans_index.word` (`lang='ru'`),
 * поэтому расхождение здесь ломает весь поиск рус→чеч.
 */
class RuNormalizerTest {

    @Test
    fun `selfTest проходит`() {
        assertTrue("RuNormalizer.selfTest() провалился", RuNormalizer.selfTest())
    }

    @Test
    fun `ударение снимается, ё сводится к е, пробелы схлопываются`() {
        assertEquals("каждый раз", RuNormalizer.normalize("Ка́ждый  раз"))
        assertEquals("елка", RuNormalizer.normalize("Ёлка"))
        assertEquals("утомление", RuNormalizer.normalize("  утомле́ние \n"))
    }

    @Test
    fun `пустой ввод даёт пустой ключ`() {
        assertEquals("", RuNormalizer.normalize(null))
        assertEquals("", RuNormalizer.normalize("   "))
    }

    /**
     * Главная причина, по которой нормализатора два. [ChechenNormalizer] превращает
     * `1`, `i`, `l`, `|` в палочку — это способ набрать `Ӏ` с обычной раскладки,
     * и русскую строку через него гнать нельзя.
     */
    @Test
    fun `цифры и латиница не превращаются в палочку`() {
        assertEquals("1-й ряд", RuNormalizer.normalize("1-й ряд"))
        assertNotEquals(
            ChechenNormalizer.normalize("1-й ряд"),
            RuNormalizer.normalize("1-й ряд")
        )
    }
}

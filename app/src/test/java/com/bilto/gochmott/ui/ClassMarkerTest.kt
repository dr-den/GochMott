package com.bilto.gochmott.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Классный показатель на экране — со связкой.
 *
 * Главное здесь — `й` даёт `ю`, а не `йу`: показатель и гласная связки в чеченском
 * письме сливаются в одну букву. Так напечатано и в самом словаре 1997 года:
 * `(ю)` там встречается 554 раза, `(йу)` — ни разу.
 */
class ClassMarkerTest {

    @Test
    fun `показатель разворачивается в связку`() {
        assertEquals("ву", ClassMarker.full("в"))
        assertEquals("ю", ClassMarker.full("й"))
        assertEquals("бу", ClassMarker.full("б"))
        assertEquals("ду", ClassMarker.full("д"))
    }

    @Test
    fun `й даёт ю, а не йу`() {
        assertEquals("й + у в чеченском письме — одна буква", "ю", ClassMarker.full("й"))
    }

    @Test
    fun `список склеивается запятой`() {
        assertEquals("бу, ду", ClassMarker.list(listOf("б", "д")))
        assertEquals("ю, ю", ClassMarker.list(listOf("й", "й")))
        assertEquals("", ClassMarker.list(emptyList()))
    }

    /** В базе показателей ровно четыре, но чужое значение врать не должно. */
    @Test
    fun `незнакомое значение отдаётся как есть`() {
        assertEquals("х", ClassMarker.full("х"))
    }
}

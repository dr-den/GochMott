package com.bilto.gochmott.repository

import com.bilto.gochmott.repository.LinkGroups.Edge
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkGroupsTest {

    private val karasaev = "karasaev1978"
    private val sport = "aslakhanov2012"
    private val maciev = "maciev1961"

    @Test
    fun `homonyms of one book are not glued through a third book`() {
        // пасовать¹ «юкъаравала; юхавала», пасовать² «буьрка дӀаялар», Аслаханов «буьрка дӀаяла»
        val groups = LinkGroups.build(
            edges = listOf(Edge(1, 3, 1.0), Edge(2, 3, 1.0)),
            bookOf = mapOf(1L to karasaev, 2L to karasaev, 3L to sport),
            wordsOf = mapOf(
                1L to LinkGroups.words(listOf("юкъаравала", "юхавала")),
                2L to LinkGroups.words(listOf("буьрка дӀаялар")),
                3L to LinkGroups.words(listOf("буьрка дӀаяла"))
            )
        )
        assertEquals(listOf(listOf(2L, 3L)), groups)
    }

    @Test
    fun `ambiguous link to homonyms attaches to none`() {
        val groups = LinkGroups.build(
            edges = listOf(Edge(1, 3, 1.0), Edge(2, 3, 1.0)),
            bookOf = mapOf(1L to karasaev, 2L to karasaev, 3L to sport),
            wordsOf = mapOf(
                1L to setOf("знак"),
                2L to setOf("цифра"),
                3L to setOf("точка")
            )
        )
        assertEquals(emptyList<List<Long>>(), groups)
    }

    @Test
    fun `one word in three different books stays one group`() {
        val groups = LinkGroups.build(
            edges = listOf(Edge(1, 2, 1.0), Edge(2, 3, 0.95)),
            bookOf = mapOf(1L to maciev, 2L to "math1997", 3L to "comp2017"),
            wordsOf = emptyMap()
        )
        assertEquals(listOf(listOf(1L, 2L, 3L)), groups)
    }

    @Test
    fun `links inside one book never merge`() {
        val groups = LinkGroups.build(
            edges = listOf(Edge(1, 2, 1.0)),
            bookOf = mapOf(1L to karasaev, 2L to karasaev),
            wordsOf = emptyMap()
        )
        assertEquals(emptyList<List<Long>>(), groups)
    }

    @Test
    fun `short words do not count as shared`() {
        assertEquals(setOf("буьрка"), LinkGroups.words(listOf("буьрка а")))
    }
}

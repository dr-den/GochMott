package com.vaynah.gochmott.repository

import com.vaynah.gochmott.model.SearchDirection
import com.vaynah.gochmott.settingsrepo.SettingKey
import com.vaynah.gochmott.settingsrepo.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryRepositoryTest {

    private class FakeSettingsRepository : SettingsRepository {
        override val coroutineContext = Dispatchers.Unconfined + Job()

        private val values = MutableStateFlow<Map<String, String>>(emptyMap())

        @Suppress("UNCHECKED_CAST")
        override fun <T> get(key: SettingKey<T>): Flow<T> =
            values.map { (it[key.name] ?: key.defaultValue) as T }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> getOrNull(key: SettingKey<T>): T? =
            values.value[key.name] as T?

        override fun <T> set(key: SettingKey<T>, value: T): Job {
            values.value = values.value + (key.name to value.toString())
            return CompletableDeferred(Unit)
        }
    }

    private val settings = FakeSettingsRepository()
    private val history = SearchHistoryRepository(settings)

    private fun historyOf(direction: SearchDirection): List<String> =
        runBlocking { history.history(direction).first() }

    @Test
    fun `recent queries come first`() = runBlocking {
        history.record(SearchDirection.CE_TO_RU, "мохк")
        history.record(SearchDirection.CE_TO_RU, "куьг")

        assertEquals(listOf("куьг", "мохк"), historyOf(SearchDirection.CE_TO_RU))
    }

    @Test
    fun `repeated query moves up instead of duplicating`() = runBlocking {
        history.record(SearchDirection.CE_TO_RU, "мохк")
        history.record(SearchDirection.CE_TO_RU, "куьг")
        history.record(SearchDirection.CE_TO_RU, "МОХК")

        // Регистр не создаёт вторую запись, но обновляет её положение.
        assertEquals(listOf("МОХК", "куьг"), historyOf(SearchDirection.CE_TO_RU))
    }

    @Test
    fun `directions keep separate lists`() = runBlocking {
        history.record(SearchDirection.CE_TO_RU, "мохк")
        history.record(SearchDirection.RU_TO_CE, "страна")

        assertEquals(listOf("мохк"), historyOf(SearchDirection.CE_TO_RU))
        assertEquals(listOf("страна"), historyOf(SearchDirection.RU_TO_CE))
    }

    @Test
    fun `history is capped and drops the oldest`() = runBlocking {
        repeat(20) { history.record(SearchDirection.CE_TO_RU, "слово$it") }

        val entries = historyOf(SearchDirection.CE_TO_RU)
        assertEquals(15, entries.size)
        assertEquals("слово19", entries.first())
        assertEquals("слово5", entries.last())
    }

    @Test
    fun `blank query is not recorded and whitespace is trimmed`() = runBlocking {
        history.record(SearchDirection.CE_TO_RU, "   ")
        history.record(SearchDirection.CE_TO_RU, "  мохк  ")

        assertEquals(listOf("мохк"), historyOf(SearchDirection.CE_TO_RU))
    }

    @Test
    fun `remove deletes a single entry and clear empties the direction`() = runBlocking {
        history.record(SearchDirection.CE_TO_RU, "мохк")
        history.record(SearchDirection.CE_TO_RU, "куьг")
        history.record(SearchDirection.RU_TO_CE, "страна")

        history.remove(SearchDirection.CE_TO_RU, "мохк")
        assertEquals(listOf("куьг"), historyOf(SearchDirection.CE_TO_RU))

        history.clear(SearchDirection.CE_TO_RU)
        assertEquals(emptyList<String>(), historyOf(SearchDirection.CE_TO_RU))
        assertEquals(listOf("страна"), historyOf(SearchDirection.RU_TO_CE))
    }
}

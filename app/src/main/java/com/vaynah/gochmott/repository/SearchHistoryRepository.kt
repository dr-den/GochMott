package com.vaynah.gochmott.repository

import com.vaynah.gochmott.model.SearchDirection
import com.vaynah.gochmott.settingsrepo.SettingKey
import com.vaynah.gochmott.settingsrepo.SettingKeys
import com.vaynah.gochmott.settingsrepo.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class SearchHistoryRepository @Inject constructor(
    private val settings: SettingsRepository
) {


    private val mutex = Mutex()

    fun history(direction: SearchDirection): Flow<List<String>> =
        settings.get(keyFor(direction)).map(::decode)

    suspend fun record(direction: SearchDirection, rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return
        mutex.withLock {
            val key = keyFor(direction)
            val current = decode(settings.getOrNull(key).orEmpty())
            val updated = buildList {
                add(query)
                current.forEach { if (!it.equals(query, ignoreCase = true)) add(it) }
            }.take(MAX_ENTRIES)
            settings.set(key, encode(updated)).join()
        }
    }

    suspend fun remove(direction: SearchDirection, query: String) {
        mutex.withLock {
            val key = keyFor(direction)
            val current = decode(settings.getOrNull(key).orEmpty())
            val updated = current.filterNot { it.equals(query, ignoreCase = true) }
            if (updated.size != current.size) settings.set(key, encode(updated)).join()
        }
    }

    suspend fun clear(direction: SearchDirection) {
        mutex.withLock { settings.set(keyFor(direction), "").join() }
    }

    private fun keyFor(direction: SearchDirection): SettingKey.Str<String> = when (direction) {
        SearchDirection.CE_TO_RU -> SettingKeys.searchHistoryCeToRu
        SearchDirection.RU_TO_CE -> SettingKeys.searchHistoryRuToCe
    }

    private fun decode(stored: String): List<String> =
        stored.split(SEPARATOR).map(String::trim).filter(String::isNotEmpty)

    private fun encode(entries: List<String>): String = entries.joinToString(SEPARATOR)

    private companion object {
        const val MAX_ENTRIES = 15
        const val SEPARATOR = "\n"
    }
}

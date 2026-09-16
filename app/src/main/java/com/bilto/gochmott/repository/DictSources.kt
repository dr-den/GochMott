package com.bilto.gochmott.repository

import com.bilto.gochmott.db.DatabaseHelper
import com.bilto.gochmott.model.BookInfo
import com.bilto.gochmott.model.SourceAuthority
import com.bilto.gochmott.model.SourceQuality
import com.bilto.gochmott.settingsrepo.SettingKeys
import com.bilto.gochmott.settingsrepo.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Книги базы и то, какие из них читатель отключил.
 *
 * Одно место на оба вопроса, потому что их задают вместе: плашке нужна оценка
 * книги, фильтру — список книг и их оценки, поиску — какие книги не трогать.
 *
 * Список книг читается из `dicts` один раз: база read-only и меняется только
 * целиком. Отключённые книги лежат в `common.db` и переживают пересборку базы;
 * код книги, которой в новой базе нет, просто ни на что не влияет.
 */
@Singleton
class DictSources @Inject constructor(
    private val dbHelper: DatabaseHelper,
    private val settings: SettingsRepository
) {

    private val _books = MutableStateFlow<List<BookInfo>>(emptyList())

    /** Книги в порядке `dicts.priority`; пусто, пока база не открыта. */
    val books: StateFlow<List<BookInfo>> = _books.asStateFlow()

    private val loadMutex = Mutex()

    /** null — настройка ещё не прочитана из Room. */
    private val _disabled = MutableStateFlow<Set<String>?>(null)

    val disabledBooks: Flow<Set<String>> = _disabled.filterNotNull().distinctUntilChanged()

    init {
        settings.launch {
            settings.get(SettingKeys.disabledBooks).collect { raw -> _disabled.value = parse(raw) }
        }
    }

    /**
     * Книги, которые поиск и карточка должны обходить.
     *
     * Ждёт первого чтения настройки: иначе поиск сразу после старта успел бы
     * пройти по всем книгам и показать ровно то, что читатель отключил.
     */
    suspend fun disabled(): Set<String> = _disabled.value ?: _disabled.filterNotNull().first()

    /** Читает список книг, если ещё не читан. Открывает базу — звать не с главного потока. */
    suspend fun load() {
        if (_books.value.isNotEmpty()) return
        loadMutex.withLock {
            if (_books.value.isNotEmpty()) return
            _books.value = withContext(Dispatchers.IO) { readBooks() }
        }
    }

    fun setEnabled(book: String, enabled: Boolean) {
        val current = _disabled.value ?: return
        val next = if (enabled) current - book else current + book
        // Хоть одна книга должна остаться: с пустым фильтром поиск молча
        // ничего не находит, и понять почему — не по чему.
        if (_books.value.isNotEmpty() && _books.value.all { it.book in next }) return
        save(next)
    }

    fun enableAll() = save(emptySet())

    private fun save(disabled: Set<String>) {
        // Сразу в память: запись в Room асинхронная, а галочка должна
        // переключиться в том же кадре, что и нажатие.
        _disabled.value = disabled
        settings.set(SettingKeys.disabledBooks, disabled.sorted().joinToString(SEP))
    }

    private fun parse(raw: String): Set<String> =
        raw.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun readBooks(): List<BookInfo> {
        val sql = """
            SELECT book, year, title, authority, quality, caveat
            FROM dicts
            ORDER BY priority
        """.trimIndent()
        val rows = dbHelper.database.rawQuery(sql, null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(BookInfo(
                        book = c.getString(0) ?: "",
                        year = if (c.isNull(1)) null else c.getInt(1),
                        title = c.getString(2) ?: "",
                        authority = SourceAuthority.of(c.getString(3)),
                        quality = SourceQuality.of(c.getString(4)),
                        caveat = if (c.isNull(5)) null else c.getString(5)?.takeIf { it.isNotBlank() }
                    ))
                }
            }
        }
        // Половины одной книги сводим в строку: худшая оценка и первое непустое пояснение.
        return rows.groupBy { it.book }.map { (_, halves) ->
            halves.first().copy(
                authority = halves.maxOf { it.authority },
                quality = halves.maxOf { it.quality },
                caveat = halves.firstNotNullOfOrNull { it.caveat }
            )
        }
    }

    private companion object {
        const val SEP = ","
    }
}

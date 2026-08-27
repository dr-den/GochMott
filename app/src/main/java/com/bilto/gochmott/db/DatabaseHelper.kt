package com.bilto.gochmott.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Достаёт готовый dict.db из assets в каталог баз приложения.
 *
 * Словарь целиком read-only: пользовательских данных в нём нет (см. SCHEMA.md — «Только
 * чтение»), поэтому при смене версии файл не мигрируется, а просто перезаписывается копией
 * из assets. Если когда-нибудь в эту же БД начнут писать пользовательские данные (закладки,
 * история), перезапись станет опасной — тогда их надо вынести в отдельный файл.
 *
 * Раньше копирование шло только «если файла нет», и после обновления приложения на
 * устройстве оставалась старая БД: запрос к `ru_index.lemma_id` падал с «no such column».
 * Теперь версия локальной копии сверяется с [EXPECTED_DB_VERSION] при каждом открытии.
 */
@Singleton
class DatabaseHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "DatabaseHelper"
        private const val DB_NAME = "dict.db"
        private const val TMP_NAME = "$DB_NAME.tmp"
        private const val COPY_BUFFER = 64 * 1024

        /**
         * Версия словаря. ДОЛЖНА совпадать с `PRAGMA user_version` в assets/dict.db:
         * при каждой пересборке БД поднимайте оба числа, иначе на устройствах со старой
         * копией она не обновится.
         */
        const val EXPECTED_DB_VERSION = 3

        /** Файлы SQLite рядом с БД: от прежней копии к новой они не относятся. */
        private val SIDECAR_SUFFIXES = listOf("-journal", "-wal", "-shm")

        private const val OPEN_FLAGS =
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
    }

    val database: SQLiteDatabase by lazy { openDatabase() }

    val hasFts5: Boolean by lazy {
        try {
            database.rawQuery("SELECT 1 FROM forms_trgm LIMIT 1", null).use { true }
        } catch (_: Exception) {
            false
        }
    }

    private val _dbVersion  = MutableStateFlow(-1)
    val dbVersion  = _dbVersion.asStateFlow()

    private fun openDatabase(): SQLiteDatabase {
        val dbFile = context.getDatabasePath(DB_NAME)

        val localVersion = fetchSchemaVersion(dbFile)
        if (localVersion != EXPECTED_DB_VERSION) {
            Log.i(TAG, "$DB_NAME: версия копии $localVersion, нужна $EXPECTED_DB_VERSION — ставим из assets")
            installFromAssets(dbFile)
        }
        _dbVersion.value = fetchSchemaVersion(dbFile)
        return SQLiteDatabase.openDatabase(dbFile.absolutePath, null, OPEN_FLAGS)
    }

    private fun fetchSchemaVersion(dbFile: File): Int = if (dbFile.exists()) readUserVersion(dbFile) ?: -1 else -1

    /**
     * `PRAGMA user_version` локальной копии. `null` — файл не открылся или не читается
     * как SQLite (обрезанная копия от прерванного копирования); тогда ставим заново.
     */
    private fun readUserVersion(dbFile: File): Int? = try {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, OPEN_FLAGS).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Не удалось прочитать user_version у $DB_NAME: ${e.message}")
        null
    }

    /**
     * Копирует БД во временный файл рядом и только потом атомарно подменяет им старую.
     * Напрямую нельзя: если процесс убьют посреди копирования 25 МБ, на диске останется
     * обрезанный файл, который выглядит как настоящая БД.
     */
    private fun installFromAssets(dbFile: File) {
        val dir = dbFile.parentFile ?: error("Нет каталога для баз данных")
        dir.mkdirs()
        val tmpFile = File(dir, TMP_NAME)
        tmpFile.delete()

        try {
            context.assets.open(DB_NAME).use { input ->
                FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output, COPY_BUFFER)
                    output.fd.sync() // дожать на диск ДО подмены
                }
            }
            moveAtomically(tmpFile, dbFile)
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }

        SIDECAR_SUFFIXES.forEach { File(dir, DB_NAME + it).delete() }
    }

    private fun moveAtomically(from: File, to: File) {
        try {
            Files.move(
                from.toPath(), to.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // тот же каталог — на практике сюда не попадаем, но подстрахуемся
            if (!to.delete() && to.exists()) error("Не удалось удалить старый $DB_NAME")
            if (!from.renameTo(to)) error("Не удалось заменить $DB_NAME")
        }
    }
}

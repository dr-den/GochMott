package com.vaynah.gochmott.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val DB_NAME = "dict.db"
    }

    val database: SQLiteDatabase by lazy { openDatabase() }

    val hasFts5: Boolean by lazy {
        try {
            database.rawQuery("SELECT 1 FROM forms_trgm LIMIT 1", null).use { true }
        } catch (_: Exception) {
            false
        }
    }

    private fun openDatabase(): SQLiteDatabase {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) {
            dbFile.parentFile?.mkdirs()
            context.assets.open(DB_NAME).use { input ->
                FileOutputStream(dbFile).use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
        }
        return SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
    }
}

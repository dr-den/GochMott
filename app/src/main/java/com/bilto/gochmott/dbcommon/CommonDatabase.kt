package com.bilto.gochmott.dbcommon

import androidx.room.Database
import androidx.room.RoomDatabase
import com.bilto.gochmott.settingsrepo.Setting
import com.bilto.gochmott.settingsrepo.SettingsDao

@Database(
    entities = [Setting::class,],
    version = 1,
    exportSchema = true
)


abstract class CommonDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
}


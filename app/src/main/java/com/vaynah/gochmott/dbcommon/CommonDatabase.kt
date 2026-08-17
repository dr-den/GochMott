package com.vaynah.gochmott.dbcommon

import androidx.room.Database
import androidx.room.RoomDatabase
import com.vaynah.gochmott.settingsrepo.Setting
import com.vaynah.gochmott.settingsrepo.SettingsDao

@Database(
    entities = [Setting::class,],
    version = 1,
    exportSchema = true
)


abstract class CommonDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
}


package com.bilto.gochmott.settingsrepo

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy.Companion.REPLACE
import androidx.room.Query
import com.bilto.gochmott.settingsrepo.Setting
import kotlinx.coroutines.flow.Flow
import com.bilto.gochmott.settingsrepo.Setting.Companion.TABLE_SETTINGS

@Dao
interface SettingsDao {
    @Insert(onConflict = REPLACE)
    suspend fun save(setting: Setting)

    @Query("DELETE FROM $TABLE_SETTINGS WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM $TABLE_SETTINGS WHERE `key` = :key")
    fun loadByKey(key: String): Flow<Setting?>

    @Query("SELECT * FROM $TABLE_SETTINGS WHERE `key` = :key")
    fun loadByKeyNow(key: String): Setting?
}
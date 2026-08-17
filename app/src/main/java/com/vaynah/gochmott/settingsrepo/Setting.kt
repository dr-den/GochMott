package com.vaynah.gochmott.settingsrepo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vaynah.gochmott.settingsrepo.Setting.Companion.TABLE_SETTINGS

@Entity(tableName = TABLE_SETTINGS)
data class Setting(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String?
){
    companion object{
        const val TABLE_SETTINGS = "Settings"
    }
}
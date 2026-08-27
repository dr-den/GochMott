package com.bilto.gochmott.settingsrepo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow

//Универсальный солдат
interface SettingsRepository: CoroutineScope {
    fun <T> get(key: SettingKey<T>): Flow<T>
    suspend fun <T> getOrNull(key: SettingKey<T>): T?
    fun <T> set(key: SettingKey<T>, value: T): Job

}


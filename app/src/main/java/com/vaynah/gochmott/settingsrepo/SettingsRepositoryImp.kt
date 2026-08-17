package com.vaynah.gochmott.settingsrepo

import com.vaynah.gochmott.settingsrepo.Setting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImp @Inject constructor(
    private val settingsDao: SettingsDao,
    coroutineScope: CoroutineScope,
) : SettingsRepository, CoroutineScope by coroutineScope + Dispatchers.Default {


    override fun <T> get(key: SettingKey<T>): Flow<T> {
        return settingsDao.loadByKey(key.name)
            .map { castValue(it?.value, key) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    override suspend fun <T> getOrNull(key: SettingKey<T>): T? = withContext(Dispatchers.Default) {
            settingsDao.loadByKeyNow(key.name)
                ?.value
                ?.let {
                    castValueOrNull(it, key)
                }
        }

    override fun <T> set(key: SettingKey<T>, value: T): Job {
      return launch {
          if (value == null) {
              settingsDao.delete(key.name)
          } else {
              settingsDao.save(Setting(key.name, value.toString()))
          }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> castValue(untypedValue: String?, key: SettingKey<T>): T {
        if (untypedValue == null) return key.defaultValue
        return castValueOrNull(untypedValue, key) ?: key.defaultValue
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> castValueOrNull(untypedValue: String, key: SettingKey<T>): T? = when (key) {
        is SettingKey.Str    -> untypedValue as T
        is SettingKey.Bool   -> untypedValue.toBooleanStrictOrNull() as T?
        is SettingKey.Int    -> untypedValue.toIntOrNull() as T?
        is SettingKey.Long   -> untypedValue.toLongOrNull() as T?
        is SettingKey.Float  -> untypedValue.toFloatOrNull() as T?
        is SettingKey.Double -> untypedValue.toDoubleOrNull() as T?
    }
}

@JvmName("toggleNonNull")
fun SettingsRepository.toggle(key: SettingKey.Bool<Boolean>): Job {
    return launch {
        val current = getOrNull(key) ?: key.defaultValue
        set(key, !current)
    }
}

@JvmName("toggleNullable")
fun SettingsRepository.toggle(key: SettingKey.Bool<Boolean?>): Job {
    return launch {
        val current = getOrNull(key) ?: key.defaultValue ?: false
        set(key, !current)
    }
}
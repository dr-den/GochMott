package com.bilto.gochmott.settingsrepo


object SettingKeys {
    val searchHistoryCeToRu = SettingKey.Str("searchHistoryCeToRu", "")
    val searchHistoryRuToCe = SettingKey.Str("searchHistoryRuToCe", "")

    /** Чёрточки долготы в чеченских словах: `ха̃дадала` против `хададала`. */
    val showCeLength = SettingKey.Bool("showCeLength", true)

    /** Ударения в русских переводах: `ка́ждый раз` против `каждый раз`. */
    val showRuStress = SettingKey.Bool("showRuStress", true)

    /**
     * Язык вводной части словаря: `RU` или `CE`. Это язык ТЕКСТА Мациева,
     * а не интерфейса — книга напечатана на обоих, и выбор общий для всех
     * её разделов.
     */
    val bookLanguage = SettingKey.Str("bookLanguage", "RU")

    /**
     * Кэш подписи пустого экрана: `версия|книг|чеченских слов|русских слов`.
     *
     * Считать это при каждом старте нельзя — запрос по русской стороне пробегает
     * весь обратный индекс на 93 тысячи строк. А раз словарь read-only и меняется
     * только вместе с `PRAGMA user_version`, версия в первом поле и есть признак
     * годности кэша: не совпала — пересчитать.
     *
     * Хранится строкой, а не отдельной таблицей: это производный кэш, ради него
     * не стоит трогать схему `common.db` — миграция там с
     * `fallbackToDestructiveMigration`, и ошибка стоила бы настроек и истории.
     */
    val dictStats = SettingKey.Str("dictStats", "")
}


sealed class SettingKey<T>(val name: String, val defaultValue: T) {

    class Str<T : String?> private constructor(name: String, default: T) : SettingKey<T>(name, default) {
        companion object {
            @JvmName("invoke")
            operator fun invoke(name: String, default: String)  = Str<String>(name, default)
            @JvmName("invokeNullable")
            operator fun invoke(name: String, default: String?) = Str<String?>(name, default)
        }
    }

    class Bool<T : Boolean?> private constructor(name: String, default: T) : SettingKey<T>(name, default) {
        companion object {
            operator fun invoke(name: String, default: Boolean)  = Bool<Boolean>(name, default)
            operator fun invoke(name: String, default: Boolean?) = Bool<Boolean?>(name, default)
        }
    }

    class Int<T : kotlin.Int?> private constructor(name: String, default: T) : SettingKey<T>(name, default) {
        companion object {
            operator fun invoke(name: String, default: kotlin.Int)  = Int<kotlin.Int>(name, default)
            operator fun invoke(name: String, default: kotlin.Int?) = Int<kotlin.Int?>(name, default)
        }
    }

    class Long<T : kotlin.Long?> private constructor(name: String, default: T) : SettingKey<T>(name, default) {
        companion object {
            operator fun invoke(name: String, default: kotlin.Long)  = Long<kotlin.Long>(name, default)
            operator fun invoke(name: String, default: kotlin.Long?) = Long<kotlin.Long?>(name, default)
        }
    }

    class Float<T : kotlin.Float?> private constructor(name: String, default: T) : SettingKey<T>(name, default) {
        companion object {
            operator fun invoke(name: String, default: kotlin.Float)  = Float<kotlin.Float>(name, default)
            operator fun invoke(name: String, default: kotlin.Float?) = Float<kotlin.Float?>(name, default)
        }
    }

    class Double<T : kotlin.Double?> private constructor(name: String, default: T) : SettingKey<T>(name, default) {
        companion object {
            operator fun invoke(name: String, default: kotlin.Double)  = Double<kotlin.Double>(name, default)
            operator fun invoke(name: String, default: kotlin.Double?) = Double<kotlin.Double?>(name, default)
        }
    }
}


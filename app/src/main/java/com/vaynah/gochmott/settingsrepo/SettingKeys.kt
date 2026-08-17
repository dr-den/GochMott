package com.vaynah.gochmott.settingsrepo


object SettingKeys {
    val searchHistoryCeToRu = SettingKey.Str("searchHistoryCeToRu", "")
    val searchHistoryRuToCe = SettingKey.Str("searchHistoryRuToCe", "")
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


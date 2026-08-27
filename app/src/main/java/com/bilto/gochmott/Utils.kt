package com.bilto.gochmott


import java.util.Locale


fun String.capitalizeFirst(): String {
    return replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()}
}


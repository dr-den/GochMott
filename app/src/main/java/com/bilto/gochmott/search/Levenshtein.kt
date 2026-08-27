package com.bilto.gochmott.search

/** Расстояние редактирования. Используется для ранжирования примерных совпадений. */
object Levenshtein {

    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val curr = IntArray(b.length + 1)
            curr[0] = i
            for (j in 1..b.length) {
                curr[j] = if (a[i - 1] == b[j - 1]) prev[j - 1]
                          else minOf(prev[j], curr[j - 1], prev[j - 1]) + 1
            }
            prev = curr
        }
        return prev[b.length]
    }

    /**
     * То же, но с отсечкой: как только вся строка матрицы больше [max], считать дальше
     * бессмысленно и возвращается `max + 1`. Это позволяет прогонять запрос по всему
     * индексу заголовков (20 тыс. слов) за единицы миллисекунд.
     */
    fun atMost(a: String, b: String, max: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        if (a.isEmpty()) return minOf(b.length, max + 1)
        if (b.isEmpty()) return minOf(a.length, max + 1)
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val curr = IntArray(b.length + 1)
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                curr[j] = if (a[i - 1] == b[j - 1]) prev[j - 1]
                          else minOf(prev[j], curr[j - 1], prev[j - 1]) + 1
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > max) return max + 1
            prev = curr
        }
        return minOf(prev[b.length], max + 1)
    }
}
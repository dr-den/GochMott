package com.bilto.gochmott.repository

/**
 * Какие связанные статьи (`lemma_links`) показывать одной строкой.
 *
 * Связи попарны, и раньше группой считалась вся компонента связности. Это
 * ломается на омонимах: «пасовать» Аслаханова сборщик связал по написанию с
 * ОБОИМИ омонимами Карасаева — картёжным «юкъаравала» и спортивным «буьрка
 * дӀаялар», — и через неё оба омонима склеивались в одну строку, хотя книга
 * нарочно печатает их порознь.
 *
 * Поэтому два правила:
 *
 *  1. **В группе не бывает двух статей одной книги.** Раз книга развела слово
 *     на омонимы, сводить их обратно не нам.
 *  2. **Статья, связанная с несколькими омонимами одной книги, достаётся тому,
 *     с чьими переводами у неё больше общих слов.** Спортивное «буьрка дӀаяла»
 *     делит «буьрка» со вторым омонимом и ничего — с первым. Если явного
 *     лидера нет (ничья или ни одного общего слова), статья не прикрепляется
 *     ни к кому: показать строку лишний раз честнее, чем угадать.
 *
 * Объект без базы и Android — ему передают уже прочитанное, и так его можно
 * проверить обычным юнит-тестом.
 */
object LinkGroups {

    data class Edge(val a: Long, val b: Long, val confidence: Double)

    /** Слова короче этого в сравнении переводов не участвуют: «а», «я», «и» совпадут у всех. */
    private const val MIN_WORD = 3

    private val WORD_SPLIT = Regex("[^\\p{L}\\u0300-\\u036F]+")

    /** Слова перевода для сравнения; на входе — `glosses.text_norm`. */
    fun words(glosses: Collection<String>): Set<String> =
        glosses.flatMapTo(HashSet()) { text ->
            text.lowercase().split(WORD_SPLIT).filter { it.length >= MIN_WORD }
        }

    /**
     * Группы из двух и более статей, каждая отсортирована по id.
     *
     * [bookOf] — книга каждой статьи; статья без книги ни с кем не сливается.
     * [wordsOf] — слова её переводов (см. [words]).
     */
    fun build(
        edges: Collection<Edge>,
        bookOf: Map<Long, String>,
        wordsOf: Map<Long, Set<String>>
    ): List<List<Long>> {
        fun overlap(e: Edge): Int {
            val a = wordsOf[e.a] ?: return 0
            val b = wordsOf[e.b] ?: return 0
            return a.count { it in b }
        }

        val usable = edges.filter { e ->
            val bookA = bookOf[e.a]
            val bookB = bookOf[e.b]
            e.a != e.b && bookA != null && bookB != null && bookA != bookB
        }.distinctBy { minOf(it.a, it.b) to maxOf(it.a, it.b) }

        // Правило 2: у каждой статьи к каждой чужой книге остаётся не больше одной связи.
        val rejected = HashSet<Edge>()
        val byNode = HashMap<Long, MutableList<Edge>>()
        usable.forEach { e ->
            byNode.getOrPut(e.a) { mutableListOf() } += e
            byNode.getOrPut(e.b) { mutableListOf() } += e
        }
        byNode.forEach { (node, nodeEdges) ->
            nodeEdges.groupBy { e -> bookOf.getValue(if (e.a == node) e.b else e.a) }
                .values.filter { it.size > 1 }
                .forEach { rivals ->
                    val ranked = rivals.sortedWith(
                        compareByDescending<Edge> { it.confidence }.thenByDescending { overlap(it) }
                    )
                    val best = ranked[0]
                    val runnerUp = ranked[1]
                    val clearWinner = overlap(best) > 0 &&
                        (best.confidence > runnerUp.confidence || overlap(best) > overlap(runnerUp))
                    rejected += if (clearWinner) ranked.drop(1) else ranked
                }
        }

        // Правило 1: сливаем от сильной связи к слабой, пока книги в группе не повторяются.
        val parent = HashMap<Long, Long>()
        val books = HashMap<Long, MutableSet<String>>()
        fun find(x: Long): Long {
            var cur = x
            while (parent.getValue(cur) != cur) {
                parent[cur] = parent.getValue(parent.getValue(cur))
                cur = parent.getValue(cur)
            }
            return cur
        }
        fun add(x: Long) {
            if (x !in parent) {
                parent[x] = x
                books[x] = mutableSetOf(bookOf.getValue(x))
            }
        }

        usable.filter { it !in rejected }
            .sortedWith(
                compareByDescending<Edge> { it.confidence }
                    .thenByDescending { overlap(it) }
                    .thenBy { minOf(it.a, it.b) }
                    .thenBy { maxOf(it.a, it.b) }
            )
            .forEach { e ->
                add(e.a)
                add(e.b)
                val ra = find(e.a)
                val rb = find(e.b)
                if (ra == rb) return@forEach
                val booksA = books.getValue(ra)
                val booksB = books.getValue(rb)
                if (booksA.any { it in booksB }) return@forEach
                parent[ra] = rb
                booksB += booksA
                books.remove(ra)
            }

        return parent.keys.groupBy { find(it) }.values
            .filter { it.size > 1 }
            .map { it.sorted() }
            .sortedBy { it.first() }
    }
}

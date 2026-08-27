package com.bilto.gochmott.model

/**
 * Диакритика словаря Мациева.
 *
 * В `dict.db` чеченский текст лежит С чёрточками долготы (`ха̃дадала`), русский —
 * С ударениями (`ка́ждый раз`). Ключи поиска (`*_norm`) хранятся отдельно и уже
 * без знаков, поэтому снимать их нужно только для показа — одним проходом по
 * строке. Обратной операции не требуется: размеченная строка и есть источник.
 *
 * Долгота и ударение — разные вещи и переключаются независимо. Ударение помогает
 * читать русский перевод; чёрточка долготы — условность издания 1961 года,
 * в современном письме её не ставят, и комбинирующая U+0303 над кириллицей
 * на части системных шрифтов Android съезжает. Отсюда умолчания ниже.
 */
object Marks {
    const val LENGTH = '̃'   // чёрточка долготы над гласной
    const val STRESS = '́'   // русское ударение

    /** Единственная точка, где решается, что показывать. Готово под настройку. */
    var showLength = false
    var showStress = true

    fun ce(text: String?): String =
        if (text == null) "" else if (showLength) text else text.filterNot { it == LENGTH }

    fun ru(text: String?): String? =
        if (text == null) null else if (showStress) text else text.filterNot { it == STRESS }
}

data class LemmaHit(
    val id: Long,
    /** Заголовок статьи, уже подготовленный к показу через [Marks.ce]. */
    val headword: String,
    /** Номер омонима; 0 — статья не омоним (в БД `homonym IS NULL`). */
    val homographN: Int,
    val pos: String?,
    /** `class_star`: глагол меняется не только по классу `д`, но и по б/в/й. */
    val isClassAgreeing: Boolean,
    val pluraliaTantum: Boolean,
    /** Пометы статьи как напечатаны: «перен.», «уст.», «нескл.». */
    val labels: List<String>,
    /** «объект в ед.» / «объект во мн.»: `sg` | `pl` | null. */
    val objNum: String?,
    val subjNum: String?,
    val exactHeadword: Boolean,
    /** Первые значения одной строкой — для карточки в списке выдачи. */
    val firstSenses: List<String> = emptyList(),
    val classes: List<GramClass> = emptyList(),
    /**
     * Перевод, который дал совпадение при поиске рус→чеч. Обратный индекс висит
     * на глоссе, а не на статье, поэтому у «утомле́ние» видно, что у `хьахар¹`
     * совпало значение 1 из 4, а не вся статья целиком. null для чеч→рус.
     */
    val matchedGloss: String? = null
) {
    val indeclinable: Boolean get() = "нескл." in labels
}

data class GramClass(
    val marker: String,
    val number: String
)

data class Form(
    val form: String,
    val isHeadword: Boolean,
    val caseAbbr: String?,
    val caseName: String?,
    val number: String?,
    val tam: String?,
    val source: String?
)

/**
 * Один перевод внутри значения. У значения их бывает несколько:
 * «ослабле́ние; утомле́ние» — два глосса, [sep] хранит разделитель ПЕРЕД глоссом
 * (`,` — синонимы, `;` — более далёкий перевод).
 */
data class Gloss(
    val ru: String,
    val sep: String?,
    /** Уточнение в скобках: у «рука́» — «кисть». */
    val note: String?,
    /** Управление: «чем-л.», «кого-л.». */
    val gov: String?,
    val labels: List<String>
)

data class Sense(
    val id: Long,
    /** Номер значения из книги; null — значение в статье одно. */
    val senseNo: Int?,
    /** Номер блока `1.` / `2.`, если статья разбита по частям речи. */
    val blockN: Int?,
    val pos: String?,
    val labels: List<String>,
    val glosses: List<Gloss>,
    /** Примеры этого значения. В книге они стоят прямо за переводом. */
    val examples: List<Example>
)

/** Подпункт примера: «куьг таӀо — а) кри́кнуть б) окли́кнуть». */
data class Sub(
    val letter: String?,
    val ru: String,
    val note: String?,
    val gov: String?
)

data class Example(
    val ceText: String,
    val ruText: String?,
    /** `phrase` | `посл.` | `погов.` */
    val kind: String?,
    val isIdiom: Boolean,
    /** Пояснение к переводу; [noteKind] говорит какое — обычно «букв.». */
    val note: String?,
    val noteKind: String?,
    val gov: String?,
    val labels: List<String>,
    val subs: List<Sub>
)

/**
 * Отсылка к другой статье. [rel] уже человекочитаем — «понуд. от», «см.»,
 * «прил. к»: в словаре Мациева это готовые пометы, расшифровывать нечего.
 */
data class Ref(
    val rel: String,
    val toHeadword: String,
    val toLemmaId: Long?
)

data class EntryDetail(
    val lemma: LemmaHit,
    val forms: List<Form>,
    val senses: List<Sense>,
    /** Идиомы за ромбом «◊» — они относятся к статье целиком, а не к значению. */
    val idioms: List<Example>,
    val refs: List<Ref>
)

enum class SearchDirection {
    CE_TO_RU, RU_TO_CE;

    fun opposite(): SearchDirection = if (this == CE_TO_RU) RU_TO_CE else CE_TO_RU
}

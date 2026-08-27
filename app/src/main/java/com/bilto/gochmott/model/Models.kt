package com.bilto.gochmott.model

data class LemmaHit(
    val id: Long,
    val headword: String,
    val homographN: Int,
    val pos: String?,
    val isClassAgreeing: Boolean,
    val pluraliaTantum: Boolean,
    val indeclinable: Boolean,
    val gramNote: String?,
    val exactHeadword: Boolean,
    val firstSenses: List<String> = emptyList(),
    val classes: List<GramClass> = emptyList()
)

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

data class Sense(
    val id: Long,
    val senseNo: Int,
    val glossRu: String,
    val domain: String?
)

data class Example(
    val ceText: String,
    val ruText: String?,
    val kind: String
)

data class Ref(
    val relType: String,
    val toHeadwordRaw: String,
    val toLemmaId: Long?
)

data class EntryDetail(
    val lemma: LemmaHit,
    val forms: List<Form>,
    val senses: List<Sense>,
    val examples: List<Example>,
    val refs: List<Ref>
)

enum class SearchDirection {
    CE_TO_RU, RU_TO_CE;

    fun opposite(): SearchDirection = if (this == CE_TO_RU) RU_TO_CE else CE_TO_RU
}

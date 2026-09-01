package com.bilto.gochmott.model

/** Коды языков базы. Совпадают с `lemmas.lang`, `forms.lang`, `glosses.lang`. */
object Lang {
    const val CE = "ce"
    const val RU = "ru"

    /** Сторона перевода для стороны заголовка и наоборот. База двуязычная. */
    fun other(lang: String?): String = if (lang == RU) CE else RU
}

data class LemmaHit(
    val id: Long,
    /** Заголовок статьи как в книге — со знаками долготы. */
    val headword: String,
    /**
     * Язык заголовка (`lemmas.lang` = `dicts.lang_src`). В базе есть словари обоих
     * направлений, поэтому по нему выбирается и нужный надстрочный знак, и порядок
     * сторон в примерах: у статьи `ада́птер` (рус→чеч) заголовок русский.
     */
    val lang: String = Lang.CE,
    /** `dicts.book` — книга, а не направление: у двуязычной книги оно общее. */
    val dictBook: String = "",
    val dictYear: Int? = null,
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
    val matchedGloss: String? = null,
    /**
     * Другие книги, где та же статья повторена слово в слово, — строка выдачи
     * у них общая. Пусто, если статья одна или книги расходятся по значениям.
     */
    val alsoIn: List<MergedRef> = emptyList()
) {
    val indeclinable: Boolean get() = "нескл." in labels

    /** Язык переводов этой статьи: сторона, противоположная заголовку. */
    val glossLang: String get() = Lang.other(lang)
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
 *
 * [text] назван по роли, а не по языку: у словаря чеч→рус это русский перевод,
 * у словаря рус→чеч — чеченский (в базе колонка тоже переименована `ru` → `text`).
 */
data class Gloss(
    val text: String,
    /**
     * Книги, которые дают ровно этот перевод, стоя к слову ЗЕРКАЛЬНО: у них
     * оно заголовок, а наше слово — перевод. `харцо̃` у Мациева переводится
     * «непра́вда, ложь, неуда́ча…», а математический словарь ставит заголовком
     * «ложь» и переводит его как `харцо` — то же самое, сказанное с другой
     * стороны. Помета говорит, что перевод подтверждён и другой книгой.
     */
    val fromBooks: List<MergedRef> = emptyList(),
    /** Язык перевода (`glosses.lang` = `dicts.lang_tgt`). */
    val lang: String,
    /**
     * Классные показатели чеченского перевода, как их печатает книга: `(б, д)` —
     * ед. и мн. Приходят из `glosses.gram.cls`; у словарей чеч→рус пусто.
     */
    val cls: List<String>,
    val sep: String?,
    /** Уточнение в скобках: у «рука́» — «кисть». */
    val note: String?,
    /** Управление: «чем-л.», «кого-л.». */
    val gov: String?,
    val labels: List<String>
)

data class Sense(
    val id: Long,
    /**
     * Книги, откуда значение, если у эталона его нет. Пусто — значение своё.
     *
     * Список, а не одна книга: «цифра» у `хьаьрк` есть и в 1997, и в 2017, и это
     * одно значение с двумя источниками, а не две строки.
     */
    val fromBooks: List<MergedRef> = emptyList(),
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
    /** Перевод подпункта; язык — сторона перевода словаря, см. [Gloss.text]. */
    val text: String,
    val lang: String,
    val note: String?,
    val gov: String?
)

data class Example(
    /** Книга, если пример пришёл не из статьи-эталона. null — свой. */
    val dictBook: String? = null,
    val dictYear: Int? = null,
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
    val toLemmaId: Long?,
    /**
     * Первые значения статьи, на которую отсылка. Заполняются только когда своих
     * значений у статьи нет: тогда отсылка и есть весь её смысл, и показать
     * перевод цели прямо в карточке важнее, чем сэкономить запрос.
     */
    val targetSenses: List<String> = emptyList()
)

/** Та же статья в другой книге: куда идти и чем подписать. */
data class MergedRef(
    val lemmaId: Long,
    val dictBook: String,
    val dictYear: Int?
)

/** В каком числе и какой показатель стоит у книги-двойника. */
data class ClassDifference(
    /** `sg` | `pl`. */
    val number: String,
    val markers: List<String>
)

/**
 * Расхождение по классу с эталоном.
 *
 * `ед[бу] мн[ду] (мн[бу] в Математика, 1997)`: у перечисленных книг показатель
 * другой. Эталон — книга с наименьшим `dicts.priority`, у нас это Мациев.
 * Победителя не выбираем: показываем и своё, и чужое с источником.
 *
 * Группируется по КНИГАМ, а не по числам: у `агӀо` обе младшие книги расходятся
 * и в единственном, и во множественном, и четыре отдельные пометы в строке —
 * это уже нечитаемо. Одна: «(ед[ю] мн[ю] в Математика, 1997 и Компьютер, 2017)».
 */
data class ClassNote(
    val differences: List<ClassDifference>,
    val books: List<MergedRef>
)

/**
 * Паспорт словаря, из которого взята статья (`dicts`). [citation] уже готов к показу
 * и к «поделиться» — собран сборщиком, склеивать его в UI не нужно.
 */
data class DictSource(
    val code: String,
    val title: String,
    val authors: String,
    val year: Int?,
    val citation: String
)

/**
 * То же слово в другой книге (`lemma_links`). Поля книг НЕ сливаются: связь говорит
 * лишь «это одно слово». [conflict] — список полей, в которых книги расходятся
 * (класс, часть речи); непустой список это не ошибка, а разночтение источников.
 */
data class LinkedEntry(
    val lemmaId: Long,
    val headword: String,
    /** Язык заголовка связанной статьи — для выбора надстрочного знака. */
    val lang: String,
    val homographN: Int,
    val dictTitle: String,
    val method: String,
    val confidence: Double,
    val conflict: List<String>,
    /**
     * `reviewed=1` — человека уже спросили, и он подтвердил связь (`reviewed.tsv`).
     * Значит расхождение настоящее, а не подозрение сборщика: тревожную плашку
     * показывать незачем, достаточно [note] — его же объяснения.
     */
    val reviewed: Boolean = false,
    val note: String? = null
)

data class EntryDetail(
    val lemma: LemmaHit,
    val forms: List<Form>,
    val senses: List<Sense>,
    /** Идиомы за ромбом «◊» — они относятся к статье целиком, а не к значению. */
    val idioms: List<Example>,
    val refs: List<Ref>,
    /** Откуда статья. null — только если паспорт словаря не прочитался. */
    val source: DictSource? = null,
    /** Это же слово в других книгах; пусто, пока словарь в базе один. */
    val related: List<LinkedEntry> = emptyList(),
    /** Чем книги расходятся по классу; пусто, если сходятся или статья одна. */
    val classNotes: List<ClassNote> = emptyList()
)

/**
 * Одно употребление чеченского слова в словаре рус→чеч.
 *
 * Это НЕ статья: у слова `координатийн` своей статьи нет ни в одной книге, оно
 * встречается только внутри чужих переводов. [lemmaId] ведёт в ту русскую статью,
 * где оно найдено.
 */
data class Usage(
    /** 0 и 3 — слово стоит переводом статьи, 1 и 2 — внутри словосочетания. */
    val src: Int,
    val lemmaId: Long,
    /** Заголовок русской статьи, в которой нашлось слово. */
    val ruHeadword: String,
    val dictBook: String,
    val dictYear: Int?,
    /** Перевод статьи целиком — заполнен при [isGloss]. */
    val gloss: String?,
    /** Чеченское словосочетание и его русская сторона — при `src` 1 и 2. */
    val phraseCe: String?,
    val phraseRu: String?
) {
    val isGloss: Boolean get() = src == 0 || src == 3
}

/**
 * Чеченское слово, у которого нет своей статьи, но которое встречается в переводах.
 *
 * Показывается заголовком — так его и ищут, — но открывается не карточкой статьи,
 * а списком употреблений: статьи-то нет, а есть места, где слово стоит.
 */
data class UsageEntry(
    /** Написание со знаками долготы, как в книге; ключ, если восстановить не вышло. */
    val word: String,
    /** Нормализованный ключ — он же аргумент маршрута. */
    val key: String,
    val usages: List<Usage>
) {
    val asGloss: List<Usage> get() = usages.filter { it.isGloss }
    val inPhrases: List<Usage> get() = usages.filterNot { it.isGloss }
}

/**
 * Что лежит в базе — для пустого экрана поиска.
 *
 * Считается по данным, а не пишется в ресурсах: цифра в строке «N слов» устаревает
 * при первой же пересборке базы, а заметить это некому.
 *
 * [books] — КНИГИ (`dicts.book`), а не направления: у двуязычной книги их два, но
 * читатель держал в руках одну.
 *
 * «Слово» с каждой стороны своё, потому что стороны устроены по-разному:
 *  * [chechenWords] — чеченские заголовки: по ним идёт прямой поиск;
 *  * [russianWords] — различные русские слова, по которым вообще можно спросить:
 *    заголовки книг рус→чеч плюс словарь обратного индекса.
 */
data class DictStats(
    val books: Int,
    val chechenWords: Int,
    val russianWords: Int
) {
    fun wordsFor(direction: SearchDirection): Int =
        if (direction == SearchDirection.CE_TO_RU) chechenWords else russianWords
}

enum class SearchDirection {
    CE_TO_RU, RU_TO_CE;

    fun opposite(): SearchDirection = if (this == CE_TO_RU) RU_TO_CE else CE_TO_RU
}

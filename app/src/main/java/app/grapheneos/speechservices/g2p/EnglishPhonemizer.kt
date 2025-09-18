/**
 * English phonemizer based on Misaki en.py with improvements: https://github.com/hexgrad/misaki
 */

package app.grapheneos.speechservices.g2p

import android.icu.lang.UCharacter
import app.grapheneos.speechservices.g2p.fallback_network.FallbackNetwork
import app.grapheneos.speechservices.verboseLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opennlp.tools.postag.POSTaggerME
import opennlp.tools.tokenize.TokenizerME
import opennlp.tools.util.Span
import java.text.Normalizer
import java.util.Locale
import kotlin.math.absoluteValue

private const val TAG: String = "EnglishPhonemizer"

fun mergeTokens(tokens: List<MToken>, unk: String? = null): MToken {
    val stress = tokens.mapNotNull { it.more?.get("stress") }
    val currency = tokens.mapNotNull { it.more?.get("currency") as String? }
    val rating = tokens.map { it.more?.get("rating") as Int? }
    var phonemes: String? = null
    if (unk != null) {
        phonemes = ""
        for (tk in tokens) {
            if (tk.more?.get("prespace") == true && phonemes.lastOrNull()
                    ?.isWhitespace() == true && tk.phonemes != null
            ) {
                phonemes += ' '
            }
            phonemes += tk.phonemes ?: unk
        }
    }
    return MToken(
        tokens.dropLast(1)
            .joinToString("") { tk -> tk.text + tk.whitespace } + (tokens.lastOrNull()?.text
            ?: emptyList<MToken>()),
        tokens.maxBy { tk ->
            tk.text.sumOf { char ->
                if (char == char.lowercaseChar()) {
                    1
                } else {
                    2
                }
            }
        }.tag,
        tokens.last().whitespace,
        phonemes,
        tokens.first().startTs,
        tokens.last().endTs,
        mutableMapOf(
            "isHead" to (tokens.first().more?.get("isHead") == true),
            "alias" to null,
            "stress" to if (stress.size == 1) {
                stress.take(1)
            } else {
                null
            },
            "currency" to currency.maxOrNull(),
            "numFlags" to tokens
                .mapNotNull { tk -> tk.more?.get("numFlags") as String? }
                .flatMap { it.toList() }
                .toSet()
                .sorted()
                .joinToString(""),
            "prespace" to tokens.first().more?.get("prespace"),
            "rating" to if (null in rating) {
                null
            } else {
                rating.mapNotNull { it }.minOrNull()
            },
        )
    )
}

val DIPHTHONGS = "AIOQWYʤʧ".toSet()
fun stressWeight(phonemes: String?): Int {
    return if (!phonemes.isNullOrEmpty()) {
        phonemes.sumOf { char ->
            if (DIPHTHONGS.contains(char)) {
                2
            } else {
                1
            }
        }
    } else {
        0
    }
}

data class TokenContext(
    val futureVowel: Boolean? = null,
    val futureTo: Boolean? = null,
)

val subtokenRegex =
    Regex("""^['‘’]+|\p{Lu}(?=\p{Lu}\p{Ll})|(?:^-)?(?:\d?[,.]?\d)+|[-_]+|['‘’]{2,}|\p{L}*?(?:['‘’]\p{L})*?\p{Ll}(?=\p{Lu})|\p{L}+(?:['‘’]\p{L})*|[^-_\p{L}'‘’\d]|['‘’]+$""")

fun findSubtokens(word: String): Sequence<MatchResult> {
    return subtokenRegex.findAll(word)
}

val LINK_REGEX = Regex("""\[([^]]+)]\(([^)]*)\)""")

// TODO: Handle '' quotes (needs context because ' is also used as an apostrophe).
val SUBTOKEN_JUNKS = """',-._‘’/""".toSet()
val PUNCTS = """;:,.!?—…"“”()""".toSet()
val NON_QUOTE_PUNCTS =
    PUNCTS.mapNotNull { if (!""""“”""".contains(it)) it.toString() else null }.toSet()

val PUNCTS_REPLACEMENTS = mutableMapOf(
    '“' to '"',
    '”' to '"',
    '…' to "...",
)

val LEXICON_ORDS = listOf(39, 45) + (65..91) + (97..123)
val CONSONANTS = "bdfhjklmnpstvwzðŋɡɹɾʃʒʤʧθ".toSet()

// EXTENDER = 'ː'
val US_TAUS = "AIOWYiuæɑəɛɪɹʊʌ".toSet()

val CURRENCIES = mutableMapOf(
    "$" to listOf("dollar", "cent"),
    "£" to listOf("pound", "pence"),
    "€" to listOf("euro", "cent"),
)
val ORDINALS = setOf("st", "nd", "rd", "th")

val PUNCT_SYMBOLS = mutableMapOf(
    "." to "dot",
    "/" to "slash",
    "_" to "underscore",
)
val SYMBOLS = mutableMapOf(
    "%" to "percent",
    "&" to "and",
    "+" to "plus",
    "@" to "at",
)

val US_VOCAB = "AIOWYbdfhijklmnpstuvwzæðŋɑɔəɛɜɡɪɹɾʃʊʌʒʤʧˈˌθᵊᵻʔ".toSet() // ɐ
val GB_VOCAB = "AIQWYabdfhijklmnpstuvwzðŋɑɒɔəɛɜɡɪɹʃʊʌʒʤʧˈˌːθᵊ".toSet() // ɐ

const val STRESSES = "ˌˈ"
const val PRIMARY_STRESS = STRESSES[1]
const val SECONDARY_STRESS = STRESSES[0]
val VOWELS = "AIOQWYaiuæɑɒɔəɛɜɪʊʌᵻ".toSet()
fun applyStress(phonemes: String?, stress: Double?): String? {
    fun restress(phonemes: String): String {
        val indexedPhonemes: MutableList<Pair<Double, Char>> =
            phonemes.mapIndexed { index, phoneme ->
                Pair(index.toDouble(), phoneme)
            }.toMutableList()
        val stresses =
            indexedPhonemes.filter { STRESSES.contains(it.second) }.map { (stressIndex, _) ->
                val nextVowel = indexedPhonemes.filter { (phonemeIndex, _) ->
                    phonemeIndex >= stressIndex
                }.first { (_, potentialVowel) ->
                    VOWELS.contains(potentialVowel)
                }
                stressIndex to nextVowel.first
            }
        for ((stressIndex, vowelIndex) in stresses) {
            val (_, stressChar) = indexedPhonemes[stressIndex.toInt()]
            indexedPhonemes[stressIndex.toInt()] = Pair(vowelIndex - 0.5, stressChar)
        }
        return indexedPhonemes.toSortedSet { o1, o2 -> o1.first.compareTo(o2.first) }
            .joinToString("") { it.second.toString() }
    }
    if (stress == null) {
        return phonemes
    } else if (stress < -1) {
        return phonemes?.replace(PRIMARY_STRESS.toString(), "")
            ?.replace(SECONDARY_STRESS.toString(), "")
    } else if (stress.toInt() == -1 || (stress in setOf(0, -0.5) && phonemes?.contains(
            PRIMARY_STRESS
        ) == true)
    ) {
        return phonemes?.replace(SECONDARY_STRESS.toString(), "")
            ?.replace(PRIMARY_STRESS, SECONDARY_STRESS)
    } else if (stress in setOf(0, 0.5, 1) && STRESSES.all { s -> phonemes?.contains(s) != true }) {
        if (VOWELS.all { v -> phonemes?.contains(v) != true }) {
            return phonemes
        }
        return restress(SECONDARY_STRESS + phonemes.orEmpty())
    } else if (stress >= 1 && phonemes?.contains(PRIMARY_STRESS) != true && phonemes?.contains(
            SECONDARY_STRESS
        ) == true
    ) {
        return phonemes.replace(SECONDARY_STRESS, PRIMARY_STRESS)
    } else if (stress > 1 && STRESSES.all { s -> phonemes?.contains(s) != true }) {
        if (VOWELS.all { v -> phonemes?.contains(v) != true }) {
            return phonemes
        }
        return restress(PRIMARY_STRESS + phonemes.orEmpty())
    }
    return phonemes
}

fun isDigit(text: String): Boolean {
    return Regex("""^[0-9]+$""").matches(text)
}

class DictionaryValueSerializer : KSerializer<DictionaryValue> {
    @OptIn(InternalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        buildSerialDescriptor("DictionaryValue", SerialKind.CONTEXTUAL)

    val mapSerializer = MapSerializer(String.serializer(), String.serializer().nullable)

    override fun serialize(
        encoder: Encoder,
        value: DictionaryValue
    ) {
        when (value) {
            is DictionaryValue.StringValue -> encoder.encodeString(value.value)
            is DictionaryValue.MapValue -> encoder.encodeSerializableValue(
                mapSerializer,
                value.value
            )
        }
    }

    override fun deserialize(decoder: Decoder): DictionaryValue {
        return when (val jsonElement = (decoder as JsonDecoder).decodeJsonElement()) {
            is JsonPrimitive -> DictionaryValue.StringValue(jsonElement.content)
            is JsonObject -> DictionaryValue.MapValue(
                Json.decodeFromJsonElement(
                    mapSerializer,
                    jsonElement
                )
            )

            else -> throw IllegalArgumentException("Unexpected JSON type for DictionaryValue field")
        }
    }
}

@Serializable(with = DictionaryValueSerializer::class)
sealed class DictionaryValue {
    data class StringValue(val value: String) : DictionaryValue()
    data class MapValue(val value: Map<String, String?>) : DictionaryValue()
}

class Lexicon(val british: Boolean, initialDictionary: Map<String, DictionaryValue>) {
    fun growDictionary(dictionary: Map<String, DictionaryValue>): Map<String, DictionaryValue> {
        val e = mutableMapOf<String, DictionaryValue>()
        for ((k, v) in dictionary.entries) {
            if (k.length < 2) {
                continue
            }
            if (k == k.lowercase(Locale.ENGLISH)) {
                val capitalizedK =
                    k.replaceFirstChar { if (it.isLowerCase()) it.uppercase(Locale.ENGLISH) else it.toString() }
                if (k != capitalizedK) {
                    e[capitalizedK] = v
                }
            } else if (k == k.lowercase(Locale.ENGLISH)
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }
            ) {
                e[k.lowercase(Locale.ENGLISH)] = v
            }
        }
        return e + dictionary
    }

    val capStresses: List<Double> = listOf(0.5, 2.0)
    val golds: Map<String, DictionaryValue> = this.growDictionary(initialDictionary)

    init {
        val vocab = if (this.british) GB_VOCAB else US_VOCAB
        for (vs in this.golds.values) {
            when (vs) {
                is DictionaryValue.StringValue -> {
                    assert(vs.value.all { c -> c in vocab })
                }

                is DictionaryValue.MapValue -> {
                    assert("DEFAULT" in vs.value)
                    for (v in vs.value.values) {
                        assert((v == null) || v.all { c -> c in vocab })
                    }
                }
            }
        }
    }

    fun getPropn(word: String?): Pair<String?, Int?> {
        val phonemes = mutableListOf<DictionaryValue?>()
        if (word != null) {
            for (c in word) {
                if (c.isLetter()) {
                    phonemes.add(this.golds[c.uppercase(Locale.ENGLISH)])
                }
            }
        }
        if (null in phonemes) {
            return Pair(null, null)
        }
        val appliedStressPhonemes = applyStress(
            phonemes.mapNotNull {
                when (it) {
                    is DictionaryValue.MapValue -> it.value["DEFAULT"]
                    is DictionaryValue.StringValue -> it.value
                    null -> null
                }
            }.joinToString(""),
            0.0
        )
        val lastIndexOfSecondaryStress = appliedStressPhonemes?.lastIndexOf(SECONDARY_STRESS)
        val result = if (lastIndexOfSecondaryStress == -1 || lastIndexOfSecondaryStress == null) {
            appliedStressPhonemes
        } else {
            appliedStressPhonemes.take(lastIndexOfSecondaryStress) + PRIMARY_STRESS + appliedStressPhonemes.takeLast(
                appliedStressPhonemes.length - (lastIndexOfSecondaryStress + 1)
            )
        }
        return Pair(result, 3)
    }

    fun getSpecialCase(
        word: String,
        tag: String,
        stress: Double?,
        ctx: TokenContext
    ): Pair<String?, Int?> {
        if (tag == "PUNCT" && word in PUNCT_SYMBOLS) {
            return this.lookup(PUNCT_SYMBOLS[word], null, -0.5, ctx)
        } else if (word in SYMBOLS) {
            return this.lookup(SYMBOLS[word], null, null, ctx)
        } else if ('.' in word.trim('.') && word.replace(".", "")
                .let { it.isNotEmpty() && it.all { c -> c.isLetter() } } && (word.split('.')
                .maxByOrNull { it.length }?.length ?: 0) < 3
        ) {
            return this.getPropn(word)
        } else if (word in setOf("a", "A")) {
            return Pair(
                if (tag == "DET") {
                    "ɐ"
                } else {
                    "ˈA"
                },
                4
            )
        } else if (word in setOf("am", "Am", "AM")) {
            if (tag in setOf("PROPN", "NOUN")) {
                return this.getPropn(word)
            } else if (ctx.futureVowel == null || word != "am" || (stress != null && stress > 0)) {
                this.golds["am"]?.let { am ->
                    if (am is DictionaryValue.StringValue) {
                        return Pair(am.value, 4)
                    }
                }
            }
            return Pair("ɐm", 4)
        } else if (word in setOf("an", "An", "AN")) {
            if (word == "AN" && tag in setOf("PROPN", "NOUN")) {
                return this.getPropn(word)
            }
            return Pair("ɐn", 4)
        } else if (word == "I" && tag == "PRON") {
            return Pair("${SECONDARY_STRESS}I", 4)
        } else if (word in setOf("by", "By", "BY") && tag == "ADV") {
            return Pair("bˈI", 4)
        } else if (word in setOf("to", "To") || (word == "TO" && tag in setOf("ADP", "SCONJ"))) {
            this@Lexicon.golds["to"]?.let { to ->
                if (to is DictionaryValue.StringValue) {
                    val map = mutableMapOf(
                        null to to.value,
                        false to "tə",
                        true to "tʊ",
                    )
                    return Pair(map[ctx.futureVowel], 4)
                }
            }
        } else if (word in setOf("in", "In") || (word == "IN" && tag != "PROPN")) {
            val stress = if (ctx.futureVowel == null || tag !in setOf("ADP", "SCONJ")) {
                PRIMARY_STRESS.toString()
            } else {
                ""
            }
            return Pair(stress + "ɪn", 4)
        } else if (word in setOf("the", "The") || (word == "THE" && tag == "DET")) {
            return Pair(
                if (ctx.futureVowel == true) {
                    "ði"
                } else {
                    "ðə"
                },
                4
            )
        } else if (tag == "IN" && Regex("""(?i)vs\.?$""").matches(word)) {
            return this.lookup("versus", null, null, ctx)
        } else if (word in setOf("used", "Used", "USED")) {
            val used = this.golds["used"]
            if (used is DictionaryValue.MapValue) {
                if (tag in setOf("VERB", "ADJ") && ctx.futureTo == true) {
                    used.value["VBD"]?.let { vbd ->
                        return Pair(vbd, 4)
                    }
                }
                used.value["DEFAULT"]?.let { default ->
                    return Pair(default, 4)
                }
            }
        }
        return Pair(null, null)
    }

    fun isKnown(word: String?, tag: String?): Boolean {
        if (word in this.golds || word in SYMBOLS) {
            return true
        } else if (!word.let { !it.isNullOrEmpty() && it.all { c -> c.isLetter() } } || word?.all { c -> c.code in LEXICON_ORDS } ?: true) {
            return false // TODO: café
        } else if (word.length == 1) {
            return true
        } else if (word == word.uppercase(Locale.ENGLISH) && word.lowercase(Locale.ENGLISH) in this.golds) {
            return true
        }
        return word.drop(1).let { it == it.uppercase(Locale.ENGLISH) } // && word.length < 8
    }

    fun lookup(
        word: String?,
        tag: String?,
        stress: Double?,
        ctx: TokenContext?
    ): Pair<String?, Int?> {
        var isPropn: Boolean? = null
        var word = word
        var tag = tag
        if (word == word?.uppercase(Locale.ENGLISH) && word !in this.golds) {
            word = word?.lowercase(Locale.ENGLISH)
            isPropn = tag == "PROPN"
        }
        var (phonemes, rating: Int?) = Pair(this.golds[word], 4)
        var resultPhonemes: String? = if (phonemes is DictionaryValue.StringValue) {
            phonemes.value
        } else {
            null
        }
        if (phonemes is DictionaryValue.MapValue) {
            if (ctx != null && ctx.futureVowel == null && "None" in phonemes.value) {
                tag = "None"
            }
            resultPhonemes = phonemes.value[tag] ?: phonemes.value["DEFAULT"]
        }
        if (phonemes == null || (isPropn == true && (phonemes is DictionaryValue.StringValue && PRIMARY_STRESS !in phonemes.value))) {
            this.getPropn(word).let { propn ->
                resultPhonemes = propn.first
                rating = propn.second
            }
            if (resultPhonemes != null) {
                return Pair(resultPhonemes, rating)
            }
        }
        return Pair(applyStress(resultPhonemes, stress), rating)
    }

    fun s(stem: String?): String? {
        // https://en.wiktionary.org/wiki/-s
        if (stem.isNullOrEmpty()) {
            return null
        } else if (stem.takeLast(1) in "ptkfθ") {
            return stem + 's'
        } else if (stem.takeLast(1) in "szʃʒʧʤ") {
            return stem + if (this.british) {
                'ɪ'
            } else {
                'ᵻ'
            } + 'z'
        }
        return stem + 'z'
    }

    fun stemS(
        word: String?,
        tag: String?,
        stress: Double?,
        ctx: TokenContext?
    ): Pair<String?, Int?> {
        val stem = if (word == null || word.length < 3 || !word.endsWith('s')) {
            return Pair(null, null)
        } else if (!word.endsWith("ss") && this.isKnown(word.dropLast(1), tag)) {
            word.dropLast(1)
        } else if ((word.endsWith("'s") || (word.length > 4 && word.endsWith("es") || !word.endsWith(
                "ies"
            ))) && this.isKnown(word.dropLast(2), tag)
        ) {
            word.dropLast(2)
        } else if (word.length > 4 && word.endsWith("ies") && this.isKnown(
                word.dropLast(3) + 'y',
                tag
            )
        ) {
            word.dropLast(3) + 'y'
        } else {
            return Pair(null, null)
        }
        this.lookup(stem, tag, stress, ctx).let { (stem, rating) ->
            return Pair(this.s(stem), rating)
        }
    }

    fun ed(stem: String?): String? {
        // https://en.wiktionary.org/wiki/-ed
        if (stem.isNullOrEmpty()) {
            return null
        }
        val stemLastChar = stem.takeLast(1)
        if (stemLastChar in "pkfθʃsʧ") {
            return stem + 't'
        } else if (stemLastChar == "d") {
            return stem + if (this.british) {
                'ɪ'
            } else {
                'ᵻ'
            } + 'd'
        } else if (stemLastChar != "t") {
            return stem + 'd'
        } else if (this.british || stem.length < 2) {
            return stem + "ɪd"
        } else if (stem.getOrNull(stem.length - 2) in US_TAUS) {
            return stem.dropLast(1) + "ɾᵻd"
        }
        return stem + "ᵻd"
    }

    fun stemEd(
        word: String?,
        tag: String?,
        stress: Double?,
        ctx: TokenContext
    ): Pair<String?, Int?> {
        val stem = if (word == null || word.length < 4 || !word.endsWith('d')) {
            return Pair(null, null)
        } else if (!word.endsWith("dd") && this.isKnown(word.dropLast(1), tag)) {
            word.dropLast(1)
        } else if (word.length > 4 && word.endsWith("ed") && !word.endsWith("eed") && this.isKnown(
                word.dropLast(2),
                tag
            )
        ) {
            word.dropLast(2)
        } else {
            return Pair(null, null)
        }
        this.lookup(stem, tag, stress, ctx).let { (stem, rating) ->
            return Pair(this.ed(stem), rating)
        }
    }

    fun ing(stem: String?): String? {
        // https://en.wiktionary.org/wiki/-ing
//         if (this.british) {
//             // TODO: Fix this
//             val r = if (stem.endsWith("ring") && stem.takeLast(1) in "əː") {
//                 'ɹ'
//             } else {
//                 ""
//             }
//             return stem + r + "ɪŋ"
//         }
        if (stem.isNullOrEmpty()) {
            return null
        } else if (this.british) {
            if (stem.takeLast(1) in "əː") {
                return null
            }
        } else if (stem.length > 1 && stem[stem.length - 1] == 't' && stem[stem.length - 2] in US_TAUS) {
            return stem.dropLast(1) + "ɾɪŋ"
        }
        return stem + "ɪŋ"
    }

    fun stemIng(
        word: String?,
        tag: String?,
        stress: Double?,
        ctx: TokenContext
    ): Pair<String?, Int?> {
        if (word == null || word.length < 5 || !word.endsWith("ing")) {
            return Pair(null, null)
        }
        val stem = if (word.length > 5 && this.isKnown(word.dropLast(3), tag)) {
            word.dropLast(3)
        } else if (this.isKnown(word.dropLast(3) + "e", tag)) {
            word.dropLast(3) + "e"
        } else if (word.length > 5 && Regex("""([bcdgklmnprstvxz])\1ing$|cking$""").containsMatchIn(
                word
            ) && this.isKnown(word.dropLast(4), tag)
        ) {
            word.dropLast(4)
        } else {
            return Pair(null, null)
        }
        this.lookup(stem, tag, stress, ctx).let { (stem, rating) ->
            return Pair(this.ing(stem), rating)
        }
    }

    fun getWord(
        word: String,
        tag: String,
        stress: Double?,
        ctx: TokenContext
    ): Pair<String?, Int?> {
        var word = word
        var (phonemes, rating) = this.getSpecialCase(word, tag, stress, ctx)
        if (phonemes != null) {
            return Pair(phonemes, rating)
        }
        val wl = word.lowercase(Locale.ENGLISH)
        if (word.length > 1 && word.replace("'", "").let {
                it.isNotEmpty() && it.all { c -> c.isLetter() }
            } && word != word.lowercase(Locale.ENGLISH) && (
                    tag != "PROPN" || word.length > 7
                    ) && word !in this.golds && (
                    word == word.uppercase(Locale.ENGLISH) || word.drop(1) == word.drop(1)
                        .lowercase(Locale.ENGLISH)
                    ) && (
                    wl in this.golds || !stemS(wl, tag, stress, ctx).first.isNullOrEmpty()
                            || !stemEd(wl, tag, stress, ctx).first.isNullOrEmpty()
                            || !stemIng(wl, tag, stress, ctx).first.isNullOrEmpty()
                    )) {
            word = wl
        }
        if (this.isKnown(word, tag)) {
            return this.lookup(word, tag, stress, ctx)
        } else if (word.endsWith("s'") && this.isKnown(word.dropLast(2) + "'s", tag)) {
            return this.lookup(word.dropLast(2) + "'s", tag, stress, ctx)
        } else if (word.endsWith("'") && this.isKnown(word.dropLast(1), tag)) {
            return this.lookup(word.dropLast(1), tag, stress, ctx)
        }
        var s: String?
        this.stemS(word, tag, stress, ctx).let {
            s = it.first
            rating = it.second
        }
        if (s != null) {
            return Pair(s, rating)
        }
        var ed: String?
        this.stemEd(word, tag, stress, ctx).let {
            ed = it.first
            rating = it.second
        }
        if (ed != null) {
            return Pair(ed, rating)
        }
        var ing: String?
        this.stemIng(word, tag, stress ?: 0.5, ctx).let {
            ing = it.first
            rating = it.second
        }
        if (ing != null) {
            return Pair(ing, rating)
        }
        return Pair(null, null)
    }

    fun isCurrency(word: String): Boolean {
        if ('.' !in word) {
            return true
        } else if (word.count { c -> c == '.' } > 1) {
            return false
        }
        val cents = word.split('.')[1]
        return cents.length < 3 || (cents.isNotEmpty() && cents.all { c -> c == '0' })
    }

    fun getNumber(
        word: String,
        currency: String?,
        isHead: Boolean?,
        numFlags: String?
    ): Pair<String?, Int?> {
        verboseLog(TAG) { "getNumber parameters: word: $word, currency: $currency, isHead: $isHead, numFlags: $numFlags" }
        val suffix = Regex("""[a-z']+$""").find(word)?.value
        var word = if (!suffix.isNullOrEmpty()) {
            word.dropLast(suffix.length)
        } else {
            word
        }
        val result = mutableListOf<Pair<String?, Int?>>()
        if (word.startsWith('-')) {
            result.add(this.lookup("minus", null, null, null))
            word = word.drop(1)
        }
        fun extendNum(num: String, first: Boolean = true, escape: Boolean = false) {
            val splits = Regex("""[^a-z]+""").split(
                if (escape) {
                    num
                } else {
                    numToWords(num, locale = Locale.ENGLISH)
                }
            )
            for ((splitWordIndex, splitWord) in splits.withIndex()) {
                if (splitWord != "and" || numFlags?.contains('&') == true) {
                    if (first && splitWordIndex == 0 && splits.size > 1 && splitWord == "one" && numFlags?.contains(
                            'a'
                        ) == true
                    ) {
                        result.add(Pair("ə", 4))
                    } else {
                        result.add(
                            this.lookup(
                                splitWord, null, if (splitWord == "point") {
                                    -2.0
                                } else {
                                    null
                                }, null
                            )
                        )
                    }
                } else if (numFlags?.contains('n') == true && result.isNotEmpty()) {
                    result[result.size - 1] =
                        result[result.size - 1].let { Pair(it.first + "ən", it.second) }
                }
            }
        }
        if (isDigit(word) && suffix in ORDINALS) {
            extendNum(numToWords(word, NumToWordsRuleSet.Ordinal, Locale.ENGLISH), escape = true)
        } else if (result.isEmpty() && word.length == 4 && currency !in CURRENCIES && isDigit(word)) {
            extendNum(
                numToWords(word, NumToWordsRuleSet.NumberingYear, Locale.ENGLISH),
                escape = true
            )
        } else if ((isHead == null || !isHead) && '.' !in word) {
            val num = word.replace(",", "")
            if (num[0] == '0' || num.length > 3) {
                num.forEach { n -> extendNum(n.toString(), first = false) }
            } else if (num.length == 3 && !num.endsWith("00")) {
                extendNum(num[0].toString())
                if (num[1] == '0') {
                    result.add(this.lookup("O", null, -2.0, null))
                    extendNum(num[2].toString(), first = false)
                } else {
                    extendNum(num.drop(1), first = false)
                }
            } else {
                extendNum(num)
            }
        } else if (word.count { c -> c == '.' } > 1 || (isHead == null || !isHead)) {
            var first = true
            for (num in word.replace(",", "").split('.')) {
                if (num.isEmpty()) {
                    // pass
                } else if (num[0] == '0' || (num.length != 2 && num.drop(1)
                        .any { n -> n != '0' })
                ) {
                    num.forEach { n -> extendNum(n.toString(), first = false) }
                } else {
                    extendNum(num, first = first)
                }
                first = false
            }
        } else if (currency in CURRENCIES && this.isCurrency(word)) {
            var pairs =
                word.replace(",", "").split('.').zip(CURRENCIES[currency]!!).map { (num, unit) ->
                    Pair(
                        if (num.isNotEmpty()) {
                            num.toInt()
                        } else {
                            0
                        },
                        unit
                    )
                }
            if (pairs.size > 1) {
                if (pairs[1].first == 0) {
                    pairs = pairs.take(1)
                } else if (pairs[0].first == 0) {
                    pairs = pairs.drop(1)
                }
            }
            for ((index, pair) in pairs.withIndex()) {
                val (num, unit) = pair
                if (index > 0) {
                    result.add(this.lookup("and", null, null, null))
                }
                extendNum(num.toString(), first = index == 0)
                result.add(
                    if (num.absoluteValue != 1 && unit != "pence") {
                        this.stemS(unit + "s", null, null, null)
                    } else {
                        this.lookup(unit, null, null, null)
                    }
                )
            }
        } else {
            if (isDigit(word)) {
                word = numToWords(word, locale = Locale.ENGLISH)
            } else if ('.' !in word) {
                word = numToWords(
                    word.replace(",", ""),
                    if (suffix in ORDINALS) {
                        NumToWordsRuleSet.Ordinal
                    } else {
                        NumToWordsRuleSet.Cardinal
                    },
                    Locale.ENGLISH
                )
            } else {
                word = word.replace(",", "")
                word = if (word[0] == '.') {
                    "point " + word.drop(1)
                        .map { n -> numToWords(n.toString(), locale = Locale.ENGLISH) }
                        .joinToString(" ")
                } else {
                    numToWords(word, locale = Locale.ENGLISH)
                }
            }
            extendNum(word, escape = true)
        }
        if (result.isEmpty()) {
            // TODO:
            return Pair(null, null)
        }
        val finalResult = result.mapNotNull { it.first }.joinToString(" ")
        val rating = result.mapNotNull { it.second }.minOfOrNull { it }
        return when (suffix) {
            in setOf("s", "'s") -> {
                Pair(this.s(finalResult), rating)
            }

            in setOf("ed", "'d") -> {
                Pair(this.ed(finalResult), rating)
            }

            "ing" -> {
                Pair(this.ing(finalResult), rating)
            }

            else -> Pair(finalResult, rating)
        }
    }

    fun appendCurrency(phonemes: String?, currency: String?): String? {
        if (currency == null) {
            return phonemes
        }
        val currencyWordForms = CURRENCIES[currency]
        val currencyStemmed = if (!currencyWordForms.isNullOrEmpty()) {
            this.stemS(currencyWordForms[0] + 's', null, null, null).first
        } else {
            null
        }
        return if (!currencyStemmed.isNullOrEmpty()) {
            "$phonemes $currencyStemmed"
        } else {
            phonemes
        }
    }

    fun numericIfNeeded(char: Char): String {
        if (!char.isDigit()) {
            return char.toString()
        }
        val numericValue = Character.getNumericValue(char)
        return if (numericValue != -1 && numericValue != -2) {
            numericValue.toString()
        } else {
            char.toString()
        }
    }

    fun isNumber(word: String?, isHead: Boolean?): Boolean {
        var word = word
        if (word?.all { c -> !isDigit(c.toString()) } ?: true) {
            return false
        }
        val suffixes = ORDINALS + setOf("ing", "'d", "ed", "'s", "s")
        for (s in suffixes) {
            if (word?.endsWith(s) ?: false) {
                word = word.dropLast(s.length)
                break
            }
        }
        return word?.withIndex()?.all { (index, char) ->
            isDigit(char.toString()) || char in ",." || (isHead == true && index == 0 && char == '-')
        } ?: true
    }

    fun main(tk: MToken, ctx: TokenContext): Pair<String?, Int?> {
        var word = ((tk.more?.get("alias") as String?) ?: tk.text)
            .replace(Char(8216).toString(), "'")
            .replace(Char(8217).toString(), "'")
        word = Normalizer.normalize(word, Normalizer.Form.NFKC)
        word = word.flatMap { c -> this.numericIfNeeded(c).toList() }.joinToString("")
        verboseLog(TAG) { "Lexicon main word: $word" }
        val stress = if (word == word.lowercase(Locale.ENGLISH)) {
            null
        } else {
            this.capStresses[if (word == word.uppercase(Locale.ENGLISH)) {
                1
            } else {
                0
            }]
        }
        var (phonemes, rating) = this.getWord(word, tk.tag, stress, ctx)
        if (phonemes != null) {
            return Pair(
                applyStress(
                    this.appendCurrency(
                        phonemes,
                        tk.more?.get("currency") as String?
                    ),
                    tk.more?.get("stress") as Double?
                ),
                rating
            )
        } else if (this.isNumber(word, tk.more?.get("isHead") as Boolean?)) {
            this.getNumber(
                word,
                tk.more?.get("currency") as String?,
                tk.more?.get("isHead") as Boolean?,
                tk.more?.get("numFlags") as String?
            ).let {
                phonemes = it.first
                rating = it.second
            }
            return Pair(applyStress(phonemes, tk.more?.get("stress") as Double?), rating)
        } else if (!word.all { c -> c.code in LEXICON_ORDS }) {
            return Pair(null, null)
        }
        // unported Python code (commented out in the original so no need)
//        # if word != word.lower() and (word == word.upper() or word[1:] == word[1:].lower()):
//        #     ps, rating = self.get_word(word.lower(), tk.tag, stress, ctx)
//        #     if ps is not None:
//        #         return apply_stress(self.append_currency(ps, tk._.currency), tk._.stress), rating
        return Pair(null, null)
    }
}

class EnglishPhonemizer(
    val lexicon: Lexicon,
    val unk: String,
    val tokenizer: TokenizerME,
    val posTagger: POSTaggerME,
    val fallback: FallbackNetwork? = null,
) : AutoCloseable {
    sealed class FeatureValue {
        data class IntValue(val value: Int) : FeatureValue()
        data class DoubleValue(val value: Double) : FeatureValue()
        data class StringValue(val value: String) : FeatureValue()
    }

    fun preprocess(text: String): Triple<String, List<String>, Map<Int, FeatureValue>> {
        var result = ""
        val tokens = mutableListOf<String>()
        val features = mutableMapOf<Int, FeatureValue>()
        var lastEnd = 0
        val text = text.trimStart()
        for (m in LINK_REGEX.findAll(text)) {
            result += text.substring(lastEnd, m.range.first)
            tokens.addAll(text.substring(lastEnd, m.range.first).trim().split(Regex("""\\s+""")))
            val thirdGroupValue = m.groupValues[2]
            var feature: FeatureValue? = null
            if (isDigit(
                    if (thirdGroupValue.take(1) in setOf("-", "+")) {
                        thirdGroupValue[1].toString()
                    } else {
                        thirdGroupValue
                    }
                )
            ) {
                feature = FeatureValue.IntValue(thirdGroupValue.toInt())
            } else if (thirdGroupValue in setOf("0.5", "+0.5")) {
                feature = FeatureValue.DoubleValue(0.5)
            } else if (thirdGroupValue == "-0.5") {
                feature = FeatureValue.DoubleValue(-0.5)
            } else if (thirdGroupValue.length > 1 && thirdGroupValue[0] == '/' && thirdGroupValue.last() == '/') {
                feature = FeatureValue.StringValue(
                    thirdGroupValue[0] + thirdGroupValue.drop(1).trimEnd('/')
                )
            } else if (thirdGroupValue.length > 1 && thirdGroupValue[0] == '#' && thirdGroupValue.last() == '#') {
                feature = FeatureValue.StringValue(
                    thirdGroupValue[0] + thirdGroupValue.drop(1).trimEnd('#')
                )
            }
            if (feature != null) {
                features[tokens.size] = feature
            }
            result += m.groupValues[1]
            tokens.add(m.groupValues[1])
            lastEnd = m.range.last
        }
        if (lastEnd < text.length) {
            result += text.drop(lastEnd)
            tokens.addAll(text.drop(lastEnd).trim().split(Regex("""\\s+""")))
        }
        return Triple(result, tokens, features)
    }

    fun tokenize(
        text: String,
        tokens: List<String>,
        features: Map<Int, FeatureValue>
    ): List<MToken> {
        val mutableTokens = run {
            val spans = tokenizer.tokenizePos(text)
            val intermediaryTokens = Span.spansToStrings(spans, text)
            val tags = posTagger.tag(intermediaryTokens)
            intermediaryTokens.indices.map { index ->
                val currentTokenSpan = spans[index]
                val nextTokenStart = if (index < spans.lastIndex) {
                    spans[index + 1].start
                } else {
                    text.length
                }
                val whitespace = text.substring(currentTokenSpan.end, nextTokenStart)
                MToken(
                    text = intermediaryTokens[index],
                    tag = tags[index],
                    whitespace = whitespace,
                    more = mutableMapOf(
                        "isHead" to true,
                        "numFlags" to "",
                        "prespace" to false,
                    )
                )
            }
        }
        verboseLog(TAG) { "tokenize tokens text: ${mutableTokens.joinToString { token -> token.text }}" }
        if (features.isEmpty()) {
            return mutableTokens
        }
        // TODO: implement processing aligned tokens with features
        return mutableTokens
    }

    fun foldLeft(tokens: List<MToken>): List<MToken> {
        val result = mutableListOf<MToken>()
        for (tk in tokens) {
            val tk = if (result.isNotEmpty() && tk.more?.get("isHead") == false) {
                mergeTokens(listOf(result.removeLast(), tk), this.unk)
            } else {
                tk
            }
            result.add(tk)
        }
        return result
    }

    sealed class RetokenizeValue {
        data class MTokenValue(val value: MToken) : RetokenizeValue()
        data class MTokenListValue(val value: MutableList<MToken>) : RetokenizeValue()
    }

    suspend fun retokenize(tokens: List<MToken>): List<RetokenizeValue> {
        val words = mutableListOf<RetokenizeValue>()
        var currency: MToken? = null
        for ((index, token) in tokens.withIndex()) {
            currentCoroutineContext().ensureActive()
            val currentIterTks = if (token.more?.get("alias") == null && token.phonemes == null) {
                findSubtokens(token.text).map { t ->
                    token.copy(
                        text = t.value,
                        whitespace = "",
                        more = mutableMapOf(
                            "isHead" to true,
                            "numFlags" to token.more?.get("numFlags"),
                            "stress" to token.more?.get("stress"),
                            "prespace" to false,
                        )
                    )
                }.toMutableList()
            } else {
                mutableListOf(token)
            }
            if (currentIterTks.isNotEmpty()) {
                currentIterTks.last().whitespace = token.whitespace
            }
            for ((currentIterTkIndex, currentIterTk) in currentIterTks.withIndex()) {
                currentCoroutineContext().ensureActive()
                verboseLog(TAG) { "retokenize currentIterTk word and tag: ${currentIterTk.text}, ${currentIterTk.tag}" }
                if (currentIterTk.more?.get("alias") != null || currentIterTk.phonemes != null) {
                    // pass
                } else if (currentIterTk.tag in setOf(
                        "SYM",
                        "NUM"
                    ) && currentIterTk.text in CURRENCIES
                ) {
                    currency = currentIterTk
                    lexicon.lookup(CURRENCIES[currentIterTk.text]?.first(), null, null, null)
                        .let { (phonemes, rating) ->
                            currentIterTk.phonemes = phonemes
                            currentIterTk.more?.set("rating", rating)
                        }
                } else if (currentIterTk.tag == "PUNCT" && (currentIterTk.text.length == 1 && currentIterTk.text.first() in PUNCTS) && !currentIterTk.text.all { c -> c.lowercaseChar().code in 97..122 }) {
                    currentIterTk.phonemes =
                        currentIterTk.text.map { c -> PUNCTS_REPLACEMENTS[c] ?: c }.joinToString("")
                    currentIterTk.more?.set("rating", 4)
                } else if (currency != null) {
                    if (currentIterTk.tag != "NUM" /* "CD" */) {
                        currency = null
                    } else if (currentIterTkIndex + 1 == currentIterTks.size && (index + 1 == tokens.size || tokens[index + 1].tag != "NUM" /* "CD" */)) {
                        currentIterTk.more?.set("currency", currency.text)
                        currency.phonemes = ""
                        currency.more?.set("rating", 4)
                    }
                } else if (0 < currentIterTkIndex && currentIterTkIndex < (currentIterTks.size - 1) && currentIterTk.text == "2" && (currentIterTks[currentIterTkIndex - 1].text.takeLast(
                        1
                    ) + currentIterTks[currentIterTkIndex + 1].text.take(1)).let { it.isNotEmpty() && it.all { c -> c.isLetter() } }
                ) {
                    currentIterTk.more?.set("alias", "to")
                }
                if (currentIterTk.more?.get("alias") != null || currentIterTk.phonemes != null) {
                    words.add(RetokenizeValue.MTokenValue(currentIterTk))
                } else if (words.isNotEmpty() && words.last() is RetokenizeValue.MTokenListValue && (words.last() as RetokenizeValue.MTokenListValue).value.last().whitespace.isEmpty()) {
                    currentIterTk.more?.set("isHead", false)
                    (words.last() as RetokenizeValue.MTokenListValue).value.add(currentIterTk)
                } else {
                    words.add(
                        if (currentIterTk.whitespace.isNotEmpty()) {
                            RetokenizeValue.MTokenValue(currentIterTk)
                        } else {
                            RetokenizeValue.MTokenListValue(mutableListOf(currentIterTk))
                        }
                    )
                }
                verboseLog(TAG) { "retokenize words: $words" }
            }
        }
        return words.map { w ->
            if (w is RetokenizeValue.MTokenListValue && w.value.size == 1) {
                RetokenizeValue.MTokenValue(w.value[0])
            } else {
                w
            }
        }
    }

    fun tokenContext(ctx: TokenContext, phonemes: String?, token: MToken): TokenContext {
        var vowel = ctx.futureVowel
        if (!phonemes.isNullOrEmpty()) {
            vowel = phonemes
                .filter { c -> (c in VOWELS || c in CONSONANTS || c.toString() in NON_QUOTE_PUNCTS) }
                .mapNotNull { c ->
                    if (c.toString() in NON_QUOTE_PUNCTS) {
                        null
                    } else {
                        c in VOWELS
                    }
                }
                .firstOrNull() ?: vowel
        }
        val futureTo = token.text in setOf("to", "To") || (token.text == "TO" && token.tag in setOf(
            "TO",
            "IN"
        ))
        return TokenContext(futureVowel = vowel, futureTo = futureTo)
    }

    fun resolveTokens(tokens: List<MToken>) {
        val text = tokens.dropLast(1)
            .joinToString("") { tk -> tk.text + tk.whitespace } + tokens.last().text
        val prespace = ' ' in text || '/' in text || text.filter { c -> c !in SUBTOKEN_JUNKS }
            .map { c ->
                if (c.isLetter()) {
                    0
                } else {
                    if (isDigit(c.toString())) {
                        1
                    } else {
                        2
                    }
                }
            }.size > 1
        for ((index, tk) in tokens.withIndex()) {
            if (tk.phonemes == null) {
                if (index == tokens.lastIndex && tk.text in NON_QUOTE_PUNCTS) {
                    tk.phonemes = tk.text
                    tk.more?.set("rating", 3)
                } else if (tk.text.all { c -> c in SUBTOKEN_JUNKS }) {
                    tk.phonemes = ""
                    tk.more?.set("rating", 3)
                }
            } else if (index > 0) {
                tk.more?.set("prespace", prespace)
            }
        }
        if (prespace) {
            return
        }
        var indices =
            tokens.filter { tk -> !tk.phonemes.isNullOrEmpty() }.withIndex().map { (index, tk) ->
                Triple(
                    tk.phonemes?.contains(PRIMARY_STRESS) == true,
                    stressWeight(tk.phonemes),
                    index
                )
            }
        if (indices.size == 2 && tokens[indices[0].third].text.length == 1) {
            val index = indices[1].third
            tokens[index].phonemes = applyStress(tokens[index].phonemes, -0.5)
            return
        } else if (indices.size < 2 || indices.sumOf { (bool, _, _) ->
                if (bool) {
                    1
                } else {
                    0
                }
            } <= (indices.size + 1).floorDiv(2)) {
            return
        }
        indices = indices.sortedWith(
            compareBy<Triple<Boolean, Int, Int>> { it.first }
                .thenBy { it.second }
                .thenBy { it.third }
        ).dropLast(indices.size.floorDiv(2))
        for ((_, _, index) in indices) {
            tokens[index].phonemes = applyStress(tokens[index].phonemes, -0.5)
        }
    }

    suspend fun tokenFallback(tk: MToken): Pair<String?, Int?> {
        verboseLog(TAG) { "tokenFallback tk: $tk" }
        return if (tk.text in PUNCT_SYMBOLS) {
            lexicon.lookup(PUNCT_SYMBOLS[tk.text], null, -0.5, null)
        } else if (lexicon.isNumber(tk.text, tk.more?.get("isHead") == true)) {
            lexicon.getNumber(
                tk.text,
                tk.more?.get("currency") as String?,
                tk.more?.get("isHead") as Boolean?,
                tk.more?.get("numFlags") as String?,
            )
        } else {
            (if (this.fallback != null && tk.text.length != 1 && !(tk.tag == "PROPN" && tk.text.let { it.isNotEmpty() && it.all { c -> c.isUpperCase() } })) {
                this.fallback.main(tk)
            } else {
                if (tk.text.all { c -> c.isLetter() }) {
                    lexicon.getPropn(tk.text)
                } else {
                    val result = tk.text.fold(
                        Pair(
                            mutableListOf<String>(),
                            mutableListOf<MToken>()
                        )
                    ) { acc, ch ->
                        UCharacter.getName(ch.code)
                            // Unicode names are all uppercase, so convert to
                            // lowercase so the words are found in the dictionary
                            ?.lowercase(Locale.ENGLISH)
                            ?.let { this.main(it) }
                            .let { subResult ->
                                val subResult = subResult ?: Pair(unk, null)
                                acc.first.add(subResult.first)
                                subResult.second?.let { acc.second.addAll(it) }
                            }
                        acc
                    }
                    Pair(
                        result.first.joinToString(", "),
                        if (result.second.isNotEmpty()) {
                            mergeTokens(result.second)
                                .more?.get("rating") as Int?
                        } else {
                            null
                        }
                    )
                }
            })
        }
    }

    suspend fun main(text: String, preprocess: Boolean = true): Pair<String, List<MToken>> {
        val (text, tokens, features) = if (preprocess) {
            this.preprocess(text)
        } else {
            Triple(text, emptyList(), emptyMap())
        }
        val tokenized = this.tokenize(text, tokens, features)
        val foldLefted = this.foldLeft(tokenized)
        // Currently used POS tagger does not classify the "a" in ["a"] and ["capital", "a"] as DET,
        // which means it gets pronounced instead of spelled out. Manually override those two cases.
        if ((foldLefted.size == 1 && foldLefted[0].text == "a") || (foldLefted.size == 2 && foldLefted.joinToString(
                " "
            ) { it.text } == "capital A")
        ) {
            foldLefted.last().phonemes = "ˈA"
        }
        val retokenized = this.retokenize(foldLefted)
        var ctx = TokenContext()
        for (word in retokenized.reversed()) {
            currentCoroutineContext().ensureActive()
            when (word) {
                is RetokenizeValue.MTokenListValue -> {
                    var (left, right) = Pair(0, word.value.size)
                    var shouldFallback = false
                    while (left < right) {
                        val leftRightSublist = word.value.subList(left, right)
                        var tk =
                            if (leftRightSublist.any { tk -> tk.more?.get("alias") != null || tk.phonemes != null }) {
                                null
                            } else {
                                mergeTokens(leftRightSublist)
                            }
                        val (phonemes, rating) = if (tk == null) {
                            Pair(null, null)
                        } else {
                            this.lexicon.main(tk, ctx)
                        }
                        if (phonemes != null && tk != null) {
                            word.value[left].phonemes = phonemes
                            word.value[left].more?.set("rating", rating)
                            for (x in word.value.subList(left + 1, right)) {
                                x.phonemes = ""
                                x.more?.set("rating", rating)
                            }
                            ctx = this.tokenContext(ctx, phonemes, tk)
                            right = left
                            left = 0
                        } else if (left + 1 < right) {
                            left += 1
                        } else {
                            right -= 1
                            tk = word.value[right]
                            if (tk.phonemes == null) {
                                if (tk.text.all { c -> c in SUBTOKEN_JUNKS }) {
                                    tk.phonemes = ""
                                    tk.more?.set("rating", 3)
                                } else {
                                    shouldFallback = true
                                    break
                                }
                            }
                            left = 0
                        }
                    }
                    if (shouldFallback) {
                        verboseLog(TAG) { "fallback tokens: ${word.value}"}
                        for (tk in word.value) {
                            (this.tokenFallback(tk)).let { (phonemes, rating) ->
                                tk.phonemes = phonemes
                                tk.more?.set("rating", rating)
                            }
                        }
                    } else {
                        verboseLog(TAG) { "resolving tokens: ${word.value}" }
                        this.resolveTokens(word.value)
                    }
                }

                is RetokenizeValue.MTokenValue -> {
                    if (word.value.phonemes == null) {
                        this.lexicon.main(word.value.copy(more = word.value.more), ctx)
                            .let { (phonemes, rating) ->
                                word.value.phonemes = phonemes
                                word.value.more?.set("rating", rating)
                            }
                    }
                    if (word.value.phonemes == null) {
                        this.tokenFallback(word.value).let { (phonemes, rating) ->
                            word.value.phonemes = phonemes
                            word.value.more?.set("rating", rating)
                        }
                    }
                    ctx = this.tokenContext(ctx, word.value.phonemes, word.value)
                }
            }
        }
        val mergedMTokens = retokenized.map { tk ->
            when (tk) {
                is RetokenizeValue.MTokenListValue -> mergeTokens(tk.value, unk = this.unk)
                is RetokenizeValue.MTokenValue -> tk.value
            }
        }
        for (tk in mergedMTokens) {
            if (!tk.phonemes.isNullOrEmpty()) {
                tk.phonemes = tk.phonemes?.replace('ɾ', 'T')?.replace('ʔ', 't')
            }
        }
        val result = mergedMTokens.joinToString("") { tk ->
            if (tk.phonemes == null) {
                this.unk
            } else {
                tk.phonemes
            } + tk.whitespace
        }
        return Pair(result, mergedMTokens)
    }

    override fun close() {
        fallback?.close()
    }
}
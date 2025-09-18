package app.grapheneos.speechservices.tts

object Symbols {
    const val PAD = "_"
    const val PUNCTUATION = ";:,.!?¡¿—…\"«»“” "
    const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    const val LETTERS_IPA =
        "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'̩'ᵻ"
    const val LETTERS_IPA_NONSTANDARD = "ᵊ"

    val symbols: List<String> = buildList {
        add(PAD)
        PUNCTUATION.forEach { add(it.toString()) }
        LETTERS.forEach { add(it.toString()) }
        LETTERS_IPA.forEach { add(it.toString()) }
        LETTERS_IPA_NONSTANDARD.forEach { add(it.toString()) }
    }

    val index: Map<String, Int> = symbols.withIndex().associate { it.value to it.index }

    val SPACE_ID: Int = index[" "] ?: error("Space not found in symbols")
}

package app.grapheneos.speechservices.g2p.fallback_network

data class G2PTokenizerConfig(
    val graphemeChars: String,
    val phonemeChars: String,
)

class G2PTokenizer(config: G2PTokenizerConfig) {
    private val pad = Pair(0, "<pad>")
    private val bos = Pair(1, "<s>")
    private val eos = Pair(2, "</s>")
    private val unk = Pair(3, "<unk>")

    private val special = listOf(pad, bos, eos, unk)

    private val graphemeList =
        special.map { it.second } + config.graphemeChars.trimStart('_').map { it.toString() }
    private val phonemeList =
        special.map { it.second } + config.phonemeChars.trimStart('_').map { it.toString() }

    private val tokenToId = HashMap<String, Int>().apply {
        graphemeList.forEachIndexed { index, token -> put(token, index) }
        phonemeList.forEachIndexed { index, token -> put(token, index) }
    }

    private val idToPhoneme = HashMap<Int, String>().apply {
        phonemeList.forEachIndexed { index, token -> put(index, token) }
    }

    /**
     * Encode a single word into grapheme token IDs: [bos] + characters + [eos]
     *
     * Encodes unknown characters as [unk].
     */
    fun encodeWord(word: String): LongArray {
        val ids = ArrayList<Int>(word.length + 2)
        ids.add(bos.first)
        for (char in word) {
            ids.add(tokenToId[char.toString()] ?: unk.first)
        }
        ids.add(eos.first)
        return ids.map { it.toLong() }.toLongArray()
    }

    /**
     * Decode an array of symbol IDs (Long) to a string of phonemes and punctuation.
     *
     * Throws [IllegalArgumentException] on unknown IDs.
     */
    fun decodePhonemes(ids: LongArray): String {
        val stringBuilder = StringBuilder()
        for (id in ids) {
            // skip special tokens
            if (special.any { it.first == id.toInt() }) {
                continue
            }
            stringBuilder.append(
                idToPhoneme[id.toInt()] ?: error("ID ${id.toInt()} not found in idToPhoneme!")
            )
        }
        return stringBuilder.toString()
    }
}

package app.grapheneos.speechservices.tts

import android.util.Log
import app.grapheneos.speechservices.verboseLog

private const val TAG: String = "SymbolTokenizer"

class SymbolTokenizer {
    /**
     * Encode a string of phoneme and punctuation to Matcha symbol IDs (Long) including padding.
     *
     * Skips unknown characters.
     */
    fun encodeToIds(phonemeText: String): LongArray {
        val ids = ArrayList<Long>(phonemeText.length)
        val padId = Symbols.index[Symbols.PAD]!!.toLong()
        for (char in phonemeText) {
            val id = Symbols.index[char.toString()]
            if (id == null) {
                Log.w(TAG, "Encountered unknown character, skipping!")
                verboseLog(TAG) { "Unknown character: $char" }
            } else {
                ids.add(padId)
                ids.add(id.toLong())
            }
        }
        ids.add(padId)
        return ids.toLongArray()
    }
}
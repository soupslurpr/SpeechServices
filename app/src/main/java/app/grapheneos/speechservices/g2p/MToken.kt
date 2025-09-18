/**
 * Port of Misaki token.py: https://github.com/hexgrad/misaki
 */

package app.grapheneos.speechservices.g2p

data class MToken(
    val text: String,
    var tag: String,
    var whitespace: String,
    var phonemes: String? = null,
    val startTs: Float? = null,
    val endTs: Float? = null,
    // originally named _
    val more: MutableMap<String, Any?>? = null,
)

package app.grapheneos.speechservices.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetFileDescriptor
import java.nio.channels.FileChannel

/**
 * Converts input IDs into encoder output, to be decoded into audio by the [Decoder].
 *
 * The benefit of this approach is that the encoder output can be computed fast, and then it can
 * gradually be decoded into audio. This allows for fast time-to-first-audio while maintaining
 * sentence context in the generated speech.
 */
class Encoder(modelFileDescriptor: AssetFileDescriptor) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val sessionOptions = OrtSession.SessionOptions()

        modelFileDescriptor.createInputStream().use { inputStream ->
            val fileChannel = inputStream.channel
            val startOffset = modelFileDescriptor.startOffset
            val declaredLength = modelFileDescriptor.declaredLength

            session = env.createSession(
                fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    startOffset,
                    declaredLength,
                ),
                sessionOptions,
            )
        }
    }

    /**
     * @param x [Array] (length = batch size) of [LongArray] where each item in the first dimension
     * represents the input IDs of the item with the same first dimensional index in return value
     * `muY`.
     * @param xLengths [LongArray] (length = batch size) where each item represents the length of
     * the item with the same first dimensional index in [x].
     * @param lengthScale [FloatArray] (length = 1) which should only contain 1 item that represents
     * the scale to use for the encoded speech speed (value of each item for the return value
     * `yLengths` and what they represent gets scaled according to this). Using a value of 1.0F is
     * recommended. Higher or lower values may produce lower quality results than post-processing
     * the decoded return result would.
     * @param spks [LongArray] (length = batch size) where each item represents the speaker ID of
     * the item with the same first dimensional index in return value `muY`.
     *
     * @return [OrtSession.Result] where each index represents a value from the return result.
     * Return result index to value list:
     * 1. `yLengths` - [LongArray] (length = batch size) where each item represents the non-padded
     * length of the items in the second dimension of the return value `muY` with the same first
     * dimensional index in return value `muY`.
     * 2. `muY` - Actual encoder output. [Array] (length = batch size) of [Array] (length =
     * features) of [FloatArray] (length = length of the longest item in the second dimension).
     * 3. `yMask` - Mask for encoder output. Tells the decoder which part of the encoded data to use
     * for each part of the output. [Array] (length = batch size) of [Array] (length = 1) of
     * [FloatArray] (length = same as return value `muY`'s third dimension) where each item in the
     * third dimension represents which items in the third dimension of the return value `muY` with
     * the same first dimensional index in return value `muY` to use for the part of the decoder
     * output represented by the index of that item.
     */
    fun run(
        x: OnnxTensor,
        xLengths: OnnxTensor,
        lengthScale: OnnxTensor,
        spks: OnnxTensor? = null
    ): OrtSession.Result {
        val inputs = HashMap<String, OnnxTensor>()
        inputs["x"] = x
        inputs["x_lengths"] = xLengths
        inputs["length_scale"] = lengthScale
        if (spks != null) {
            inputs["spks"] = spks
        }

        return session.run(inputs)
    }

    override fun close() {
        session.close()
    }
}
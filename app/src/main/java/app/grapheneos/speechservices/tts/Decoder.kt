package app.grapheneos.speechservices.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensor.createTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetFileDescriptor
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * Converts encoder output into audio.
 *
 * @see Encoder
 */
class Decoder(modelFileDescriptor: AssetFileDescriptor) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val temperature: OnnxTensor

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

        temperature = createTensor(env, FloatBuffer.wrap(floatArrayOf(0.667F)), longArrayOf(1))
    }

    /**
     * @param muY Actual encoder output. See [Encoder.run].
     * @param yMask Mask for encoder output. See [Encoder.run].
     * @param nTimesteps [LongArray] (length = 1) which should only contain 1 item that represents
     * how many synthesis steps the decoder will take. A value of 5 seems to be a good balance of
     * speed and quality.
     * @param spks See [Encoder.run].
     *
     * @return [OrtSession.Result] where each index represents a value from the return result.
     * Return result index to value list:
     * 1. `pcmFloatWav` - [Array] (length = batch size) of [FloatArray] (length = length of the
     * longest item in the first dimension) where each item in the first dimension is audio encoded
     * in PCM Float (-1.0 to 1.0 range for each subitem). Non-padded length of each item in the
     * first dimension corresponds to [Encoder.run] return value `yLengths` multiplied by the hop
     * length of the mel-spectogram.
     */
    fun run(
        muY: OnnxTensor,
        yMask: OnnxTensor,
        nTimesteps: OnnxTensor,
        spks: OnnxTensor? = null
    ): OrtSession.Result {
        val inputs = HashMap<String, OnnxTensor>()
        inputs["mu_y"] = muY
        inputs["y_mask"] = yMask
        inputs["n_timesteps"] = nTimesteps
        inputs["temperature"] = temperature
        if (spks != null) {
            inputs["spks"] = spks
        }

        return session.run(inputs)
    }

    override fun close() {
        temperature.close()
        session.close()
    }
}
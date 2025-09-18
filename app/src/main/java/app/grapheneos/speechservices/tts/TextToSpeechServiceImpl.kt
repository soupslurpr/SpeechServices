package app.grapheneos.speechservices.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.media.AudioFormat
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import app.grapheneos.speechservices.R
import app.grapheneos.speechservices.g2p.DictionaryValue
import app.grapheneos.speechservices.g2p.EnglishPhonemizer
import app.grapheneos.speechservices.g2p.Lexicon
import app.grapheneos.speechservices.g2p.fallback_network.FallbackNetwork
import app.grapheneos.speechservices.g2p.fallback_network.G2PTokenizer
import app.grapheneos.speechservices.g2p.fallback_network.G2PTokenizerConfig
import app.grapheneos.speechservices.verboseLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import opennlp.tools.postag.POSModel
import opennlp.tools.postag.POSTaggerME
import opennlp.tools.tokenize.TokenizerME
import opennlp.tools.tokenize.TokenizerModel
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

private const val TAG = "TextToSpeechServiceImpl"

private enum class DefaultVoice(val voice: Voice) {
    EnUs(
        Voice(
            "en_US",
            Locale.US,
            Voice.QUALITY_VERY_HIGH,
            Voice.LATENCY_NORMAL,
            false,
            emptySet<String>()
        )
    )
}

val supportedVoices = DefaultVoice.entries.map { it.voice } + listOf()

val availableVoices = supportedVoices

fun getAvailableVoiceByName(voiceName: String?): Voice? {
    return availableVoices.find { availableVoice -> availableVoice.name == voiceName }
}

fun isVoiceAvailable(voice: Voice?): Boolean {
    return availableVoices.find { availableVoice -> availableVoice == voice } != null
}

fun isVoiceAvailable(voiceName: String?): Boolean {
    return getAvailableVoiceByName(voiceName) != null
}

fun isLanguageAvailableWithDefaultVoiceName(
    lang: String?,
    country: String?,
    variant: String?
): Pair<Int, String?> {
    verboseLog(TAG) { "isLanguageAvailableWithDefaultVoiceName parameters: lang: $lang, country: $country, variant: $variant" }

    val modernizedLocale = deprecatedLocaleToModern(lang, country, variant)
    val lang = modernizedLocale.language
    val country = modernizedLocale.country
    val variant = modernizedLocale.variant

    verboseLog(TAG) { "isLanguageAvailableWithDefaultVoiceName converted parameters: lang: $lang, country: $country, variant: $variant" }

    val best =
        DefaultVoice.entries.map { Pair<Int?, Voice?>(null, it.voice) }.reduce { best, candidate ->
            val bestVoice = best.second
            val candidateVoice = best.second
            val bestLocale = bestVoice?.locale
            val candidateLocale = candidateVoice?.locale

            val isBestVoiceAvailable =
                bestVoice != null && bestLocale != null && isVoiceAvailable(bestVoice)
            val isCandidateVoiceAvailable =
                candidateVoice != null && candidateLocale != null && isVoiceAvailable(candidateVoice)

            if (isBestVoiceAvailable && bestLocale.language == lang && bestLocale.country == country && bestLocale.variant == variant) {
                Pair(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE, bestVoice)
            } else if (isCandidateVoiceAvailable && candidateLocale.language == lang && candidateLocale.country == country && candidateLocale.variant == variant) {
                Pair(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE, candidateVoice)
            } else if (isBestVoiceAvailable && bestLocale.language == lang && bestLocale.country == country) {
                Pair(TextToSpeech.LANG_COUNTRY_AVAILABLE, best.second)
            } else if (isCandidateVoiceAvailable && candidateLocale.language == lang && candidateLocale.country == country) {
                Pair(TextToSpeech.LANG_COUNTRY_AVAILABLE, candidate.second)
            } else if (bestLocale != null && bestLocale.language == lang) {
                if (isBestVoiceAvailable) {
                    Pair(TextToSpeech.LANG_AVAILABLE, best.second)
                } else {
                    Pair(TextToSpeech.LANG_MISSING_DATA, null)
                }
            } else if (candidateLocale != null && candidateLocale.language == lang) {
                if (isCandidateVoiceAvailable) {
                    Pair(TextToSpeech.LANG_AVAILABLE, candidate.second)
                } else {
                    Pair(TextToSpeech.LANG_MISSING_DATA, null)
                }
            } else {
                Pair(TextToSpeech.LANG_NOT_SUPPORTED, null)
            }
        }

    return Pair(best.first ?: TextToSpeech.LANG_NOT_SUPPORTED, best.second?.name)
}

class TextToSpeechServiceImpl : TextToSpeechService() {

    private val loadVoiceMutex = Mutex()

    private var loadVoiceJob: Job? = null

    private var synthesizeTextJob: Job? = null

    private lateinit var encoder: Encoder

    private lateinit var decoder: Decoder

    private val symbolTokenizer: SymbolTokenizer by lazy { SymbolTokenizer() }

    private lateinit var englishPhonemizer: EnglishPhonemizer

    private fun loadG2PTokenizerConfig(): G2PTokenizerConfig {
        resources.openRawResource(R.raw.g2p_config).bufferedReader().use { bufferedReader ->
            val config = JSONObject(bufferedReader.readText())
            return G2PTokenizerConfig(
                config.getString("grapheme_chars"),
                config.getString("phoneme_chars")
            )
        }
    }

    private val sonicAudioProcessor: SonicAudioProcessor by lazy { SonicAudioProcessor() }

    override fun onGetVoices(): List<Voice> {
        return supportedVoices
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        return if (isVoiceAvailable(voiceName)) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }
    }

    private suspend fun loadVoice(voiceName: String?) {
        Log.d(TAG, "loadVoice parameters: voiceName: $voiceName")

        if (!isVoiceAvailable(voiceName)) {
            return
        }

        lateinit var currentJob: Job

        loadVoiceMutex.withLock {
            val prevJob = loadVoiceJob
            if (prevJob != null && !prevJob.isCompleted) {
                verboseLog(TAG) { "cancelling previous loadVoiceJob" }
                prevJob.cancelAndJoin()
                verboseLog(TAG) { "cancelled previous loadVoiceJob" }
                loadVoiceJob = null
            }

            currentJob = CoroutineScope(Dispatchers.IO).launch {
                ensureActive()
                val totalLoadingTime = SystemClock.elapsedRealtime()
                if (!::encoder.isInitialized) {
                    val encoderTime = SystemClock.elapsedRealtime()
                    encoder = resources.openRawResourceFd(R.raw.encoder).use {
                        Encoder(it)
                    }
                    verboseLog(TAG) {
                        "encoder loading time: ${SystemClock.elapsedRealtime() - encoderTime}"
                    }
                }
                ensureActive()
                if (!::decoder.isInitialized) {
                    val decoderTime = SystemClock.elapsedRealtime()
                    decoder = resources.openRawResourceFd(R.raw.decoder).use {
                        Decoder(it)
                    }
                    verboseLog(TAG) {
                        "decoder loading time: ${SystemClock.elapsedRealtime() - decoderTime}"
                    }
                }
                ensureActive()
                if (!::englishPhonemizer.isInitialized) {
                    val lexiconTime = SystemClock.elapsedRealtime()
                    val lexicon = Lexicon(
                        false,
                        resources.openRawResource(R.raw.us_gold).use { inputStream ->
                            @OptIn(ExperimentalSerializationApi::class)
                            val decoded =
                                Json.decodeFromStream<Map<String, DictionaryValue>>(inputStream)
                            return@use decoded
                        }
                    )
                    verboseLog(TAG) {
                        "lexicon loading time: ${SystemClock.elapsedRealtime() - lexiconTime}"
                    }
                    val fallbackNetworkTime = SystemClock.elapsedRealtime()
                    val config = loadG2PTokenizerConfig()
                    val g2PTokenizer = G2PTokenizer(config)
                    verboseLog(TAG) {
                        "fallbackNetwork tokenizer loading time: ${SystemClock.elapsedRealtime() - fallbackNetworkTime}"
                    }
                    val fallbackNetwork =
                        resources.openRawResourceFd(R.raw.smaller_and_only_encoder_four_layer_g2p)
                            .use {
                                FallbackNetwork(it, g2PTokenizer)
                            }
                    verboseLog(TAG) {
                        "fallbackNetwork total loading time: ${SystemClock.elapsedRealtime() - fallbackNetworkTime}"
                    }
                    val englishPhonemizerTime = SystemClock.elapsedRealtime()
                    englishPhonemizer = EnglishPhonemizer(
                        lexicon,
                        "ˌʌnnˈOn", // "unknown"
                        resources.openRawResource(R.raw.opennlp_en_ud_ewt_tokens__1_3__2_5_4).use {
                            TokenizerME(TokenizerModel(it))
                        },
                        resources.openRawResource(R.raw.opennlp_en_ud_ewt_pos__1_3__2_5_4).use {
                            POSTaggerME(POSModel(it))
                        },
                        fallbackNetwork
                    )
                    verboseLog(TAG) {
                        "englishPhonemizer loading time: ${SystemClock.elapsedRealtime() - englishPhonemizerTime}"
                    }
                }
                Log.d(
                    TAG,
                    "total voice loading time: ${SystemClock.elapsedRealtime() - totalLoadingTime}"
                )
            }
            loadVoiceJob = currentJob
        }

        currentJob.join()
    }

    override fun onLoadVoice(voiceName: String?): Int {
        // This makes sense because onLoadVoice is called only on the synthesis thread, so synthesis
        // won't be interfered with.
        runBlocking {
            loadVoice(voiceName)
        }
        return TextToSpeech.SUCCESS
    }

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?
    ): String? {
        verboseLog(TAG) {
            "onGetDefaultVoiceNameFor parameters: lang: $lang, country: $country, variant: $variant"
        }

        return isLanguageAvailableWithDefaultVoiceName(lang, country, variant).second
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        verboseLog(TAG) {
            "onIsLanguageAvailable parameters: lang: $lang, country: $country, variant: $variant"
        }

        return isLanguageAvailableWithDefaultVoiceName(lang, country, variant).first
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        Log.d(TAG, "onLoadLanguage parameters: lang: $lang, country: $country, variant: $variant")

        val (languageAvailability, defaultVoiceName) = isLanguageAvailableWithDefaultVoiceName(
            lang,
            country,
            variant
        )

        when (languageAvailability) {
            TextToSpeech.LANG_NOT_SUPPORTED, TextToSpeech.LANG_MISSING_DATA -> {
                return languageAvailability
            }
        }

        // TODO: Rethink this part, it results in multiple requests building up. Probably make a job
        //  specific to onLoadLanguage to ensure only 1 instance at a time, and still wait until
        //  synthesis is finished before calling loadVoice().
        // Load the voice in the background.
        CoroutineScope(Dispatchers.IO).launch {
            // If synthesizing, wait until it's finished to avoid cancelling synthesis.
            synthesizeTextJob?.join()
            loadVoice(defaultVoiceName)
        }

        return languageAvailability
    }

    override fun onGetLanguage(): Array<out String?> {
        Log.w(
            TAG, "onGetLanguage called, returning emptyArray(). Method is not supposed " +
                    "to be called on modern Android versions (API > 17)."
        )
        return emptyArray()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        verboseLog(TAG) {
            "onSynthesizeText parameters: charSequenceText: ${request?.charSequenceText}, " +
                    "voiceName: ${request?.voiceName}, language: ${request?.language}, " +
                    "country: ${request?.country}, variant: ${request?.variant}, " +
                    "speechRate: ${request?.speechRate}, pitch: ${request?.pitch}, " +
                    "params?.keySet(): ${request?.params?.keySet()}, " +
                    "callerUid: ${request?.callerUid}, " +
                    "callback?.maxBufferSize: ${callback?.maxBufferSize}"
        }

        lateinit var currentJob: Job

        synchronized(this) {
            val prevJob = synthesizeTextJob
            if (prevJob?.isCompleted == false) {
                verboseLog(TAG) { "cancelling previous synthesizeTextJob" }
                runBlocking {
                    prevJob.cancelAndJoin()
                }
                verboseLog(TAG) { "cancelled previous synthesizeTextJob" }
                synthesizeTextJob = null
            }

            currentJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (request == null || callback == null) {
                        Log.d(TAG, "request or callback was null")
                        callback?.error(TextToSpeech.ERROR_INVALID_REQUEST)
                        return@launch
                    }

                    var timeToFirstAudio: Long? = null
                    val startTime = SystemClock.elapsedRealtime()

                    val requestVoice =
                        getAvailableVoiceByName(request.voiceName) ?: getAvailableVoiceByName(
                            isLanguageAvailableWithDefaultVoiceName(
                                request.language,
                                request.country,
                                request.variant
                            ).second
                        )
                    if (requestVoice != null) {
                        loadVoice(requestVoice.name)

                        callback.start(
                            22050,
                            AudioFormat.ENCODING_PCM_16BIT,
                            1
                        )

                        val env = OrtEnvironment.getEnvironment()

                        // TODO: support rangeStart()

                        val lengthScale = OnnxTensor.createTensor(
                            env,
                            FloatBuffer.wrap(floatArrayOf(1.0F)),
                            longArrayOf(1)
                        )

                        val currentQueue = StringBuilder()
                        // Score based on some characters likely being longer in phonemes, meant to
                        // ensure fast time-to-first-audio.
                        var currentQueueScore = 0
                        val bestQueueBreakOnlyThreshold = 250
                        val midAndUpQueueBreakOnlyThreshold = 350
                        val mehAndUpQueueBreakOnlyThreshold = 450
                        val worstAndUpQueueBreakOnlyThreshold = 500

                        for ((index, char) in request.charSequenceText.withIndex()) {
                            val isLastIndex = index == request.charSequenceText.lastIndex
                            currentQueue.append(char)
                            currentQueueScore += if (char.isDigit()) {
                                4
                            } else if (char == '-') {
                                11
                            } else {
                                1
                            }
                            val breakQueue = run {
                                if (isLastIndex || currentQueue.endsWith(". ") || currentQueue.endsWith(
                                        "? "
                                    ) || currentQueue.endsWith("! ") || currentQueue.endsWith(
                                        '\n'
                                    )
                                ) {
                                    return@run true
                                }
                                if (currentQueueScore >= bestQueueBreakOnlyThreshold) {
                                    if (currentQueue.endsWith(": ") || currentQueue.endsWith(" ;") || currentQueue.endsWith(
                                            '—'
                                        ) || currentQueue.endsWith(" ...") || currentQueue.endsWith(
                                            "... "
                                        ) || currentQueue.endsWith(" …") || currentQueue.endsWith(
                                            "… "
                                        ) || currentQueue.endsWith(
                                            """ """"
                                        ) || currentQueue.endsWith("""" """) || currentQueue.endsWith(
                                            " “"
                                        ) || currentQueue.endsWith("” ") || currentQueue.endsWith(
                                            " ("
                                        ) || currentQueue.endsWith(
                                            ") "
                                        )
                                    ) {
                                        return@run true
                                    }
                                }
                                if (currentQueueScore >= midAndUpQueueBreakOnlyThreshold) {
                                    if (currentQueue.endsWith(", ")) {
                                        return@run true
                                    }
                                }
                                if (currentQueueScore >= mehAndUpQueueBreakOnlyThreshold) {
                                    if (char.isWhitespace()) {
                                        return@run true
                                    }
                                }
                                if (currentQueueScore >= worstAndUpQueueBreakOnlyThreshold) {
                                    return@run true
                                }

                                false
                            }
                            if (breakQueue) {
                                val phonemeText =
                                    englishPhonemizer.main(currentQueue.toString())
                                currentQueue.clear()
                                currentQueueScore = 0
                                verboseLog(TAG) { "Queued phonemes: ${phonemeText.first}" }
                                val queuePhoneIds =
                                    symbolTokenizer.encodeToIds(phonemeText.first)

                                val x = OnnxTensor.createTensor(env, arrayOf(queuePhoneIds))
                                val xLengths = OnnxTensor.createTensor(
                                    env,
                                    longArrayOf(queuePhoneIds.size.toLong())
                                )

                                // Should be set to the input length that's supported by the decoder.
                                val yMaxLengthInBatch = 64

                                val (yLengths, muY, yMask) = encoder.run(
                                    x,
                                    xLengths,
                                    lengthScale
                                )
                                    .use { result ->
                                        @Suppress("UNCHECKED_CAST")
                                        Triple(
                                            result[0].value as LongArray,
                                            result[1].value as Array<Array<FloatArray>>,
                                            result[2].value as Array<Array<FloatArray>>,
                                        )
                                    }
                                val firstYLength = yLengths[0]
                                val yLengthBatchesSize =
                                    ceil(firstYLength.toFloat() / yMaxLengthInBatch.toFloat()).toInt()
                                for (splitIndex in 0..<yLengthBatchesSize) {
                                    fun pcmFloatToPcm16Le(pcmFloat: FloatArray): ByteArray {
                                        val pcm16 = ByteArray(pcmFloat.size * 2)
                                        var pcm16Index = 0
                                        for (pcmFloatIndex in pcmFloat.indices) {
                                            val s = (pcmFloat[pcmFloatIndex].coerceIn(
                                                -1f,
                                                1f
                                            ) * 32767.0f).toInt()
                                            pcm16[pcm16Index] = (s and 0xFF).toByte()
                                            pcm16[pcm16Index + 1] = ((s ushr 8) and 0xFF).toByte()
                                            pcm16Index += 2
                                        }
                                        return pcm16
                                    }

                                    val startOfRange = splitIndex * yMaxLengthInBatch
                                    val endOfRange = (splitIndex + 1) * yMaxLengthInBatch
                                    verboseLog(TAG) {
                                        "onSynthesizeText decoding: startOfRange: $startOfRange, endOfRange: $endOfRange"
                                    }
                                    val cutMuY = mutableListOf<FloatArray>()
                                    for (secondDimObject in muY[0]) {
                                        val toAdd = mutableListOf<Float>()
                                        for (i in startOfRange..<endOfRange) {
                                            toAdd.add(secondDimObject.getOrElse(i) { 0F })
                                        }
                                        cutMuY.add(toAdd.toFloatArray())
                                    }
                                    val cutYMask = mutableListOf<FloatArray>()
                                    for (secondDimObject in yMask[0]) {
                                        val toAdd = mutableListOf<Float>()
                                        for (i in startOfRange..<endOfRange) {
                                            toAdd.add(secondDimObject.getOrElse(i) { 1F })
                                        }
                                        cutYMask.add(toAdd.toFloatArray())
                                    }
                                    val (pcmFloats) = decoder.run(
                                        OnnxTensor.createTensor(
                                            env,
                                            arrayOf(cutMuY.toTypedArray())
                                        ),
                                        OnnxTensor.createTensor(
                                            env,
                                            arrayOf(cutYMask.toTypedArray())
                                        ),
                                        OnnxTensor.createTensor(
                                            env,
                                            LongBuffer.wrap(longArrayOf(5)),
                                            longArrayOf(1)
                                        ),
                                    ).use { result ->
                                        @Suppress("UNCHECKED_CAST")
                                        result[0].value as Array<FloatArray>
                                    }
                                    val unpaddedSize =
                                        (min(
                                            endOfRange,
                                            firstYLength.toInt()
                                        ) - startOfRange) * 256
                                    var segment =
                                        pcmFloatToPcm16Le(
                                            pcmFloats.take(unpaddedSize).toFloatArray()
                                        )
                                    sonicAudioProcessor.setOutputSampleRateHz(
                                        SonicAudioProcessor.SAMPLE_RATE_NO_CHANGE
                                    )
                                    sonicAudioProcessor.configure(
                                        AudioProcessor.AudioFormat(
                                            22050,
                                            1,
                                            C.ENCODING_PCM_16BIT,
                                        )
                                    )
                                    sonicAudioProcessor.setSpeed(request.speechRate / 100F)
                                    sonicAudioProcessor.setPitch(request.pitch / 100F)
                                    sonicAudioProcessor.flush()
                                    // if it's inactive, it means no processing is needed
                                    if (sonicAudioProcessor.isActive) {
                                        val input =
                                            ByteBuffer.wrap(segment)
                                                .order(ByteOrder.nativeOrder())
                                        sonicAudioProcessor.queueInput(input)
                                        sonicAudioProcessor.queueEndOfStream()
                                        var concatenatedOutputBuffers =
                                            SonicAudioProcessor.EMPTY_BUFFER
                                        while (true) {
                                            val outputBuffer = sonicAudioProcessor.output
                                            if (!outputBuffer.hasRemaining()) {
                                                break
                                            }
                                            val temp = ByteBuffer.allocateDirect(
                                                concatenatedOutputBuffers.remaining() + outputBuffer.remaining()
                                            ).order(ByteOrder.nativeOrder())
                                            temp.put(concatenatedOutputBuffers)
                                            temp.put(outputBuffer)
                                            temp.rewind()
                                            concatenatedOutputBuffers = temp
                                        }
                                        segment =
                                            ByteArray(concatenatedOutputBuffers.remaining())
                                        concatenatedOutputBuffers.get(segment)
                                    }

                                    val bufferSize = callback.maxBufferSize
                                    for (i in segment.indices step bufferSize) {
                                        val endBufferSize =
                                            min(i + bufferSize, segment.lastIndex)
                                        val result =
                                            callback.audioAvailable(
                                                segment,
                                                i,
                                                endBufferSize - i
                                            )
                                        if (timeToFirstAudio == null) {
                                            timeToFirstAudio =
                                                SystemClock.elapsedRealtime() - startTime
                                            Log.d(TAG, "time-to-first-audio: $timeToFirstAudio")
                                        }
                                        if (result == TextToSpeech.STOPPED) {
                                            return@launch
                                        } else if (result == TextToSpeech.ERROR) {
                                            callback.error(TextToSpeech.ERROR_OUTPUT)
                                            return@launch
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
                        return@launch
                    }
                } finally {
                    callback?.done()
                }
            }
            synthesizeTextJob = currentJob
        }

        runBlocking {
            currentJob.join()
        }
    }

    override fun onStop() {
        loadVoiceJob?.let { job ->
            if (!job.isCompleted) {
                verboseLog(TAG) { "onStop: calling cancel on loadLanguageJob" }
                job.cancel()
                verboseLog(TAG) { "onStop: called cancel on loadLanguageJob" }
                loadVoiceJob = null
            }
        }
        synthesizeTextJob?.let { job ->
            if (!job.isCompleted) {
                verboseLog(TAG) { "onStop: calling cancel on synthesizeTextJob" }
                job.cancel()
                verboseLog(TAG) { "onStop: called cancel on synthesizeTextJob" }
                synthesizeTextJob = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        encoder.close()
        decoder.close()
    }
}
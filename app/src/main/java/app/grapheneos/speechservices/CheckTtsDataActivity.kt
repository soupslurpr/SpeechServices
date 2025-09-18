package app.grapheneos.speechservices

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import app.grapheneos.speechservices.tts.availableVoices
import java.util.MissingResourceException

class CheckTtsDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent()
        val availableVoices = availableVoices.map { voice ->
            val locale = voice.locale
            try {
                val code = StringBuilder()
                code.append(locale.isO3Language)
                val country = locale.isO3Country
                val variant = locale.variant
                if (country.isNotEmpty()) {
                    code.append("-$country")
                }
                if (variant.isNotEmpty()) {
                    code.append("-$variant")
                }
                code.toString()
            } catch (_: MissingResourceException) {
                locale.toLanguageTag()
            }
        }.toSet()
        result.putStringArrayListExtra(
            TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
            ArrayList(availableVoices)
        )
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }
}

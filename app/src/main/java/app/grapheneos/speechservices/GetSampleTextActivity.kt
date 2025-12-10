package app.grapheneos.speechservices

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import app.grapheneos.speechservices.tts.getAvailableVoiceByName
import app.grapheneos.speechservices.tts.isLanguageAvailableWithDefaultVoiceName

class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val language = intent.getStringExtra("language")
        val country = intent.getStringExtra("country")
        val variant = intent.getStringExtra("variant")

        val (isAvailableLanguage, defaultVoiceName) = isLanguageAvailableWithDefaultVoiceName(
            language,
            country,
            variant
        )
        val isAvailable = when (isAvailableLanguage) {
            TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE, TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> true
            TextToSpeech.LANG_NOT_SUPPORTED, TextToSpeech.LANG_MISSING_DATA -> false
            else -> false
        }
        val defaultVoice = defaultVoiceName?.let { getAvailableVoiceByName(it) }

        val result = Intent()
        if (isAvailable) {
            result.putExtra(
                TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
                "This is an example of speech synthesis in ${defaultVoice?.locale?.displayName}."
            )
        }
        setResult(
            // No granularity; only accepts LANG_AVAILABLE for availability.
            if (isAvailable) {
                TextToSpeech.LANG_AVAILABLE
            } else {
                RESULT_OK
            },
            result
        )
        finish()
    }
}

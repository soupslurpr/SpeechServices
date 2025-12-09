/**
 * Based on https://android.googlesource.com/platform/frameworks/base/+/main/core/java/android/speech/tts/TtsEngines.java
 *
 * Attribution:
 *
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package app.grapheneos.speechservices.tts

import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.MissingResourceException

private val deprecatedToModernLanguage by lazy {
    val mutableMap = mutableMapOf<String, String>()
    for (language in Locale.getISOLanguages()) {
        try {
            mutableMap[Locale.of(language).getISO3Language()] = language
        } catch (_: MissingResourceException) {
            continue
        }
    }
    mutableMap.toMap()
}

private val deprecatedToModernCountry by lazy {
    val mutableMap = mutableMapOf<String, String>()
    for (country in Locale.getISOCountries()) {
        try {
            mutableMap[Locale.of("", country).getISO3Country()] = country
        } catch (_: MissingResourceException) {
            continue
        }
    }
    mutableMap.toMap()
}

/**
 * This method tries its best to return a valid [Locale] object from the TTS-specific
 * Locale input (returned by [TextToSpeech.getLanguage]
 * and [TextToSpeech.getDefaultLanguage]). A TTS Locale language field contains
 * a three-letter ISO 639-2/T code (where a proper Locale would use a two-letter ISO 639-1
 * code), and the country field contains a three-letter ISO 3166 country code (where a proper
 * Locale would use a two-letter ISO 3166-1 code).
 *
 * This method tries to convert three-letter language and country codes into their two-letter
 * equivalents. If it fails to do so, it keeps the value from the TTS locale.
 */
fun deprecatedLocaleToModern(lang: String?, country: String?, variant: String?): Locale {
    val localeBuilder = Locale.Builder()
    if (!lang.isNullOrEmpty()) {
        val normalizedLanguage = deprecatedToModernLanguage[lang]
        if (normalizedLanguage != null) {
            localeBuilder.setLanguage(normalizedLanguage)
        } else {
            localeBuilder.setLanguage(lang)
        }
    }
    if (!country.isNullOrEmpty()) {
        val normalizedCountry = deprecatedToModernCountry[country]
        if (normalizedCountry != null) {
            localeBuilder.setRegion(normalizedCountry)
        } else {
            localeBuilder.setRegion(country)
        }
    }
    if (!variant.isNullOrEmpty()) {
        localeBuilder.setVariant(variant)
    }
    return localeBuilder.build()
}

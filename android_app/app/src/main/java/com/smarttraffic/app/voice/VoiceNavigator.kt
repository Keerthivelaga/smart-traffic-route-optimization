package com.smarttraffic.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceNavigator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null

    fun initialize() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val preferred = Locale("en", "IN")
                val availability = tts?.isLanguageAvailable(preferred) ?: TextToSpeech.LANG_NOT_SUPPORTED
                tts?.language = if (availability >= TextToSpeech.LANG_AVAILABLE) preferred else Locale.getDefault()
                tts?.setSpeechRate(0.96f)
                tts?.setPitch(1.02f)
            }
        }
    }

    fun speak(text: String) {
        initialize()
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}


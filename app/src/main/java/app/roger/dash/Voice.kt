package app.roger.dash

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Spoken turn instructions through whatever is playing audio: the helmet intercom or earphones over
 * Bluetooth. Music ducks (gets quieter) while it speaks, then comes back.
 *
 * Each turn is announced twice, like car navigators: early ("In 300 metres, turn left onto MG Road")
 * and at the turn ("Turn left onto MG Road").
 */
object Voice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private lateinit var am: AudioManager
    private var focus: AudioFocusRequest? = null

    private var alertedFor = -1
    private var nowFor = -1

    /** The phone's text-to-speech engine started and has an English voice. */
    val available: Boolean get() = ready

    fun init(ctx: Context) {
        if (tts != null) return
        am = ctx.getSystemService(AudioManager::class.java)
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val t = tts ?: return@TextToSpeech
            // Indian English if the phone has it, else plain English.
            val ok = t.setLanguage(Locale("en", "IN"))
            if (ok == TextToSpeech.LANG_MISSING_DATA || ok == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.UK)
            t.setAudioAttributes(attrs)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { release() }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { release() }
            })
            ready = true
        }
    }

    private val attrs by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    fun say(text: String) {
        val t = tts ?: return
        if (!ready) return
        val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        focus = f
        am.requestAudioFocus(f)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav-${System.nanoTime()}")
    }

    private fun release() { focus?.let { am.abandonAudioFocusRequest(it) }; focus = null }

    /** New navigation (or re-route): forget what was announced. */
    fun reset() { alertedFor = -1; nowFor = -1 }

    /** Called with every navigation step; speaks when a turn gets close. [speedMps] sets how early. */
    fun onStep(s: NavStep, speedMps: Float?) {
        if (s.status != '1') return
        val d = s.toTurnM ?: return
        val v = (speedMps ?: 8f).coerceAtLeast(5f)
        val early = (v * 18).coerceIn(200f, 700f)   // ~18 s ahead: 200 m in town, up to 700 m on highways
        val now = (v * 4).coerceIn(30f, 120f)       // ~4 s ahead
        if (d <= now && nowFor != s.turnId) {
            nowFor = s.turnId; alertedFor = s.turnId
            s.verbalNow?.let { say(it) }
        } else if (d <= early && d > now * 2 && alertedFor != s.turnId) {
            alertedFor = s.turnId
            s.verbalAlert?.let { say("In ${spoken(d)}, ${it.replaceFirstChar { c -> c.lowercase() }}") }
        }
    }

    /** "300 metres", "1.5 kilometres". */
    fun spoken(m: Int): String = when {
        m < 1000 -> "${((m + 25) / 50) * 50} metres"
        else -> "%.1f kilometres".format(Locale.US, (m / 100.0).roundToInt() / 10.0).replace(".0 ", " ")
    }
}

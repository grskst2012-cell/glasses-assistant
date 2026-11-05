package com.example.glassesassistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Фоновый голосовой ассистент (foreground-сервис + wake lock).
 * Запуск диалога: слово пробуждения (распознавание Android или офлайн-Vosk), кнопка «Говорить»,
 * плитка, виджет, кнопка очков (MediaSession), текстовый ввод или быстрая команда.
 * Локальные команды без обращения к модели: стоп, повтори, помедленнее/побыстрее, подробнее.
 */
class AssistantService : Service() {
    enum class UiState { IDLE, WAITING, LISTENING, THINKING, SPEAKING, MUTED }
    data class Msg(val fromUser: Boolean, val text: String, val time: Long = System.currentTimeMillis(), val error: Boolean = false)

    companion object {
        const val ACTION_STOP = "com.example.glassesassistant.STOP"
        const val ACTION_MUTE = "com.example.glassesassistant.MUTE"
        const val ACTION_PTT = "com.example.glassesassistant.PTT"
        const val ACTION_ASK = "com.example.glassesassistant.ASK"
        const val ACTION_REPEAT = "com.example.glassesassistant.REPEAT"
        const val ACTION_HUSH = "com.example.glassesassistant.HUSH"

        // Состояние для интерфейса (меняется только в главном потоке)
        var running by mutableStateOf(false)
        var uiState by mutableStateOf(UiState.IDLE)
        var status by mutableStateOf("")
        var muted by mutableStateOf(false)
        val log = mutableStateListOf<Msg>()

        /** Команда уже запущенному сервису (кнопки интерфейса). */
        fun send(ctx: Context, action: String, q: String? = null) {
            ctx.startService(Intent(ctx, AssistantService::class.java).setAction(action).apply { if (q != null) putExtra("q", q) })
        }

        /** Нормализация для сравнения: нижний регистр, ё→е, без пунктуации. */
        fun norm(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

        /** Убираем то, что нельзя произносить. */
        fun clean(s: String) = s.replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*#`_>~|]"), "").replace(Regex("\\s+"), " ").trim()
    }

    private enum class Mode { WAKE, COMMAND }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var L: Context          // ресурсы на языке ассистента
    private lateinit var cmds: Lang.Commands
    private var lang = "ru"
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var llm: LlmClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private var wakeOn = false
    private var wakeWord = ""
    private var useVosk = false
    private var bargeOn = false
    private var mode = Mode.COMMAND
    private var busy = false      // идёт запрос к модели или озвучка — обычное слушание не нужно
    private var speaking = false  // идёт озвучка (для перебивания голосом)
    private var lastAnswer = ""
    private var lastTag: String? = null

    // Vosk
    private var voskModel: org.vosk.Model? = null
    private var voskSvc: org.vosk.android.SpeechService? = null
    private var voskLoading = false

    private fun t(id: Int, vararg a: Any) = L.getString(id, *a)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) { if (action != null) handleCommand(intent!!); return START_NOT_STICKY }
        if (action != null) { stopSelf(); return START_NOT_STICKY } // команда без запущенного ассистента
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        lang = prefs.getString("lang", Lang.systemDefault()) ?: "ru"
        L = Lang.ctx(this, lang); cmds = Lang.commands(lang)
        llm = LlmClient(prefs.getString("url", "")!!, prefs.getString("key", "")!!, prefs.getString("model", "")!!)
        wakeWord = norm(prefs.getString("wake", "") ?: "")
        wakeOn = prefs.getBoolean("wakeOn", true) && wakeWord.isNotEmpty()
        useVosk = wakeOn && prefs.getString("engine", "android") == "vosk" && VoskModels.installed(this, lang)
        bargeOn = prefs.getBoolean("barge", false)
        mode = if (wakeOn) Mode.WAKE else Mode.COMMAND
        muted = false
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        running = true
        AssistantWidget.update(this)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "metamockup:listen").apply { acquire() }
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (prefs.getBoolean("useGlasses", true)) routeToGlasses() else setState(UiState.IDLE, t(R.string.status_phone_mic))
        if (prefs.getBoolean("media", false)) startMediaSession()
        if (wakeOn && prefs.getString("engine", "android") == "vosk" && !useVosk) setState(UiState.IDLE, t(R.string.status_vosk_missing))
        tts = TextToSpeech(this) { st ->
            ui.post {
                if (!running) return@post
                ttsReady = st == TextToSpeech.SUCCESS
                tts?.language = Lang.ttsLocale(lang)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { if (id == "u") ui.post { onSpeechStarted() } }
                    override fun onError(id: String?) { if (id == "u") ui.post { afterSpeech() } }
                    override fun onDone(id: String?) { if (id == "u") ui.post { afterSpeech() } }
                })
                if (wakeOn) { idleState(); listen() }
                else speak(t(R.string.say_ready), remember = false)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleCommand(i: Intent) {
        when (i.action) {
            ACTION_MUTE -> toggleMute()
            ACTION_PTT -> pushToTalk()
            ACTION_HUSH -> hush()
            ACTION_REPEAT -> repeatLast()
            ACTION_ASK -> i.getStringExtra("q")?.takeIf { it.isNotBlank() }?.let {
                tts?.stop(); muted = false; answer(it)
            }
        }
    }

    // ---------- уведомление и кнопка очков ----------

    private fun notification(): Notification {
        val ch = "assistant"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, t(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        fun svc(action: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, AssistantService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = if (wakeOn) t(R.string.notif_text_wake, wakeLabel()) else t(R.string.notif_text_always)
        return Notification.Builder(this, ch).setContentTitle(t(R.string.notif_title))
            .setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, t(R.string.notif_talk), svc(ACTION_PTT, 2)).build())
            .addAction(Notification.Action.Builder(null, t(R.string.notif_mic), svc(ACTION_MUTE, 3)).build())
            .addAction(Notification.Action.Builder(null, t(R.string.notif_stop), svc(ACTION_STOP, 1)).build())
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
    }

    /**
     * Медиакнопки очков: касание (play/pause/hook) = «Говорить» (или «Тихо», если ассистент говорит),
     * следующий трек = повтор, предыдущий = «Тихо». Работает, пока ассистент запущен и опция включена.
     */
    private fun startMediaSession() {
        try {
            val s = MediaSession(this, "MetaMockup")
            s.setCallback(object : MediaSession.Callback() {
                @Suppress("DEPRECATION")
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val e = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return true
                    when (e.keyCode) {
                        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> if (speaking) hush() else pushToTalk()
                        KeyEvent.KEYCODE_MEDIA_NEXT -> repeatLast()
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> hush()
                        else -> return false
                    }
                    return true
                }
            })
            s.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f).build())
            s.isActive = true
            mediaSession = s
        } catch (_: Exception) { mediaSession = null }
    }

    /** Направляем микрофон и звук на Bluetooth-очки (если подключены). */
    private fun routeToGlasses() {
        val dev = audio.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (dev != null) { audio.setCommunicationDevice(dev); setState(UiState.IDLE, t(R.string.status_glasses_found)) }
        else setState(UiState.IDLE, t(R.string.status_glasses_missing))
    }

    // ---------- состояние ----------

    private fun setState(s: UiState, text: String? = null) { uiState = s; if (text != null) status = text }
    private fun wakeLabel() = prefs.getString("wake", "") ?: ""
    private fun rate() = prefs.getFloat("rate", 1.0f)
    private fun addLog(fromUser: Boolean, text: String, error: Boolean = false) {
        log.add(Msg(fromUser, text, error = error)); while (log.size > 100) log.removeAt(0)
    }

    private fun earcon(tone: Int) {
        try {
            val g = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
            g.startTone(tone, 120); ui.postDelayed({ g.release() }, 400)
        } catch (_: Exception) {}
    }

    private fun idleState() {
        if (muted) setState(UiState.MUTED, t(R.string.status_mic_off))
        else if (mode == Mode.WAKE) setState(UiState.WAITING, t(R.string.status_waiting_word, wakeLabel()))
        else setState(UiState.LISTENING, t(R.string.status_listening))
    }

    // ---------- управление ----------

    private fun toggleMute() {
        muted = !muted
        if (muted) { recognizer?.cancel(); stopVosk(); setState(UiState.MUTED, t(R.string.status_mic_off)) }
        else { idleState(); if (!busy) listen() }
    }

    private fun pushToTalk() {
        muted = false; tts?.stop(); speaking = false; busy = false; recognizer?.cancel(); stopVosk()
        mode = Mode.COMMAND; earcon(ToneGenerator.TONE_PROP_PROMPT)
        setState(UiState.LISTENING, t(R.string.status_listening)); listen(300)
    }

    private fun hush() {
        tts?.stop(); speaking = false; busy = false; recognizer?.cancel(); stopVosk()
        mode = if (wakeOn) Mode.WAKE else Mode.COMMAND
        idleState(); listen(250)
    }

    private fun repeatLast() {
        if (lastAnswer.isEmpty()) speak(t(R.string.say_nothing_to_repeat), remember = false)
        else speak(lastAnswer, remember = false, tag = lastTag)
    }

    private fun changeRate(d: Float) {
        prefs.edit().putFloat("rate", (rate() + d).coerceIn(0.6f, 1.6f)).apply()
        speak(t(if (d < 0) R.string.say_slower else R.string.say_faster), remember = false)
    }

    /** Команды, которые не нужно отправлять модели. */
    private fun localCommand(txt: String): Boolean {
        fun has(words: List<String>) = words.any { txt == it || txt == "$it please" || txt == "$it пожалуйста" }
        return when {
            has(cmds.stop) -> { hush(); true }
            has(cmds.repeat) -> { repeatLast(); true }
            has(cmds.slower) -> { changeRate(-0.15f); true }
            has(cmds.faster) -> { changeRate(0.15f); true }
            has(cmds.more) -> { answer(t(R.string.q_follow_up), detailed = true); true }
            else -> false
        }
    }

    // ---------- речь ----------

    private fun speak(text: String, remember: Boolean = true, tag: String? = null) {
        if (!running) return
        val c = clean(text)
        if (remember) { lastAnswer = c; lastTag = tag }
        busy = true
        stopVosk()
        setState(UiState.SPEAKING, t(R.string.status_speaking))
        recognizer?.cancel()
        if (ttsReady && c.isNotEmpty()) {
            val wanted = if (tag != null) Locale.forLanguageTag(tag) else Lang.ttsLocale(lang)
            val r = tts?.setLanguage(wanted)
            if (r == null || r < TextToSpeech.LANG_AVAILABLE) tts?.language = Lang.ttsLocale(lang)
            tts?.setSpeechRate(rate())
            tts?.speak(c, TextToSpeech.QUEUE_FLUSH, null, "u")
        } else afterSpeech()
    }

    private fun onSpeechStarted() {
        speaking = true
        if (bargeOn && !muted) listen(300) // слушаем «стоп» во время речи
    }

    private fun afterSpeech() {
        speaking = false
        busy = false
        recognizer?.cancel()
        tts?.language = Lang.ttsLocale(lang)
        idleState()
        if (!muted) listen(200)
    }

    private fun listen(delayMs: Long = 0) { ui.postDelayed({ startListening() }, delayMs) }

    private fun startListening() {
        if (!running || muted) return
        if (busy && !(speaking && bargeOn)) return
        if (mode == Mode.WAKE && useVosk && !speaking) { startVosk(); return }
        stopVosk()
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer!!.setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle) {
                    onHeard(r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                }
                override fun onError(e: Int) {
                    if (!running || muted) return
                    if (speaking && bargeOn) { listen(500); return }
                    if (busy) return
                    when (e) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { setState(UiState.IDLE, t(R.string.status_no_mic_permission)); stopSelf() }
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            // тишина: после диалога возвращаемся к ожиданию слова
                            if (mode == Mode.COMMAND && wakeOn) mode = Mode.WAKE
                            idleState(); listen(300)
                        }
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { recognizer?.cancel(); listen(1000) }
                        else -> listen(1500)
                    }
                }
                override fun onReadyForSpeech(p: Bundle?) { if (!busy && !muted) idleState() }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
        }
        recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Lang.recognizerTag(lang))
        })
    }

    private fun onHeard(raw: String?) {
        if (speaking) { bargeHeard(raw); return }
        if (raw.isNullOrBlank()) { listen(200); return }
        if (mode == Mode.COMMAND) {
            if (!localCommand(norm(raw))) answer(raw)
            return
        }
        // режим ожидания: ищем слово пробуждения как отдельное слово
        val text = norm(raw)
        val m = Regex("(^| )" + Regex.escape(wakeWord) + "( |$)").find(text)
        if (m == null) { listen(100); return }
        val rest = text.substring(m.range.last + 1).trim()
        if (rest.isEmpty()) { // просто слово — короткий сигнал и ждём команду
            mode = Mode.COMMAND; earcon(ToneGenerator.TONE_PROP_PROMPT)
            setState(UiState.LISTENING, t(R.string.status_listening)); listen(350)
        } else if (!localCommand(rest)) answer(rest)
    }

    /** Перебивание: во время речи реагируем только на слова остановки и не принимаем эхо собственного голоса. */
    private fun bargeHeard(raw: String?) {
        val text = raw?.let { norm(it) }.orEmpty()
        val echo = text.isNotEmpty() && norm(lastAnswer).contains(text)
        val stop = text.isNotEmpty() && !echo &&
            cmds.stop.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") || text.contains(" $it ") }
        if (stop) hush() else if (speaking) listen(300)
    }

    // ---------- офлайн-слово пробуждения (Vosk) ----------

    private fun startVosk() {
        if (voskSvc != null || !running || muted) return
        val m = voskModel
        if (m == null) {
            if (voskLoading) return
            voskLoading = true
            setState(UiState.WAITING, t(R.string.status_vosk_loading))
            val dir = VoskModels.dir(this, lang).absolutePath
            thread {
                val model = try { org.vosk.Model(dir) } catch (_: Throwable) { null }
                ui.post {
                    voskLoading = false
                    if (!running) { model?.close(); return@post }
                    voskModel = model
                    if (model == null) { useVosk = false; setState(UiState.IDLE, t(R.string.status_vosk_missing)) }
                    listen()
                }
            }
            return
        }
        try {
            val grammar = JSONArray().put(wakeWord).put("[unk]").toString()
            val svc = org.vosk.android.SpeechService(org.vosk.Recognizer(m, 16000f, grammar), 16000f)
            svc.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(h: String?) { voskCheck(h, "partial") }
                override fun onResult(h: String?) { voskCheck(h, "text") }
                override fun onFinalResult(h: String?) {}
                override fun onError(e: Exception?) { ui.post { useVosk = false; stopVosk(); idleState(); listen() } }
                override fun onTimeout() {}
            })
            voskSvc = svc
            idleState()
        } catch (_: Exception) {
            useVosk = false; setState(UiState.IDLE, t(R.string.status_vosk_missing)); listen()
        }
    }

    private fun voskCheck(json: String?, key: String) {
        if (json == null || voskSvc == null || mode != Mode.WAKE) return
        val txt = try { norm(JSONObject(json).optString(key)) } catch (_: Exception) { "" }
        if (txt.isNotEmpty() && Regex("(^| )" + Regex.escape(wakeWord) + "( |$)").containsMatchIn(txt)) {
            ui.post {
                if (voskSvc == null) return@post
                stopVosk(); mode = Mode.COMMAND; earcon(ToneGenerator.TONE_PROP_PROMPT)
                setState(UiState.LISTENING, t(R.string.status_listening)); listen(250)
            }
        }
    }

    private fun stopVosk() {
        val s = voskSvc ?: return
        voskSvc = null
        try { s.stop(); s.shutdown() } catch (_: Exception) {}
    }

    // ---------- запрос к модели ----------

    private fun answer(q: String, detailed: Boolean = false) {
        busy = true; speaking = false
        recognizer?.cancel(); stopVosk()
        addLog(true, q)
        setState(UiState.THINKING, t(R.string.status_thinking))
        earcon(ToneGenerator.TONE_PROP_ACK)
        val cue = Runnable {
            if (running && uiState == UiState.THINKING && ttsReady) tts?.speak(t(R.string.say_wait), TextToSpeech.QUEUE_ADD, null, "cue")
        }
        ui.postDelayed(cue, 3500)
        val city = prefs.getString("city", "") ?: ""
        val toolsOn = prefs.getBoolean("tools", true)
        val env = AssistEnv(L.resources, lang, prefs.getString("search", "ddg") ?: "ddg", prefs.getString("tavily", "") ?: "")
        thread {
            val res = try { Result.success(llm!!.ask(q, city, toolsOn, detailed, env)) } catch (e: Exception) { Result.failure(e) }
            ui.post {
                ui.removeCallbacks(cue)
                if (!running) return@post
                res.onSuccess { raw ->
                    // перевод: модель начинает ответ с тега [lang=xx], озвучиваем на этом языке
                    val m = Regex("^\\[lang=([A-Za-z-]{2,8})]\\s*").find(raw)
                    val text = if (m != null) raw.substring(m.range.last + 1) else raw
                    addLog(false, text); mode = Mode.COMMAND
                    speak(text, tag = m?.groupValues?.get(1))
                }
                res.onFailure {
                    val msg = friendly(it)
                    addLog(false, msg, error = true)
                    mode = if (wakeOn) Mode.WAKE else Mode.COMMAND
                    speak(msg, remember = false)
                }
            }
        }
    }

    private fun friendly(e: Throwable): String = t(when ((e as? LlmException)?.kind) {
        LlmException.Kind.NETWORK -> R.string.err_network
        LlmException.Kind.AUTH -> R.string.err_auth
        LlmException.Kind.RATE -> R.string.err_rate
        LlmException.Kind.SERVER -> R.string.err_server
        else -> R.string.err_other
    })

    override fun onDestroy() {
        running = false
        ui.removeCallbacksAndMessages(null)
        stopVosk(); voskModel?.close(); voskModel = null
        recognizer?.destroy(); tts?.shutdown()
        mediaSession?.let { it.isActive = false; it.release() }
        if (::audio.isInitialized) { audio.clearCommunicationDevice(); audio.mode = AudioManager.MODE_NORMAL }
        wakeLock?.let { if (it.isHeld) it.release() }
        muted = false
        uiState = UiState.IDLE
        status = if (::L.isInitialized) t(R.string.status_stopped) else ""
        AssistantWidget.update(this)
        super.onDestroy()
    }
}

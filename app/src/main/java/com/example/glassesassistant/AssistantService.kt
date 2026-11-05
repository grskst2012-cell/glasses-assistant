package com.example.glassesassistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Фоновый голосовой ассистент (foreground-сервис + wake lock).
 * Запуск диалога: слово пробуждения, кнопка «Говорить» в приложении, текстовый ввод или быстрая команда.
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
        var status by mutableStateOf("Остановлено")
        var muted by mutableStateOf(false)
        val log = mutableStateListOf<Msg>()

        /** Команда уже запущенному сервису (кнопки интерфейса). */
        fun send(ctx: Context, action: String, q: String? = null) {
            ctx.startService(Intent(ctx, AssistantService::class.java).setAction(action).apply { if (q != null) putExtra("q", q) })
        }

        /** Нормализация для сравнения: нижний регистр, ё→е, без пунктуации. */
        fun norm(s: String) = s.lowercase(Locale("ru")).replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

        /** Убираем то, что нельзя произносить. */
        fun clean(s: String) = s.replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*#`_>~|]"), "").replace(Regex("\\s+"), " ").trim()
    }

    private enum class Mode { WAKE, COMMAND }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private lateinit var prefs: android.content.SharedPreferences
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var llm: LlmClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeOn = false
    private var wakeWord = ""
    private var mode = Mode.COMMAND
    private var busy = false // идёт запрос к модели или озвучка — слушать не нужно
    private var lastAnswer = ""

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) { if (action != null) handleCommand(intent!!); return START_NOT_STICKY }
        if (action != null) { stopSelf(); return START_NOT_STICKY } // команда без запущенного ассистента
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        llm = LlmClient(prefs.getString("url", "")!!, prefs.getString("key", "")!!, prefs.getString("model", "")!!)
        wakeWord = norm(prefs.getString("wake", "ассистент") ?: "")
        wakeOn = prefs.getBoolean("wakeOn", true) && wakeWord.isNotEmpty()
        mode = if (wakeOn) Mode.WAKE else Mode.COMMAND
        muted = false
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        running = true
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glassesassistant:listen").apply { acquire() }
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (prefs.getBoolean("useGlasses", true)) routeToGlasses() else setState(UiState.IDLE, "Используется микрофон телефона")
        tts = TextToSpeech(this) { st ->
            ui.post {
                if (!running) return@post
                ttsReady = st == TextToSpeech.SUCCESS
                tts?.language = Locale("ru", "RU")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onError(id: String?) { if (id == "u") ui.post { afterSpeech() } }
                    override fun onDone(id: String?) { if (id == "u") ui.post { afterSpeech() } }
                })
                if (wakeOn) { setState(UiState.WAITING, "Жду: «${wakeLabel()}»"); listen() }
                else speak("Готов. Слушаю.", remember = false)
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

    // ---------- уведомление ----------

    private fun notification(): Notification {
        val ch = "assistant"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "Ассистент", NotificationManager.IMPORTANCE_LOW))
        fun svc(action: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, AssistantService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = if (wakeOn) "Скажите «${wakeLabel()}» или нажмите «Говорить»" else "Слушаю постоянно"
        return Notification.Builder(this, ch).setContentTitle("Ассистент работает в фоне")
            .setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Говорить", svc(ACTION_PTT, 2)).build())
            .addAction(Notification.Action.Builder(null, "Микрофон", svc(ACTION_MUTE, 3)).build())
            .addAction(Notification.Action.Builder(null, "Стоп", svc(ACTION_STOP, 1)).build())
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
    }

    /** Направляем микрофон и звук на Bluetooth-очки (если подключены). */
    private fun routeToGlasses() {
        val dev = audio.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (dev != null) { audio.setCommunicationDevice(dev); setState(UiState.IDLE, "Очки найдены") }
        else setState(UiState.IDLE, "Очки не найдены, используется телефон")
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
        if (muted) setState(UiState.MUTED, "Микрофон выключен")
        else if (mode == Mode.WAKE) setState(UiState.WAITING, "Жду: «${wakeLabel()}»")
        else setState(UiState.LISTENING, "Слушаю…")
    }

    // ---------- управление ----------

    private fun toggleMute() {
        muted = !muted
        if (muted) { recognizer?.cancel(); setState(UiState.MUTED, "Микрофон выключен") }
        else { idleState(); if (!busy) listen() }
    }

    private fun pushToTalk() {
        muted = false; tts?.stop(); busy = false; recognizer?.cancel()
        mode = Mode.COMMAND; earcon(ToneGenerator.TONE_PROP_PROMPT)
        setState(UiState.LISTENING, "Слушаю…"); listen(300)
    }

    private fun hush() {
        tts?.stop(); busy = false; recognizer?.cancel()
        mode = if (wakeOn) Mode.WAKE else Mode.COMMAND
        idleState(); listen(200)
    }

    private fun repeatLast() {
        if (lastAnswer.isEmpty()) speak("Повторять пока нечего.", remember = false) else speak(lastAnswer, remember = false)
    }

    private fun changeRate(d: Float) {
        prefs.edit().putFloat("rate", (rate() + d).coerceIn(0.6f, 1.6f)).apply()
        speak(if (d < 0) "Хорошо, говорю медленнее." else "Хорошо, говорю быстрее.", remember = false)
    }

    /** Команды, которые не нужно отправлять модели. */
    private fun localCommand(t: String): Boolean {
        fun has(vararg w: String) = w.any { t == it || t == "$it пожалуйста" }
        return when {
            has("стоп", "хватит", "замолчи", "тихо", "помолчи", "отмена") -> { hush(); true }
            has("повтори", "еще раз", "повтори еще раз") -> { repeatLast(); true }
            has("помедленнее", "медленнее", "говори медленнее") -> { changeRate(-0.15f); true }
            has("побыстрее", "быстрее", "говори быстрее") -> { changeRate(0.15f); true }
            has("подробнее", "поподробнее", "расскажи подробнее") -> { answer("Расскажи подробнее об этом.", detailed = true); true }
            else -> false
        }
    }

    // ---------- речь ----------

    private fun speak(text: String, remember: Boolean = true) {
        if (!running) return
        val c = clean(text)
        if (remember) lastAnswer = c
        busy = true
        setState(UiState.SPEAKING, "Говорю…")
        recognizer?.cancel()
        if (ttsReady && c.isNotEmpty()) {
            tts?.setSpeechRate(rate())
            tts?.speak(c, TextToSpeech.QUEUE_FLUSH, null, "u")
        } else afterSpeech()
    }

    private fun afterSpeech() {
        busy = false
        idleState()
        if (!muted) listen()
    }

    private fun listen(delayMs: Long = 0) { ui.postDelayed({ startListening() }, delayMs) }

    private fun startListening() {
        if (!running || busy || muted) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer!!.setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle) {
                    onHeard(r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                }
                override fun onError(e: Int) {
                    if (!running || busy || muted) return
                    when (e) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { setState(UiState.IDLE, "Нет доступа к микрофону"); stopSelf() }
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        })
    }

    private fun onHeard(raw: String?) {
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
        if (rest.isEmpty()) { // просто «ассистент» — короткий сигнал и ждём команду
            mode = Mode.COMMAND; earcon(ToneGenerator.TONE_PROP_PROMPT)
            setState(UiState.LISTENING, "Слушаю…"); listen(350)
        } else if (!localCommand(rest)) answer(rest)
    }

    private fun answer(q: String, detailed: Boolean = false) {
        busy = true
        recognizer?.cancel()
        addLog(true, q)
        setState(UiState.THINKING, "Думаю…")
        earcon(ToneGenerator.TONE_PROP_ACK)
        val cue = Runnable {
            if (running && uiState == UiState.THINKING && ttsReady) tts?.speak("Секунду.", TextToSpeech.QUEUE_ADD, null, "cue")
        }
        ui.postDelayed(cue, 3500)
        val city = prefs.getString("city", "") ?: ""
        val toolsOn = prefs.getBoolean("tools", true)
        thread {
            val res = try { Result.success(llm!!.ask(q, city, toolsOn, detailed)) } catch (e: Exception) { Result.failure(e) }
            ui.post {
                ui.removeCallbacks(cue)
                if (!running) return@post
                res.onSuccess { addLog(false, it); mode = Mode.COMMAND; speak(it) }
                res.onFailure {
                    val m = friendly(it)
                    addLog(false, m, error = true)
                    mode = if (wakeOn) Mode.WAKE else Mode.COMMAND
                    speak(m, remember = false)
                }
            }
        }
    }

    private fun friendly(e: Throwable): String = when ((e as? LlmException)?.kind) {
        LlmException.Kind.NETWORK -> "Нет связи с интернетом."
        LlmException.Kind.AUTH -> "Проверьте ключ API и баланс."
        LlmException.Kind.RATE -> "Модель сейчас перегружена. Попробуйте через минуту."
        LlmException.Kind.SERVER -> "Сервер модели не отвечает."
        else -> "Не получилось получить ответ."
    }

    override fun onDestroy() {
        running = false
        ui.removeCallbacksAndMessages(null)
        recognizer?.destroy(); tts?.shutdown()
        if (::audio.isInitialized) { audio.clearCommunicationDevice(); audio.mode = AudioManager.MODE_NORMAL }
        wakeLock?.let { if (it.isHeld) it.release() }
        muted = false
        setState(UiState.IDLE, "Остановлено")
        super.onDestroy()
    }
}

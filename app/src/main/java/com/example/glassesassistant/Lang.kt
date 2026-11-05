package com.example.glassesassistant

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Языки ассистента (речь, ответы, команды) и приложения. Поддерживаются русский и английский. */
object Lang {
    val supported = listOf("ru", "en")

    /** Язык по умолчанию: русский, если система русская, иначе английский. */
    fun systemDefault() = if (Locale.getDefault().language == "ru") "ru" else "en"

    /** Контекст с ресурсами нужного языка (для строк, которые произносит или отправляет сервис). */
    fun ctx(base: Context, code: String): Context {
        val c = Configuration(base.resources.configuration)
        c.setLocale(Locale(code))
        return base.createConfigurationContext(c)
    }

    fun recognizerTag(code: String) = if (code == "ru") "ru-RU" else "en-US"
    fun ttsLocale(code: String): Locale = if (code == "ru") Locale("ru", "RU") else Locale.US

    class Commands(
        val stop: List<String>, val repeat: List<String>, val slower: List<String>,
        val faster: List<String>, val more: List<String>,
    )

    /** Голосовые команды, которые выполняются без обращения к модели (уже в нормализованном виде). */
    fun commands(code: String) = if (code == "ru") Commands(
        stop = listOf("стоп", "хватит", "замолчи", "тихо", "помолчи", "отмена", "достаточно"),
        repeat = listOf("повтори", "еще раз", "повтори еще раз"),
        slower = listOf("помедленнее", "медленнее", "говори медленнее"),
        faster = listOf("побыстрее", "быстрее", "говори быстрее"),
        more = listOf("подробнее", "поподробнее", "расскажи подробнее"),
    ) else Commands(
        stop = listOf("stop", "quiet", "be quiet", "enough", "shut up", "cancel", "hush", "that s enough"),
        repeat = listOf("repeat", "repeat that", "say again", "say that again", "again"),
        slower = listOf("slower", "slow down", "speak slower", "talk slower"),
        faster = listOf("faster", "speed up", "speak faster", "talk faster"),
        more = listOf("more", "more detail", "tell me more", "elaborate", "in detail", "go on"),
    )
}

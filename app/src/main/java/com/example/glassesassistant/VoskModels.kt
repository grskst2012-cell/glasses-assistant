package com.example.glassesassistant

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/** Офлайн-модели Vosk для слова пробуждения. Скачиваются по запросу, в APK не входят. */
object VoskModels {
    class Info(val dir: String, val url: String, val mb: Int)

    fun info(lang: String) = if (lang == "ru")
        Info("vosk-model-small-ru-0.22", "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", 45)
    else Info("vosk-model-small-en-us-0.15", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40)

    fun dir(ctx: Context, lang: String) = File(File(ctx.filesDir, "vosk"), info(lang).dir)
    fun installed(ctx: Context, lang: String) = File(dir(ctx, lang), "am").isDirectory

    // состояние для интерфейса
    var downloading by mutableStateOf<String?>(null)
    var progress by mutableIntStateOf(0)
    var error by mutableStateOf("")
    var version by mutableIntStateOf(0) // увеличивается при изменении набора моделей

    fun delete(ctx: Context, lang: String) {
        dir(ctx, lang).deleteRecursively(); version++
    }

    fun download(ctx: Context, lang: String) {
        if (downloading != null) return
        val app = ctx.applicationContext
        downloading = lang; progress = 0; error = ""
        thread {
            val root = File(app.filesDir, "vosk").apply { mkdirs() }
            val zip = File(app.cacheDir, "vosk-model.zip")
            try {
                val c = URL(info(lang).url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                val total = c.contentLengthLong.coerceAtLeast(1L)
                c.inputStream.use { ins -> zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        progress = (done * 90 / total).toInt()
                    }
                } }
                // распаковка (с защитой от выхода за пределы папки)
                val rootPath = root.canonicalPath + File.separator
                ZipInputStream(zip.inputStream().buffered()).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        val f = File(root, e.name)
                        if (!f.canonicalPath.startsWith(rootPath)) continue
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zin.copyTo(it) } }
                    }
                }
                progress = 100
                if (!installed(app, lang)) error("unexpected archive layout")
            } catch (e: Exception) {
                dir(app, lang).deleteRecursively()
                error = e.message ?: e.javaClass.simpleName
            } finally {
                zip.delete(); downloading = null; version++
            }
        }
    }
}

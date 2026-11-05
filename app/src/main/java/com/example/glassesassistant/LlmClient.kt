package com.example.glassesassistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class LlmException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NETWORK, AUTH, RATE, SERVER, BAD_REQUEST, OTHER }
}

/** Любой OpenAI-совместимый API (DeepSeek, Kimi, OpenRouter, Yandex AI Studio…) с вызовом инструментов. */
class LlmClient(private val baseUrl: String, private val apiKey: String, private val model: String) {
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    private val history = JSONArray()
    private var toolsSupported = true

    private fun system(city: String, tools: Boolean, detailed: Boolean): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale("ru")).format(Date())
        return buildString {
            append("Ты голосовой ассистент в умных очках. Пользователь идёт по улице: слушает, не читает. ")
            append("Сейчас $now, часовой пояс ${TimeZone.getDefault().id}. ")
            append(if (city.isBlank()) "Город пользователя не задан. " else "Город пользователя: $city. ")
            append("Правила: отвечай по-русски, ")
            append(if (detailed) "подробно, но не больше пяти предложений. " else "в один-два коротких предложения, суть сразу в начале. ")
            append("Только устная речь: никакого markdown, списков, эмодзи, ссылок и скобок. ")
            append("Числа, время, дату и единицы пиши словами в правильном падеже (например «двадцать два градуса», «пятнадцать тридцать»). ")
            if (tools) append("Любые изменчивые факты — время, погоду, курсы валют, справочные данные — получай ТОЛЬКО через инструменты, " +
                "не называй такие цифры по памяти. Всегда коротко называй источник и время данных (например «по данным Центробанка на сегодня»). " +
                "Если инструмент вернул ОШИБКА или ничего — честно скажи, что данных нет. ")
            append("Если не уверен — так и скажи. Не выдумывай.")
        }
    }

    @Synchronized
    fun ask(text: String, city: String, tools: Boolean, detailed: Boolean): String {
        history.put(JSONObject().put("role", "user").put("content", text))
        val work = JSONArray()
        for (i in maxOf(0, history.length() - 12) until history.length()) work.put(history.get(i))
        var useTools = tools && toolsSupported
        var iter = 0
        while (true) {
            val defs = if (useTools && iter < 4) Tools.definitions else null
            val m = try {
                chat(system(city, useTools, detailed), work, defs)
            } catch (e: LlmException) {
                // модель/провайдер не поддерживает инструменты — отвечаем без них
                if (e.kind == LlmException.Kind.BAD_REQUEST && defs != null) { toolsSupported = false; useTools = false; continue }
                history.remove(history.length() - 1)
                throw e
            }
            val calls = m.optJSONArray("tool_calls")
            if (calls != null && calls.length() > 0 && defs != null) {
                work.put(m)
                for (i in 0 until calls.length()) {
                    val c = calls.getJSONObject(i); val f = c.getJSONObject("function")
                    val args = try { JSONObject(f.optString("arguments", "{}").ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                    val res = Tools.run(f.getString("name"), args, city)
                    work.put(JSONObject().put("role", "tool").put("tool_call_id", c.optString("id")).put("content", res))
                }
                iter++; continue
            }
            val ans = (if (m.isNull("content")) "" else m.getString("content")).trim()
                .ifBlank { "Не получилось сформулировать ответ." }
            history.put(JSONObject().put("role", "assistant").put("content", ans))
            return ans
        }
    }

    private fun chat(system: String, work: JSONArray, tools: JSONArray?): JSONObject {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (i in 0 until work.length()) msgs.put(work.get(i))
        val body = JSONObject().put("model", model.trim()).put("messages", msgs)
            .put("max_tokens", 500).put("temperature", 0.3)
        if (tools != null) body.put("tools", tools).put("tool_choice", "auto")
        val req = Request.Builder().url(baseUrl.trim().trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        try {
            http.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val kind = when (r.code) {
                        401, 402, 403 -> LlmException.Kind.AUTH
                        429 -> LlmException.Kind.RATE
                        400, 404, 422 -> LlmException.Kind.BAD_REQUEST
                        in 500..599 -> LlmException.Kind.SERVER
                        else -> LlmException.Kind.OTHER
                    }
                    throw LlmException(kind, "HTTP ${r.code}: ${s.take(200)}")
                }
                return JSONObject(s).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            }
        } catch (e: IOException) {
            throw LlmException(LlmException.Kind.NETWORK, e.message ?: "network")
        }
    }

    fun clearHistory() { synchronized(this) { while (history.length() > 0) history.remove(0) } }

    companion object {
        /** GET /models — список моделей, доступных для ключа. */
        fun listModels(baseUrl: String, apiKey: String): List<String> {
            val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
            val req = Request.Builder().url(baseUrl.trim().trimEnd('/') + "/models")
                .header("Authorization", "Bearer ${apiKey.trim()}").build()
            http.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                if (!r.isSuccessful) error("HTTP ${r.code}: ${s.take(200)}")
                val arr = JSONObject(s).getJSONArray("data")
                return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.sorted()
            }
        }
    }
}

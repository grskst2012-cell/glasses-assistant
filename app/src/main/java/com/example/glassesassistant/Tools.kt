package com.example.glassesassistant

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Инструменты, через которые модель получает проверяемые факты вместо «памяти»:
 * время (с телефона), погода (Open-Meteo), курсы ЦБ РФ, справка (Википедия).
 * Каждый результат содержит источник и время данных — модель обязана их озвучить.
 */
object Tools {
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
    private val cache = HashMap<String, Pair<Long, String>>()
    private val ru = Locale("ru")

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props)
                .put("required", JSONArray(required))))

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

    val definitions: JSONArray by lazy {
        JSONArray()
            .put(fn("get_current_time", "Текущие дата, время и день недели на телефоне пользователя."))
            .put(fn("get_weather", "Текущая погода и прогноз на сегодня для города. Без city берётся город пользователя.",
                JSONObject().put("city", str("Название города, например «Алматы»"))))
            .put(fn("get_currency_rates", "Официальные курсы валют ЦБ РФ к рублю на сегодня.",
                JSONObject().put("codes", str("Коды валют через запятую, например USD,EUR,CNY"))))
            .put(fn("wikipedia_summary", "Краткая справка из русской Википедии: кто/что такое, определения, факты.",
                JSONObject().put("query", str("Что искать, например «Эйфелева башня»")), listOf("query")))
    }

    fun run(name: String, args: JSONObject, defaultCity: String): String = try {
        when (name) {
            "get_current_time" -> time()
            "get_weather" -> weather(args.optString("city").ifBlank { defaultCity })
            "get_currency_rates" -> currency(args.optString("codes").ifBlank { "USD,EUR,CNY" })
            "wikipedia_summary" -> wiki(args.optString("query"))
            else -> "ОШИБКА: неизвестный инструмент"
        }
    } catch (e: Exception) {
        "ОШИБКА: источник недоступен (${e.javaClass.simpleName}). Скажи пользователю, что данные сейчас получить не удалось, и не называй цифр."
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "GlassesAssistant/0.4 (personal app)").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    private fun cached(key: String, ttlMs: Long, load: () -> String): String {
        synchronized(cache) { cache[key]?.let { if (System.currentTimeMillis() - it.first < ttlMs) return it.second } }
        val v = load()
        synchronized(cache) { cache[key] = System.currentTimeMillis() to v }
        return v
    }

    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20")

    private fun time(): String {
        val now = Date()
        val f = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", ru)
        return "${f.format(now)}, часовой пояс ${TimeZone.getDefault().id}. Источник: часы телефона."
    }

    private fun weatherText(code: Int) = when (code) {
        0 -> "ясно"; 1 -> "преимущественно ясно"; 2 -> "переменная облачность"; 3 -> "пасмурно"
        45, 48 -> "туман"; in 51..57 -> "морось"; in 61..67 -> "дождь"; in 71..77 -> "снег"
        in 80..82 -> "ливень"; 85, 86 -> "снегопад"; in 95..99 -> "гроза"; else -> "без описания"
    }

    private fun weather(city: String): String {
        if (city.isBlank()) return "ОШИБКА: город не указан. Спроси пользователя, для какого города нужна погода."
        return cached("w:" + city.lowercase(), 10 * 60_000L) {
            val g = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=${enc(city)}&count=1&language=ru"))
            val res = g.optJSONArray("results") ?: return@cached "ОШИБКА: город «$city» не найден."
            val p = res.getJSONObject(0)
            val lat = p.getDouble("latitude"); val lon = p.getDouble("longitude")
            val w = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=1&wind_speed_unit=ms"))
            val c = w.getJSONObject("current"); val d = w.getJSONObject("daily")
            "Город: ${p.optString("name", city)}. Сейчас ${Math.round(c.getDouble("temperature_2m"))} °C " +
                "(ощущается как ${Math.round(c.getDouble("apparent_temperature"))}), ${weatherText(c.getInt("weather_code"))}, " +
                "ветер ${Math.round(c.getDouble("wind_speed_10m"))} м/с. " +
                "Сегодня от ${Math.round(d.getJSONArray("temperature_2m_min").getDouble(0))} до " +
                "${Math.round(d.getJSONArray("temperature_2m_max").getDouble(0))} °C, " +
                "вероятность осадков ${d.getJSONArray("precipitation_probability_max").optInt(0)} %. " +
                "Данные на ${c.optString("time").takeLast(5)}. Источник: Open-Meteo."
        }
    }

    private fun currency(codes: String): String {
        val raw = cached("cbr", 3 * 3_600_000L) { get("https://www.cbr-xml-daily.ru/daily_json.js") }
        val j = JSONObject(raw); val v = j.getJSONObject("Valute")
        val date = j.optString("Date").take(10)
        val out = codes.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.mapNotNull { c ->
            val x = v.optJSONObject(c) ?: return@mapNotNull null
            val nom = x.getInt("Nominal")
            "$c: ${"%.2f".format(ru, x.getDouble("Value"))} руб. за ${if (nom == 1) "1" else nom.toString()} " +
                "(вчера ${"%.2f".format(ru, x.getDouble("Previous"))})"
        }
        if (out.isEmpty()) return "ОШИБКА: этих валют нет в данных ЦБ."
        return out.joinToString("; ") + ". Официальный курс ЦБ РФ на $date."
    }

    private fun wiki(query: String): String {
        if (query.isBlank()) return "ОШИБКА: пустой запрос."
        return cached("wiki:" + query.lowercase(), 3_600_000L) {
            val s = JSONObject(get("https://ru.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc(query)}&format=json&srlimit=1"))
            val hits = s.getJSONObject("query").getJSONArray("search")
            if (hits.length() == 0) return@cached "Ничего не найдено в Википедии по запросу «$query»."
            val title = hits.getJSONObject(0).getString("title")
            val sum = JSONObject(get("https://ru.wikipedia.org/api/rest_v1/page/summary/${enc(title)}"))
            "«$title»: ${sum.optString("extract").take(700)} Источник: Википедия (статья может быть неточной)."
        }
    }
}

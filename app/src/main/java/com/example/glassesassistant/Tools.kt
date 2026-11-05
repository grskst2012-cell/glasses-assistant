package com.example.glassesassistant

import android.content.res.Resources
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Всё, что инструментам нужно знать о языке и настройках. */
class AssistEnv(val res: Resources, val lang: String, val search: String, val tavilyKey: String) {
    val locale: Locale get() = Locale(lang)
    fun s(id: Int, vararg a: Any) = res.getString(id, *a)
}

/**
 * Инструменты, через которые модель получает проверяемые факты вместо «памяти»:
 * время (с телефона), погода (Open-Meteo), курсы ЦБ РФ, справка (Википедия), поиск в интернете.
 * Каждый результат содержит источник и время данных — модель обязана их озвучить.
 */
object Tools {
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val cache = HashMap<String, Pair<Long, String>>()
    private const val UA = "Mozilla/5.0 (Linux; Android 14) MetaMockup/0.5"

    private fun fn(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props)
                .put("required", JSONArray(required))))

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

    fun definitions(searchOn: Boolean): JSONArray = JSONArray().apply {
        put(fn("get_current_time", "Current date, time and weekday on the user's phone."))
        put(fn("get_weather", "Current weather and today's forecast for a city. Without city the user's city is used.",
            JSONObject().put("city", str("City name, e.g. Almaty"))))
        put(fn("get_currency_rates", "Official Bank of Russia exchange rates to the ruble for today.",
            JSONObject().put("codes", str("Comma-separated currency codes, e.g. USD,EUR,CNY"))))
        put(fn("wikipedia_summary", "Short reference answer from Wikipedia: who/what is something, definitions, facts.",
            JSONObject().put("query", str("What to look up, e.g. Eiffel Tower")), listOf("query")))
        if (searchOn) put(fn("web_search", "Search the web for fresh information: news, events, prices, anything current.",
            JSONObject().put("query", str("Search query")), listOf("query")))
    }

    fun run(name: String, args: JSONObject, defaultCity: String, env: AssistEnv): String = try {
        when (name) {
            "get_current_time" -> time(env)
            "get_weather" -> weather(args.optString("city").ifBlank { defaultCity }, env)
            "get_currency_rates" -> currency(args.optString("codes").ifBlank { "USD,EUR,CNY" }, env)
            "wikipedia_summary" -> wiki(args.optString("query"), env)
            "web_search" -> webSearch(args.optString("query"), env)
            else -> env.s(R.string.tool_err_unknown)
        }
    } catch (e: Exception) {
        env.s(R.string.tool_err_unavailable, e.javaClass.simpleName)
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
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

    private fun time(env: AssistEnv): String {
        val f = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", env.locale)
        return env.s(R.string.tool_time, f.format(Date()), TimeZone.getDefault().id)
    }

    private fun weatherText(code: Int, env: AssistEnv) = env.s(when (code) {
        0 -> R.string.w_clear; 1 -> R.string.w_mostly_clear; 2 -> R.string.w_partly_cloudy; 3 -> R.string.w_overcast
        45, 48 -> R.string.w_fog; in 51..57 -> R.string.w_drizzle; in 61..67 -> R.string.w_rain
        in 71..77 -> R.string.w_snow; in 80..82 -> R.string.w_showers; 85, 86 -> R.string.w_snowfall
        in 95..99 -> R.string.w_thunderstorm; else -> R.string.w_unknown
    })

    private fun weather(city: String, env: AssistEnv): String {
        if (city.isBlank()) return env.s(R.string.tool_err_no_city)
        return cached("w:${env.lang}:" + city.lowercase(), 10 * 60_000L) {
            val g = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=${enc(city)}&count=1&language=${env.lang}"))
            val res = g.optJSONArray("results") ?: return@cached env.s(R.string.tool_err_city_not_found, city)
            val p = res.getJSONObject(0)
            val lat = p.getDouble("latitude"); val lon = p.getDouble("longitude")
            val w = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=1&wind_speed_unit=ms"))
            val c = w.getJSONObject("current"); val d = w.getJSONObject("daily")
            env.s(R.string.tool_weather, p.optString("name", city),
                Math.round(c.getDouble("temperature_2m")).toInt(), Math.round(c.getDouble("apparent_temperature")).toInt(),
                weatherText(c.getInt("weather_code"), env), Math.round(c.getDouble("wind_speed_10m")).toInt(),
                Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
                Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                d.getJSONArray("precipitation_probability_max").optInt(0), c.optString("time").takeLast(5))
        }
    }

    private fun currency(codes: String, env: AssistEnv): String {
        val raw = cached("cbr", 3 * 3_600_000L) { get("https://www.cbr-xml-daily.ru/daily_json.js") }
        val j = JSONObject(raw); val v = j.getJSONObject("Valute")
        val date = j.optString("Date").take(10)
        val out = codes.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.mapNotNull { c ->
            val x = v.optJSONObject(c) ?: return@mapNotNull null
            env.s(R.string.tool_currency_item, c, "%.2f".format(env.locale, x.getDouble("Value")),
                x.getInt("Nominal").toString(), "%.2f".format(env.locale, x.getDouble("Previous")))
        }
        if (out.isEmpty()) return env.s(R.string.tool_err_no_currency)
        return out.joinToString("; ") + ". " + env.s(R.string.tool_currency_footer, date)
    }

    private fun wiki(query: String, env: AssistEnv): String {
        if (query.isBlank()) return env.s(R.string.tool_err_empty_query)
        return cached("wiki:${env.lang}:" + query.lowercase(), 3_600_000L) {
            val host = "${env.lang}.wikipedia.org"
            val s = JSONObject(get("https://$host/w/api.php?action=query&list=search&srsearch=${enc(query)}&format=json&srlimit=1"))
            val hits = s.getJSONObject("query").getJSONArray("search")
            if (hits.length() == 0) return@cached env.s(R.string.tool_wiki_none, query)
            val title = hits.getJSONObject(0).getString("title")
            val sum = JSONObject(get("https://$host/api/rest_v1/page/summary/${enc(title)}"))
            env.s(R.string.tool_wiki, title, sum.optString("extract").take(700))
        }
    }

    // ---------- поиск в интернете ----------

    private fun webSearch(query: String, env: AssistEnv): String {
        if (query.isBlank()) return env.s(R.string.tool_err_empty_query)
        return when (env.search) {
            "tavily" -> if (env.tavilyKey.isBlank()) env.s(R.string.tool_search_need_key) else
                cached("tav:${env.lang}:" + query.lowercase(), 5 * 60_000L) { tavily(query, env) }
            "ddg" -> cached("ddg:${env.lang}:" + query.lowercase(), 5 * 60_000L) { duckDuckGo(query, env) }
            else -> env.s(R.string.tool_search_off)
        }
    }

    private fun tavily(query: String, env: AssistEnv): String {
        val body = JSONObject().put("query", query).put("max_results", 4).put("include_answer", true)
        val req = Request.Builder().url("https://api.tavily.com/search")
            .header("Authorization", "Bearer ${env.tavilyKey.trim()}").header("User-Agent", UA)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val j = JSONObject(s)
            val sb = StringBuilder()
            j.optString("answer").takeIf { it.isNotBlank() && it != "null" }?.let { sb.append(it.take(500)).append(" ") }
            val arr = j.optJSONArray("results") ?: JSONArray()
            for (i in 0 until minOf(arr.length(), 3)) {
                val x = arr.getJSONObject(i)
                sb.append("[").append(host(x.optString("url"))).append("] ")
                    .append(x.optString("title")).append(": ").append(x.optString("content").take(300)).append(" ")
            }
            if (sb.isBlank()) return env.s(R.string.tool_search_none, query)
            return sb.toString().trim() + " " + env.s(R.string.tool_search_footer)
        }
    }

    /** HTML-версия DuckDuckGo без ключа. Разбор регулярками — хрупкий, поэтому «по возможности». */
    private fun duckDuckGo(query: String, env: AssistEnv): String {
        val kl = if (env.lang == "ru") "ru-ru" else "us-en"
        val html = get("https://html.duckduckgo.com/html/?q=${enc(query)}&kl=$kl")
        val links = Regex("""result__a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("""result__snippet[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val sb = StringBuilder()
        var n = 0
        for ((i, m) in links.withIndex()) {
            val href = m.groupValues[1]
            val target = Regex("""uddg=([^&]+)""").find(href)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: href
            if (target.contains("duckduckgo.com/y.js")) continue // реклама
            val title = plain(m.groupValues[2])
            val snip = snippets.getOrNull(i)?.groupValues?.get(1)?.let { plain(it) }.orEmpty()
            sb.append("[").append(host(target)).append("] ").append(title).append(": ").append(snip.take(300)).append(" ")
            if (++n >= 3) break
        }
        if (n == 0) return env.s(R.string.tool_search_none, query)
        return sb.toString().trim() + " " + env.s(R.string.tool_search_footer)
    }

    private fun plain(html: String) = html.replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun host(url: String) = try { java.net.URI(url).host?.removePrefix("www.") ?: url } catch (_: Exception) { url }
}

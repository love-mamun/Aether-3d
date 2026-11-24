package com.example.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

class FreeWebToolsRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var cachedWeatherSummary: Pair<Long, String>? = null

    internal fun isCurrentLocationToken(cityQuery: String?): Boolean {
        val lower = cityQuery?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (lower.isBlank()) return true
        val exactTokens = setOf(
            "current", "here", "current location", "my location", "local", "today",
            "auto", "gps", "nearby", "unknown", "this city", "my city",
            "এখানে", "বর্তমান", "আমার অবস্থান", "আমার লোকেশন", "amar location", "ajke"
        )
        return lower in exactTokens ||
            lower.contains("current location") ||
            lower.contains("my location") ||
            lower.contains("your location")
    }

    suspend fun fetchWeather(
        cityQuery: String?,
        fallbackLat: Double?,
        fallbackLon: Double?,
        fallbackCityName: String? = null,
        preferBangla: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        try {
            val cleanQuery = cityQuery?.trim().orEmpty()
            var resolvedName = cleanQuery
            var lat = fallbackLat
            var lon = fallbackLon

            if (!isCurrentLocationToken(cleanQuery)) {
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json"
                val geoJson = runCatching { executeGet(geoUrl) }.getOrNull()
                val top = geoJson?.let { raw ->
                    runCatching {
                        val geoRoot = jsonParser.parseToJsonElement(raw).jsonObject
                        val results = geoRoot["results"] as? JsonArray
                        results?.firstOrNull() as? JsonObject
                    }.getOrNull()
                }
                if (top != null) {
                    lat = (top["latitude"] as? JsonPrimitive)?.doubleOrNull ?: lat
                    lon = (top["longitude"] as? JsonPrimitive)?.doubleOrNull ?: lon
                    val name = top.optStr("name", cleanQuery)
                    val country = top.optStr("country", "")
                    resolvedName = listOf(name, country)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                }
            } else {
                resolvedName = fallbackCityName?.takeIf { it.isNotBlank() }
                    ?: if (preferBangla) "আপনার বর্তমান অবস্থান" else "your current location"
            }

            if (lat == null || lon == null) {
                val ipResolved = fetchApproximateLocationByIpFast()
                if (ipResolved != null) {
                    lat = ipResolved.first
                    lon = ipResolved.second
                    if (isCurrentLocationToken(cleanQuery) ||
                        resolvedName == "your current location" ||
                        resolvedName == "আপনার বর্তমান অবস্থান"
                    ) {
                        resolvedName = ipResolved.third.ifBlank { resolvedName }
                    }
                }
            }

            if (lat == null || lon == null) {
                val recent = cachedWeatherSummary?.takeIf { System.currentTimeMillis() - it.first < 7_200_000L }
                if (recent != null) {
                    return@withContext recent.second
                }
                return@withContext buildWeatherFailureReply(preferBangla)
            }

            val forecastUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,rain,weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,rain_sum&timezone=auto&forecast_days=1"
            val forecastRaw = executeGet(forecastUrl)
            val formatted = formatOpenMeteoForecast(
                locationLabel = resolvedName,
                forecastRawJson = forecastRaw,
                preferBangla = preferBangla
            )
            cachedWeatherSummary = System.currentTimeMillis() to formatted
            formatted
        } catch (_: Exception) {
            val recent = cachedWeatherSummary?.takeIf { System.currentTimeMillis() - it.first < 7_200_000L }
            recent?.second ?: buildWeatherFailureReply(preferBangla)
        }
    }

    internal fun buildWeatherFailureReply(preferBangla: Boolean): String {
        return if (preferBangla) {
            "দুঃখিত, এই মুহূর্তে লাইভ আবহাওয়া ও লোকেশন পাওয়া যায়নি। ইন্টারনেট বা লোকেশন চালু আছে কিনা দেখুন, অথবা শহরের নাম বলুন।"
        } else {
            "Weather unavailable right now because location and internet lookup failed. Please check Location or ask with a city name."
        }
    }

    internal fun isWeatherErrorText(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.startsWith("weather unavailable") ||
            lower.startsWith("could not fetch live weather") ||
            lower.startsWith("weather data incomplete") ||
            text.startsWith("দুঃখিত, এই মুহূর্তে লাইভ আবহাওয়া")
    }

    internal fun formatOpenMeteoForecast(
        locationLabel: String,
        forecastRawJson: String,
        preferBangla: Boolean = false
    ): String {
        val root = jsonParser.parseToJsonElement(forecastRawJson).jsonObject
        val current = root["current"] as? JsonObject
            ?: return if (preferBangla) {
                "$locationLabel-এর আবহাওয়ার সম্পূর্ণ তথ্য পাওয়া যায়নি।"
            } else {
                "Weather data incomplete for $locationLabel."
            }
        val temp = (current["temperature_2m"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val feelsLike = (current["apparent_temperature"] as? JsonPrimitive)?.doubleOrNull ?: temp
        val humidity = (current["relative_humidity_2m"] as? JsonPrimitive)?.intOrNull ?: 0
        val wind = (current["wind_speed_10m"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val code = (current["weather_code"] as? JsonPrimitive)?.intOrNull ?: 0

        val daily = root["daily"] as? JsonObject
        val high = ((daily?.get("temperature_2m_max") as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.doubleOrNull
        val low = ((daily?.get("temperature_2m_min") as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.doubleOrNull
        val rainProb = ((daily?.get("precipitation_probability_max") as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.intOrNull
        val rainSum = ((daily?.get("rain_sum") as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.doubleOrNull
            ?: ((daily?.get("precipitation_sum") as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.doubleOrNull

        if (preferBangla) {
            val conditionBn = describeWmoCodeBangla(code)
            val rangeBn = if (high != null && low != null && !high.isNaN() && !low.isNaN()) {
                " সর্বোচ্চ ${high.toInt()}°C, সর্বনিম্ন ${low.toInt()}°C।"
            } else ""
            val rainInfoBn = buildString {
                if (rainProb != null) {
                    append(" আজ বৃষ্টির সম্ভাবনা $rainProb%")
                    if (rainSum != null && !rainSum.isNaN() && rainSum > 0.0) {
                        append(String.format(Locale.US, " (~%.1f মিমি বৃষ্টি)", rainSum))
                    }
                    append("।")
                } else if (rainSum != null && !rainSum.isNaN()) {
                    append(String.format(Locale.US, " আজ সম্ভাব্য বৃষ্টি %.1f মিমি।", rainSum))
                }
            }
            return "$locationLabel-এ এখন তাপমাত্রা ${temp.toInt()}°C ($conditionBn, অনুভূত হচ্ছে ${feelsLike.toInt()}°C), আর্দ্রতা $humidity%, বাতাস ${wind.toInt()} কিমি/ঘণ্টা।$rangeBn$rainInfoBn"
        }

        val condition = describeWmoCode(code)
        val range = if (high != null && low != null && !high.isNaN() && !low.isNaN()) {
            " High ${high.toInt()}°C, low ${low.toInt()}°C."
        } else ""

        val rainInfo = buildString {
            if (rainProb != null) {
                append(" Rain chance today: $rainProb%")
                if (rainSum != null && !rainSum.isNaN() && rainSum > 0.0) {
                    append(String.format(Locale.US, " (~%.1f mm rain)", rainSum))
                }
                append(".")
            } else if (rainSum != null && !rainSum.isNaN()) {
                append(String.format(Locale.US, " Expected rain today: %.1f mm.", rainSum))
            }
        }

        return "Weather in $locationLabel: ${temp.toInt()}°C ($condition, feels like ${feelsLike.toInt()}°C), humidity $humidity%, wind ${wind.toInt()} km/h.$range$rainInfo"
    }

    private suspend fun fetchApproximateLocationByIpFast(): Triple<Double, Double, String>? {
        val urls = listOf(
            "https://get.geojs.io/v1/ip/geo.json",
            "https://ipapi.co/json/",
            "https://ipwho.is/"
        )
        return withTimeoutOrNull(3_800L) {
            val winner = CompletableDeferred<Triple<Double, Double, String>?>()
            kotlinx.coroutines.coroutineScope {
                urls.forEach { url ->
                    launch(Dispatchers.IO) {
                        val parsed = runCatching {
                            val raw = executeGet(url)
                            val obj = jsonParser.parseToJsonElement(raw).jsonObject
                            val lat = (obj["latitude"] as? JsonPrimitive)?.let {
                                it.doubleOrNull ?: it.content.toDoubleOrNull()
                            }
                            val lon = (obj["longitude"] as? JsonPrimitive)?.let {
                                it.doubleOrNull ?: it.content.toDoubleOrNull()
                            }
                            if (lat != null && lon != null) {
                                val city = obj.optStr("city", "")
                                val country = obj.optStr("country", obj.optStr("country_name", ""))
                                val label = listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")
                                Triple(lat, lon, label)
                            } else null
                        }.getOrNull()
                        if (parsed != null && !winner.isCompleted) {
                            winner.complete(parsed)
                        }
                    }
                }
                winner.await()
            }
        }
    }

    private fun JsonObject.optStr(key: String, fallback: String): String {
        val prim = this[key] as? JsonPrimitive ?: return fallback
        if (prim is JsonNull) return fallback
        return prim.content.takeUnless { it == "null" } ?: fallback
    }

    suspend fun fetchWikipediaSummary(topic: String): String = withContext(Dispatchers.IO) {
        try {
            val clean = topic.trim().replace(" ", "_")
            if (clean.isBlank()) return@withContext "No topic provided for Wikipedia lookup."
            val encoded = URLEncoder.encode(clean, "UTF-8").replace("%2F", "/")
            val url = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Aether3DAndroidAgent/1.0 (https://github.com)")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext "Wikipedia article for '$topic' was not found (HTTP ${resp.code})."
                }
                val body = resp.body?.string().orEmpty()
                val obj = jsonParser.parseToJsonElement(body).jsonObject
                val title = obj.optStr("title", topic)
                val extract = obj.optStr("extract", "").trim()
                if (extract.isBlank()) {
                    "Found Wikipedia entry for $title, but no summary text was returned."
                } else {
                    "$title: $extract"
                }
            }
        } catch (e: Exception) {
            "Wikipedia lookup failed: ${e.localizedMessage ?: "network error"}"
        }
    }

    suspend fun convertCurrency(amount: Double, fromCurrency: String, toCurrency: String): String =
        withContext(Dispatchers.IO) {
            try {
                val safeAmount = if (amount <= 0.0) 1.0 else amount
                val from = fromCurrency.trim().uppercase().ifBlank { "USD" }
                val to = toCurrency.trim().uppercase().ifBlank { "INR" }
                if (from == to) return@withContext "$safeAmount $from equals $safeAmount $to."
                val url = "https://api.frankfurter.dev/v1/latest?amount=$safeAmount&from=$from&to=$to"
                val obj = jsonParser.parseToJsonElement(executeGet(url)).jsonObject
                val rates = obj["rates"] as? JsonObject
                    ?: return@withContext "Currency rates unavailable."
                val converted = (rates[to] as? JsonPrimitive)?.doubleOrNull
                    ?: return@withContext "Currency rate for $to unavailable."
                val date = obj.optStr("date", "latest")
                String.format(Locale.US, "%.2f %s = %.2f %s (ECB rate %s)", safeAmount, from, converted, to, date)
            } catch (e: Exception) {
                "Currency conversion failed: ${e.localizedMessage ?: "invalid currency or network error"}"
            }
        }

    private fun executeGet(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Aether3DAndroidAgent/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            return resp.body?.string().orEmpty()
        }
    }

    private fun describeWmoCode(code: Int): String = when (code) {
        0 -> "Clear sky"
        1, 2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        61, 63, 65 -> "Rain"
        71, 73, 75 -> "Snow"
        80, 81, 82 -> "Rain showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Variable conditions"
    }

    private fun describeWmoCodeBangla(code: Int): String = when (code) {
        0 -> "পরিষ্কার আকাশ"
        1, 2 -> "আংশিক মেঘলা"
        3 -> "মেঘলা আকাশ"
        45, 48 -> "কুয়াশাচ্ছন্ন"
        51, 53, 55 -> "হালকা গুঁড়ি গুঁড়ি বৃষ্টি"
        61, 63, 65 -> "বৃষ্টি"
        71, 73, 75 -> "তুষারপাত"
        80, 81, 82 -> "বৃষ্টির ঝাপটা"
        95, 96, 99 -> "বজ্রসহ বৃষ্টি"
        else -> "পরিবর্তনশীল আবহাওয়া"
    }
}

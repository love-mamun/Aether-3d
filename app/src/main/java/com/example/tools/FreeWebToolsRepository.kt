package com.example.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

class FreeWebToolsRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchWeather(
        cityQuery: String?,
        fallbackLat: Double?,
        fallbackLon: Double?
    ): String = withContext(Dispatchers.IO) {
        try {
            var resolvedName = cityQuery?.trim().orEmpty()
            var lat = fallbackLat
            var lon = fallbackLon

            if (resolvedName.isNotBlank() && !resolvedName.equals("current", ignoreCase = true) && !resolvedName.equals("here", ignoreCase = true)) {
                val encoded = URLEncoder.encode(resolvedName, "UTF-8")
                val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json"
                val geoJson = executeGet(geoUrl)
                val results = JSONObject(geoJson).optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val top = results.getJSONObject(0)
                    lat = top.getDouble("latitude")
                    lon = top.getDouble("longitude")
                    val country = top.optString("country", "")
                    resolvedName = listOf(top.optString("name", resolvedName), country)
                        .filter { it.isNotBlank() }
                        .joinToString(", ")
                }
            } else if (lat != null && lon != null) {
                resolvedName = "your current location"
            }

            if (lat == null || lon == null) {
                return@withContext "Weather unavailable: specify a city name or grant Coarse Location permission."
            }

            val forecastUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
            val forecastJson = JSONObject(executeGet(forecastUrl))
            val current = forecastJson.getJSONObject("current")
            val temp = current.optDouble("temperature_2m")
            val feelsLike = current.optDouble("apparent_temperature")
            val humidity = current.optInt("relative_humidity_2m")
            val wind = current.optDouble("wind_speed_10m")
            val code = current.optInt("weather_code")
            val daily = forecastJson.optJSONObject("daily")
            val high = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)
            val low = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)

            val condition = describeWmoCode(code)
            val range = if (high != null && low != null && !high.isNaN() && !low.isNaN()) {
                " High ${high.toInt()}°C, low ${low.toInt()}°C."
            } else ""

            "Weather in $resolvedName: ${temp.toInt()}°C ($condition, feels like ${feelsLike.toInt()}°C), humidity $humidity%, wind ${wind.toInt()} km/h.$range"
        } catch (e: Exception) {
            "Could not fetch live weather: ${e.localizedMessage ?: "network error"}"
        }
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
                val json = JSONObject(body)
                val title = json.optString("title", topic)
                val extract = json.optString("extract", "").trim()
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
                val json = JSONObject(executeGet(url))
                val rates = json.getJSONObject("rates")
                val converted = rates.getDouble(to)
                val date = json.optString("date", "latest")
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
}

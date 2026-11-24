package dev.vq.sidecarlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

data class DayForecast(val date: LocalDate, val code: Int, val tMin: Double, val tMax: Double, val rainProb: Int?)

data class HourForecast(val hour: Int, val temp: Double, val code: Int, val isDay: Boolean, val rainProb: Int?, val humidity: Int?)

data class Weather(
    val place: String,
    val temperature: Double,
    val code: Int,
    val isDay: Boolean,
    val days: List<DayForecast>,
    /** Today 00:00–23:00. */
    val hours: List<HourForecast> = emptyList(),
)

/** Open-Meteo: free, open source, no API key. */
object WeatherRepo {

    suspend fun load(context: Context, city: String): Weather? = withContext(Dispatchers.IO) {
        try {
            val (lat, lon, place) = resolve(context, city) ?: return@withContext null
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,is_day" +
                "&hourly=temperature_2m,weather_code,precipitation_probability,is_day,relative_humidity_2m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=4"
            val json = JSONObject(httpGet(url))
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            val rain = daily.optJSONArray("precipitation_probability_max")
            val days = (0 until dates.length()).map { i ->
                DayForecast(
                    date = LocalDate.parse(dates.getString(i)),
                    code = daily.getJSONArray("weather_code").getInt(i),
                    tMin = daily.getJSONArray("temperature_2m_min").getDouble(i),
                    tMax = daily.getJSONArray("temperature_2m_max").getDouble(i),
                    rainProb = rain?.takeUnless { it.isNull(i) }?.getInt(i),
                )
            }
            val hourly = json.optJSONObject("hourly")
            val hours = hourly?.let { h ->
                val n = minOf(24, h.getJSONArray("time").length())
                val rp = h.optJSONArray("precipitation_probability")
                val hum = h.optJSONArray("relative_humidity_2m")
                (0 until n).map { i ->
                    HourForecast(
                        hour = i,
                        temp = h.getJSONArray("temperature_2m").getDouble(i),
                        code = h.getJSONArray("weather_code").getInt(i),
                        isDay = h.getJSONArray("is_day").optInt(i, 1) == 1,
                        rainProb = rp?.takeUnless { it.isNull(i) }?.getInt(i),
                        humidity = hum?.takeUnless { it.isNull(i) }?.getInt(i),
                    )
                }
            } ?: emptyList()
            Weather(
                hours = hours,
                place = place,
                temperature = current.getDouble("temperature_2m"),
                code = current.getInt("weather_code"),
                isDay = current.optInt("is_day", 1) == 1,
                days = days,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun resolve(context: Context, city: String): Triple<Double, Double, String>? {
        if (city.isNotBlank()) {
            val q = URLEncoder.encode(city, "UTF-8")
            val json = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=${if (I18n.en) "en" else "it"}"))
            val r = json.optJSONArray("results")?.optJSONObject(0) ?: return null
            return Triple(r.getDouble("latitude"), r.getDouble("longitude"), r.optString("name", city))
        }
        return lastKnownLocation(context)?.let { Triple(it.first, it.second, "") }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(context: Context): Pair<Double, Double>? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val lm = context.getSystemService(LocationManager::class.java)
        val loc = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .firstNotNullOfOrNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            ?: return null
        return loc.latitude to loc.longitude
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** WMO weather code -> (Material Symbol, Italian description). */
    fun describe(code: Int, isDay: Boolean = true): Pair<Char, String> = when (code) {
        0 -> (if (isDay) Sym.SUNNY else Sym.CLEAR_NIGHT) to tr("Sereno", "Clear")
        1 -> (if (isDay) Sym.PARTLY_CLOUDY_DAY else Sym.PARTLY_CLOUDY_NIGHT) to tr("Poco nuvoloso", "Mostly clear")
        2 -> (if (isDay) Sym.PARTLY_CLOUDY_DAY else Sym.PARTLY_CLOUDY_NIGHT) to tr("Parz. nuvoloso", "Partly cloudy")
        3 -> Sym.CLOUD to tr("Coperto", "Overcast")
        45, 48 -> Sym.FOGGY to tr("Nebbia", "Fog")
        in 51..57 -> Sym.RAINY_LIGHT to tr("Pioviggine", "Drizzle")
        61, 66, 80 -> Sym.RAINY_LIGHT to tr("Pioggia debole", "Light rain")
        in 61..67 -> Sym.RAINY to tr("Pioggia", "Rain")
        81 -> Sym.RAINY to tr("Rovesci", "Showers")
        82 -> Sym.RAINY_HEAVY to tr("Rovesci forti", "Heavy showers")
        in 71..77, 85, 86 -> Sym.SNOWY to tr("Neve", "Snow")
        in 95..99 -> Sym.THUNDERSTORM to tr("Temporale", "Thunderstorm")
        else -> Sym.CLOUD to ""
    }
}

package dev.vq.sidecarlauncher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Billable Hours (Pebble watchapp) via its Google Sheets backup web app:
 * GET <url>?token=… returns the full snapshot; totals are computed here exactly like the watch
 * (seconds worked since midnight / since the 1st, running timer included, × hourly rate).
 */
object Billable {
    data class Session(val proj: Int, val start: Long, val end: Long)

    data class Snapshot(
        val rates: Map<Int, Double>,
        val names: Map<Int, String>,
        val sessions: List<Session>,
        val activeProj: Int?,
        val activeStart: Long?,
        val currency: String,
    )

    data class Totals(
        val todaySec: Long,
        val todayAmount: Double,
        val monthSec: Long,
        val monthAmount: Double,
        val running: String?,
        val hasRates: Boolean,
        val currency: String,
    )

    val snapshot = MutableStateFlow<Snapshot?>(null)
    /** Last refresh outcome, shown in the launcher settings. */
    val status = MutableStateFlow("")

    suspend fun refresh(url: String, token: String) = withContext(Dispatchers.IO) {
        if (url.isBlank() || token.isBlank()) {
            snapshot.value = null
            return@withContext
        }
        try {
            val sep = if ('?' in url) '&' else '?'
            val conn = URL("$url${sep}token=${URLEncoder.encode(token, "UTF-8")}").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            val text = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            val root = JSONObject(text)
            if (!root.optBoolean("ok")) {
                status.value = "✗ " + root.optString("error", "risposta non valida")
                return@withContext
            }
            val d = root.getJSONObject("data")
            val rates = mutableMapOf<Int, Double>()
            val names = mutableMapOf<Int, String>()
            d.optJSONArray("projects")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONObject(i)
                    rates[p.getInt("id")] = p.optDouble("rate", 0.0)
                    names[p.getInt("id")] = p.optString("name")
                }
            }
            val sessions = d.optJSONArray("sessions")?.let { a ->
                (0 until a.length()).map { i ->
                    val s = a.getJSONObject(i)
                    Session(s.getInt("proj"), s.getLong("start"), s.getLong("end"))
                }
            } ?: emptyList()
            val active = d.optJSONObject("active")
            snapshot.value = Snapshot(
                rates, names, sessions,
                activeProj = active?.optInt("proj")?.takeIf { it > 0 },
                activeStart = active?.optLong("start")?.takeIf { it > 0 },
                currency = d.optJSONObject("settings")?.optString("currency")?.takeIf { it.isNotBlank() } ?: "€",
            )
            status.value = "✓ " + java.time.LocalTime.now().withNano(0)
        } catch (e: Exception) {
            status.value = "✗ " + (e.message ?: e.javaClass.simpleName)  // keep the last good snapshot
        }
    }

    private fun overlap(a0: Long, a1: Long, b0: Long, b1: Long) = maxOf(0L, minOf(a1, b1) - maxOf(a0, b0))

    fun totals(s: Snapshot, nowSec: Long = System.currentTimeMillis() / 1000): Totals {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val day0 = today.atStartOfDay(zone).toEpochSecond()
        val month0 = today.withDayOfMonth(1).atStartOfDay(zone).toEpochSecond()
        var ts = 0L
        var tc = 0.0
        var ms = 0L
        var mc = 0.0
        fun add(proj: Int, a: Long, b: Long) {
            val rate = s.rates[proj] ?: 0.0
            val od = overlap(a, b, day0, nowSec)
            val om = overlap(a, b, month0, nowSec)
            ts += od; tc += od * rate / 3600
            ms += om; mc += om * rate / 3600
        }
        s.sessions.forEach { add(it.proj, it.start, it.end) }
        if (s.activeProj != null && s.activeStart != null) add(s.activeProj, s.activeStart, nowSec)
        return Totals(
            ts, tc, ms, mc,
            running = s.activeProj?.let { s.names[it] },
            hasRates = s.rates.values.any { it > 0 },
            currency = s.currency,
        )
    }

    /** "3:20" like the watch. */
    fun hm(sec: Long) = "%d:%02d".format(sec / 3600, sec % 3600 / 60)

    fun money(v: Double, currency: String): String =
        NumberFormat.getNumberInstance(if (I18n.en) Locale.UK else Locale.ITALY).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(v) + " " + currency
}

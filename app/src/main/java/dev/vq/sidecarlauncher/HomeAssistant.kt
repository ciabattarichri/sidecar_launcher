package dev.vq.sidecarlauncher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Home Assistant through its REST API (base URL + long-lived access token from the HA profile page).
 * Lights, switches, fans and input booleans toggle; climate entities show temperature and mode.
 * Same [HomeUi]/[HomeDevice] as Google Home, so the widget is shared.
 */
object HomeAssistant {
    val ui = MutableStateFlow(HomeUi(false))
    private val TOGGLE = setOf("light", "switch", "fan", "input_boolean")

    private fun base(url: String) = url.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }

    private fun call(url: String, token: String, path: String, body: JSONObject? = null): String {
        val c = URL(base(url) + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6_000; c.readTimeout = 10_000
            c.setRequestProperty("Authorization", "Bearer ${token.trim()}")
            c.setRequestProperty("Content-Type", "application/json")
            if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            if (code == 401) error(tr("Token non valido", "Invalid token"))
            if (code !in 200..299) error("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    suspend fun refresh(url: String, token: String) = withContext(Dispatchers.IO) {
        if (url.isBlank() || token.isBlank()) { ui.value = HomeUi(false); return@withContext }
        try {
            val arr = JSONArray(call(url, token, "/api/states"))
            val out = (0 until arr.length()).mapNotNull { i -> parse(arr.getJSONObject(i)) }.sortedBy { it.name.lowercase() }
            ui.value = HomeUi(true, out)
        } catch (e: Exception) {
            ui.value = ui.value.copy(signedIn = true, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun parse(o: JSONObject): HomeDevice? {
        val id = o.optString("entity_id")
        val domain = id.substringBefore(".")
        if (domain !in TOGGLE && domain != "climate") return null
        val a = o.optJSONObject("attributes") ?: JSONObject()
        if (a.optBoolean("hidden")) return null
        val st = o.optString("state")
        val online = st != "unavailable" && st != "unknown"
        val climate = domain == "climate"
        val state = DevState(
            online = online,
            on = if (climate) null else st == "on",
            brightness = if (domain == "light" && a.has("brightness") && !a.isNull("brightness")) (a.optInt("brightness") * 100 + 127) / 255 else null,
            mode = if (climate) st else null,
            setpointC = if (climate && a.has("temperature") && !a.isNull("temperature")) a.optDouble("temperature") else null,
            ambientC = if (climate && a.has("current_temperature") && !a.isNull("current_temperature")) a.optDouble("current_temperature") else null,
        )
        return HomeDevice(
            id = id, name = a.optString("friendly_name").ifBlank { id }, room = null,
            type = when (domain) { "light" -> "LIGHT"; "switch", "input_boolean" -> "SWITCH"; else -> domain.uppercase() },
            agentId = "", partnerId = "", canOnOff = !climate, isThermostat = climate, state = state,
        )
    }

    suspend fun toggle(url: String, token: String, d: HomeDevice) = withContext(Dispatchers.IO) {
        val target = d.state?.on != true
        ui.value = ui.value.copy(devices = ui.value.devices.map { if (it.id == d.id) it.copy(state = it.state?.copy(on = target)) else it })
        try {
            call(url, token, "/api/services/${d.id.substringBefore(".")}/toggle", JSONObject().put("entity_id", d.id))
        } catch (e: Exception) {
            ui.value = ui.value.copy(error = e.message)
        }
        refresh(url, token)
    }
}

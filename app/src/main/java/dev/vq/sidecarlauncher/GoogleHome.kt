package dev.vq.sidecarlauncher

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

/*
 * Google Home through the Home app's private "foyer" API, as reverse engineered by Core Devices
 * (github.com/coredevices/pebble-apps, googlehome/). Not an official API: it can break any time.
 *
 * Auth: EmbeddedSetup sign-in gives an oauth_token cookie -> exchanged once for a master token
 * (gpsoauth) -> short-lived bearers minted as the Google Home app.
 */

data class DevState(
    val online: Boolean,
    val on: Boolean?,
    val brightness: Int?,
    val mode: String?,
    val setpointC: Double?,
    val ambientC: Double?,
)

data class HomeDevice(
    val id: String,
    val name: String,
    val room: String?,
    val type: String,
    val agentId: String,
    val partnerId: String,
    val canOnOff: Boolean,
    val isThermostat: Boolean,
    val state: DevState? = null,
)

data class HomeUi(
    val signedIn: Boolean,
    val devices: List<HomeDevice> = emptyList(),
    val error: String? = null,
)

object GoogleHome {
    val ui = MutableStateFlow(HomeUi(false))

    private const val AUTH_URL = "https://android.clients.google.com/auth"
    private const val FOYER = "https://googlehomefoyer-pa.clients6.google.com/\$rpc/google.internal.home.foyer.v1."
    private const val GMS_SIG = "38918a453d07199354f8b19af05ec6562ced5788"
    private const val HOME_APP = "com.google.android.apps.chromecast.app"
    private const val HOME_SIG = "24bb24c05e47e0aefa68a58a766179d9b613a600"
    private val SCOPES = listOf(
        "oauth2:https://www.googleapis.com/auth/homegraph",
        "oauth2:https://www.google.com/accounts/OAuthLogin",
    )
    private const val GRAPH_TTL_MS = 30 * 60 * 1000L

    private lateinit var sp: SharedPreferences
    private val lock = Mutex()
    private var bearer: String? = null
    private var bearerExpiresAt = 0L
    private var scopeIndex = 0
    private var graph: List<HomeDevice> = emptyList()
    private var graphAt = 0L

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("google_home", Context.MODE_PRIVATE)
        ui.value = HomeUi(signedIn = sp.getString("master", null) != null)
    }

    // ---- Auth ------------------------------------------------------------------------------------

    /** Exchanges an EmbeddedSetup oauth_token for a master token. Blocking. */
    fun signIn(oauthToken: String) {
        val androidId = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val r = gpsPost(
            mapOf(
                "accountType" to "HOSTED_OR_GOOGLE", "Email" to "owner@example.com", "has_permission" to "1",
                "add_account" to "1", "ACCESS_TOKEN" to "1", "Token" to oauthToken, "service" to "ac2dm",
                "source" to "android", "androidId" to androidId, "device_country" to "it", "operatorCountry" to "it",
                "lang" to "it", "sdk_version" to "17", "google_play_services_version" to "240913000",
                "client_sig" to GMS_SIG, "callerSig" to GMS_SIG, "droidguard_results" to "dummy123",
            )
        )
        val master = r["Token"] ?: error("Scambio token fallito: ${r["Error"] ?: "nessun Token"}")
        sp.edit().putString("master", master).putString("android_id", androidId).apply()
        bearer = null
        graphAt = 0
        ui.value = HomeUi(signedIn = true)
    }

    fun signOut() {
        sp.edit().clear().apply()
        bearer = null
        graph = emptyList()
        ui.value = HomeUi(false)
    }

    private fun mintBearer(): String {
        val now = System.currentTimeMillis()
        bearer?.let { if (now < bearerExpiresAt) return it }
        val master = sp.getString("master", null) ?: error("Non collegato")
        val androidId = sp.getString("android_id", "0123456789abcdef")!!
        var last: String? = null
        for (offset in SCOPES.indices) {
            val i = (scopeIndex + offset) % SCOPES.size
            val r = gpsPost(
                mapOf(
                    "accountType" to "HOSTED_OR_GOOGLE", "Email" to "owner@example.com", "has_permission" to "1",
                    "EncryptedPasswd" to master, "service" to SCOPES[i], "source" to "android",
                    "androidId" to androidId, "app" to HOME_APP, "client_sig" to HOME_SIG,
                    "device_country" to "it", "operatorCountry" to "it", "lang" to "it", "sdk_version" to "17",
                    "google_play_services_version" to "240913000",
                )
            )
            val auth = r["Auth"]
            if (auth != null) {
                scopeIndex = i
                val expiry = r["Expiry"]?.toLongOrNull()?.times(1000) ?: (now + 55 * 60 * 1000)
                bearer = auth
                bearerExpiresAt = minOf(expiry, now + 55 * 60 * 1000) - 60_000
                return auth
            }
            last = r["Error"]
        }
        error("Token Google rifiutato: ${last ?: "?"}")
    }

    private fun gpsPost(form: Map<String, String>): Map<String, String> {
        val body = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val conn = (URL(AUTH_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "GoogleAuth/1.4")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return text.lines().mapNotNull { l ->
                val i = l.indexOf('=')
                if (i > 0) l.substring(0, i).trim() to l.substring(i + 1).trim() else null
            }.toMap()
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---- RPC -------------------------------------------------------------------------------------

    private fun rpc(service: String, method: String, body: JSONArray): Any? {
        fun post(token: String): Pair<Int, String> {
            val conn = (URL("$FOYER$service/$method").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-User-Agent", "grpc-web-javascript/0.1")
                setRequestProperty("Content-Type", "application/json+protobuf")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                return code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } finally {
                conn.disconnect()
            }
        }
        var token = mintBearer()
        var (code, text) = post(token)
        if (code == 401 || code == 403) {
            bearer = null
            scopeIndex = (scopeIndex + 1) % SCOPES.size
            token = mintBearer()
            val retry = post(token)
            code = retry.first
            text = retry.second
        }
        if (code !in 200..299) error("Google Home $method: HTTP $code")
        val trimmed = text.trimStart().let { if (it.startsWith(")]}'")) it.substring(4).trimStart() else it }
        return org.json.JSONTokener(trimmed).nextValue()
    }

    private fun ja(vararg items: Any?) = JSONArray().apply { items.forEach { put(it ?: JSONObject.NULL) } }
    private fun Any?.arr(i: Int): JSONArray? = (this as? JSONArray)?.opt(i) as? JSONArray
    private fun Any?.str(i: Int): String? = (this as? JSONArray)?.opt(i) as? String
    private fun Any?.num(i: Int): Double? = ((this as? JSONArray)?.opt(i) as? Number)?.toDouble()
    private fun Any?.bool(i: Int): Boolean {
        val v = (this as? JSONArray)?.opt(i)
        return v == true || (v as? Number)?.toInt() == 1
    }

    private fun loadGraph(): List<HomeDevice> {
        val json = rpc("StructuresService", "GetHomeGraph", JSONArray())
        val homes = json.arr(1) ?: return emptyList()
        val out = mutableListOf<HomeDevice>()
        for (h in 0 until homes.length()) {
            val home = homes.opt(h)
            val roomOf = mutableMapOf<String, String>()
            home.arr(5)?.let { rooms ->
                for (r in 0 until rooms.length()) {
                    val room = rooms.opt(r)
                    val name = room.str(2) ?: continue
                    room.arr(4)?.let { members ->
                        for (m in 0 until members.length()) members.opt(m).arr(0).str(0)?.let { roomOf[it] = name }
                    }
                }
            }
            val devs = home.arr(6) ?: continue
            for (d in 0 until devs.length()) {
                val rec = devs.opt(d)
                val key = rec.arr(0) ?: continue
                val id = key.str(0) ?: continue
                val pair = key.arr(1)
                val type = (rec.arr(20).str(0)?.takeIf { it.isNotBlank() } ?: rec.str(5) ?: "").uppercase()
                val traits = rec.arr(6)?.let { t -> (0 until t.length()).mapNotNull { t.opt(it) as? String } } ?: emptyList()
                val suffixes = traits.map { it.substringAfterLast('.').lowercase() }
                val thermostat = "temperaturesetting" in suffixes
                val onOff = "onoff" in suffixes
                if (!thermostat && !onOff) continue
                if (!thermostat && listOf("SPEAKER", "TV", "DISPLAY", "REMOTE", "STREAMING").any { it in type }) continue
                out += HomeDevice(
                    id = id, name = rec.str(3) ?: "?", room = roomOf[id], type = type,
                    agentId = pair.str(0) ?: "", partnerId = pair.str(1) ?: "",
                    canOnOff = onOff, isThermostat = thermostat,
                )
            }
        }
        return out.sortedWith(compareBy({ !it.isThermostat }, { it.room ?: "" }, { it.name }))
    }

    private fun loadStates(ids: List<String>): Map<String, DevState> {
        if (ids.isEmpty()) return emptyMap()
        val json = rpc("HomeControlService", "GetTraits", ja(JSONArray(ids.map { ja(it) })))
        return parseStates(json)
    }

    private fun parseStates(json: Any?): Map<String, DevState> {
        val results = json.arr(0) ?: return emptyMap()
        val out = mutableMapOf<String, DevState>()
        for (i in 0 until results.length()) {
            val r = results.opt(i)
            val id = r.arr(0).str(0) ?: continue
            var online = false
            var on: Boolean? = null
            var bright: Int? = null
            var mode: String? = null
            var setpoint: Double? = null
            var ambient: Double? = null
            val traits = r.arr(1)
            if (traits != null) for (t in 0 until traits.length()) {
                val e = traits.opt(t)
                val fields = mutableMapOf<String, JSONArray?>()
                e.arr(1)?.let { f -> for (k in 0 until f.length()) f.opt(k).str(0)?.let { fields[it] = f.opt(k).arr(1) } }
                when (e.str(0)) {
                    "deviceStatus" -> if ("online" in fields) online = fields["online"].bool(3)
                    "onOff" -> if ("onOff" in fields) on = fields["onOff"].bool(3)
                    "brightness" -> bright = fields["brightness"].num(1)?.toInt()
                    "temperatureSetting" -> {
                        mode = fields["mode"].str(2)
                        setpoint = fields["thermostatTemperatureSetpointC"].num(1)
                        ambient = fields["ambientAirTemperatureC"].num(1)
                    }
                }
            }
            out[id] = DevState(online, on, bright, mode, setpoint, ambient)
        }
        return out
    }

    // ---- Public API (suspending, IO) ---------------------------------------------------------------

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!ui.value.signedIn) return@withContext
        lock.withLock {
            try {
                val now = System.currentTimeMillis()
                if (graph.isEmpty() || now - graphAt > GRAPH_TTL_MS) {
                    graph = loadGraph()
                    graphAt = now
                }
                val states = loadStates(graph.map { it.id })
                ui.value = HomeUi(true, graph.map { it.copy(state = states[it.id]) })
            } catch (e: Exception) {
                ui.value = ui.value.copy(error = e.message)
            }
        }
    }

    suspend fun toggle(device: HomeDevice) = withContext(Dispatchers.IO) {
        val target = device.state?.on != true
        // Optimistic update
        ui.value = ui.value.copy(devices = ui.value.devices.map {
            if (it.id == device.id) it.copy(state = (it.state ?: DevState(true, null, null, null, null, null)).copy(on = target)) else it
        })
        try {
            val field = ja("onOff", ja(null, null, null, if (target) 1 else 0))
            val trait = ja("onOff", ja(field))
            val command = ja(ja(device.id, ja(device.agentId, device.partnerId)), ja(trait))
            rpc("HomeControlService", "UpdateTraits", ja(ja(command)))
        } catch (e: Exception) {
            ui.value = ui.value.copy(error = e.message)
        }
        refresh()
    }
}

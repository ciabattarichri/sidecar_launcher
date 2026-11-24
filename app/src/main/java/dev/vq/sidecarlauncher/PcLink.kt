package dev.vq.sidecarlauncher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pull link to the PC (`phonedash` pull server, port 8766): photos from the PC slideshow folder and
 * the DMS/matugen palette. `phonedash pair-launcher` drops pc.json into the app's external files dir.
 */
object PcLink {
    private const val TAG = "SidecarPcLink"
    val photosVersion = MutableStateFlow(0)
    private var lastPhotoSync = 0L

    fun photoDir(ctx: Context) = File(ctx.filesDir, "pcphotos").apply { mkdirs() }
    fun photos(ctx: Context): List<File> = photoDir(ctx).listFiles { f -> f.name.endsWith(".jpg") }?.toList().orEmpty()

    /** Pairing waiting for the user's confirmation on screen (JSON from `phonedash pair-launcher`). */
    val pendingPairing = MutableStateFlow<JSONObject?>(null)

    /**
     * Pairing from `phonedash pair-launcher`: base64 JSON {host, port, token, billable_*} as an intent
     * extra. Never applied silently: the user confirms it on screen, so no app can redirect the link.
     */
    fun importPairing(intent: android.content.Intent?) {
        val b64 = intent?.getStringExtra("pc_pair_b64") ?: return
        intent.removeExtra("pc_pair_b64")
        runCatching { pendingPairing.value = JSONObject(String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))) }
            .onFailure { android.util.Log.w(TAG, "bad pairing payload: $it") }
    }

    fun confirmPairing(accept: Boolean) {
        val j = pendingPairing.value ?: return
        pendingPairing.value = null
        if (!accept) return
        Settings.update {
            it.copy(
                pcHost = j.getString("host"), pcPort = j.optInt("port", 8766), pcToken = j.getString("token"),
                billableUrl = j.optString("billable_url").ifBlank { it.billableUrl },
                billableToken = j.optString("billable_token").ifBlank { it.billableToken },
            )
        }
        lastPhotoSync = 0L
        photosVersion.value++
    }

    @Volatile private var goodHost: String? = null

    /** pcHost may list several addresses ("tailnet-name,192.168.x.y"): the last one that worked is tried first. */
    private fun get(path: String): ByteArray? {
        val c = Settings.state.value
        if (c.pcHost.isBlank() || c.pcToken.isBlank()) return null
        val hosts = c.pcHost.split(",").map { it.trim() }.filter { it.isNotEmpty() }.sortedBy { if (it == goodHost) 0 else 1 }
        for (h in hosts) {
            val conn = runCatching { URL("http://$h:${c.pcPort}$path").openConnection() as HttpURLConnection }.getOrNull() ?: continue
            conn.connectTimeout = 3_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer ${c.pcToken}")
            try {
                val code = conn.responseCode
                goodHost = h
                return if (code == 200) conn.inputStream.use { it.readBytes() } else null
            } catch (e: Exception) {
                android.util.Log.w(TAG, "PC $h unreachable: $e") // try the next address
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun unmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        // isActiveNetworkMetered looks through VPNs (Tailscale) to the real network underneath.
        return runCatching { !cm.isActiveNetworkMetered }.getOrDefault(false)
    }

    /** Mirrors the PC photo list (on Wi-Fi only, at most every 30 min). */
    suspend fun syncPhotos(ctx: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val c = Settings.state.value
        if (!force && (System.currentTimeMillis() - lastPhotoSync < 30 * 60_000 || !unmetered(ctx))) return@withContext
        val list = get("/photos")?.let { JSONObject(String(it)).getJSONArray("photos") }
        if (list == null) { android.util.Log.w(TAG, "photo list: no answer from ${c.pcHost}"); return@withContext }
        lastPhotoSync = System.currentTimeMillis()
        val wanted = (0 until list.length()).map { list.getString(it) }.toSet()
        val dir = photoDir(ctx)
        var changed = false
        dir.listFiles()?.filter { it.name !in wanted }?.forEach { it.delete(); changed = true }
        for (name in wanted) {
            val out = File(dir, name)
            if (out.exists()) continue
            val bytes = get("/photos/$name?max=1440") ?: continue
            File(dir, "$name.part").apply { writeBytes(bytes); renameTo(out) }
            changed = true
        }
        if (changed) photosVersion.value++
    }

    /** Fetches the PC palette into the same file desk_sidecar uses; returns it or null. */
    suspend fun fetchPalette(ctx: Context): Palette? = withContext(Dispatchers.IO) {
        val bytes = get("/colors")
        val f = Palette.file(ctx) ?: return@withContext null
        if (bytes != null) { f.parentFile?.mkdirs(); f.writeBytes(bytes) }
        if (f.isFile) Palette.load(ctx) else null
    }
}

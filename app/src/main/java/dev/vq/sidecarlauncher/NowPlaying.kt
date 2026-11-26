package dev.vq.sidecarlauncher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow


data class NowPlaying(
    val title: String,
    /** Label of the app that owns the session, e.g. "Spotify". */
    val appName: String,
    val artist: String,
    val album: String,
    val art: Bitmap?,
    val playing: Boolean,
    val durationMs: Long,
    /** Position at [positionAtElapsed] (SystemClock.elapsedRealtime). */
    val positionMs: Long,
    val positionAtElapsed: Long,
    val speed: Float,
    /** Wall clock time the playback was paused, 0 if playing. */
    val pausedAtMs: Long,
) {
    fun currentPositionMs(): Long {
        if (!playing) return positionMs
        val elapsed = SystemClock.elapsedRealtime() - positionAtElapsed
        return (positionMs + elapsed * speed).toLong().coerceIn(0, durationMs.coerceAtLeast(0))
    }
}

/** Follows the active media session (Spotify or any other player). */
class NowPlayingSource(private val context: Context) {
    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state

    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, PhoneListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controllers: List<MediaController> = emptyList()
    private var current: MediaController? = null
    private var pausedAt = 0L
    private var started = false
    private val labels = mutableMapOf<String, String>()

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() = bind(msm.getActiveSessions(listenerComponent))
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        bind(list.orEmpty())
    }

    fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun start() {
        if (started || !hasAccess()) return
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            bind(msm.getActiveSessions(listenerComponent))
            started = true
        } catch (_: SecurityException) {
            // Access revoked between check and call.
        }
    }

    fun stop() {
        if (!started) return
        msm.removeOnActiveSessionsChangedListener(sessionsListener)
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = emptyList()
        started = false
    }

    fun togglePlayPause() {
        val c = current ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() {
        current?.transportControls?.skipToNext()
    }

    fun previous() {
        current?.transportControls?.skipToPrevious()
    }

    private fun bind(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list
        list.forEach { it.registerCallback(callback, handler) }
        refresh()
    }

    private fun refresh() {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val c = playing
            ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
        current = c
        val meta = c?.metadata
        val ps = c?.playbackState
        if (c == null || meta == null || ps == null) {
            _state.value = null
            return
        }
        val isPlaying = ps.state == PlaybackState.STATE_PLAYING
        pausedAt = when {
            isPlaying -> 0L
            pausedAt == 0L -> System.currentTimeMillis()
            else -> pausedAt
        }
        val pkg = c.packageName
        LastMusic.remember(context, pkg)
        val appName = labels.getOrPut(pkg) {
            runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
        }
        _state.value = NowPlaying(
            appName = appName,
            title = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = isPlaying,
            durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = ps.position,
            positionAtElapsed = ps.lastPositionUpdateTime,
            speed = if (ps.playbackSpeed > 0f) ps.playbackSpeed else 1f,
            pausedAtMs = pausedAt,
        )
    }
}

/** The last app that had a media session, so the empty player can reopen it. */
object LastMusic {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private var cached: String? = null

    fun remember(ctx: Context, pkg: String) {
        if (pkg == cached) return
        cached = pkg
        prefs(ctx).edit().putString("lastMusicPkg", pkg).apply()
    }

    private fun pkg(ctx: Context) = prefs(ctx).getString("lastMusicPkg", null)
        ?.takeIf { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    fun label(ctx: Context): String? = pkg(ctx)?.let { p ->
        runCatching { ctx.packageManager.run { getApplicationLabel(getApplicationInfo(p, 0)).toString() } }.getOrNull()
    }

    fun open(ctx: Context) {
        val i = pkg(ctx)?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
            ?: android.content.Intent.makeMainSelectorActivity(android.content.Intent.ACTION_MAIN, android.content.Intent.CATEGORY_APP_MUSIC)
        runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

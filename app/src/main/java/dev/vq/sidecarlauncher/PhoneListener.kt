package dev.vq.sidecarlauncher

import android.app.Notification
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow

data class PhoneNotification(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    val timeMs: Long,
    val intent: PendingIntent?,
    val clearable: Boolean,
)

/**
 * Grants access to media sessions (now playing) and mirrors the phone's notifications into
 * [notifications]. Ongoing/foreground/group-summary/media notifications are skipped.
 */
class PhoneListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        refresh()
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()

    private fun refresh() {
        val pm = packageManager
        val labels = mutableMapOf<String, String>()
        notifications.value = runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { s ->
                val n = s.notification
                !s.isOngoing && n.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                    n.category != Notification.CATEGORY_TRANSPORT && s.packageName != packageName
            }
            .sortedByDescending { it.postTime }
            .take(30)
            .map { s ->
                val e = s.notification.extras
                PhoneNotification(
                    key = s.key,
                    app = labels.getOrPut(s.packageName) {
                        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, PackageManager.ApplicationInfoFlags.of(0))).toString() }
                            .getOrDefault(s.packageName)
                    },
                    title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                    text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
                    timeMs = s.postTime,
                    intent = s.notification.contentIntent,
                    clearable = s.isClearable,
                )
            }
    }

    companion object {
        val notifications = MutableStateFlow<List<PhoneNotification>>(emptyList())
        @Volatile var instance: PhoneListener? = null

        fun dismiss(key: String) = runCatching { instance?.cancelNotification(key) }
    }
}

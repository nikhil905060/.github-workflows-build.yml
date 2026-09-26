package com.example.voiceterminal

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationMonitor : NotificationListenerService() {

    companion object {
        val notificationLogs = mutableListOf<String>()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.notification?.extras?.let { extras ->
            val title = extras.getString("android.title") ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            val app = sbn.packageName ?: "App"

            if (title.isNotEmpty() || text.isNotEmpty()) {
                val entry = "[$app] $title: $text"
                synchronized(notificationLogs) {
                    if (notificationLogs.size > 20) notificationLogs.removeAt(0)
                    notificationLogs.add(entry)
                }
            }
        }
    }
}

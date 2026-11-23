package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

class NotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeCount = try { activeNotifications?.size ?: 0 } catch (_: Exception) { 0 }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        activeCount = try { activeNotifications?.size ?: 0 } catch (_: Exception) { activeCount }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        activeCount = try { activeNotifications?.size ?: 0 } catch (_: Exception) { 0 }
    }

    companion object {
        @Volatile
        var activeCount: Int = 0
            private set

        const val CHANNEL_FLOATING = "ai_controller_floating"
        const val CHANNEL_ALERTS = "ai_controller_alerts"

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val floatingChannel = NotificationChannel(
                    CHANNEL_FLOATING,
                    context.getString(R.string.floating_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.floating_channel_desc)
                }
                val alertsChannel = NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.alerts_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.alerts_channel_desc)
                }
                nm.createNotificationChannel(floatingChannel)
                nm.createNotificationChannel(alertsChannel)
            }
        }

        fun postSystemAlert(context: Context, title: String, message: String, id: Int = 2001) {
            ensureChannels(context)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, notification)
        }
    }
}

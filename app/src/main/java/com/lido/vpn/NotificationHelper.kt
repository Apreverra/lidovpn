package com.lido.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap

object NotificationHelper {
    private const val CHANNEL_ID_PROGRESS = "lido_vpn_progress"
    private const val CHANNEL_NAME_PROGRESS = "Progress Notifications"
    
    const val NOTIFICATION_ID_SERVER_CHECK = 1001
    const val NOTIFICATION_ID_BYEDPI = 1002
    const val NOTIFICATION_ID_SERVICE_CHECK = 1003

    // Throttling to prevent system UI lag
    private val lastUpdateTimes = ConcurrentHashMap<Int, Long>()
    private const val MIN_UPDATE_INTERVAL = 400L // ms

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val progressChannel = NotificationChannel(
                CHANNEL_ID_PROGRESS,
                CHANNEL_NAME_PROGRESS,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress for VPN checks and optimizations"
                setShowBadge(false)
            }
            
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(progressChannel)
        }
    }

    fun showProgressNotification(
        context: Context, 
        title: String, 
        message: String, 
        progress: Int, 
        max: Int, 
        notificationId: Int = NOTIFICATION_ID_SERVER_CHECK,
        largeIconRes: Int? = null,
        subText: String? = null,
        forceText: String? = null
    ) {
        val now = System.currentTimeMillis()
        val lastUpdate = lastUpdateTimes[notificationId] ?: 0L
        
        // Skip update if too frequent, unless it's the start or finish
        if (progress > 0 && progress < max && now - lastUpdate < MIN_UPDATE_INTERVAL) {
            return
        }
        lastUpdateTimes[notificationId] = now

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_PROGRESS)
            .setSmallIcon(R.drawable.ic_vpn_shield)
            .setContentTitle(title)
            .setContentText(message)
            .setSubText(subText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(max, progress, false)
            .setContentIntent(pendingIntent)
            .setSilent(true) // Don't buzz on every progress step

        if (largeIconRes != null) {
            try {
                val bitmap = BitmapFactory.decodeResource(context.resources, largeIconRes)
                builder.setLargeIcon(bitmap)
            } catch (_: Exception) {}
        }

        if (forceText != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(forceText))
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (_: SecurityException) {}
    }

    fun dismissProgressNotification(context: Context, notificationId: Int = NOTIFICATION_ID_SERVER_CHECK) {
        lastUpdateTimes.remove(notificationId)
        NotificationManagerCompat.from(context).cancel(notificationId)
    }
}

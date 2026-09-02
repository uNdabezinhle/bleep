package za.bleep.personal.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** T10: wake-up only. No name, no preview, no body. */
object Notify {
    private const val CH = "bleep-wake"

    fun ensure(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CH, "Bleep", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Mailbox wake-up. Bodies stay on the device."
                    setShowBadge(false)
                },
            )
        }
    }

    fun wake(ctx: Context) {
        ensure(ctx)
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Bleep")
            .setContentText(" ")
            .setSilent(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(1, n)
        } catch (_: SecurityException) {
            /* POST_NOTIFICATIONS denied */
        }
    }
}

package za.bleep.personal.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import za.bleep.personal.protocol.Session
import za.bleep.personal.protocol.ringNotice

/** T10: wake-up only. No name, no preview, no body. */
object Notify {
    private const val MAIL = "bleep-mail"
    private const val RING = "bleep-ring"
    private const val MAIL_ID = 1
    private const val RING_ID = 3
    private var mailTone: android.media.Ringtone? = null

    fun ensure(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(MAIL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(MAIL, "Bleep", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Mailbox wake-up. Bodies stay on the device."
                        setShowBadge(false)
                        val sound = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        setSound(Settings.System.DEFAULT_NOTIFICATION_URI, sound)
                    },
                )
            }
        }
    }

    fun wake(ctx: Context) {
        ensure(ctx)
        val n = NotificationCompat.Builder(ctx, MAIL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Bleep")
            .setContentText(" ")
            .setSilent(false)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            val mgr = NotificationManagerCompat.from(ctx)
            mgr.cancel(MAIL_ID)
            mgr.notify(MAIL_ID, n)
        } catch (_: SecurityException) {
            /* POST_NOTIFICATIONS denied */
        }
    }

    /** Default notification sound while the person is already in Bleep. */
    fun playMail(ctx: Context) {
        try {
            mailTone?.stop()
            val tone = RingtoneManager.getRingtone(ctx, Settings.System.DEFAULT_NOTIFICATION_URI) ?: return
            mailTone = tone
            tone.play()
        } catch (_: Exception) {
        }
    }

    /** Blank ring. Title and text come from ringNotice(), which has no peer field. */
    fun ring(ctx: Context) {
        val copy = ringNotice()
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(RING) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(RING, "Bleep", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Call ring. No name and no message."
                        setShowBadge(false)
                        val sound = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        setSound(Settings.System.DEFAULT_RINGTONE_URI, sound)
                    },
                )
            }
        }
        val open = PendingIntent.getActivity(
            ctx,
            1,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, RING)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(RING_ID, n)
        } catch (_: SecurityException) {
        }
    }

    fun clearRing(ctx: Context) {
        try {
            NotificationManagerCompat.from(ctx).cancel(RING_ID)
        } catch (_: SecurityException) {
        }
    }

    fun afterPoll(ctx: Context, session: Session) {
        if (session.quietNow()) return
        if (session.ringOffer && !session.uiResumed) {
            ring(ctx)
            return
        }
        if (!session.ringOffer) clearRing(ctx)
        if (session.ringOffer || session.mailAlert <= 0 || !session.alertSound) return
        if (session.uiResumed) playMail(ctx) else wake(ctx)
    }
}

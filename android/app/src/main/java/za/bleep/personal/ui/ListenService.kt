package za.bleep.personal.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import za.bleep.personal.BleepApp

/** Opt-in listener. The notice has no message text. A force-stop ends it. */
class ListenService : Service() {
    private var running = false
    private var worker: Thread? = null
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val note = listeningNote()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTE_ID, note)
        }
        if (running) return START_STICKY
        running = true
        val svc = this
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bleep:listen").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
        worker = Thread {
            while (running) {
                val app = application as BleepApp
                val session = app.live
                if (session == null || !session.ready || !session.listenBackground) {
                    stopSelf()
                    break
                }
                try {
                    session.poll()
                    session.pushHistory()
                    Notify.afterPoll(svc, session)
                    if (app.livePin.length >= 4) app.vault.save(app.livePin, session.snapshot())
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(20_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        lock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun listeningNote(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CH, "Bleep listening", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps the mailbox awake. Message text stays on this phone."
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Bleep is listening")
            .setContentText("Message text stays on this phone.")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CH = "bleep-listen"
        private const val NOTE_ID = 7

        fun start(ctx: Context) {
            val intent = Intent(ctx, ListenService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ListenService::class.java))
        }
    }
}

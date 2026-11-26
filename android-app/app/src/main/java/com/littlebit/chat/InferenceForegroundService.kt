package com.littlebit.chat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps token generation alive while the app is in the background.
 *
 * Android 14-16 kills the process with
 * `ForegroundServiceDidNotStartInTimeException` when a service started through
 * `startForegroundService()` has not called `startForeground()` within a few
 * seconds. Every entry point therefore promotes the service to the foreground
 * first: in [onCreate] and again at the very top of [onStartCommand], before any
 * stop/action handling. The service is only ever stopped from inside itself
 * (after the foreground promotion) or through an [ForegroundServicePolicy.ACTION_STOP]
 * intent, never by an external `context.stopService()`.
 */
class InferenceForegroundService : Service() {
    companion object {
        private const val CHANNEL = "local_inference"
        private const val ID = 11
        @Volatile private var operation: (() -> Unit)? = null
        @Volatile private var serviceStarted = false
        private var revision = 0

        /** Registers the cancel action and (when allowed) shows the notification. */
        @Synchronized fun begin(context: Context, stage: String, cancel: () -> Unit) {
            revision++
            operation = cancel
            val granted = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!ForegroundServicePolicy.shouldStartForegroundService(Build.VERSION.SDK_INT, granted)) return
            val request = Intent(context, InferenceForegroundService::class.java)
                .putExtra("stage", stage).putExtra("revision", revision)
            try {
                serviceStarted = true
                ContextCompat.startForegroundService(context, request)
            } catch (e: RuntimeException) {
                serviceStarted = false
                Log.w("LittleBitChat", "Foreground notification unavailable", e)
            }
        }

        private var lastUpdate = 0L
        @Synchronized fun speed(context: Context, tps: Double) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (operation == null || !serviceStarted || now - lastUpdate < 2000) return
            lastUpdate = now
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val existing = manager.activeNotifications.firstOrNull { it.id == ID } ?: return
            runCatching { manager.notify(ID, Notification.Builder.recoverBuilder(context, existing.notification)
                .setContentText("Generating · %.1f t/s".format(tps)).build()) }
        }

        /**
         * Ends the operation. Must not call `context.stopService()`: a
         * `startForegroundService()` call may still be pending, and stopping it
         * from outside skips `startForeground()` and crashes the process.
         */
        @Synchronized fun finish(context: Context) {
            operation = null
            if (!serviceStarted) return
            serviceStarted = false
            val stop = Intent(context, InferenceForegroundService::class.java)
                .setAction(ForegroundServicePolicy.ACTION_STOP)
                .putExtra("revision", revision)
            runCatching { context.startService(stop) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        enterForeground("Preparing on-device inference…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote before any early return or stop check.
        enterForeground(intent?.getStringExtra("stage") ?: "On-device inference")
        val version = intent?.getIntExtra("revision", -1) ?: -1
        val action = intent?.action
        if (ForegroundServicePolicy.shouldCancelOperation(action, version == revision)) operation?.invoke()
        // Only the stop that belongs to the latest start epoch may end the
        // service: a stale ACTION_STOP from a rapid start→stop→start burst must
        // not tear down the newer foreground notification.
        if (ForegroundServicePolicy.shouldStopSelf(action, operation != null, version == revision)) {
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    /** Android 15+ foreground-service watchdog: never linger half-started. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    override fun onTimeout(startId: Int) {
        if (Build.VERSION.SDK_INT >= 35) return // the two-argument overload handles it
        stopSelf(startId)
    }

    private fun enterForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL, "On-device inference", NotificationManager.IMPORTANCE_LOW),
        )
        runCatching {
            ServiceCompat.startForeground(
                this,
                ID,
                notification(text),
                ForegroundServicePolicy.foregroundServiceType(Build.VERSION.SDK_INT),
            )
        }.onFailure { Log.w("LittleBitChat", "Could not enter the foreground", it) }
    }

    private fun notification(text: String): Notification {
        val version = revision
        val stop = Intent(this, InferenceForegroundService::class.java)
            .setAction(ForegroundServicePolicy.ACTION_STOP)
            .putExtra("revision", version)
        val action = PendingIntent.getService(this, version, stop, PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("LittleBit Chat")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", action).build())
            .build()
    }
}

package io.github.vdurante.filesplitter.scan

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
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.vdurante.filesplitter.MainActivity
import io.github.vdurante.filesplitter.FileSplitterApp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process (and CPU, via a wake lock) alive while [ScanEngine] is busy, so uploads continue
 * with the app in the background or the screen off. Shows progress in a notification and stops
 * itself once the engine is idle.
 */
class ScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    private val engine get() = (application as FileSplitterApp).engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_NOT_STICKY
        started = true
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FileSplitter:scan")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }

        scope.launch {
            var idleChecks = 0
            while (true) {
                // Two idle checks in a row: the queue goes briefly idle between runs.
                idleChecks = if (engine.busy.value) 0 else idleChecks + 1
                if (idleChecks >= 2) {
                    notifyFinished()
                    stopSelf()
                    return@launch
                }
                NotificationManagerCompat.from(this@ScanService).let {
                    if (it.areNotificationsEnabled()) it.notify(NOTIFICATION_ID, buildNotification())
                }
                // Notification updates are rate-limited by the system anyway.
                delay(1_000)
            }
        }
        // If the process is killed, the saved state lets the user resume; don't restart on our own.
        return START_NOT_STICKY
    }

    /** Android 15+ caps dataSync services at 6 hours per day; pause cleanly when that hits. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        engine.pause("Android stopped the background work after its time limit. Tap Resume to continue.")
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Scan progress", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL_ID, "Scan results", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** One-off summary once the queue stops: finished, or paused on a rejected API key. */
    private fun notifyFinished() {
        val session = engine.session.value ?: return
        if (session.phase != Phase.Ready) return
        val items = session.items
        val completed = items.mapNotNull { it.status as? ItemStatus.Completed }
        val detected = completed.count { (it.stats?.malicious ?: 0) + (it.stats?.suspicious ?: 0) > 0 }
        val failed = items.count { it.status is ItemStatus.Failed }
        val title = when {
            session.apiKeyRejected -> "Paused: API key rejected"
            items.any { it.status == ItemStatus.Queued } -> "Scan paused"
            else -> "Scan finished"
        }
        val text = listOfNotNull(
            "${completed.size - detected} clean",
            "$detected with detections".takeIf { detected > 0 },
            "$failed failed".takeIf { failed > 0 },
        ).joinToString(" · ")
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("$title: ${session.fileName}")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        NotificationManagerCompat.from(this).let {
            if (it.areNotificationsEnabled()) it.notify(RESULT_NOTIFICATION_ID, notification)
        }
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(): android.app.Notification {
        val session = engine.session.value
        val items = session?.items.orEmpty()
        val done = items.count { it.status is ItemStatus.Completed }
        val active = items.firstOrNull {
            it.status !is ItemStatus.Queued && it.status !is ItemStatus.Completed && it.status !is ItemStatus.Failed
        }
        val text = when {
            session == null -> "Preparing…"
            session.phase is Phase.Preparing -> session.phase.message
            active != null -> "${active.displayName}: ${shortStatus(active.status)} · $done of ${items.size} done"
            else -> "$done of ${items.size} done"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Scanning ${session?.fileName ?: "APK"}")
            .setContentText(text)
            .setProgress(items.size.coerceAtLeast(1), done, items.isEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun shortStatus(status: ItemStatus) = when (status) {
        is ItemStatus.Packing -> "building bundle ${(status.progress * 100).toInt()}%"
        is ItemStatus.Hashing -> "hashing ${(status.progress * 100).toInt()}%"
        ItemStatus.CheckingHash -> "checking hash"
        is ItemStatus.Uploading -> "uploading ${(status.progress * 100).toInt()}%"
        is ItemStatus.Analyzing -> "analyzing"
        is ItemStatus.Waiting -> status.reason.lowercase()
        else -> ""
    }

    companion object {
        private const val CHANNEL_ID = "scan"
        private const val NOTIFICATION_ID = 1
        private const val RESULT_CHANNEL_ID = "results"
        private const val RESULT_NOTIFICATION_ID = 2
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ScanService::class.java))
            } catch (e: IllegalStateException) {
                // Starting from the background isn't allowed; work still runs while the app is open.
                Log.w("ScanService", "Couldn't start foreground service", e)
            }
        }
    }
}

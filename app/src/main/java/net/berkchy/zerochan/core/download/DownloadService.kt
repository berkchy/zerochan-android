package net.berkchy.zerochan.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.berkchy.zerochan.MainActivity
import net.berkchy.zerochan.ZerochanApp
import net.berkchy.zerochan.core.data.DownloadEntity
import net.berkchy.zerochan.core.data.DownloadStatus
import net.berkchy.zerochan.core.net.AppLog
import java.util.Locale

/**
 * Foreground service that keeps the queue alive while the app is in the background and hosts
 * the ongoing download notification.
 */
class DownloadService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stopping = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(NOTIFICATION_SUMMARY, summaryNotification(0, 0, 0))
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> intent.getLongExtra(EXTRA_ID, -1L).takeIf { it > 0 }?.let {
                scope.launch { center()?.cancel(it) }
            }
            ACTION_CLEAR -> scope.launch { center()?.dismissFinished() }
        }
        (application as? ZerochanApp)?.let { app ->
            app.downloadCenter.pump()
            app.downloadCenter.startTicker(app.serviceScope)
        }
        return android.app.app.Service.START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun center(): DownloadCenter? = (application as? ZerochanApp)?.downloadCenter

    private fun observe() {
        val app = application as? ZerochanApp ?: return
        scope.launch {
            app.database.libraryDao().downloads()
                .combine(app.downloadCenter.tick) { rows, _ -> rows }
                .collect { rows -> render(rows) }
        }
        app.downloadCenter.pump()
    }

    private fun render(rows: List<DownloadEntity>) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return

        val active = rows.filter { !it.isFinished }
        val recentlyDone = rows.filter { it.state == DownloadStatus.DONE }.take(1)

        active.forEach { manager.notify(idOf(it.id), progressNotification(it)) }
        rows.filter { it.isFinished }.forEach { manager.cancel(idOf(it.id)) }

        val total = active.size + recentlyDone.size
        manager.notify(NOTIFICATION_SUMMARY, summaryNotification(active.size, total, active.sumOf { it.totalBytes }))

        if (active.isEmpty()) {
            stopForegroundIfIdle()
        }
    }

    private fun stopForegroundIfIdle() {
        if (stopping) return
        stopping = true
        scope.launch {
            kotlinx.coroutines.delay(1500)
            stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun summaryNotification(running: Int, total: Int, bytes: Long): Notification =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle(
                when {
                    running > 0 -> "Downloading $running image${if (running == 1) "" else "s"}"
                    total > 0 -> "Downloads complete"
                    else -> "Downloads"
                }
            )
            .setContentText(
                when {
                    running > 1 -> "$running running · ${humanBytes(bytes)} queued"
                    running == 1 -> "1 running"
                    else -> "No active downloads"
                }
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent())
            .build()

    private fun progressNotification(item: DownloadEntity): Notification {
        val speed = if (item.bytesPerSecond > 0) "${humanBytes(item.bytesPerSecond)}/s" else null
        val eta = item.etaMillis.takeIf { it > 0 && item.bytesPerSecond > 0 }?.let(::humanDuration)
        val parts = buildList {
            if (item.totalBytes > 0) {
                add("${humanBytes(item.downloadedBytes)} / ${humanBytes(item.totalBytes)}")
            }
            speed?.let(::add)
            eta?.let { add("$eta left") }
        }

        val title = item.tag.ifBlank { item.fileName }.ifBlank { "Post ${item.id}" }

        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle(title)
            .setContentText(
                when {
                    item.state == DownloadStatus.QUEUED && parts.isEmpty() -> "Queued"
                    item.state == DownloadStatus.QUEUED -> "Queued · ${parts.joinToString(" · ")}"
                    parts.isEmpty() -> "${item.percent}%"
                    else -> "${item.percent}% · ${parts.joinToString(" · ")}"
                }
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(
                if (item.totalBytes > 0) 100 else 0,
                item.percent,
                item.totalBytes <= 0,
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent())
            .addAction(
                0,
                "Cancel",
                PendingIntent.getService(
                    this,
                    item.id.toInt(),
                    Intent(this, DownloadService::class.java).apply {
                        action = ACTION_CANCEL
                        putExtra(EXTRA_ID, item.id)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, "library")
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress of image downloads"
                setShowBadge(true)
            }
        )
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { context.startForegroundService(intent) }
                    .onFailure { context.startService(intent) }
            } else {
                context.startService(intent)
            }
        }

        const val CHANNEL_PROGRESS = "downloads"
        const val ACTION_CANCEL = "net.berkchy.zerochan.CANCEL"
        const val ACTION_CLEAR = "net.berkchy.zerochan.CLEAR"
        const val EXTRA_ID = "id"
        const val NOTIFICATION_SUMMARY = 1

        private fun idOf(id: Long) = (id % Int.MAX_VALUE).toInt() + 1000

        fun humanBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f kB", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }

        fun humanDuration(millis: Long): String {
            val total = millis / 1000
            return when {
                total < 60 -> "${total}s"
                total < 3600 -> "${total / 60}m ${total % 60}s"
                else -> "${total / 3600}h ${(total % 3600) / 60}m"
            }
        }
    }
}

private fun kotlinx.coroutines.flow.Flow<T>.cancel() = (this as kotlinx.coroutines.CoroutineScope).let { }
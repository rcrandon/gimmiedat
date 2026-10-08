package app.gimmiedat

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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.gimmiedat.engine.formatEta
import app.gimmiedat.engine.formatSpeed
import app.gimmiedat.engine.truncate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while yt-dlp works, so a download survives the user leaving the app.
 * The download itself runs in [Grabber]; this only mirrors its state into a notification.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var lastNotify = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            Grabber.cancel()
            return START_NOT_STICKY
        }
        ensureChannels(this)
        val current = Grabber.phase.value as? Phase.Downloading
        val notification = progressNotification(current)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID_PROGRESS, notification)
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gimmiedat:download")
                .apply { acquire(3 * 60 * 60 * 1000L) }
        }
        if (watcher == null) watcher = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        Grabber.phase.collect { phase ->
            when (phase) {
                is Phase.Downloading -> {
                    // notifications are rate-limited by the system; ~1/s is plenty
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 900 || phase.processing || phase.saving) {
                        lastNotify = now
                        notify(ID_PROGRESS, progressNotification(phase))
                    }
                }
                is Phase.Done -> {
                    if (inBackground()) notify(ID_RESULT, doneNotification(phase))
                    finish()
                }
                is Phase.Failed -> {
                    if (inBackground()) notify(ID_RESULT, failedNotification(phase))
                    finish()
                }
                else -> finish()
            }
        }
    }

    private fun inBackground() =
        !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun notify(id: Int, notification: Notification) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
        }
    }

    private fun finish() {
        watcher?.cancel()
        watcher = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 caps dataSync services at 6h a day; let the system have it back
        finish()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(phase: Phase.Downloading?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(
                0, "cancel",
                PendingIntent.getService(
                    this, 1,
                    Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        if (phase == null) {
            return builder.setContentTitle("grabbing…").setProgress(0, 0, true).build()
        }
        builder.setContentTitle(truncate(phase.from.info.title, 60))
        val progress = phase.progress
        val total = progress?.totalBytes
        val item = if (phase.itemCount > 1) "${phase.item}/${phase.itemCount} · " else ""
        when {
            phase.saving -> builder.setContentText("saving to Download/Gimmie 'Dat…").setProgress(0, 0, true)
            phase.processing -> builder.setContentText("${item}processing…").setProgress(0, 0, true)
            phase.refreshing && progress == null -> builder.setContentText("link expired, grabbing a fresh one…").setProgress(0, 0, true)
            progress != null && total != null && total > 0 -> {
                val pct = (progress.downloadedBytes / total * 100).toInt().coerceIn(0, 100)
                val bits = listOfNotNull(
                    "$pct%",
                    progress.speed?.let(::formatSpeed)?.takeIf { it.isNotEmpty() },
                    progress.eta?.let(::formatEta)?.takeIf { it.isNotEmpty() }?.let { "$it left" },
                )
                builder.setContentText(item + bits.joinToString(" · ")).setProgress(100, pct, false)
            }
            else -> builder.setContentText("${item}starting download…").setProgress(0, 0, true)
        }
        builder.setSubText(phase.choice.label.substringBefore(" · ~"))
        return builder.build()
    }

    private fun doneNotification(phase: Phase.Done): Notification {
        val file = phase.files.first()
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val share = Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(file.mime).putExtra(Intent.EXTRA_STREAM, file.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val text = if (phase.files.size > 1) "${phase.files.size} files in Download/Gimmie 'Dat" else file.path
        return NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("✓ got it! ${truncate(phase.from.info.title, 48)}")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 2, view, flags))
            .addAction(0, "share", PendingIntent.getActivity(this, 3, share, flags))
            .build()
    }

    private fun failedNotification(phase: Phase.Failed): Notification =
        NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("✗ couldn't grab that")
            .setContentText(phase.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(phase.message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

    companion object {
        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_DONE = "done"
        private const val ID_PROGRESS = 1
        private const val ID_RESULT = 2
        private const val ACTION_CANCEL = "app.gimmiedat.CANCEL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.channel_progress_desc); setShowBadge(false) },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, context.getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.channel_done_desc) },
            )
        }
    }
}

package com.example.telegramproxychecker

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
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The foreground service, not ViewModel, owns the active bulk scan. */
class ProxyScanService : Service() {
    companion object {
        private const val CHANNEL = "proxy_scan_progress"
        private const val NOTIFICATION_ID = 1001
        private const val START = "com.example.telegramproxychecker.scan.START"
        private const val PAUSE = "com.example.telegramproxychecker.scan.PAUSE"
        private const val RESUME = "com.example.telegramproxychecker.scan.RESUME"
        private const val STOP = "com.example.telegramproxychecker.scan.STOP"

        fun start(context: Context, force: Boolean = false) {
            val intent = Intent(context, ProxyScanService::class.java)
                .setAction(START).putExtra("force", force)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifications: NotificationManager
    private var foreground = false
    private var scanJob: Job? = null
    private var saveJob: Job? = null
    // The same immutable progress value consumed by ProxyViewModel/Compose.
    // Do not throttle only the notification: that can leave it stale indefinitely.
    private var lastPublishedProgress: ScanProgress? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Фоновая проверка прокси", NotificationManager.IMPORTANCE_LOW)
        )
        scope.launch {
            ScanSession.state.collect { snapshot ->
                updateNotification(snapshot)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                if (scanJob?.isActive == true) return START_NOT_STICKY
                ScanSession.start()
                try {
                    promote()
                } catch (e: Exception) {
                    ScanSession.setError(e.message ?: "Не удалось запустить сервис")
                    ScanSession.finish()
                    stopSelf()
                    return START_NOT_STICKY
                }
                scanJob = scope.launch {
                    try {
                        ScanSession.loadCache(applicationContext)
                        ScanSession.repository.loadAndCheckProxies(
                            cachedProxies = ScanSession.state.value.proxies,
                            force = intent.getBooleanExtra("force", false),
                            onUpdate = {
                                ScanSession.updateProxies(it)
                                scheduleSave()
                            },
                            onProgress = ScanSession::progress,
                            beforeCheck = ScanSession::awaitResume
                        )
                    } catch (_: CancellationException) {
                        // Stop: keep checked proxies and their cached results.
                    } catch (e: Exception) {
                        ScanSession.setError(
                            if (ScanSession.state.value.proxies.isEmpty())
                                e.message ?: "Ошибка загрузки"
                            else
                                "Не удалось обновить. Показан сохранённый список."
                        )
                    } finally {
                        withContext(NonCancellable) {
                            try {
                                ScanSession.saveCache(applicationContext)
                            } finally {
                                // Keep the scan marked active until saving and shutdown finish.
                                ScanSession.finish()
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                foreground = false
                                stopSelf()
                            }
                        }
                    }
                }
            }
            PAUSE -> {
                ScanSession.pause()
                updateNotification(ScanSession.state.value)
            }
            RESUME -> {
                ScanSession.resume()
                updateNotification(ScanSession.state.value)
            }
            STOP -> {
                if (scanJob != null) {
                    scanJob?.cancel()
                } else {
                    ScanSession.finish()
                    scope.launch {
                        ScanSession.saveCache(applicationContext)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promote() {
        val initial = ScanSession.state.value
        val notification = buildNotification(initial)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
        lastPublishedProgress = initial.progress
    }

    private fun scheduleSave() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            delay(1000)
            ScanSession.saveCache(applicationContext)
        }
    }

    private fun actionPendingIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, ProxyScanService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotification(state: ScanSnapshot): Notification {
        val open = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val action = if (state.paused) RESUME else PAUSE
        val actionIcon = if (state.paused) android.R.drawable.ic_media_play
            else android.R.drawable.ic_media_pause
        val progressText = state.progress.label()
        val controls = RemoteViews(packageName, R.layout.proxy_scan_notification).apply {
            setTextViewText(R.id.scan_progress_label, progressText)
            setProgressBar(R.id.scan_progress_bar, state.total.coerceAtLeast(1),
                state.checked, state.total <= 0)
            setImageViewResource(R.id.scan_pause_resume, actionIcon)
            setOnClickPendingIntent(R.id.scan_pause_resume, actionPendingIntent(action, 1))
            setOnClickPendingIntent(R.id.scan_stop, actionPendingIntent(STOP, 2))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Сканирование Telegram-прокси")
            .setContentText(progressText)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(controls)
            .setCustomBigContentView(controls)
            .build()
    }

    private fun updateNotification(state: ScanSnapshot) {
        if (!foreground) return
        val progress = state.progress
        if (progress == lastPublishedProgress) return
        try {
            // No timer-based skip: even the last completed result must reach the drawer.
            notifications.notify(NOTIFICATION_ID, buildNotification(state))
            lastPublishedProgress = progress
        } catch (_: SecurityException) {
            // Denied POST_NOTIFICATIONS does not prevent a user-initiated FGS.
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ScanSession.setError("Достигнут лимит фоновой работы Android")
        scanJob?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scanJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}

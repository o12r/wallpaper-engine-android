package com.weworkshop.app.download

import com.weworkshop.app.App
import com.weworkshop.app.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.weworkshop.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps the process alive while the download queue is non-empty and shows progress. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, build(getString(R.string.notif_preparing), 0f, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (watcher == null) {
            watcher = scope.launch {
                DownloadManager.entries.collectLatest { map ->
                    val running = map.values.firstOrNull { it.status is DownloadStatus.Running }
                    val queued = map.values.count { it.status is DownloadStatus.Queued }
                    if (running == null && queued == 0) {
                        stopSelf()
                        return@collectLatest
                    }
                    val nm = getSystemService(NotificationManager::class.java)
                    if (running != null) {
                        val s = running.status as DownloadStatus.Running
                        val text = if (queued > 0) getString(R.string.notif_queue_more, running.title, queued) else running.title
                        nm.notify(NOTIF_ID, build(text, s.progress, false))
                    } else {
                        nm.notify(NOTIF_ID, build(getString(R.string.notif_queued, queued), 0f, true))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, progress: Float, indeterminate: Boolean) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(1000, (progress * 1000).toInt(), indeterminate)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1001
    }
}

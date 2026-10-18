package com.qshlab.folio.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.qshlab.folio.FolioApp
import com.qshlab.folio.MainActivity
import com.qshlab.folio.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps Folio running while it records or processes, with the screen off or the app closed, and shows
 * what it's doing in an ongoing notification (with Stop while recording). Stops itself when idle.
 */
class FolioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = FolioApp.instance
        when (intent?.action) {
            ACTION_STOP_RECORDING -> app.recorder.stop()
            ACTION_RECORD -> {
                val pid = intent.getStringExtra("pid")!!
                val title = intent.getStringExtra("title")!!
                val mic = intent.getBooleanExtra("mic", true)
                val code = intent.getIntExtra("projCode", 0)
                val data = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("projData", Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra("projData")
                // Foreground first: Android requires it before using the microphone or a media projection.
                var type = 0
                if (mic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (data != null) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(ID, build("Recording", "Starting…", true), type)
                val projection = data?.let { (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code, it) }
                try {
                    app.recorder.start(pid, title, mic, projection)
                } catch (e: Exception) {
                    app.lastError.value = e.message ?: "Couldn't start recording"
                }
            }
            else -> if (!app.recorder.active) {
                if (app.engine.tasks.value.isEmpty()) { stopSelf(); return START_NOT_STICKY }
                startForeground(ID, build("Folio", "Working…", false), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        }
        watch()
        return START_NOT_STICKY
    }

    private fun watch() {
        if (watcher?.isActive == true) return
        val app = FolioApp.instance
        watcher = scope.launch {
            app.recorder.state.combine(app.engine.tasks) { rec, tasks -> rec to tasks }.collect { (rec, tasks) ->
                val nm = getSystemService(NotificationManager::class.java)
                when {
                    rec != null -> nm.notify(ID, build(if (rec.stopping) "Saving recording…" else "Recording · ${clock(rec.elapsed)}", rec.title, !rec.stopping))
                    tasks.isNotEmpty() -> {
                        val t = tasks.firstOrNull { it.running } ?: tasks.first()
                        val pct = if (t.progress > 0) " ${(t.progress * 100).toInt()}%" else ""
                        nm.notify(ID, build("${KIND_LABEL[t.kind]}$pct", t.title + if (tasks.size > 1) " · ${tasks.size - 1} more waiting" else "", false))
                    }
                    else -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                }
            }
        }
    }

    private fun clock(s: Double) = "%d:%02d".format((s / 60).toInt(), (s % 60).toInt())

    private fun build(title: String, text: String, recording: Boolean): Notification {
        channels(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (recording) {
            val stop = PendingIntent.getService(this, 1, Intent(this, FolioService::class.java).setAction(ACTION_STOP_RECORDING), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, "Stop recording", stop)
        }
        return b.build()
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }

    companion object {
        const val ID = 7
        const val CH_STATUS = "status"
        const val CH_DONE = "done"
        const val ACTION_RECORD = "record"
        const val ACTION_STOP_RECORDING = "stop-recording"

        fun channels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Recording and processing", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished transcripts", NotificationManager.IMPORTANCE_DEFAULT))
        }

        /** Runs the service while there's work or a recording; stops it otherwise. */
        fun ensure(ctx: Context) {
            val app = FolioApp.instance
            if (app.recorder.state.value != null || app.engine.tasks.value.isNotEmpty())
                runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, FolioService::class.java)) }
        }

        fun record(ctx: Context, pid: String, title: String, mic: Boolean, projCode: Int, projData: Intent?) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, FolioService::class.java).setAction(ACTION_RECORD)
                .putExtra("pid", pid).putExtra("title", title).putExtra("mic", mic)
                .putExtra("projCode", projCode).putExtra("projData", projData))
        }
    }
}

/** "Transcript ready" notifications, only when Folio isn't on screen. */
object Notify {
    private val DONE = mapOf("state" to "has an updated project state", "process" to "is transcribed", "speakers" to "has new speaker labels",
        "summarize" to "is transcribed and summarized", "correct" to "has been checked for fixes")

    fun done(ctx: Context, engine: Engine, t: Task) {
        if (engine.foreground || t.mid == "_state") return
        FolioService.channels(ctx)
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java)
            .putExtra("pid", t.pid).putExtra("mid", t.mid), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, FolioService.CH_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Folio").setContentText("“${t.title}” ${DONE[t.kind] ?: "is done"}.")
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(t.key.hashCode(), n) }
    }
}

package com.boucle.app.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.boucle.app.BoucleApp
import com.boucle.app.MainActivity
import com.boucle.app.R
import com.boucle.app.domain.Sport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Service de premier plan pendant une activité : il garde l'app en vie écran éteint pour que le GPS continue, et
 * affiche une notification tant que l'enregistrement tourne. L'enregistrement lui-même vit dans [ActivityRecorder].
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recorder = (application as BoucleApp).recorder
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Activité en cours", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Affichée tant que Boucle enregistre une activité avec le GPS."
            },
        )
        startForeground(NOTIFICATION_ID, notification(recorder.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        scope.launch {
            recorder.state
                // La notification change à la minute et tous les 100 m, pas à chaque seconde.
                .map { Triple(it.phase, it.activeSec / 60, (it.stats.distanceMeters / 100).toInt()) to it }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (_, state) ->
                    if (state.active) {
                        manager.notify(NOTIFICATION_ID, notification(state))
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: RecordingState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sport = when (state.sport) {
            Sport.WALK -> "Marche"
            Sport.RUN -> "Course"
            Sport.BIKE -> "Vélo"
        }
        val status = if (state.phase == RecordingPhase.PAUSED) "en pause" else "en cours"
        val minutes = state.activeSec / 60
        val time = if (minutes >= 60) "${minutes / 60} h %02d".format(minutes % 60) else "$minutes min"
        val distance = String.format(Locale.FRANCE, "%.2f km", state.stats.distanceMeters / 1000)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_activity)
            .setContentTitle("$sport $status")
            .setContentText("$time · $distance")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_WORKOUT)
            .build()
    }

    private companion object {
        const val CHANNEL = "recording"
        const val NOTIFICATION_ID = 41
    }
}

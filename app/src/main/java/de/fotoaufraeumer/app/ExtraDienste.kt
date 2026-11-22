package de.fotoaufraeumer.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

// ---------- Homescreen-Widget ----------

class FotoWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val ergebnis = goAsync()
        Thread {
            try {
                val ansicht = baue(ctx)
                ids.forEach { manager.updateAppWidget(it, ansicht) }
            } catch (e: Exception) {
                // Widget bleibt wie es ist
            } finally {
                ergebnis.finish()
            }
        }.start()
    }

    private fun baue(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_foto)
        val oeffnen = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        v.setOnClickPendingIntent(R.id.widget_root, oeffnen)
        val serie = Fortschritt.lese(ctx).serie

        if (!hasMediaPermission(ctx)) {
            v.setTextViewText(R.id.widget_wert, "Los geht's")
            v.setTextViewText(R.id.widget_info, "Tippen, um die App zu starten")
            return v
        }
        val fotos = MediaRepo.loadPhotos(ctx.contentResolver)
        val screenshots = fotos.filter { isScreenshot(it) }
        val messenger = fotos.filter { isMessenger(it) }
        val videos = try {
            Medien.videos(ctx.contentResolver).filter { it.size >= 50L * 1024 * 1024 }
        } catch (e: Exception) {
            emptyList()
        }
        val bytes = screenshots.sumOf { it.size } + messenger.sumOf { it.size } + videos.sumOf { it.size }
        v.setTextViewText(R.id.widget_wert, formatSize(bytes))
        val serieText = if (serie > 0) "🔥 $serie Tage · " else ""
        v.setTextViewText(
            R.id.widget_info,
            "${serieText}aufräumbar: ${screenshots.size} Screenshots, ${messenger.size} Messenger-Bilder, ${videos.size} große Videos",
        )
        return v
    }

    companion object {
        fun aktualisieren(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = m.getAppWidgetIds(ComponentName(ctx, FotoWidget::class.java))
            if (ids.isEmpty()) return
            ctx.sendBroadcast(
                Intent(ctx, FotoWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }
}

// ---------- Wöchentliche Erinnerung ----------

class ErinnerungWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val ctx = applicationContext
        if (!hasMediaPermission(ctx)) return androidx.work.ListenableWorker.Result.success()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return androidx.work.ListenableWorker.Result.success()

        val grenze = System.currentTimeMillis() - 7L * 86_400_000L
        val neu = try {
            MediaRepo.loadPhotos(ctx.contentResolver).filter { it.dateTaken >= grenze }
        } catch (e: Exception) {
            return androidx.work.ListenableWorker.Result.success()
        }
        if (neu.isEmpty()) return androidx.work.ListenableWorker.Result.success()
        val screenshots = neu.count { isScreenshot(it) }
        val messenger = neu.count { isMessenger(it) }

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(KANAL, "Aufräum-Erinnerung", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val oeffnen = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val serie = Fortschritt.lese(ctx).serie
        val text = "Diese Woche ${neu.size} neue Fotos – davon $screenshots Screenshots und $messenger Messenger-Bilder." +
            if (serie > 0) " Halte deine 🔥 $serie-Tage-Serie!" else " Jetzt kurz aufräumen?"
        val n = NotificationCompat.Builder(ctx, KANAL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Zeit zum Aufräumen 🧹")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(oeffnen)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(4711, n)
        } catch (e: SecurityException) {
            // keine Berechtigung
        }
        return androidx.work.ListenableWorker.Result.success()
    }

    companion object {
        private const val KANAL = "erinnerung"
        private const val NAME = "woechentliche_erinnerung"

        fun planen(ctx: Context, an: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (an) {
                wm.enqueueUniquePeriodicWork(
                    NAME, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<ErinnerungWorker>(7, TimeUnit.DAYS).build(),
                )
            } else {
                wm.cancelUniqueWork(NAME)
            }
        }
    }
}

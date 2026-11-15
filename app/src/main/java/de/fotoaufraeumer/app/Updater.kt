package de.fotoaufraeumer.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Selbst-Update über GitHub Releases:
 * prüft regelmäßig im Hintergrund (nur im WLAN), lädt neue Versionen herunter und installiert sie.
 * Ab Android 12 ohne Nachfrage, sobald die App einmal sich selbst aktualisiert hat.
 */
object Updater {
    private const val REPO = "robertpahl1987-creator/foto-aufraeumer"
    private const val CHANNEL = "updates"
    private const val WORK = "auto-update"

    data class Release(val tag: String, val versionCode: Long, val apkUrl: String)

    sealed class Result {
        data object UpToDate : Result()
        data class Installing(val tag: String) : Result()
        data class Failed(val message: String) : Result()
    }

    /** Hintergrundprüfung alle 12 Stunden einplanen (nur im WLAN). */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun installedVersion(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    fun installedName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Versionsnummer aus dem Tag "v1.2.17" → 100 + 17 (entspricht versionCode im Build). */
    private fun codeFromTag(tag: String): Long =
        100L + (tag.substringAfterLast('.').toLongOrNull() ?: 0L)

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val con = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        con.setRequestProperty("Accept", "application/vnd.github+json")
        con.connectTimeout = 15_000
        con.readTimeout = 15_000
        try {
            if (con.responseCode != 200) return@withContext null
            val json = JSONObject(con.inputStream.bufferedReader().readText())
            val tag = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
            }
            url?.let { Release(tag, codeFromTag(tag), it) }
        } finally {
            con.disconnect()
        }
    }

    /** Prüfen und – falls neuer – herunterladen und installieren. */
    suspend fun checkAndInstall(ctx: Context): Result {
        val rel = try {
            latest()
        } catch (e: Exception) {
            return Result.Failed("Keine Verbindung zu GitHub")
        } ?: return Result.Failed("Keine Version auf GitHub gefunden")
        if (rel.versionCode <= installedVersion(ctx)) return Result.UpToDate
        return try {
            val apk = download(ctx, rel.apkUrl)
            install(ctx, apk)
            Result.Installing(rel.tag)
        } catch (e: Exception) {
            Result.Failed("Update fehlgeschlagen: ${e.message}")
        }
    }

    private suspend fun download(ctx: Context, url: String): File = withContext(Dispatchers.IO) {
        val file = File(ctx.cacheDir, "update.apk")
        val con = URL(url).openConnection() as HttpURLConnection
        con.instanceFollowRedirects = true
        con.connectTimeout = 20_000
        con.readTimeout = 60_000
        try {
            if (con.responseCode != 200) error("Download-Fehler ${con.responseCode}")
            con.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        } finally {
            con.disconnect()
        }
        file
    }

    private suspend fun install(ctx: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(InstallReceiver.ACTION_STATUS)
            val pending = PendingIntent.getBroadcast(
                ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pending.intentSender)
        }
        apk.delete()
    }

    fun notify(ctx: Context, id: Int, title: String, text: String, tap: PendingIntent? = null) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "App-Updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .apply { if (tap != null) setContentIntent(tap) }
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        Updater.checkAndInstall(applicationContext)
        return androidx.work.ListenableWorker.Result.success()
    }
}

/** Empfängt das Ergebnis der Installation und meldet sich nach einem Update. */
class InstallReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STATUS = "de.fotoaufraeumer.app.INSTALL_STATUS"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Updater.notify(
                ctx, 2, "Foto-Aufräumer aktualisiert",
                "Jetzt auf Version ${Updater.installedName(ctx)}"
            )
            Updater.schedule(ctx)
            return
        }
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // App im Vordergrund → direkt fragen, sonst Benachrichtigung zum Antippen
                val started = runCatching { ctx.startActivity(confirm) }.isSuccess
                val tap = PendingIntent.getActivity(
                    ctx, 1, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                if (!started || Build.VERSION.SDK_INT >= 29) {
                    Updater.notify(ctx, 1, "Update für Foto-Aufräumer bereit", "Tippen zum Installieren", tap)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unbekannter Fehler"
                Updater.notify(ctx, 3, "Update fehlgeschlagen", msg)
            }
        }
    }
}

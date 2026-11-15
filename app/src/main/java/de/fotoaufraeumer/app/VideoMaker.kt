package de.fotoaufraeumer.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VideoFormat(val label: String, val width: Int, val height: Int) {
    PORTRAIT("Hochformat", 1080, 1920),
    LANDSCAPE("Querformat", 1920, 1080),
    SQUARE("Quadrat", 1080, 1080),
}

data class VideoOptions(
    val secondsPerPhoto: Float = 2.5f,
    val format: VideoFormat = VideoFormat.PORTRAIT,
    val zoom: Boolean = true,
    val music: Uri? = null,
)

const val MAX_VIDEO_PHOTOS = 60

/** Erstellt aus Fotos ein MP4-Kurzvideo (Diashow mit sanftem Zoom, optional mit Musik). */
@androidx.annotation.OptIn(UnstableApi::class)
object VideoMaker {

    fun start(
        ctx: Context,
        uris: List<Uri>,
        o: VideoOptions,
        out: File,
        onDone: (Exception?) -> Unit,
    ): Transformer {
        val cr = ctx.contentResolver
        val durMs = (o.secondsPerPhoto * 1000).toLong()
        val durUs = durMs * 1000

        val items = uris.mapIndexed { i, uri ->
            val effects = ArrayList<Effect>()
            effects += Presentation.createForWidthAndHeight(
                o.format.width, o.format.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            )
            if (o.zoom) {
                // abwechselnd langsam hinein- und herauszoomen ("Ken-Burns-Effekt")
                val zoomIn = i % 2 == 0
                effects += MatrixTransformation { t ->
                    val p = ((t % durUs).toFloat() / durUs).coerceIn(0f, 1f)
                    val s = if (zoomIn) 1f + 0.10f * p else 1.10f - 0.10f * p
                    Matrix().apply { setScale(s, s) }
                }
            }
            val mime = cr.getType(uri) ?: MimeTypes.IMAGE_JPEG
            EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(uri)
                    .setMimeType(mime)
                    .setImageDurationMs(durMs)
                    .build()
            )
                .setFrameRate(30)
                .setEffects(Effects(emptyList(), effects))
                .build()
        }

        val sequences = mutableListOf(EditedMediaItemSequence(items))
        o.music?.let { m ->
            val audio = EditedMediaItem.Builder(MediaItem.fromUri(m)).setRemoveVideo(true).build()
            sequences += EditedMediaItemSequence(listOf(audio), /* isLooping = */ true)
        }
        val composition = Composition.Builder(sequences).build()

        val transformer = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onDone(null)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    onDone(exportException)
                }
            })
            .build()
        transformer.start(composition, out.absolutePath)
        return transformer
    }

    /** Fortschritt 0–100 oder null, wenn gerade nicht verfügbar. */
    fun progress(t: Transformer): Int? {
        val h = ProgressHolder()
        return if (t.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) h.progress else null
    }

    /** Kopiert das fertige Video nach "Filme/Foto-Aufräumer" in die Galerie. */
    fun saveToGallery(ctx: Context, file: File): Uri {
        val cr = ctx.contentResolver
        val name = "Moment_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Foto-Aufräumer")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = cr.insert(collection, values) ?: error("Galerie-Eintrag konnte nicht angelegt werden")
        cr.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            ?: error("Video konnte nicht gespeichert werden")
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        file.delete()
        return uri
    }
}

package de.fotoaufraeumer.app

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ---------- Inhalte erkennen (offline, ML Kit) ----------

object Erkennung {
    private val textErkenner by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val bildErkenner by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.7f).build())
    }

    suspend fun erkenne(cr: ContentResolver, p: Photo, mitDokument: Boolean): Inhalt? {
        val bmp = try {
            var b = cr.loadThumbnail(p.uri, Size(1024, 1024), null)
            if (b.config == Bitmap.Config.HARDWARE) b = b.copy(Bitmap.Config.ARGB_8888, false)
            b
        } catch (e: Exception) {
            return null
        }
        val bild = InputImage.fromBitmap(bmp, 0)
        val text = try { textErkenner.process(bild).await() } catch (e: Exception) { null }
        val labels = try { bildErkenner.process(bild).await().map { it.text } } catch (e: Exception) { emptyList() }
        val roh = text?.text.orEmpty()
        val zeichen = roh.count { it.isLetterOrDigit() }
        val dokument = mitDokument && zeichen >= 120 && (text?.textBlocks?.size ?: 0) >= 3
        return Inhalt(dokument, roh.replace('\n', ' ').take(600), labels)
    }
}

/** Smart-Alben: Bildinhalte (englische Erkennung) → deutsche Kategorien */
val SMART_ALBEN: List<Pair<String, List<String>>> = listOf(
    "Tiere" to listOf("dog", "cat", "bird", "pet", "horse", "insect", "butterfly", "rabbit", "reptile", "animal", "duck", "cattle"),
    "Aquarium & Fische" to listOf("aquarium", "fish", "coral", "underwater", "shell"),
    "Essen & Trinken" to listOf("food", "cake", "dish", "fruit", "vegetable", "pizza", "bread", "cuisine", "dessert",
        "coffee", "drink", "cookie", "meal", "sushi", "juice", "wine", "beer", "pasta", "sandwich", "hot dog", "cheese"),
    "Natur & Draußen" to listOf("plant", "flower", "tree", "sky", "mountain", "beach", "lake", "river", "waterfall",
        "forest", "garden", "cloud", "sunset", "grass", "leaf", "field", "rock", "snow", "sand"),
    "Menschen & Selfies" to listOf("selfie", "smile", "crowd", "baby", "team", "bride", "beard", "child", "people"),
    "Fahrzeuge" to listOf("car", "bus", "bicycle", "motorcycle", "vehicle", "train", "boat", "airplane", "wheel", "tire", "truck"),
    "Feiern & Freizeit" to listOf("party", "balloon", "christmas", "fireworks", "concert", "musical", "dance", "toy", "game", "sports"),
    "Gebäude & Stadt" to listOf("building", "skyscraper", "tower", "bridge", "church", "city", "house", "stairs", "monument"),
)

fun smartAlbenVon(labels: List<String>): List<String> {
    val l = labels.map { it.lowercase(Locale.ROOT) }
    return SMART_ALBEN.filter { (_, worte) -> l.any { label -> worte.any { w -> label == w || label.contains(w) } } }.map { it.first }
}

// ---------- GPS ----------

object Gps {
    fun lese(cr: ContentResolver, p: Photo): String = try {
        val uri = try { MediaStore.setRequireOriginal(p.uri) } catch (e: Exception) { p.uri }
        val ll = cr.openInputStream(uri)?.use { ExifInterface(it).latLong }
        if (ll != null && !(ll[0] == 0.0 && ll[1] == 0.0)) String.format(Locale.US, "%.5f,%.5f", ll[0], ll[1]) else ""
    } catch (e: Exception) {
        ""
    }
}

// ---------- Speicher sparen (Fotos verkleinern) ----------

object Verkleinerer {
    const val MAX_SEITE = 4000
    const val QUALITAET = 85
    const val AB_BYTES = 2_500_000L

    fun istKandidat(p: Photo): Boolean {
        val endung = p.name.substringAfterLast('.', "").lowercase()
        return !p.isVideo && p.size >= AB_BYTES && (endung == "jpg" || endung == "jpeg") && !isScreenshot(p)
    }

    private val exifTags = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
    )

    /**
     * Speichert das Foto kleiner (max. 4000 Pixel, JPEG-Qualität 85) und behält Aufnahmedatum, Standort und Drehung.
     * Gibt die gesparten Bytes zurück (0 = lohnt sich nicht, Original bleibt unverändert).
     * Braucht vorher die Schreib-Erlaubnis (MediaStore.createWriteRequest).
     */
    fun verkleinere(ctx: Context, p: Photo): Long {
        val cr = ctx.contentResolver
        val tmp = File(ctx.cacheDir, "verkleinern.jpg")
        try {
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val ein = cr.openInputStream(p.uri) ?: return 0
            ein.use { BitmapFactory.decodeStream(it, null, opt) }
            val laengste = maxOf(opt.outWidth, opt.outHeight)
            if (laengste <= 0) return 0
            var probe = 1
            while (laengste / (probe * 2) >= MAX_SEITE) probe *= 2
            val dek = BitmapFactory.Options().apply { inSampleSize = probe }
            var bmp = cr.openInputStream(p.uri)?.use { BitmapFactory.decodeStream(it, null, dek) } ?: return 0
            val l = maxOf(bmp.width, bmp.height)
            if (l > MAX_SEITE) {
                val f = MAX_SEITE.toFloat() / l
                val neu = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
                if (neu != bmp) bmp.recycle()
                bmp = neu
            }
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, QUALITAET, it) }
            bmp.recycle()

            if (tmp.length() <= 0 || tmp.length() > p.size * 85 / 100) return 0

            // EXIF-Daten übernehmen (Datum, Standort, Drehung …)
            val original = try {
                val uri = try { MediaStore.setRequireOriginal(p.uri) } catch (e: Exception) { p.uri }
                cr.openInputStream(uri)?.use { ExifInterface(it) }
            } catch (e: Exception) {
                cr.openInputStream(p.uri)?.use { ExifInterface(it) }
            }
            if (original != null) {
                val neu = ExifInterface(tmp.absolutePath)
                exifTags.forEach { t -> original.getAttribute(t)?.let { neu.setAttribute(t, it) } }
                neu.saveAttributes()
            }

            val gespart = p.size - tmp.length()
            cr.openOutputStream(p.uri, "wt")?.use { aus -> tmp.inputStream().use { it.copyTo(aus) } } ?: return 0
            return gespart
        } catch (e: Throwable) {
            return 0
        } finally {
            tmp.delete()
        }
    }
}

// ---------- Videos & Papierkorb laden ----------

object Medien {
    private fun abfrage(cr: ContentResolver, sammlung: Uri, video: Boolean, papierkorb: Boolean): List<Photo> {
        val spalten = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATE_EXPIRES,
        )
        if (video) spalten += MediaStore.MediaColumns.DURATION
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            if (papierkorb) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val liste = ArrayList<Photo>()
        cr.query(sammlung, spalten.toTypedArray(), args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val iExp = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
            val iDur = if (video) c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION) else -1
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val taken = c.getLong(iTaken).let { if (it > 0) it else c.getLong(iAdded) * 1000L }
                liste += Photo(
                    id = id,
                    uri = ContentUris.withAppendedId(sammlung, id),
                    name = c.getString(iName) ?: if (video) "Video" else "Foto",
                    size = c.getLong(iSize),
                    // Im Papierkorb: Ablaufdatum statt Aufnahmedatum (für "noch X Tage")
                    dateTaken = if (papierkorb) c.getLong(iExp) * 1000L else taken,
                    album = c.getString(iBucket) ?: "Sonstige",
                    width = c.getInt(iW),
                    height = c.getInt(iH),
                    isVideo = video,
                    durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                )
            }
        }
        return liste
    }

    private val bilder: Uri get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val videos: Uri get() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun videos(cr: ContentResolver): List<Photo> = abfrage(cr, videos, video = true, papierkorb = false)

    fun papierkorb(cr: ContentResolver): List<Photo> =
        (abfrage(cr, bilder, false, true) + abfrage(cr, videos, true, true)).sortedBy { it.dateTaken }
}

// ---------- ZIP-Sicherung ----------

object ZipSicherung {
    fun schreibe(ctx: Context, ziel: Uri, items: List<Photo>, fortschritt: (Int) -> Unit): Int {
        val cr = ctx.contentResolver
        var ok = 0
        val namen = HashSet<String>()
        cr.openOutputStream(ziel)?.use { roh ->
            ZipOutputStream(roh.buffered()).use { zip ->
                zip.setLevel(1)
                items.forEachIndexed { i, p ->
                    var name = p.name.ifBlank { "foto_${p.id}.jpg" }
                    var n = 2
                    while (!namen.add(name)) {
                        name = p.name.substringBeforeLast('.') + "_$n." + p.name.substringAfterLast('.', "jpg"); n++
                    }
                    try {
                        cr.openInputStream(p.uri)?.use { ein ->
                            zip.putNextEntry(ZipEntry(name).apply { time = p.dateTaken })
                            ein.copyTo(zip)
                            zip.closeEntry()
                            ok++
                        }
                    } catch (e: Exception) {
                        // Datei überspringen
                    }
                    fortschritt(i + 1)
                }
            }
        }
        return ok
    }
}

fun formatDauer(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.GERMANY, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format(Locale.GERMANY, "%d:%02d", s / 60, s % 60)
}

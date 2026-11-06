package de.fotoaufraeumer.app

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import java.security.MessageDigest

data class Photo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateTaken: Long,
    val album: String,
    val width: Int,
    val height: Int,
)

object MediaRepo {
    /** Liest alle Fotos aus der Galerie (MediaStore). Papierkorb-Fotos sind automatisch ausgeschlossen. */
    fun loadPhotos(cr: ContentResolver): List<Photo> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        val result = ArrayList<Photo>()
        cr.query(collection, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val iW = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val taken = c.getLong(iTaken).let { if (it > 0) it else c.getLong(iAdded) * 1000L }
                result += Photo(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(iName) ?: "Foto",
                    size = c.getLong(iSize),
                    dateTaken = taken,
                    album = c.getString(iBucket) ?: "Sonstige",
                    width = c.getInt(iW),
                    height = c.getInt(iH),
                )
            }
        }
        return result
    }
}

object Scanner {
    /** Exakter Fingerabdruck des Dateiinhalts. Gleich = Byte für Byte identisch. */
    fun md5(cr: ContentResolver, uri: Uri): String? = try {
        cr.openInputStream(uri)?.use { input ->
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                md.update(buf, 0, read)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        null
    }

    /** Wahrnehmungs-Hash (dHash): ähnliche Bilder haben ähnliche 64-Bit-Werte. */
    fun dHash(cr: ContentResolver, uri: Uri): Long? = try {
        var bmp = cr.loadThumbnail(uri, Size(128, 128), null)
        if (bmp.config == Bitmap.Config.HARDWARE) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (luma(small.getPixel(x, y)) > luma(small.getPixel(x + 1, y))) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        hash
    } catch (e: Exception) {
        null
    }

    private fun luma(c: Int): Int =
        (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
}

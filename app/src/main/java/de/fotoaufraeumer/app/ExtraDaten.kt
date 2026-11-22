package de.fotoaufraeumer.app

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate

/** Schlüssel für Zwischenspeicher: ändert sich, wenn die Datei sich ändert (z. B. nach Verkleinern). */
fun Photo.cacheKey(): String = "${id}_$size"

// ---------- Erkannte Inhalte (Text, Bildinhalte, Dokument) ----------

data class Inhalt(val dokument: Boolean, val text: String, val labels: List<String>)

class InhaltCache(ctx: Context) {
    private val datei = File(ctx.filesDir, "inhalte.json")
    private val map = HashMap<String, Inhalt>()

    init {
        try {
            if (datei.exists()) {
                val o = JSONObject(datei.readText())
                for (k in o.keys()) {
                    val e = o.getJSONObject(k)
                    val l = e.optJSONArray("l") ?: JSONArray()
                    map[k] = Inhalt(e.optBoolean("d"), e.optString("t"), (0 until l.length()).map { l.getString(it) })
                }
            }
        } catch (e: Exception) {
            map.clear()
        }
    }

    @Synchronized fun get(p: Photo): Inhalt? = map[p.cacheKey()]
    @Synchronized fun put(p: Photo, i: Inhalt) { map[p.cacheKey()] = i }

    @Synchronized fun speichern() {
        val o = JSONObject()
        for ((k, v) in map) {
            o.put(k, JSONObject().put("d", v.dokument).put("t", v.text).put("l", JSONArray(v.labels)))
        }
        datei.writeText(o.toString())
    }
}

// ---------- GPS-Positionen ----------

class GpsCache(ctx: Context) {
    private val datei = File(ctx.filesDir, "gps.json")
    private val map = HashMap<String, String>()
    private val namen = ctx.getSharedPreferences("ortsnamen", Context.MODE_PRIVATE)

    init {
        try {
            if (datei.exists()) {
                val o = JSONObject(datei.readText())
                for (k in o.keys()) map[k] = o.getString(k)
            }
        } catch (e: Exception) {
            map.clear()
        }
    }

    /** null = noch nicht gelesen, "" = Foto hat keinen Standort, sonst "lat,lon" */
    @Synchronized fun get(p: Photo): String? = map[p.cacheKey()]
    @Synchronized fun put(p: Photo, wert: String) { map[p.cacheKey()] = wert }

    @Synchronized fun speichern() {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        datei.writeText(o.toString())
    }

    fun ortsname(schluessel: String): String? = namen.getString(schluessel, null)
    fun merkeOrtsname(schluessel: String, name: String) { namen.edit().putString(schluessel, name).apply() }
}

// ---------- Eigene Tags ----------

class TagStore(ctx: Context) {
    private val datei = File(ctx.filesDir, "tags.json")
    private val tags = LinkedHashMap<String, MutableSet<Long>>()

    init {
        try {
            if (datei.exists()) {
                val o = JSONObject(datei.readText())
                for (k in o.keys()) {
                    val a = o.getJSONArray(k)
                    tags[k] = (0 until a.length()).map { a.getLong(it) }.toMutableSet()
                }
            }
        } catch (e: Exception) {
            tags.clear()
        }
        if (tags.isEmpty()) {
            listOf("Familie", "Urlaub", "Aquarium", "Arbeit").forEach { tags[it] = mutableSetOf() }
            speichern()
        }
    }

    fun alle(): Map<String, Set<Long>> = tags.mapValues { it.value.toSet() }

    fun neu(name: String) {
        val n = name.trim()
        if (n.isNotEmpty() && n !in tags) { tags[n] = mutableSetOf(); speichern() }
    }

    fun loeschen(name: String) { tags.remove(name); speichern() }

    fun setze(name: String, ids: Collection<Long>, an: Boolean) {
        val set = tags.getOrPut(name.trim()) { mutableSetOf() }
        if (an) set.addAll(ids) else set.removeAll(ids.toSet())
        speichern()
    }

    private fun speichern() {
        val o = JSONObject()
        for ((k, v) in tags) o.put(k, JSONArray(v.toList()))
        datei.writeText(o.toString())
    }
}

// ---------- Privater Tresor ----------

data class TresorEintrag(
    val datei: String,
    val name: String,
    val mime: String,
    val datum: Long,
    val groesse: Long,
) {
    val istVideo: Boolean get() = mime.startsWith("video")
}

class TresorStore(private val ctx: Context) {
    val ordner = File(ctx.filesDir, "tresor").apply { mkdirs() }
    private val index = File(ordner, "index.json")
    private val prefs = ctx.getSharedPreferences("tresor", Context.MODE_PRIVATE)

    fun alle(): List<TresorEintrag> = try {
        if (!index.exists()) emptyList() else {
            val a = JSONArray(index.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                TresorEintrag(o.getString("f"), o.getString("n"), o.getString("m"), o.getLong("d"), o.getLong("s"))
            }.sortedByDescending { it.datum }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun schreibe(liste: List<TresorEintrag>) {
        val a = JSONArray()
        liste.forEach {
            a.put(JSONObject().put("f", it.datei).put("n", it.name).put("m", it.mime).put("d", it.datum).put("s", it.groesse))
        }
        index.writeText(a.toString())
    }

    fun datei(e: TresorEintrag) = File(ordner, e.datei)

    /** Kopiert ein Foto/Video in den Tresor. Das Original wird danach separat gelöscht. */
    @Synchronized
    fun hinzufuegen(cr: ContentResolver, p: Photo): TresorEintrag? {
        val endung = p.name.substringAfterLast('.', if (p.isVideo) "mp4" else "jpg").lowercase()
        val ziel = File(ordner, "${System.nanoTime()}.$endung")
        return try {
            cr.openInputStream(p.uri)?.use { ein -> ziel.outputStream().use { ein.copyTo(it) } } ?: return null
            if (ziel.length() == 0L) { ziel.delete(); return null }
            val e = TresorEintrag(ziel.name, p.name, mimeAusName(p.name, p.isVideo), p.dateTaken, ziel.length())
            schreibe(alle() + e)
            e
        } catch (ex: Exception) {
            ziel.delete()
            null
        }
    }

    @Synchronized
    fun entfernen(eintraege: Collection<TresorEintrag>) {
        val weg = eintraege.map { it.datei }.toSet()
        weg.forEach { File(ordner, it).delete() }
        schreibe(alle().filterNot { it.datei in weg })
    }

    /** Legt das Foto wieder in der Galerie ab (Ordner "Wiederhergestellt"). */
    fun wiederherstellen(e: TresorEintrag): Boolean {
        val cr = ctx.contentResolver
        val sammlung = if (e.istVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val werte = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, e.name)
            put(MediaStore.MediaColumns.MIME_TYPE, e.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (e.istVideo) "Movies/Wiederhergestellt/" else "Pictures/Wiederhergestellt/")
            put(MediaStore.MediaColumns.DATE_TAKEN, e.datum)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(sammlung, werte) ?: return false
        return try {
            cr.openOutputStream(uri)?.use { aus -> datei(e).inputStream().use { it.copyTo(aus) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (ex: Exception) {
            cr.delete(uri, null, null)
            false
        }
    }

    // --- PIN ---

    val pinGesetzt: Boolean get() = prefs.contains("hash")

    fun pinSetzen(pin: String) {
        val salz = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit().putString("salz", salz).putString("hash", hash(salz, pin)).apply()
    }

    fun pinPruefen(pin: String): Boolean {
        val salz = prefs.getString("salz", null) ?: return false
        return prefs.getString("hash", null) == hash(salz, pin)
    }

    private fun hash(salz: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salz + pin).toByteArray()).joinToString("") { "%02x".format(it) }
}

fun mimeAusName(name: String, video: Boolean): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "heic", "heif" -> "image/heif"
    "gif" -> "image/gif"
    "mp4" -> "video/mp4"
    "3gp" -> "video/3gpp"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "mov" -> "video/quicktime"
    else -> if (video) "video/mp4" else "image/jpeg"
}

// ---------- Aufräum-Serie, Punkte & Statistik ----------

data class Stand(
    val frei: Long = 0,
    val geloescht: Int = 0,
    val punkte: Int = 0,
    val serie: Int = 0,
    val rekord: Int = 0,
    val heute: Int = 0,
    val erinnerung: Boolean = false,
)

object Fortschritt {
    const val TAGESZIEL = 20

    private val stufen = listOf(
        0 to "Anfänger",
        100 to "Sortierer",
        500 to "Aufräumer",
        1500 to "Ordnungsprofi",
        4000 to "Foto-Meister",
        10000 to "Galerie-Legende",
    )

    var stand by mutableStateOf(Stand()); private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("fortschritt", Context.MODE_PRIVATE)

    fun laden(ctx: Context) { stand = lese(ctx) }

    fun lese(ctx: Context): Stand {
        val p = prefs(ctx)
        val heute = LocalDate.now().toEpochDay()
        val letzter = p.getLong("tag", -10)
        return Stand(
            frei = p.getLong("frei", 0),
            geloescht = p.getInt("geloescht", 0),
            punkte = p.getInt("punkte", 0),
            serie = if (letzter >= heute - 1) p.getInt("serie", 0) else 0,
            rekord = p.getInt("rekord", 0),
            heute = if (letzter == heute) p.getInt("heute", 0) else 0,
            erinnerung = p.getBoolean("erinnerung", false),
        )
    }

    @Synchronized
    fun punkte(ctx: Context, n: Int, gesichtet: Int = n) {
        val p = prefs(ctx)
        val heute = LocalDate.now().toEpochDay()
        val letzter = p.getLong("tag", -10)
        var serie = p.getInt("serie", 0)
        var heuteZahl = p.getInt("heute", 0)
        when (letzter) {
            heute -> {}
            heute - 1 -> { serie += 1; heuteZahl = 0 }
            else -> { serie = 1; heuteZahl = 0 }
        }
        p.edit()
            .putLong("tag", heute)
            .putInt("serie", serie)
            .putInt("rekord", maxOf(p.getInt("rekord", 0), serie))
            .putInt("heute", heuteZahl + gesichtet)
            .putInt("punkte", p.getInt("punkte", 0) + n)
            .apply()
        stand = lese(ctx)
    }

    fun geloescht(ctx: Context, anzahl: Int, bytes: Long) {
        val p = prefs(ctx)
        p.edit()
            .putLong("frei", p.getLong("frei", 0) + bytes)
            .putInt("geloescht", p.getInt("geloescht", 0) + anzahl)
            .apply()
        punkte(ctx, 2 * anzahl, anzahl)
    }

    fun gespart(ctx: Context, bytes: Long, anzahl: Int) {
        val p = prefs(ctx)
        p.edit().putLong("frei", p.getLong("frei", 0) + bytes).apply()
        punkte(ctx, 3 * anzahl, anzahl)
    }

    fun setErinnerung(ctx: Context, an: Boolean) {
        prefs(ctx).edit().putBoolean("erinnerung", an).apply()
        stand = lese(ctx)
    }

    /** Name der Stufe, Fortschritt bis zur nächsten (0..1), Punkte bis dahin */
    fun stufe(punkte: Int): Triple<String, Float, Int> {
        val i = stufen.indexOfLast { punkte >= it.first }.coerceAtLeast(0)
        val (ab, name) = stufen[i]
        val naechste = stufen.getOrNull(i + 1) ?: return Triple(name, 1f, 0)
        val f = (punkte - ab).toFloat() / (naechste.first - ab)
        return Triple(name, f.coerceIn(0f, 1f), naechste.first - punkte)
    }
}

package de.fotoaufraeumer.app

import android.app.Application
import android.content.ContentValues
import android.content.IntentSender
import android.location.Geocoder
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Ein Vorgang, der auf Androids Bestätigung ("Erlauben?") wartet. */
sealed class Vorgang {
    data class Verkleinern(val items: List<Photo>) : Vorgang()
    data class Ordner(val items: List<Photo>, val name: String) : Vorgang()
    data class Tresor(val items: List<Photo>, val kopien: List<TresorEintrag>) : Vorgang()
    data class Wiederherstellen(val items: List<Photo>) : Vorgang()
    data class Endgueltig(val items: List<Photo>) : Vorgang()
    data class VideosLoeschen(val items: List<Photo>) : Vorgang()
}

class ExtraViewModel(app: Application) : AndroidViewModel(app) {
    private val cr = app.contentResolver
    private val inhaltCache = InhaltCache(app)
    private val gps = GpsCache(app)
    private val tagStore = TagStore(app)
    private val tresorStore = TresorStore(app)

    var inhalte by mutableStateOf<Map<Long, Inhalt>>(emptyMap()); private set
    var erkennung by mutableStateOf<Float?>(null); private set
    var erkennungOffen by mutableIntStateOf(0); private set
    var orte by mutableStateOf<Map<Long, String>>(emptyMap()); private set
    var orteLaden by mutableStateOf<Float?>(null); private set
    var tags by mutableStateOf(tagStore.alle()); private set
    var videos by mutableStateOf<List<Photo>?>(null); private set
    var papierkorb by mutableStateOf<List<Photo>?>(null); private set
    var tresor by mutableStateOf(tresorStore.alle()); private set
    var arbeit by mutableStateOf<Pair<String, Float>?>(null); private set
    var meldung by mutableStateOf<String?>(null)

    private var vorgang: Vorgang? = null
    private var zipItems: List<Photo> = emptyList()
    private var erkennungJob: Job? = null
    private var orteJob: Job? = null

    // ---------- Erkennung (Smart-Alben, Dokumente, Textsuche) ----------

    /** Lädt bekannte Ergebnisse und startet die Erkennung für neue Fotos im Hintergrund. */
    fun erkennungStarten(photos: List<Photo>) {
        if (erkennungJob?.isActive == true) return
        val map = HashMap<Long, Inhalt>()
        val offen = ArrayList<Photo>()
        photos.forEach { p -> inhaltCache.get(p)?.let { map[p.id] = it } ?: offen.add(p) }
        inhalte = map.toMap()
        erkennungOffen = offen.size
        if (offen.isEmpty()) return
        // Neueste Fotos zuerst
        offen.sortByDescending { it.dateTaken }
        erkennungJob = viewModelScope.launch {
            erkennung = 0f
            try {
                offen.forEachIndexed { i, p ->
                    val inhalt = withContext(Dispatchers.Default) {
                        Erkennung.erkenne(cr, p, mitDokument = !isScreenshot(p))
                    } ?: Inhalt(false, "", emptyList())
                    inhaltCache.put(p, inhalt)
                    map[p.id] = inhalt
                    erkennungOffen = offen.size - i - 1
                    if (i % 20 == 19 || i == offen.lastIndex) {
                        inhalte = map.toMap()
                        erkennung = (i + 1f) / offen.size
                    }
                    if (i % 100 == 99) withContext(Dispatchers.IO) { inhaltCache.speichern() }
                }
            } finally {
                withContext(Dispatchers.IO) { inhaltCache.speichern() }
                inhalte = map.toMap()
                erkennung = null
            }
        }
    }

    val dokumentIds: Set<Long> get() = inhalte.filterValues { it.dokument }.keys

    fun smartAlben(photos: List<Photo>): List<Pair<String, List<Photo>>> {
        val proAlbum = LinkedHashMap<String, MutableList<Photo>>()
        SMART_ALBEN.forEach { proAlbum[it.first] = mutableListOf() }
        photos.forEach { p ->
            val i = inhalte[p.id] ?: return@forEach
            smartAlbenVon(i.labels).forEach { proAlbum[it]?.add(p) }
        }
        return proAlbum.filterValues { it.isNotEmpty() }
            .map { (k, v) -> k to v.sortedByDescending { it.dateTaken } }
    }

    fun suche(frage: String, photos: List<Photo>): List<Photo> {
        val s = frage.trim().lowercase(Locale.GERMANY)
        if (s.length < 2) return emptyList()
        val tagIds = tags.filterKeys { it.lowercase(Locale.GERMANY).contains(s) }.values.flatten().toSet()
        return photos.filter { p ->
            p.id in tagIds ||
                p.name.lowercase().contains(s) ||
                p.album.lowercase().contains(s) ||
                monthTitle(p.dateTaken).lowercase(Locale.GERMANY).contains(s) ||
                orte[p.id]?.lowercase(Locale.GERMANY)?.contains(s) == true ||
                inhalte[p.id]?.let { i ->
                    i.text.lowercase().contains(s) ||
                        i.labels.any { it.lowercase().contains(s) } ||
                        smartAlbenVon(i.labels).any { it.lowercase(Locale.GERMANY).contains(s) } ||
                        (i.dokument && "dokument".contains(s))
                } == true
        }.sortedByDescending { it.dateTaken }
    }

    // ---------- Alben nach Ort ----------

    @Suppress("DEPRECATION")
    fun orteStarten(photos: List<Photo>) {
        if (orteJob?.isActive == true) return
        orteJob = viewModelScope.launch {
            orteLaden = 0f
            try {
                val ergebnis = HashMap<Long, String>()
                val geo = if (Geocoder.isPresent()) Geocoder(getApplication(), Locale.GERMANY) else null
                var geoKaputt = geo == null
                val lokal = HashMap<String, String?>()
                photos.forEachIndexed { i, p ->
                    if (isScreenshot(p)) return@forEachIndexed
                    val pos = gps.get(p) ?: withContext(Dispatchers.IO) { Gps.lese(cr, p) }.also { gps.put(p, it) }
                    if (pos.isNotEmpty()) {
                        val (lat, lon) = pos.split(",").map { it.toDouble() }
                        val schluessel = String.format(Locale.US, "%.1f,%.1f", lat, lon)
                        val name = if (lokal.containsKey(schluessel)) lokal[schluessel] else {
                            var n = gps.ortsname(schluessel)
                            if (n == null && !geoKaputt) {
                                n = try {
                                    withContext(Dispatchers.IO) {
                                        geo?.getFromLocation(lat, lon, 1)?.firstOrNull()?.let {
                                            it.locality ?: it.subAdminArea ?: it.adminArea ?: it.countryName
                                        }
                                    }
                                } catch (e: Exception) {
                                    geoKaputt = true
                                    null
                                }
                                if (n != null) gps.merkeOrtsname(schluessel, n)
                            }
                            lokal[schluessel] = n
                            n
                        }
                        ergebnis[p.id] = name ?: String.format(Locale.GERMANY, "%.2f° / %.2f°", lat, lon)
                    }
                    if (i % 50 == 49) {
                        orteLaden = (i + 1f) / photos.size
                        orte = ergebnis.toMap()
                    }
                    if (i % 500 == 499) withContext(Dispatchers.IO) { gps.speichern() }
                }
                orte = ergebnis.toMap()
            } finally {
                withContext(Dispatchers.IO) { gps.speichern() }
                orteLaden = null
            }
        }
    }

    // ---------- Tags ----------

    fun tagAnlegen(name: String) { tagStore.neu(name); tags = tagStore.alle() }
    fun tagLoeschen(name: String) { tagStore.loeschen(name); tags = tagStore.alle() }
    fun tagSetzen(name: String, ids: Collection<Long>, an: Boolean = true) {
        tagStore.setze(name, ids, an)
        tags = tagStore.alle()
        if (an) Fortschritt.punkte(getApplication(), ids.size)
    }

    // ---------- Videos & Papierkorb ----------

    fun videosLaden() {
        viewModelScope.launch {
            videos = try { withContext(Dispatchers.IO) { Medien.videos(cr) } } catch (e: Exception) { emptyList() }
        }
    }

    fun papierkorbLaden() {
        viewModelScope.launch {
            papierkorb = try { withContext(Dispatchers.IO) { Medien.papierkorb(cr) } } catch (e: Exception) { emptyList() }
        }
    }

    // ---------- Anfragen, die Android bestätigen lässt ----------

    private fun anfrage(v: Vorgang, sender: () -> IntentSender): IntentSender? = try {
        vorgang = v
        sender()
    } catch (e: Exception) {
        vorgang = null
        meldung = "Nicht möglich: ${e.message}"
        null
    }

    fun verkleinernAnfrage(items: List<Photo>): IntentSender? {
        val k = items.filter { Verkleinerer.istKandidat(it) }
        if (k.isEmpty()) {
            meldung = "Keine passenden Fotos: Verkleinert werden nur JPG-Fotos ab 2,5 MB."
            return null
        }
        return anfrage(Vorgang.Verkleinern(k)) { MediaStore.createWriteRequest(cr, k.map { it.uri }).intentSender }
    }

    fun ordnerAnfrage(items: List<Photo>, name: String): IntentSender? {
        val sauber = name.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
        if (sauber.isEmpty() || items.isEmpty()) return null
        return anfrage(Vorgang.Ordner(items, sauber)) { MediaStore.createWriteRequest(cr, items.map { it.uri }).intentSender }
    }

    fun papierkorbAnfrage(items: List<Photo>, wiederherstellen: Boolean): IntentSender? {
        if (items.isEmpty()) return null
        return if (wiederherstellen) anfrage(Vorgang.Wiederherstellen(items)) {
            MediaStore.createTrashRequest(cr, items.map { it.uri }, false).intentSender
        } else anfrage(Vorgang.Endgueltig(items)) {
            MediaStore.createDeleteRequest(cr, items.map { it.uri }).intentSender
        }
    }

    fun videosLoeschenAnfrage(items: List<Photo>): IntentSender? {
        if (items.isEmpty()) return null
        return anfrage(Vorgang.VideosLoeschen(items)) { MediaStore.createTrashRequest(cr, items.map { it.uri }, true).intentSender }
    }

    /** Kopiert erst in den Tresor, dann fragt Android, ob die Originale gelöscht werden dürfen. */
    fun tresorVorbereiten(items: List<Photo>, weiter: (IntentSender) -> Unit) {
        if (items.isEmpty() || arbeit != null) return
        viewModelScope.launch {
            val kopien = ArrayList<TresorEintrag>()
            val ok = ArrayList<Photo>()
            arbeit = "Kopiere in den Tresor …" to 0f
            items.forEachIndexed { i, p ->
                withContext(Dispatchers.IO) { tresorStore.hinzufuegen(cr, p) }?.let { kopien += it; ok += p }
                arbeit = "Kopiere in den Tresor …" to (i + 1f) / items.size
            }
            arbeit = null
            if (ok.isEmpty()) {
                meldung = "Kopieren in den Tresor hat nicht geklappt."
                return@launch
            }
            anfrage(Vorgang.Tresor(ok, kopien)) { MediaStore.createDeleteRequest(cr, ok.map { it.uri }).intentSender }
                ?.let(weiter)
                ?: run { tresorStore.entfernen(kopien) }
        }
    }

    fun ergebnis(bestaetigt: Boolean, vm: MainViewModel) {
        val v = vorgang ?: return
        vorgang = null
        val app = getApplication<Application>()
        if (!bestaetigt) {
            if (v is Vorgang.Tresor) { tresorStore.entfernen(v.kopien); tresor = tresorStore.alle() }
            return
        }
        when (v) {
            is Vorgang.Verkleinern -> viewModelScope.launch {
                var gespart = 0L
                var n = 0
                v.items.forEachIndexed { i, p ->
                    arbeit = "Verkleinere Fotos … ${i + 1} / ${v.items.size}" to i.toFloat() / v.items.size
                    val g = withContext(Dispatchers.IO) { Verkleinerer.verkleinere(app, p) }
                    if (g > 0) { gespart += g; n++ }
                }
                arbeit = null
                Fortschritt.gespart(app, gespart, n)
                meldung = if (n > 0) "$n Fotos verkleinert · ${formatSize(gespart)} gespart"
                else "Diese Fotos sind schon gut komprimiert – nichts geändert."
                vm.refreshQuietly()
            }
            is Vorgang.Ordner -> viewModelScope.launch {
                arbeit = "Verschiebe in „${v.name}“ …" to 0f
                var n = 0
                v.items.forEachIndexed { i, p ->
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            cr.update(p.uri, ContentValues().apply {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/${v.name}/")
                            }, null, null) > 0
                        } catch (e: Exception) { false }
                    }
                    if (ok) n++
                    arbeit = "Verschiebe in „${v.name}“ …" to (i + 1f) / v.items.size
                }
                arbeit = null
                meldung = "$n Fotos liegen jetzt im Galerie-Ordner „${v.name}“"
                Fortschritt.punkte(app, n)
                vm.refreshQuietly()
            }
            is Vorgang.Tresor -> {
                tresor = tresorStore.alle()
                vm.removeLocally(v.items.map { it.id }.toSet())
                Fortschritt.punkte(app, v.items.size)
                meldung = "${v.items.size} Fotos sind jetzt im Tresor 🔒"
            }
            is Vorgang.Wiederherstellen -> {
                papierkorbLaden()
                if (videos != null) videosLaden()
                vm.refreshQuietly()
                meldung = "${v.items.size} wiederhergestellt"
            }
            is Vorgang.Endgueltig -> {
                papierkorbLaden()
                meldung = "${v.items.size} endgültig gelöscht · ${formatSize(v.items.sumOf { it.size })} frei"
            }
            is Vorgang.VideosLoeschen -> {
                val ids = v.items.map { it.id }.toSet()
                videos = videos?.filterNot { it.id in ids }
                Fortschritt.geloescht(app, v.items.size, v.items.sumOf { it.size })
                FotoWidget.aktualisieren(app)
                meldung = "${v.items.size} Videos in den Papierkorb · ${formatSize(v.items.sumOf { it.size })}"
            }
        }
    }

    // ---------- Tresor ----------

    fun tresorWiederherstellen(eintraege: List<TresorEintrag>, vm: MainViewModel) {
        if (eintraege.isEmpty() || arbeit != null) return
        viewModelScope.launch {
            val fertig = ArrayList<TresorEintrag>()
            eintraege.forEachIndexed { i, e ->
                arbeit = "Lege zurück in die Galerie …" to i.toFloat() / eintraege.size
                if (withContext(Dispatchers.IO) { tresorStore.wiederherstellen(e) }) fertig += e
            }
            tresorStore.entfernen(fertig)
            tresor = tresorStore.alle()
            arbeit = null
            meldung = "${fertig.size} zurück in der Galerie (Ordner „Wiederhergestellt“)"
            vm.refreshQuietly()
        }
    }

    fun tresorLoeschen(eintraege: List<TresorEintrag>) {
        tresorStore.entfernen(eintraege)
        tresor = tresorStore.alle()
        meldung = "${eintraege.size} endgültig gelöscht"
    }

    fun tresorDatei(e: TresorEintrag) = tresorStore.datei(e)
    val pinGesetzt: Boolean get() = tresorStore.pinGesetzt
    fun pinSetzen(pin: String) = tresorStore.pinSetzen(pin)
    fun pinPruefen(pin: String) = tresorStore.pinPruefen(pin)

    // ---------- ZIP-Sicherung ----------

    fun zipVormerken(items: List<Photo>) { zipItems = items }

    fun zipSchreiben(ziel: Uri) {
        val items = zipItems
        zipItems = emptyList()
        if (items.isEmpty()) return
        viewModelScope.launch {
            arbeit = "Sichere ${items.size} Dateien …" to 0f
            val n = try {
                withContext(Dispatchers.IO) {
                    ZipSicherung.schreibe(getApplication(), ziel, items) { i ->
                        if (i % 5 == 0 || i == items.size) viewModelScope.launch {
                            arbeit = "Sichere ${items.size} Dateien … $i / ${items.size}" to i.toFloat() / items.size
                        }
                    }
                }
            } catch (e: Exception) {
                -1
            }
            arbeit = null
            meldung = if (n >= 0) "$n Dateien als ZIP gesichert (${formatSize(items.sumOf { it.size })})"
            else "Sicherung fehlgeschlagen."
        }
    }

    // ---------- Erinnerung ----------

    fun erinnerungSetzen(an: Boolean) {
        Fortschritt.setErinnerung(getApplication(), an)
        ErinnerungWorker.planen(getApplication(), an)
    }
}

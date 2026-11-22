package de.fotoaufraeumer.app

import android.app.Application
import android.content.ContentResolver
import android.content.IntentSender
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val DARK_LIMIT = 30f

enum class Category(val title: String, val desc: String, val swipeOnly: Boolean = false) {
    BLURRY("Unscharfe Fotos", "Verwackelt oder verschwommen – die unschärfsten zuerst"),
    DARK("Dunkle Fotos", "Fast schwarz, z. B. versehentliche Hosentaschen-Fotos"),
    SCREENSHOTS("Screenshots", "Bildschirmfotos – oft nur kurz gebraucht"),
    MESSENGER("Messenger-Bilder", "Empfangen über WhatsApp, Telegram, Signal & Co."),
    OLDEST("Alte Fotos durchgehen", "Alle Fotos von den ältesten an – perfekt zum Wischen", swipeOnly = true),
}

enum class AgeFilter(val label: String, val days: Int) {
    ALL("Alle", 0),
    D30("Älter als 30 Tage", 30),
    Y1("Älter als 1 Jahr", 365),
}

enum class SortMode(val label: String) {
    NEWEST("Neueste"),
    OLDEST("Älteste"),
    LARGEST("Größte"),
    ALBUM("Alben"),
    YEAR("Jahre"),
    FAVORITES("Favoriten"),
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val cr: ContentResolver = app.contentResolver

    var photos by mutableStateOf<List<Photo>>(emptyList()); private set
    var exactGroups by mutableStateOf<List<List<Photo>>>(emptyList()); private set
    var similarGroups by mutableStateOf<List<List<Photo>>>(emptyList()); private set
    var selected by mutableStateOf<Set<Long>>(emptySet()); private set
    var scanning by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(0f); private set
    var status by mutableStateOf(""); private set
    var scannedOnce by mutableStateOf(false); private set
    var threshold by mutableIntStateOf(6); private set
    var sortMode by mutableStateOf(SortMode.NEWEST)
    var lastMessage by mutableStateOf<String?>(null)

    /** Analyse-Ergebnisse (Schärfe, Helligkeit, dHash) je Foto */
    var stats by mutableStateOf<Map<Long, Stats>>(emptyMap()); private set
    var blurThreshold by mutableFloatStateOf(80f)
    private val statCache = HashMap<Long, Stats>()
    // Kurzvideo
    var videoProgress by mutableStateOf<Int?>(null); private set
    var videoResult by mutableStateOf<Uri?>(null)
    private var transformer: Transformer? = null

    // Favoriten
    private var pendingFavorite: Photo? = null

    private var pendingTrash = true
    private var pendingCount = 0
    private var pendingBytes = 0L

    val selectedBytes: Long get() = photos.filter { it.id in selected }.sumOf { it.size }

    // ---------- Scannen ----------

    fun loadAndScan() {
        if (scanning) return
        scanning = true
        progress = 0f
        status = "Galerie wird gelesen…"
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { MediaRepo.loadPhotos(cr) }
                photos = list
                val ids = list.mapTo(HashSet()) { it.id }
                selected = selected.filterTo(HashSet()) { it in ids }

                val exact = withContext(Dispatchers.IO) { findExact(list) }
                exactGroups = exact

                withContext(Dispatchers.IO) { computeHashes(list) }
                stats = HashMap(statCache)
                status = "Gruppiere ähnliche Fotos…"
                val t = threshold
                similarGroups = withContext(Dispatchers.Default) { groupSimilar(list, exact, t) }
                scannedOnce = true
            } catch (e: Exception) {
                lastMessage = "Fehler beim Scannen: ${e.message}"
            } finally {
                status = ""
                scanning = false
            }
        }
    }

    private suspend fun report(p: Float, s: String) = withContext(Dispatchers.Main) {
        progress = p
        status = s
    }

    private suspend fun findExact(list: List<Photo>): List<List<Photo>> {
        // Nur Dateien mit gleicher Größe können identisch sein → spart viel Zeit
        val candidates = list.groupBy { it.size }
            .filter { it.key > 0 && it.value.size > 1 }
            .values.flatten()
        val keyed = ArrayList<Pair<String, Photo>>()
        val total = candidates.size.coerceAtLeast(1)
        candidates.forEachIndexed { i, p ->
            Scanner.md5(cr, p.uri)?.let { keyed += "${p.size}_$it" to p }
            if (i % 5 == 0) report(0.3f * i / total, "Suche exakte Duplikate… ${i + 1} / ${candidates.size}")
        }
        return keyed.groupBy({ it.first }, { it.second }).values
            .filter { it.size > 1 }
            .map { g -> g.sortedWith(compareBy<Photo>({ it.dateTaken }, { it.name.length })) }
            .sortedByDescending { g -> g.drop(1).sumOf { it.size } }
    }

    private suspend fun computeHashes(list: List<Photo>) {
        val todo = list.filter { it.id !in statCache }
        val total = todo.size.coerceAtLeast(1)
        todo.forEachIndexed { i, p ->
            Scanner.analyze(cr, p.uri)?.let { statCache[p.id] = it }
            if (i % 10 == 0) report(0.3f + 0.7f * i / total, "Analysiere Bildinhalte… ${i + 1} / ${todo.size}")
        }
    }

    private fun groupSimilar(list: List<Photo>, exact: List<List<Photo>>, t: Int): List<List<Photo>> {
        val exactKey = HashMap<Long, Int>()
        exact.forEachIndexed { gi, g -> g.forEach { exactKey[it.id] = gi } }

        // Einfarbige Bilder (z. B. schwarze Screenshots) ausschließen – die sähen sonst alle "gleich" aus
        val items = list.mapNotNull { p ->
            val h = statCache[p.id]?.dHash ?: return@mapNotNull null
            if (java.lang.Long.bitCount(h) in 4..60) p to h else null
        }.sortedBy { it.first.dateTaken }

        val n = items.size
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }

        val window = if (n <= 8000) n else 500
        for (i in 0 until n) {
            val hi = minOf(n, i + window)
            val hash = items[i].second
            for (j in i + 1 until hi) {
                if (java.lang.Long.bitCount(hash xor items[j].second) <= t) {
                    val a = find(i)
                    val b = find(j)
                    if (a != b) parent[a] = b
                }
            }
        }

        val groups = HashMap<Int, MutableList<Photo>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { mutableListOf() }.add(items[i].first)

        return groups.values
            .filter { it.size > 1 }
            // Gruppen, die nur aus exakten Duplikaten bestehen, stehen schon im ersten Tab
            .filterNot { g ->
                val k = exactKey[g[0].id]
                k != null && g.all { exactKey[it.id] == k }
            }
            // Bestes Foto (höchste Auflösung, dann größte Datei) zuerst
            .map { g ->
                g.sortedWith(
                    compareByDescending<Photo> { it.width.toLong() * it.height }.thenByDescending { it.size }
                )
            }
            .sortedByDescending { g -> g.maxOf { it.dateTaken } }
    }

    fun changeThreshold(t: Int) {
        if (t == threshold) return
        threshold = t
        val list = photos
        val exact = exactGroups
        viewModelScope.launch {
            similarGroups = withContext(Dispatchers.Default) { groupSimilar(list, exact, t) }
        }
    }

    // ---------- Auswahl ----------

    fun toggle(id: Long) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun selectAll(ids: Collection<Long>) {
        selected = selected + ids
    }

    fun deselectAll(ids: Collection<Long>) {
        selected = selected - ids.toSet()
    }

    /** Wählt in jeder Gruppe alles außer dem ersten (besten) Foto aus. */
    fun autoSelect(groups: List<List<Photo>>) {
        selected = selected + groups.flatMap { g -> g.drop(1).map { it.id } }
    }

    fun clearSelection() {
        selected = emptySet()
    }

    // ---------- Löschen ----------

    fun buildDeleteRequest(trash: Boolean): IntentSender? {
        val chosen = photos.filter { it.id in selected }
        if (chosen.isEmpty()) return null
        pendingTrash = trash
        pendingCount = chosen.size
        pendingBytes = chosen.sumOf { it.size }
        val uris = chosen.map { it.uri }
        return try {
            if (trash) MediaStore.createTrashRequest(cr, uris, true).intentSender
            else MediaStore.createDeleteRequest(cr, uris).intentSender
        } catch (e: Exception) {
            lastMessage = "Löschen nicht möglich: ${e.message}"
            null
        }
    }

    fun onDeleteConfirmed() {
        val gone = selected
        photos = photos.filterNot { it.id in gone }
        exactGroups = exactGroups.map { g -> g.filterNot { it.id in gone } }.filter { it.size > 1 }
        similarGroups = similarGroups.map { g -> g.filterNot { it.id in gone } }.filter { it.size > 1 }
        gone.forEach { statCache.remove(it) }
        stats = HashMap(statCache)
        selected = emptySet()
        Fortschritt.geloescht(getApplication(), pendingCount, pendingBytes)
        FotoWidget.aktualisieren(getApplication())
        val what = if (pendingTrash) "in den Papierkorb verschoben" else "gelöscht"
        lastMessage = "$pendingCount Fotos $what · ${formatSize(pendingBytes)} frei"
    }

    // ---------- Für die Extra-Werkzeuge ----------

    /** Entfernt Fotos aus allen Listen, ohne neu zu scannen (z. B. nach Tresor). */
    fun removeLocally(ids: Set<Long>) {
        if (ids.isEmpty()) return
        photos = photos.filterNot { it.id in ids }
        exactGroups = exactGroups.map { g -> g.filterNot { it.id in ids } }.filter { it.size > 1 }
        similarGroups = similarGroups.map { g -> g.filterNot { it.id in ids } }.filter { it.size > 1 }
        selected = selected - ids
        FotoWidget.aktualisieren(getApplication())
    }

    /** Liest die Foto-Liste neu ein (z. B. nach Verkleinern oder Verschieben), ohne neue Analyse. */
    fun refreshQuietly() {
        viewModelScope.launch {
            val list = try { withContext(Dispatchers.IO) { MediaRepo.loadPhotos(cr) } } catch (e: Exception) { return@launch }
            val byId = list.associateBy { it.id }
            photos = list
            exactGroups = exactGroups.map { g -> g.mapNotNull { byId[it.id] } }.filter { it.size > 1 }
            similarGroups = similarGroups.map { g -> g.mapNotNull { byId[it.id] } }.filter { it.size > 1 }
            selected = selected.filterTo(HashSet()) { it in byId }
            FotoWidget.aktualisieren(getApplication())
        }
    }

    /** Punkt für die Aufräum-Serie (Wisch-Entscheidung). */
    fun swipePoint() = Fortschritt.punkte(getApplication(), 1)

    // ---------- Aufräumen-Kategorien ----------

    fun categoryPhotos(cat: Category): List<Photo> {
        val st = stats
        return when (cat) {
            Category.BLURRY -> photos.filter { p ->
                val s = st[p.id]
                s != null && !isScreenshot(p) && s.brightness >= DARK_LIMIT && s.sharpness < blurThreshold
            }.sortedBy { st[it.id]?.sharpness ?: 0f }
            Category.DARK -> photos.filter { p ->
                !isScreenshot(p) && (st[p.id]?.brightness ?: 255f) < DARK_LIMIT
            }
            Category.SCREENSHOTS -> photos.filter { isScreenshot(it) }
            Category.MESSENGER -> photos.filter { isMessenger(it) }
            Category.OLDEST -> photos.sortedBy { it.dateTaken }
        }
    }

    fun deselect(id: Long) {
        if (id in selected) selected = selected - id
    }

    fun select(id: Long) {
        if (id !in selected) selected = selected + id
    }

    // ---------- Favoriten ----------

    fun buildFavoriteRequest(p: Photo): IntentSender? {
        pendingFavorite = p
        return try {
            MediaStore.createFavoriteRequest(cr, listOf(p.uri), !p.isFavorite).intentSender
        } catch (e: Exception) {
            lastMessage = "Favorit nicht möglich: ${e.message}"
            null
        }
    }

    fun onFavoriteConfirmed() {
        val p = pendingFavorite ?: return
        pendingFavorite = null
        photos = photos.map { if (it.id == p.id) it.copy(isFavorite = !p.isFavorite) else it }
        lastMessage = if (p.isFavorite) "Aus Favoriten entfernt" else "Zu Favoriten hinzugefügt ♥"
    }

    // ---------- Kurzvideo ----------

    @androidx.annotation.OptIn(UnstableApi::class)
    fun makeVideo(chosen: List<Photo>, opts: VideoOptions) {
        if (transformer != null || chosen.size < 2) return
        val app = getApplication<Application>()
        val out = File(app.cacheDir, "moment_${System.currentTimeMillis()}.mp4")
        videoProgress = 0
        try {
            transformer = VideoMaker.start(
                app, chosen.sortedBy { it.dateTaken }.take(MAX_VIDEO_PHOTOS).map { it.uri }, opts, out
            ) { error ->
                transformer = null
                if (error != null) {
                    videoProgress = null
                    out.delete()
                    lastMessage = "Video konnte nicht erstellt werden: ${error.message}"
                } else {
                    viewModelScope.launch {
                        try {
                            videoResult = withContext(Dispatchers.IO) { VideoMaker.saveToGallery(app, out) }
                        } catch (e: Exception) {
                            lastMessage = "Speichern fehlgeschlagen: ${e.message}"
                        } finally {
                            videoProgress = null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            transformer = null
            videoProgress = null
            lastMessage = "Video konnte nicht gestartet werden: ${e.message}"
            return
        }
        viewModelScope.launch {
            while (transformer != null) {
                delay(300)
                transformer?.let { t -> VideoMaker.progress(t)?.let { videoProgress = it } }
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun cancelVideo() {
        transformer?.cancel()
        transformer = null
        videoProgress = null
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCleared() {
        transformer?.cancel()
        super.onCleared()
    }

    // ---------- Sortierte Ansicht ----------

    fun sections(): List<Pair<String, List<Photo>>> = when (sortMode) {
        SortMode.NEWEST -> photos.sortedByDescending { it.dateTaken }.groupBy { monthTitle(it.dateTaken) }.toList()
        SortMode.OLDEST -> photos.sortedBy { it.dateTaken }.groupBy { monthTitle(it.dateTaken) }.toList()
        SortMode.LARGEST -> photos.sortedByDescending { it.size }.groupBy { sizeBucket(it.size) }.toList()
        SortMode.ALBUM -> photos.groupBy { it.album }.toList()
            .sortedByDescending { it.second.size }
            .map { (album, list) -> album to list.sortedByDescending { it.dateTaken } }
        SortMode.YEAR -> photos.sortedByDescending { it.dateTaken }
            .groupBy { yearFormat.format(Date(it.dateTaken)) }.toList()
        SortMode.FAVORITES -> photos.filter { it.isFavorite }.sortedByDescending { it.dateTaken }
            .groupBy { monthTitle(it.dateTaken) }.toList()
    }
}

// ---------- Hilfsfunktionen ----------

private val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.GERMANY)
private val yearFormat = SimpleDateFormat("yyyy", Locale.GERMANY)
private val dayFormat = SimpleDateFormat("d. MMM yyyy, HH:mm", Locale.GERMANY)

fun monthTitle(ms: Long): String = monthFormat.format(Date(ms))
fun formatDate(ms: Long): String = dayFormat.format(Date(ms))

fun sizeBucket(b: Long): String = when {
    b >= 10L * 1024 * 1024 -> "Über 10 MB"
    b >= 5L * 1024 * 1024 -> "5 – 10 MB"
    b >= 1L * 1024 * 1024 -> "1 – 5 MB"
    else -> "Unter 1 MB"
}

fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.GERMANY, "%.2f GB", b / 1073741824.0)
    b >= 1L shl 20 -> String.format(Locale.GERMANY, "%.1f MB", b / 1048576.0)
    else -> String.format(Locale.GERMANY, "%d KB", b / 1024)
}

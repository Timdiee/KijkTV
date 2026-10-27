package nl.kijktv

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String,
    /** De aanbieder meldt dat dit programma terug te kijken is. */
    val archived: Boolean = false
)

/** De programmagids (EPG). Leest XMLTV en bewaart een compacte kopie op het apparaat. */
object Epg {
    @Volatile private var data: Map<String, List<Programme>> = emptyMap()
    @Volatile private var nameIndex: Map<String, String> = emptyMap()
    @Volatile private var loadedUrl: String? = null
    private val shortCache = ConcurrentHashMap<Int, List<Programme>>()
    private val shortBusy = ConcurrentHashMap<Int, Boolean>()
    /** Volledige lijst per zender incl. gemiste programma's (terugkijk-archief van de aanbieder). */
    private val archiveCache = ConcurrentHashMap<Int, List<Programme>>()
    private val archiveAt = ConcurrentHashMap<Int, Long>()
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(3)

    @Volatile var version = 0
        private set
    @Volatile var loading = false

    private const val MAX_AGE = 8L * 3600 * 1000
    private const val KEEP_BEFORE = 4L * 3600 * 1000
    private const val KEEP_AFTER = 3L * 24 * 3600 * 1000

    private val tagRegex = Regex("\\b(hd|fhd|uhd|4k|sd|hevc|h265|h264|1080p|720p)\\b")
    private val prefixRegex = Regex("^[a-z]{2,3}\\s*[:|]\\s*")
    private val nonAlnum = Regex("[^a-z0-9]")

    /** Maakt namen vergelijkbaar: "NL: NPO 1 HD" en "NPO1" worden allebei "npo1". */
    fun norm(s: String): String =
        s.lowercase()
            .replace(prefixRegex, "")
            .replace(tagRegex, "")
            .replace(nonAlnum, "")

    fun clear() {
        data = emptyMap(); nameIndex = emptyMap(); loadedUrl = null
        shortCache.clear(); shortBusy.clear(); archiveCache.clear(); archiveAt.clear()
        version++
    }

    fun programmes(c: Channel): List<Programme> {
        val base = basicProgrammes(c)
        val arch = c.streamId?.let { archiveCache[it] }
        if (arch.isNullOrEmpty()) return base
        // Archief (met gemiste programma's) voorop, aangevuld met wat de gewone gids nog verder vooruit weet.
        val lastStop = arch.last().stop
        return if (base.isEmpty()) arch else arch + base.filter { it.start >= lastStop }
    }

    private fun basicProgrammes(c: Channel): List<Programme> {
        val id = c.epgId?.lowercase()
        if (id != null) data[id]?.let { return it }
        nameIndex[norm(c.name)]?.let { key -> data[key]?.let { return it } }
        c.streamId?.let { sid -> shortCache[sid]?.let { return it } }
        return emptyList()
    }

    /** Haalt de programma's van de afgelopen dagen op (voor terugkijken). Hooguit eens per 30 minuten per zender. */
    fun fetchArchive(acc: Account?, c: Channel, done: () -> Unit) {
        val sid = c.streamId ?: return
        if (acc == null || !acc.isXtream) return
        val last = archiveAt[sid] ?: 0L
        if (System.currentTimeMillis() - last < 30 * 60_000L) return
        archiveAt[sid] = System.currentTimeMillis()
        pool.execute {
            val list = try { Xtream.archiveEpg(acc, sid) } catch (_: Exception) { emptyList() }
            if (list.isNotEmpty()) {
                archiveCache[sid] = list
                version++
            }
            done()
        }
    }

    fun now(c: Channel, t: Long = System.currentTimeMillis()): Programme? =
        programmes(c).firstOrNull { it.start <= t && it.stop > t }

    fun next(c: Channel, t: Long = System.currentTimeMillis()): Programme? =
        programmes(c).firstOrNull { it.start > t }

    // ---------- Korte gids via Xtream (per zender, op verzoek) ----------

    fun needsShort(c: Channel): Boolean {
        val sid = c.streamId ?: return false
        return !shortCache.containsKey(sid) && !shortBusy.containsKey(sid) && programmes(c).isEmpty()
    }

    /** Haalt op de achtergrond nu/straks op; [done] wordt op de achtergrond aangeroepen. */
    fun fetchShort(acc: Account?, c: Channel, done: () -> Unit) {
        val sid = c.streamId ?: return
        if (acc == null || !acc.isXtream || !needsShort(c)) return
        shortBusy[sid] = true
        Thread {
            val list = try { Xtream.shortEpg(acc, sid) } catch (_: Exception) { emptyList() }
            shortCache[sid] = list
            shortBusy.remove(sid)
            version++
            done()
        }.start()
    }

    // ---------- Volledige gids (XMLTV) ----------

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "epg.tsv")

    fun isStale(ctx: Context, url: String): Boolean {
        val f = cacheFile(ctx)
        if (!f.exists() || loadedUrl != url) return true
        return System.currentTimeMillis() - f.lastModified() > MAX_AGE
    }

    /** Leest de opgeslagen gids als die bij deze link hoort. */
    fun loadCached(ctx: Context, url: String): Boolean {
        val f = cacheFile(ctx)
        if (!f.exists()) return false
        val progs = HashMap<String, ArrayList<Programme>>()
        val names = HashMap<String, String>()
        var fileUrl: String? = null
        val minStop = System.currentTimeMillis() - KEEP_BEFORE
        try {
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val p = line.split('\t')
                    when {
                        p.size >= 2 && p[0] == "#url" -> fileUrl = p[1]
                        p.size >= 3 && p[0] == "N" -> names[p[1]] = p[2]
                        p.size >= 6 && p[0] == "P" -> {
                            val stop = p[3].toLongOrNull() ?: continue
                            if (stop < minStop) continue
                            val start = p[2].toLongOrNull() ?: continue
                            progs.getOrPut(p[1]) { ArrayList() }.add(Programme(start, stop, p[4], p[5]))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            return false
        }
        if (fileUrl != url) return false
        data = progs
        nameIndex = names
        loadedUrl = url
        version++
        return true
    }

    /** Downloadt en verwerkt de gids. Alleen zenders uit de playlist en een paar dagen vooruit. */
    fun refresh(ctx: Context, url: String, channels: List<Channel>) {
        val wantedIds = HashSet<String>()
        val wantedNames = HashSet<String>()
        for (c in channels) {
            c.epgId?.let { wantedIds.add(it.lowercase()) }
            val n = norm(c.name)
            if (n.isNotEmpty()) wantedNames.add(n)
        }
        val conn = Http.open(url, readTimeout = 120000)
        val result = try {
            openMaybeGzip(conn.inputStream).use { parseXmltv(it, wantedIds, wantedNames) }
        } finally {
            conn.disconnect()
        }
        data = result.first
        nameIndex = result.second
        loadedUrl = url
        version++
        save(ctx, url)
    }

    private fun openMaybeGzip(raw: InputStream): InputStream {
        val b = BufferedInputStream(raw, 65536)
        b.mark(4)
        val b1 = b.read()
        val b2 = b.read()
        b.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(b, 65536), 65536) else b
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun save(ctx: Context, url: String) {
        try {
            val tmp = File(ctx.filesDir, "epg.tmp")
            tmp.bufferedWriter().use { w ->
                w.write("#url\t${clean(url)}\n")
                for ((n, id) in nameIndex) w.write("N\t$n\t${clean(id)}\n")
                for ((id, list) in data) {
                    val cid = clean(id)
                    for (p in list) w.write("P\t$cid\t${p.start}\t${p.stop}\t${clean(p.title)}\t${clean(p.desc)}\n")
                }
            }
            tmp.renameTo(cacheFile(ctx))
        } catch (_: Exception) { }
    }

    private fun parseXmltv(
        input: InputStream,
        wantedIds: Set<String>,
        wantedNames: Set<String>
    ): Pair<Map<String, List<Programme>>, Map<String, String>> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val now = System.currentTimeMillis()
        val from = now - KEEP_BEFORE
        val to = now + KEEP_AFTER
        val names = HashMap<String, String>()
        val nameIds = HashSet<String>()
        val progs = HashMap<String, ArrayList<Programme>>()

        var channelId: String? = null
        var inProg = false
        var pId = ""
        var pStart = 0L
        var pStop = 0L
        var title: String? = null
        var desc: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> channelId = parser.getAttributeValue(null, "id")?.trim()?.lowercase()
                    "display-name" -> {
                        val cid = channelId
                        if (cid != null && !inProg) {
                            val n = norm(parser.nextText())
                            if (n.isNotEmpty() && wantedNames.contains(n) && !names.containsKey(n)) {
                                names[n] = cid
                                nameIds.add(cid)
                            }
                        }
                    }
                    "programme" -> {
                        inProg = true
                        pId = parser.getAttributeValue(null, "channel")?.trim()?.lowercase().orEmpty()
                        pStart = parseTime(parser.getAttributeValue(null, "start"))
                        pStop = parseTime(parser.getAttributeValue(null, "stop"))
                        title = null
                        desc = null
                    }
                    "title" -> if (inProg && title == null) title = parser.nextText().trim()
                    "desc" -> if (inProg && desc == null) desc = parser.nextText().trim()
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "channel" -> channelId = null
                    "programme" -> {
                        inProg = false
                        if (pStart > 0 && pStop <= pStart) pStop = pStart + 30 * 60 * 1000
                        if (pStart > 0 && pStop > from && pStart < to &&
                            (wantedIds.contains(pId) || nameIds.contains(pId))
                        ) {
                            progs.getOrPut(pId) { ArrayList() }.add(
                                Programme(pStart, pStop, title ?: "", (desc ?: "").take(400))
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }
        for (list in progs.values) list.sortBy { it.start }
        return Pair(progs, names)
    }

    /** XMLTV-tijd zoals "20261026200000 +0100" naar milliseconden. */
    fun parseTime(s: String?): Long {
        if (s == null || s.length < 12) return 0
        return try {
            val y = s.substring(0, 4).toInt()
            val mo = s.substring(4, 6).toInt()
            val d = s.substring(6, 8).toInt()
            val h = s.substring(8, 10).toInt()
            val mi = s.substring(10, 12).toInt()
            val sec = if (s.length >= 14 && s[12].isDigit() && s[13].isDigit()) s.substring(12, 14).toInt() else 0
            var offsetMin = 0
            val sign = s.indexOfAny(charArrayOf('+', '-'), 12)
            if (sign > 0 && s.length >= sign + 5) {
                val oh = s.substring(sign + 1, sign + 3).toInt()
                val om = s.substring(sign + 3, sign + 5).toInt()
                offsetMin = (if (s[sign] == '-') -1 else 1) * (oh * 60 + om)
            }
            val days = daysFromCivil(y, mo, d)
            ((days * 86400L + h * 3600L + mi * 60L + sec) - offsetMin * 60L) * 1000L
        } catch (_: Exception) {
            0
        }
    }

    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468
    }
}

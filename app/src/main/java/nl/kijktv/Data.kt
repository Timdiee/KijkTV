package nl.kijktv

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val USER_AGENT = "KijkTV/2.0 (Linux; Android TV)"

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val epgId: String? = null,
    val streamId: Int? = null,
    /** Aantal dagen terugkijken dat de aanbieder voor deze zender aanbiedt (0 = geen). */
    val archiveDays: Int = 0
)

/** Inloggegevens: Xtream (server + gebruikersnaam + wachtwoord) of een losse M3U-link. */
data class Account(
    val type: String,
    val server: String = "",
    val user: String = "",
    val pass: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = ""
) {
    val isXtream get() = type == XTREAM

    companion object {
        const val XTREAM = "xtream"
        const val M3U = "m3u"

        fun withScheme(s: String) = if (s.contains("://")) s else "http://$s"

        /** Alleen http(s)://host:poort overhouden van wat iemand als server invult. */
        fun cleanServer(input: String): String {
            val s = withScheme(input.trim())
            return try {
                val u = URL(s)
                val port = if (u.port > 0) ":${u.port}" else ""
                "${u.protocol}://${u.host}$port"
            } catch (_: Exception) {
                s.trimEnd('/')
            }
        }

        private fun queryParam(url: String, key: String): String? {
            val q = url.substringAfter('?', "")
            for (part in q.split('&')) {
                val k = part.substringBefore('=')
                if (k.equals(key, ignoreCase = true)) {
                    return try {
                        java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                    } catch (_: Exception) { null }
                }
            }
            return null
        }

        /** Herkent links als http://server/get.php?username=..&password=.. en maakt er een Xtream-login van. */
        fun parseXtreamLink(url: String): Account? {
            val u = queryParam(url, "username")
            val p = queryParam(url, "password")
            if (u.isNullOrEmpty() || p.isNullOrEmpty()) return null
            return Account(XTREAM, server = cleanServer(url), user = u, pass = p)
        }

        fun fromInput(server: String, user: String, pass: String, m3u: String, epg: String = ""): Account? {
            val s = server.trim(); val u = user.trim(); val p = pass.trim(); val m = m3u.trim()
            if (s.isNotEmpty() && u.isNotEmpty() && p.isNotEmpty()) {
                return Account(XTREAM, server = cleanServer(s), user = u, pass = p)
            }
            val link = when {
                m.isNotEmpty() -> m
                s.isNotEmpty() && u.isEmpty() && p.isEmpty() -> s
                else -> return null
            }
            val url = withScheme(link)
            parseXtreamLink(url)?.let { return it }
            val e = epg.trim()
            return Account(M3U, m3uUrl = url, epgUrl = if (e.isEmpty()) "" else withScheme(e))
        }
    }
}

/** Eenvoudige HTTP-helper die ook http↔https-doorverwijzingen volgt. */
object Http {
    fun open(startUrl: String, readTimeout: Int = 60000): HttpURLConnection {
        var url = startUrl.trim()
        var redirects = 0
        while (true) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = readTimeout
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty()) throw Exception("Doorverwijzing zonder adres")
                url = URL(URL(url), loc).toString()
                redirects++
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw Exception("Server antwoordde met code $code")
            }
            return conn
        }
    }

    fun getText(url: String): String {
        val conn = open(url)
        try {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    /** Voor stukjes van een pad (spatie als %20 i.p.v. +). */
    fun encPath(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** Leest M3U / M3U8 playlists (#EXTINF met tvg-id, tvg-logo, group-title, enz.). */
object M3uParser {
    class Result(val channels: List<Channel>, val epgUrl: String?)

    private val attrRegex = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    fun parse(text: String): Result {
        val out = ArrayList<Channel>()
        var epgUrl: String? = null
        var name: String? = null
        var group = ""
        var logo: String? = null
        var epgId: String? = null
        var extGrp: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTM3U", ignoreCase = true)) {
                for (m in attrRegex.findAll(line)) {
                    val k = m.groupValues[1].lowercase()
                    if ((k == "url-tvg" || k == "x-tvg-url") && m.groupValues[2].isNotBlank()) {
                        epgUrl = m.groupValues[2].split(',').first().trim()
                    }
                }
            } else if (line.startsWith("#EXTINF", ignoreCase = true)) {
                var inQuotes = false
                var comma = -1
                for (i in line.indices) {
                    val c = line[i]
                    if (c == '"') inQuotes = !inQuotes
                    else if (c == ',' && !inQuotes) { comma = i; break }
                }
                val head = if (comma >= 0) line.substring(0, comma) else line
                val title = if (comma >= 0) line.substring(comma + 1).trim() else ""
                val attrs = HashMap<String, String>()
                for (m in attrRegex.findAll(head)) {
                    attrs[m.groupValues[1].lowercase()] = m.groupValues[2]
                }
                name = title.ifEmpty { attrs["tvg-name"] ?: "Zender" }
                group = attrs["group-title"].orEmpty().trim()
                logo = attrs["tvg-logo"]?.trim()?.takeIf { it.isNotEmpty() }
                epgId = attrs["tvg-id"]?.trim()?.takeIf { it.isNotEmpty() }
            } else if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                extGrp = line.substring(8).trim()
            } else if (line.startsWith("#")) {
                continue
            } else {
                var g = group
                if (g.isEmpty()) g = extGrp ?: ""
                if (g.isEmpty()) g = "Overig"
                val n = name ?: line.substringAfterLast('/').ifEmpty { "Zender" }
                out.add(Channel(n, line, g, logo, epgId))
                name = null; group = ""; logo = null; epgId = null; extGrp = null
            }
        }
        return Result(out, epgUrl)
    }
}

/** Houdt de geladen zenders bij en bewaart een kopie op het apparaat. */
object Repo {
    @Volatile var all: List<Channel> = emptyList()
    /** De lijst waar speler en gids doorheen bladeren. */
    @Volatile var playList: List<Channel> = emptyList()
    @Volatile var epgUrl: String? = null
    @Volatile var accountInfo: String? = null
    /** Tijdzone van de Xtream-server (nodig voor terugkijken). */
    @Volatile var serverTz: String? = null
    @Volatile var version = 0

    fun setChannels(list: List<Channel>) {
        all = list
        version++
    }

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "channels.tsv")

    /** Laadt zenders bij de aanbieder. Gooit een Exception met een duidelijke Nederlandse melding. */
    fun load(ctx: Context, acc: Account): List<Channel> {
        val list: List<Channel>
        val epg: String?
        if (acc.isXtream) {
            val r = Xtream.load(acc)
            list = r.channels
            epg = Xtream.epgUrl(acc)
            accountInfo = r.info
            serverTz = r.timezone
        } else {
            val text = try { Http.getText(acc.m3uUrl) } catch (e: Exception) {
                throw Exception("Kon de playlist niet downloaden. Klopt de link?\n(${e.message})")
            }
            val res = M3uParser.parse(text)
            list = res.channels
            epg = acc.epgUrl.ifEmpty { res.epgUrl ?: "" }.ifEmpty { null }
            accountInfo = null
            serverTz = null
        }
        if (list.isEmpty()) throw Exception("Geen zenders gevonden bij deze aanbieder.")
        epgUrl = epg
        saveCache(ctx, list, epg)
        return list
    }

    private fun clean(s: String?) = s.orEmpty().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun saveCache(ctx: Context, list: List<Channel>, epg: String?) {
        try {
            cacheFile(ctx).bufferedWriter().use { w ->
                w.write("#epg\t${clean(epg)}\n")
                w.write("#tz\t${clean(serverTz)}\n")
                for (c in list) {
                    w.write("${clean(c.name)}\t${clean(c.url)}\t${clean(c.group)}\t${clean(c.logo)}\t${clean(c.epgId)}\t${c.streamId ?: ""}\t${c.archiveDays}\n")
                }
            }
        } catch (_: Exception) { }
    }

    fun loadCached(ctx: Context): List<Channel> {
        val f = cacheFile(ctx)
        if (!f.exists()) return emptyList()
        val out = ArrayList<Channel>()
        try {
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val p = line.split('\t')
                    if (p.size >= 2 && p[0] == "#epg") {
                        epgUrl = p[1].ifEmpty { null }
                    } else if (p.size >= 2 && p[0] == "#tz") {
                        serverTz = p[1].ifEmpty { null }
                    } else if (p.size >= 6) {
                        out.add(
                            Channel(
                                p[0], p[1], p[2], p[3].ifEmpty { null }, p[4].ifEmpty { null },
                                p[5].toIntOrNull(), p.getOrNull(6)?.toIntOrNull() ?: 0
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }

    fun clearCache(ctx: Context) {
        try { cacheFile(ctx).delete() } catch (_: Exception) { }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("kijktv", Context.MODE_PRIVATE)
    private val app: Context = ctx.applicationContext ?: ctx

    var account: Account?
        get() {
            val type = sp.getString("type", null)
            if (type == null) {
                // Overstap van versie 1 (alleen een link)
                val old = sp.getString("url", null) ?: return null
                return Account.parseXtreamLink(old) ?: Account(Account.M3U, m3uUrl = old)
            }
            return Account(
                type,
                sp.getString("server", "")!!,
                sp.getString("user", "")!!,
                sp.getString("pass", "")!!,
                sp.getString("m3u", "")!!,
                sp.getString("epg", "")!!
            )
        }
        set(a) {
            val e = sp.edit()
            if (a == null) {
                e.remove("type").remove("server").remove("user").remove("pass").remove("m3u").remove("epg").remove("url")
            } else {
                e.putString("type", a.type).putString("server", a.server).putString("user", a.user)
                    .putString("pass", a.pass).putString("m3u", a.m3uUrl).putString("epg", a.epgUrl).remove("url")
            }
            e.apply()
        }

    var lastGroup: String?
        get() = sp.getString("group", null)
        set(v) { sp.edit().putString("group", v).apply() }

    var lastChannelUrl: String?
        get() = sp.getString("last", null)
        set(v) { sp.edit().putString("last", v).apply() }

    /** "smooth" (meer buffer) of "fast" (dichter op live). */
    var playMode: String
        get() = sp.getString("mode", MODE_SMOOTH) ?: MODE_SMOOTH
        set(v) { sp.edit().putString("mode", v).apply() }

    /** "auto", "ts" of "hls". */
    var streamFormat: String
        get() = sp.getString("format", FORMAT_AUTO) ?: FORMAT_AUTO
        set(v) { sp.edit().putString("format", v).apply() }

    /** "auto" of "software": welke videodecoder eerst geprobeerd wordt. */
    var decoder: String
        get() = sp.getString("decoder", DECODER_AUTO) ?: DECODER_AUTO
        set(v) { sp.edit().putString("decoder", v).apply() }

    /** Taal van de ondertiteling bij films/series ("off" = uit). Standaard Nederlands als die er is. */
    var subtitleLang: String
        get() = sp.getString("subLang", "nl") ?: "nl"
        set(v) { sp.edit().putString("subLang", v).apply() }

    /** Voorkeurstaal voor audio ("" = wat de film standaard heeft). */
    var audioLang: String
        get() = sp.getString("audioLang", "") ?: ""
        set(v) { sp.edit().putString("audioLang", v).apply() }

    /** 0 = klein, 1 = normaal, 2 = groot, 3 = extra groot. */
    var subtitleSize: Int
        get() = sp.getInt("subSize", 1)
        set(v) { sp.edit().putInt("subSize", v).apply() }

    companion object {
        const val SUB_OFF = "off"
        const val DECODER_AUTO = "auto"
        const val DECODER_SOFTWARE = "software"
        const val MODE_SMOOTH = "smooth"
        const val MODE_FAST = "fast"
        const val FORMAT_AUTO = "auto"
        const val FORMAT_TS = "ts"
        const val FORMAT_HLS = "hls"
    }

    fun favorites(): Set<String> = sp.getStringSet("fav", null)?.toSet() ?: emptySet()

    /** Geeft true terug als de zender nu favoriet is. */
    fun toggleFavorite(url: String): Boolean {
        val set = favorites().toMutableSet()
        val nowFav = if (set.contains(url)) { set.remove(url); false } else { set.add(url); true }
        sp.edit().putStringSet("fav", set).apply()
        Sync.favoritesChanged(app)
        return nowFav
    }

    fun setFavorites(urls: Set<String>) {
        sp.edit().putStringSet("fav", HashSet(urls)).apply()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
object Players {
    /** Afspeelinstellingen (worden bij elke nieuwe speler uit de voorkeuren gelezen). */
    @Volatile private var fast = false
    @Volatile private var format = Prefs.FORMAT_AUTO

    /** @param vod true voor films/series/terugkijken: dan gaat de ondertiteling volgens de voorkeur aan. */
    fun create(ctx: Context, vod: Boolean = false): ExoPlayer {
        val prefs = Prefs(ctx)
        fast = prefs.playMode == Prefs.MODE_FAST
        format = prefs.streamFormat

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
            .setKeepPostFor302Redirects(true)
        val dataSource = DefaultDataSource.Factory(ctx, http)

        // IPTV-streams (MPEG-TS) beginnen vaak niet met een "schoon" beeld: dit laat ze sneller en
        // met minder haperingen starten.
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )

        val loadControl = if (fast) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(8_000, 20_000, 1_000, 2_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            // Soepel: ruime buffer, zodat korte haperingen van de server niet in beeld komen.
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 60_000, 2_500, 5_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }

        // Valt de videochip niet mee, dan automatisch een andere decoder proberen.
        val renderers = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        if (prefs.decoder == Prefs.DECODER_SOFTWARE) {
            // Software-decoders eerst: helpt op emulators en kastjes met een haperende videochip.
            renderers.setMediaCodecSelector { mime, secure, tunneling ->
                MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
                    .sortedBy { if (it.softwareOnly) 0 else 1 }
            }
        }

        val player = ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors))
            .setLoadControl(loadControl)
            .build()

        // Ondertiteling en audiotaal volgens de voorkeur. Bij live tv staat ondertiteling standaard uit.
        val sub = prefs.subtitleLang
        val tp = player.trackSelectionParameters.buildUpon()
        if (!vod || sub == Prefs.SUB_OFF) {
            tp.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            tp.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(sub)
        }
        if (prefs.audioLang.isNotEmpty()) tp.setPreferredAudioLanguage(prefs.audioLang)
        player.trackSelectionParameters = tp.build()
        return player
    }

    /** Past het streamtype (TS of HLS) toe op live-links van Xtream-servers. */
    fun applyFormat(url: String): String {
        if (!url.contains("/live/")) return url
        return when (format) {
            Prefs.FORMAT_HLS -> if (url.endsWith(".ts")) url.removeSuffix(".ts") + ".m3u8" else url
            Prefs.FORMAT_TS -> if (url.endsWith(".m3u8")) url.removeSuffix(".m3u8") + ".ts" else url
            else -> url
        }
    }

    fun item(url: String): MediaItem {
        val b = MediaItem.Builder().setUri(url)
        if (url.lowercase().contains("m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (fast) {
            // Bij HLS dicht op live blijven en een achterstand langzaam inhalen.
            b.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(6_000)
                    .setMaxPlaybackSpeed(1.04f)
                    .build()
            )
        }
        return b.build()
    }
}

/** Tijd- en datumweergave in het Nederlands. */
object Fmt {
    private val nl = Locale("nl", "NL")
    private val time = SimpleDateFormat("HH:mm", nl)
    private val day = SimpleDateFormat("EEEE d MMMM", nl)

    fun time(ms: Long): String = synchronized(time) { time.format(Date(ms)) }

    fun day(ms: Long): String {
        val today = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val diff = dayNumber(c) - dayNumber(today)
        return when (diff) {
            0 -> "Vandaag"
            1 -> "Morgen"
            -1 -> "Gisteren"
            else -> synchronized(day) { day.format(Date(ms)) }.replaceFirstChar { it.uppercase() }
        }
    }

    fun dayNumber(c: Calendar): Int = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)

    fun range(p: Programme) = "${time(p.start)} – ${time(p.stop)}"

    /** Voortgang 0..1000 van een programma dat nu loopt. */
    fun progress(p: Programme, now: Long = System.currentTimeMillis()): Int {
        val len = p.stop - p.start
        if (len <= 0) return 0
        return (((now - p.start) * 1000) / len).toInt().coerceIn(0, 1000)
    }
}

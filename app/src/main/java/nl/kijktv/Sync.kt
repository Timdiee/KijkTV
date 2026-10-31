package nl.kijktv

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Verder kijken op al je apparaten.
 *
 * Elk apparaat zet de kijkpositie van films, afleveringen en terugkijk-uitzendingen (en je favoriete
 * zenders) in een eigen Firebase-database. Apparaten met dezelfde IPTV-login vinden elkaar via een
 * geheime sleutel die uit die login wordt berekend. Het IPTV-wachtwoord zelf gaat er nooit naartoe:
 * in de links wordt het vervangen door {U}/{P} en op het andere apparaat weer ingevuld.
 *
 * Dezelfde regels staan in de LG-versie (webos/app/js/sync.js); houd die gelijk.
 */
object Sync {
    private const val BASE = "https://kijktv-26626-default-rtdb.europe-west1.firebasedatabase.app/kijktv/"
    private const val PULL_EVERY = 20_000L
    private const val PUSH_DELAY = 10_000L

    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, Runnable>()
    private val latest = HashMap<String, String>()
    @Volatile private var lastPullAt = 0L
    @Volatile private var pulling = false
    /** Gevraagd terwijl er al een ronde liep: daarna nog een keer. */
    @Volatile private var again: Pair<Context, (() -> Unit)?>? = null

    /** Alleen deze soorten gaan mee (live tv heeft geen kijkpositie). */
    fun syncable(kind: String) = kind == WatchItem.MOVIE || kind == WatchItem.EPISODE || kind == WatchItem.CATCHUP

    private fun hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in d) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    private fun hostPort(server: String) =
        server.trim().lowercase().substringAfter("://").trimEnd('/')

    /** Geheime sleutel voor dit account (64 tekens); null = niet synchroniseren. */
    private fun keyFor(acc: Account?): String? {
        if (acc == null || !acc.isXtream || acc.user.isEmpty()) return null
        return hex("kijktv1|" + hostPort(acc.server) + "|" + acc.user + "|" + acc.pass)
    }

    private fun itemId(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_")

    // ---------- Links zonder wachtwoord ----------

    private fun hideLogin(acc: Account, url: String): String {
        var u = url
        if (u.lowercase().startsWith(acc.server.lowercase())) u = "{S}" + u.substring(acc.server.length)
        u = u.replace("/" + Http.encPath(acc.user) + "/" + Http.encPath(acc.pass) + "/", "/{U}/{P}/")
        // Voor de zekerheid: staat het wachtwoord er nog in, dan de link niet meesturen.
        if (acc.pass.length >= 3 && (u.contains(acc.pass) || u.contains(Http.encPath(acc.pass)))) return ""
        return u
    }

    private fun showLogin(acc: Account, url: String): String =
        url.replace("{S}", acc.server).replace("/{U}/{P}/", "/" + Http.encPath(acc.user) + "/" + Http.encPath(acc.pass) + "/")

    private fun toJson(acc: Account, w: WatchItem) = JSONObject()
        .put("key", w.key).put("kind", w.kind).put("title", w.title).put("sub", w.subtitle)
        .put("img", w.image ?: "").put("url", hideLogin(acc, w.url)).put("pos", w.positionMs)
        .put("dur", w.durationMs).put("at", w.updatedAt).put("sid", w.seriesId)

    private fun fromJson(acc: Account, o: JSONObject): WatchItem? {
        val key = o.optString("key")
        val kind = o.optString("kind")
        val url = o.optString("url")
        if (key.isEmpty() || !syncable(kind) || url.isEmpty()) return null
        return WatchItem(
            key, kind, o.optString("title"), o.optString("sub"), o.optString("img").ifEmpty { null },
            showLogin(acc, url), o.optLong("pos"), o.optLong("dur"), o.optLong("at"), o.optInt("sid")
        )
    }

    // ---------- Netwerk ----------

    private fun request(method: String, path: String, body: String? = null): String? {
        val conn = URL(BASE + path + ".json").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.requestMethod = method
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("kijktv_sync", Context.MODE_PRIVATE)
    private fun sentPrefs(ctx: Context) = ctx.getSharedPreferences("kijktv_sync_sent", Context.MODE_PRIVATE)

    /** Onthoud tot welke stand een item al op de server stond (voor het herkennen van verwijderen). */
    @Synchronized
    private fun markSent(ctx: Context, itemKey: String, at: Long) {
        val sp = sentPrefs(ctx)
        if (sp.getLong(itemKey, 0L) < at) sp.edit().putLong(itemKey, at).apply()
    }

    // ---------- Wijzigingen op dit apparaat ----------

    /** Een item is bekeken of de positie is veranderd: even later doorsturen. */
    fun changed(ctx: Context, w: WatchItem) {
        if (!syncable(w.kind)) return
        val app = ctx.applicationContext
        val acc = Prefs(app).account
        val key = keyFor(acc) ?: return
        val id = itemId(w.key)
        val json = toJson(acc!!, w).toString()
        synchronized(pending) {
            latest[id] = json
            // Al ingepland? Dan gaat de nieuwste stand mee met die keer (niet steeds opnieuw uitstellen).
            if (pending.containsKey(id)) return
            val r = Runnable {
                val body = synchronized(pending) { pending.remove(id); latest.remove(id) }
                if (body != null) pool.execute {
                    try {
                        if (request("PUT", "$key/h/$id", body) != null) markSent(app, w.key, JSONObject(body).optLong("at"))
                    } catch (_: Exception) { }
                }
            }
            pending[id] = r
            main.postDelayed(r, PUSH_DELAY)
        }
    }

    /** Alles wat nog klaarstaat meteen versturen (bij het sluiten van de speler). */
    fun flush() {
        val list = synchronized(pending) {
            val l = pending.values.toList()
            pending.clear()
            l
        }
        for (r in list) { main.removeCallbacks(r); r.run() }
    }

    fun removed(ctx: Context, itemKey: String, kind: String) {
        if (!syncable(kind)) return
        val key = keyFor(Prefs(ctx).account) ?: return
        val id = itemId(itemKey)
        synchronized(pending) { pending.remove(id)?.let { main.removeCallbacks(it) }; latest.remove(id) }
        pool.execute { try { request("DELETE", "$key/h/$id") } catch (_: Exception) { } }
    }

    fun cleared(ctx: Context) {
        val key = keyFor(Prefs(ctx).account) ?: return
        synchronized(pending) { pending.values.forEach { main.removeCallbacks(it) }; pending.clear(); latest.clear() }
        pool.execute { try { request("DELETE", "$key/h") } catch (_: Exception) { } }
    }

    fun favoritesChanged(ctx: Context) {
        val app = ctx.applicationContext
        val acc = Prefs(app).account
        val key = keyFor(acc) ?: return
        val now = System.currentTimeMillis()
        prefs(app).edit().putLong("favT", now).apply()
        val body = favJson(app, now) ?: return
        pool.execute { try { request("PUT", "$key/f", body) } catch (_: Exception) { } }
    }

    /** Favorieten als lijst met zender-nummers (stream_id), want de links verschillen per apparaat. */
    private fun favJson(ctx: Context, t: Long): String? {
        if (Repo.all.isEmpty()) return null
        val favs = Prefs(ctx).favorites()
        val ids = JSONArray()
        Repo.all.filter { favs.contains(it.url) && it.streamId != null }.map { it.streamId!! }.distinct().forEach { ids.put(it) }
        return JSONObject().put("t", t).put("ids", ids).toString()
    }

    // ---------- Ophalen van de andere apparaten ----------

    /**
     * Haalt de stand van de andere apparaten op en voegt die samen met wat hier staat.
     * [onChanged] wordt (op de hoofdthread) aangeroepen als er hier iets veranderd is.
     */
    fun pull(ctx: Context, force: Boolean = false, onChanged: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val acc = Prefs(app).account
        val key = keyFor(acc) ?: return
        val now = System.currentTimeMillis()
        if (pulling) { if (force) again = Pair(app, onChanged); return }
        if (!force && now - lastPullAt < PULL_EVERY) return
        pulling = true
        lastPullAt = now
        pool.execute {
            var changed = false
            var ok = true
            fun put(path: String, body: String) { if (request("PUT", path, body) == null) ok = false }
            try {
                val text = request("GET", key) ?: return@execute
                val root = if (text.trim() == "null") JSONObject() else JSONObject(text)
                val sp = prefs(app)
                val lastSync = sp.getLong("last_$key", 0L)
                val syncStart = System.currentTimeMillis()

                // Kijkgeschiedenis
                val remote = HashMap<String, WatchItem>()
                root.optJSONObject("h")?.let { h ->
                    for (id in h.keys()) {
                        val w = h.optJSONObject(id)?.let { fromJson(acc!!, it) } ?: continue
                        remote[w.key] = w
                    }
                }
                val local = History.all(app).filter { syncable(it.kind) }.associateBy { it.key }
                for (r in remote.values) {
                    val l = local[r.key]
                    if (l == null || r.updatedAt > l.updatedAt) { History.applyRemote(app, r); changed = true }
                }
                for (l in local.values) {
                    val r = remote[l.key]
                    if (r == null) {
                        if (l.updatedAt <= lastSync || l.updatedAt <= sentPrefs(app).getLong(l.key, 0L)) {
                            // Op een ander apparaat verwijderd.
                            History.removeLocal(app, l.key); changed = true
                        } else {
                            put("$key/h/${itemId(l.key)}", toJson(acc!!, l).toString())
                        }
                    } else if (l.updatedAt > r.updatedAt) {
                        put("$key/h/${itemId(l.key)}", toJson(acc!!, l).toString())
                    }
                }

                // Favoriete zenders
                if (Repo.all.isNotEmpty()) {
                    val favT = sp.getLong("favT", 0L)
                    val f = root.optJSONObject("f")
                    val rt = f?.optLong("t") ?: 0L
                    if (f != null && rt > favT) {
                        val ids = HashSet<Int>()
                        val arr = f.optJSONArray("ids")
                        if (arr != null) for (i in 0 until arr.length()) ids.add(arr.optInt(i))
                        val urls = Repo.all.filter { it.streamId != null && ids.contains(it.streamId) }.map { it.url }.toSet()
                        // Favorieten die niet in de zenderlijst staan (bv. M3U-zenders) blijven staan.
                        val known = Repo.all.map { it.url }.toSet()
                        val keep = Prefs(app).favorites().filter { !known.contains(it) }
                        Prefs(app).setFavorites(urls + keep)
                        sp.edit().putLong("favT", rt).apply()
                        changed = true
                    } else if (favT > rt) {
                        favJson(app, favT)?.let { put("$key/f", it) }
                    }
                }

                // Alleen als alles is aangekomen: anders zou een niet-verstuurd item later als "verwijderd" gelden.
                if (ok) {
                    sp.edit().putLong("last_$key", syncStart).apply()
                    val sent = sentPrefs(app)
                    val ed = sent.edit()
                    for ((k, v) in sent.all) if (v is Long && v <= syncStart) ed.remove(k)
                    ed.apply()
                }
            } catch (_: Exception) {
                // Geen internet of database onbereikbaar: volgende keer opnieuw.
            } finally {
                pulling = false
                if (changed && onChanged != null) main.post(onChanged)
                again?.let { a -> again = null; main.post { pull(a.first, true, a.second) } }
            }
        }
    }
}

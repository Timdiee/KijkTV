package nl.kijktv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Eén regel in de kijkgeschiedenis. */
class WatchItem(
    val key: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val image: String?,
    val url: String,
    var positionMs: Long = 0,
    var durationMs: Long = 0,
    var updatedAt: Long = System.currentTimeMillis(),
    val seriesId: Int = 0,
    /** Beschrijving voor het pauzescherm (wordt niet in de geschiedenis bewaard). */
    val plot: String = ""
) {
    val isVideo get() = kind == MOVIE || kind == EPISODE || kind == CATCHUP
    val finished get() = durationMs > 0 && positionMs >= durationMs * 95 / 100
    /** Halverwege gestopt en de moeite waard om verder te kijken. */
    val resumable get() = isVideo && positionMs > 60_000 && !finished
    val progress get() = if (durationMs > 0) ((positionMs * 1000) / durationMs).toInt().coerceIn(0, 1000) else 0

    companion object {
        const val LIVE = "live"
        const val MOVIE = "movie"
        const val EPISODE = "episode"
        const val CATCHUP = "catchup"
    }
}

/** Kijkgeschiedenis + "verder kijken", opgeslagen op de box. */
object History {
    private const val MAX = 300
    private var items: MutableList<WatchItem>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "history.json")

    @Synchronized
    private fun list(ctx: Context): MutableList<WatchItem> {
        items?.let { return it }
        val out = ArrayList<WatchItem>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(
                        WatchItem(
                            o.optString("key"), o.optString("kind"), o.optString("title"),
                            o.optString("sub"), o.optString("img").ifEmpty { null }, o.optString("url"),
                            o.optLong("pos"), o.optLong("dur"), o.optLong("at"), o.optInt("sid")
                        )
                    )
                }
            }
        } catch (_: Exception) { }
        items = out
        return out
    }

    @Synchronized
    private fun save(ctx: Context) {
        val l = items ?: return
        try {
            val arr = JSONArray()
            for (w in l) {
                arr.put(
                    JSONObject()
                        .put("key", w.key).put("kind", w.kind).put("title", w.title).put("sub", w.subtitle)
                        .put("img", w.image ?: "").put("url", w.url).put("pos", w.positionMs)
                        .put("dur", w.durationMs).put("at", w.updatedAt).put("sid", w.seriesId)
                )
            }
            val tmp = File(ctx.filesDir, "history.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file(ctx))
        } catch (_: Exception) { }
    }

    @Synchronized
    fun all(ctx: Context): List<WatchItem> = list(ctx).sortedByDescending { it.updatedAt }

    @Synchronized
    fun get(ctx: Context, key: String): WatchItem? = list(ctx).firstOrNull { it.key == key }

    fun resumable(ctx: Context): List<WatchItem> = all(ctx).filter { it.resumable }

    /** Voegt toe of zet bovenaan; bewaart de opgeslagen positie als die er al was. */
    @Synchronized
    fun record(ctx: Context, item: WatchItem) {
        val l = list(ctx)
        val old = l.firstOrNull { it.key == item.key }
        if (old != null) {
            l.remove(old)
            if (item.positionMs == 0L) item.positionMs = old.positionMs
            if (item.durationMs == 0L) item.durationMs = old.durationMs
        }
        item.updatedAt = System.currentTimeMillis()
        l.add(0, item)
        while (l.size > MAX) l.removeAt(l.size - 1)
        save(ctx)
    }

    @Synchronized
    fun updatePosition(ctx: Context, key: String, pos: Long, dur: Long) {
        val w = list(ctx).firstOrNull { it.key == key } ?: return
        w.positionMs = pos
        if (dur > 0) w.durationMs = dur
        w.updatedAt = System.currentTimeMillis()
        save(ctx)
    }

    @Synchronized
    fun remove(ctx: Context, key: String) {
        list(ctx).removeAll { it.key == key }
        save(ctx)
    }

    @Synchronized
    fun clear(ctx: Context) {
        list(ctx).clear()
        save(ctx)
    }

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    /**
     * Speelt een film/aflevering/uitzending af. Was je al eens begonnen, dan vraagt hij
     * "verder kijken of opnieuw beginnen?".
     */
    fun playWithResume(activity: Activity, item: WatchItem) {
        val saved = get(activity, item.key)
        if (saved != null && saved.resumable) {
            AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(item.title)
                .setItems(
                    arrayOf(
                        "▶  Verder kijken vanaf ${clock(saved.positionMs)}",
                        "⟲  Opnieuw beginnen"
                    )
                ) { _, which ->
                    activity.startActivity(VodPlayerActivity.intent(activity, item, if (which == 0) saved.positionMs else 0))
                }
                .show()
        } else {
            activity.startActivity(VodPlayerActivity.intent(activity, item, 0))
        }
    }
}

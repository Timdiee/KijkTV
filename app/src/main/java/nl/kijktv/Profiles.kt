package nl.kijktv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Een kijkprofiel (zoals bij Netflix): eigen Verder kijken en geschiedenis. */
class Profile(
    val id: String,
    var name: String,
    var color: Int,
    /** Laatst gewijzigd (voor het samenvoegen tussen apparaten). */
    var t: Long,
    var deleted: Boolean = false
)

/**
 * Profielen. De lijst gaat mee naar je andere apparaten (Sync); welk profiel er op dit
 * apparaat kijkt, blijft op het apparaat zelf.
 * Dezelfde regels staan in de LG-versie (webos/app/js/data.js: KTV.profiles).
 */
object Profiles {
    const val MAIN = "main"
    const val MAX = 6
    val COLORS = intArrayOf(
        0xFF2F6FED.toInt(), 0xFFE5484D.toInt(), 0xFF30A46C.toInt(),
        0xFFF5A524.toInt(), 0xFF8E4EC6.toInt(), 0xFF0EA5E9.toInt()
    )

    /** Al gekozen sinds de app is gestart (dan niet opnieuw "Wie kijkt er?" vragen). */
    @Volatile var chosenThisRun = false

    private fun sp(ctx: Context) = ctx.getSharedPreferences("kijktv_profiles", Context.MODE_PRIVATE)

    @Synchronized
    fun all(ctx: Context): MutableList<Profile> {
        val out = ArrayList<Profile>()
        try {
            val arr = JSONArray(sp(ctx).getString("list", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Profile(o.optString("id"), o.optString("n"), o.optInt("c"), o.optLong("t"), o.optInt("d") == 1))
            }
        } catch (_: Exception) { }
        if (out.none { it.id == MAIN }) out.add(0, Profile(MAIN, "Ik", 0, 0))
        return out
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Profile>) {
        val arr = JSONArray()
        for (p in list) arr.put(toJson(p).put("id", p.id))
        sp(ctx).edit().putString("list", arr.toString()).apply()
    }

    fun toJson(p: Profile): JSONObject =
        JSONObject().put("n", p.name).put("c", p.color).put("t", p.t).put("d", if (p.deleted) 1 else 0)

    fun visible(ctx: Context): List<Profile> = all(ctx).filter { !it.deleted }

    fun current(ctx: Context): String {
        val id = sp(ctx).getString("cur", MAIN) ?: MAIN
        return if (id == MAIN || visible(ctx).any { it.id == id }) id else MAIN
    }

    fun currentProfile(ctx: Context): Profile = visible(ctx).firstOrNull { it.id == current(ctx) } ?: all(ctx).first { it.id == MAIN }

    fun setCurrent(ctx: Context, id: String) {
        if (id == current(ctx)) { chosenThisRun = true; return }
        Sync.flush()
        sp(ctx).edit().putString("cur", id).commit()
        chosenThisRun = true
        History.profileChanged()
    }

    fun add(ctx: Context, name: String): Profile? {
        val list = all(ctx)
        if (list.count { !it.deleted } >= MAX) return null
        val used = list.filter { !it.deleted }.map { it.color }.toSet()
        val color = COLORS.indices.firstOrNull { it !in used } ?: (list.size % COLORS.size)
        val id = java.lang.Long.toHexString(System.currentTimeMillis()) + Integer.toHexString((Math.random() * 0xfff).toInt())
        val p = Profile(id, name.trim().take(20).ifEmpty { "Profiel" }, color, System.currentTimeMillis())
        list.add(p)
        save(ctx, list)
        Sync.profileChanged(ctx, p)
        return p
    }

    fun rename(ctx: Context, id: String, name: String) {
        val list = all(ctx)
        val p = list.firstOrNull { it.id == id } ?: return
        p.name = name.trim().take(20).ifEmpty { p.name }
        p.t = System.currentTimeMillis()
        save(ctx, list)
        Sync.profileChanged(ctx, p)
    }

    /** Verwijdert een profiel met zijn geschiedenis (het eerste profiel kan niet weg). */
    fun delete(ctx: Context, id: String) {
        if (id == MAIN) return
        val list = all(ctx)
        val p = list.firstOrNull { it.id == id } ?: return
        p.deleted = true
        p.t = System.currentTimeMillis()
        save(ctx, list)
        History.deleteProfile(ctx, id)
        if (sp(ctx).getString("cur", MAIN) == id) setCurrent(ctx, MAIN)
        Sync.profileChanged(ctx, p)
        Sync.profileDeleted(ctx, id)
    }

    /**
     * Profielen van een ander apparaat samenvoegen. Geeft de profielen terug die hier nieuwer
     * zijn (die moeten naar de server) en of er hier iets veranderde.
     */
    @Synchronized
    fun merge(ctx: Context, remote: JSONObject?): Pair<List<Profile>, Boolean> {
        val list = all(ctx)
        val byId = list.associateBy { it.id }.toMutableMap()
        var changed = false
        val removedHere = ArrayList<String>()
        if (remote != null) {
            for (id in remote.keys()) {
                val o = remote.optJSONObject(id) ?: continue
                val r = Profile(id, o.optString("n"), o.optInt("c"), o.optLong("t"), o.optInt("d") == 1)
                val l = byId[id]
                if (l == null || r.t > l.t) {
                    if (r.deleted && (l == null || !l.deleted)) removedHere.add(id)
                    if (l == null) { list.add(r); byId[id] = r } else {
                        l.name = r.name; l.color = r.color; l.t = r.t; l.deleted = r.deleted
                    }
                    changed = true
                }
            }
        }
        val newer = list.filter { it.t > 0 && (remote?.optJSONObject(it.id)?.optLong("t") ?: 0L) < it.t }
        if (changed) {
            save(ctx, list)
            for (id in removedHere) History.deleteProfile(ctx, id)
            if (removedHere.contains(sp(ctx).getString("cur", MAIN))) setCurrent(ctx, MAIN)
        }
        return Pair(newer, changed)
    }
}

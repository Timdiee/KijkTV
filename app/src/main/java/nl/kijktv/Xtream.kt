package nl.kijktv

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Praat met IPTV-servers die "Xtream Codes" gebruiken (server + gebruikersnaam + wachtwoord). */
object Xtream {
    class Result(val channels: List<Channel>, val info: String?, val timezone: String?)
    private class Login(val info: String?, val ext: String, val timezone: String?)

    fun JSONObject.str(key: String): String {
        val v = optString(key, "")
        return if (v == "null") "" else v.trim()
    }

    fun apiUrl(acc: Account, action: String?): String {
        var u = "${acc.server}/player_api.php?username=${Http.enc(acc.user)}&password=${Http.enc(acc.pass)}"
        if (action != null) u += "&action=$action"
        return u
    }

    fun epgUrl(acc: Account) =
        "${acc.server}/xmltv.php?username=${Http.enc(acc.user)}&password=${Http.enc(acc.pass)}"

    private fun login(acc: Account): Login {
        val text = try {
            Http.getText(apiUrl(acc, null))
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("401") || msg.contains("403")) {
                throw Exception("Inloggen mislukt: gebruikersnaam of wachtwoord klopt niet.")
            }
            throw Exception("De server is niet bereikbaar. Klopt de server-link (inclusief :poort)?\n($msg)")
        }
        val obj = try { JSONObject(text) } catch (_: Exception) {
            throw Exception("Dit adres lijkt geen IPTV-server. Controleer de server-link.")
        }
        val ui = obj.optJSONObject("user_info")
            ?: throw Exception("Inloggen mislukt: gebruikersnaam of wachtwoord klopt niet.")
        if (ui.optInt("auth", 0) != 1) {
            throw Exception("Inloggen mislukt: gebruikersnaam of wachtwoord klopt niet.")
        }
        val status = ui.str("status")
        if (status.isNotEmpty() && !status.equals("Active", ignoreCase = true)) {
            throw Exception("Je abonnement is niet actief (status: $status). Neem contact op met je aanbieder.")
        }
        val exp = ui.str("exp_date").toLongOrNull()
        val info = if (exp != null && exp > 0) {
            "Geldig tot " + SimpleDateFormat("d MMM yyyy", Locale("nl", "NL")).format(Date(exp * 1000))
        } else null

        val formats = ArrayList<String>()
        ui.optJSONArray("allowed_output_formats")?.let { arr ->
            for (i in 0 until arr.length()) formats.add(arr.optString(i).lowercase())
        }
        val ext = when {
            formats.isEmpty() || formats.contains("ts") -> "ts"
            formats.contains("m3u8") -> "m3u8"
            else -> "ts"
        }
        val tz = obj.optJSONObject("server_info")?.str("timezone")?.ifEmpty { null }
        return Login(info, ext, tz)
    }

    fun array(text: String): JSONArray =
        try { JSONArray(text) } catch (_: Exception) { JSONArray() }

    fun load(acc: Account): Result {
        val login = login(acc)

        val catNames = LinkedHashMap<String, String>()
        val cats = array(Http.getText(apiUrl(acc, "get_live_categories")))
        for (i in 0 until cats.length()) {
            val o = cats.optJSONObject(i) ?: continue
            catNames[o.str("category_id")] = o.str("category_name").ifEmpty { "Overig" }
        }

        val byCat = LinkedHashMap<String, ArrayList<Channel>>()
        for (k in catNames.keys) byCat[k] = ArrayList()

        val streams = array(Http.getText(apiUrl(acc, "get_live_streams")))
        val user = Http.encPath(acc.user)
        val pass = Http.encPath(acc.pass)
        for (i in 0 until streams.length()) {
            val o = streams.optJSONObject(i) ?: continue
            val id = o.optInt("stream_id", -1)
            if (id < 0) continue
            val cat = o.str("category_id")
            val name = o.str("name").ifEmpty { "Zender $id" }
            val logo = o.str("stream_icon").takeIf { it.startsWith("http") }
            val epgId = o.str("epg_channel_id").ifEmpty { null }
            val url = "${acc.server}/live/$user/$pass/$id.${login.ext}"
            val archive = if (o.optInt("tv_archive", 0) == 1)
                o.optInt("tv_archive_duration", 0).coerceAtLeast(1) else 0
            byCat.getOrPut(cat) { ArrayList() }
                .add(Channel(name, url, catNames[cat] ?: "Overig", logo, epgId, id, archive))
        }
        val all = ArrayList<Channel>()
        for (list in byCat.values) all.addAll(list)
        return Result(all, login.info, login.timezone)
    }

    /** Kan dit programma teruggekeken worden? (al begonnen en binnen de bewaartermijn) */
    fun canCatchup(c: Channel, p: Programme, now: Long = System.currentTimeMillis()): Boolean {
        if (c.streamId == null || p.start > now) return false
        if (p.archived) return true
        if (c.archiveDays <= 0) return false
        return now - p.start < c.archiveDays * 24L * 3600 * 1000
    }

    /** Adres om een programma terug te kijken (vanaf het begin). */
    fun catchupUrl(acc: Account, c: Channel, p: Programme, tz: String?): String? {
        val sid = c.streamId ?: return null
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        fmt.timeZone = if (tz != null) java.util.TimeZone.getTimeZone(tz) else java.util.TimeZone.getDefault()
        val end = maxOf(p.stop, p.start + 60_000)
        val minutes = ((end - p.start) / 60_000).coerceAtLeast(1) + 5
        return "${acc.server}/timeshift/${Http.encPath(acc.user)}/${Http.encPath(acc.pass)}/" +
            "$minutes/${fmt.format(Date(p.start))}/$sid.ts"
    }

    /** Korte gids (nu + straks) voor één zender, als de volledige gids hem niet kent. */
    fun shortEpg(acc: Account, streamId: Int): List<Programme> {
        val text = Http.getText(apiUrl(acc, "get_short_epg&stream_id=$streamId&limit=8"))
        val obj = try { JSONObject(text) } catch (_: Exception) { return emptyList() }
        val arr = obj.optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<Programme>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = o.str("start_timestamp").toLongOrNull() ?: continue
            val stop = o.str("stop_timestamp").toLongOrNull() ?: continue
            out.add(Programme(start * 1000, stop * 1000, b64(o.str("title")), b64(o.str("description")).take(400)))
        }
        out.sortBy { it.start }
        return out
    }

    /** Volledige gids van één zender, inclusief de afgelopen dagen (met "terug te kijken"-vlag). */
    fun archiveEpg(acc: Account, streamId: Int): List<Programme> {
        val text = Http.getText(apiUrl(acc, "get_simple_data_table&stream_id=$streamId"))
        val obj = try { JSONObject(text) } catch (_: Exception) { return emptyList() }
        val arr = obj.optJSONArray("epg_listings") ?: return emptyList()
        val now = System.currentTimeMillis()
        val from = now - 8L * 24 * 3600 * 1000
        val to = now + 3L * 24 * 3600 * 1000
        val out = ArrayList<Programme>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = (o.str("start_timestamp").toLongOrNull() ?: continue) * 1000
            val stop = (o.str("stop_timestamp").toLongOrNull() ?: continue) * 1000
            if (stop < from || start > to || stop <= start) continue
            val archived = o.optInt("has_archive", 0) == 1 || o.str("has_archive") == "1"
            out.add(Programme(start, stop, b64(o.str("title")), b64(o.str("description")).take(400), archived))
        }
        out.sortBy { it.start }
        // Dubbele items (sommige servers sturen ze twee keer) eruit halen.
        return out.distinctBy { it.start }
    }

    private fun b64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (_: Exception) { s }
}

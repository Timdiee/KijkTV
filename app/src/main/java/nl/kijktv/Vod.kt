package nl.kijktv

import org.json.JSONArray
import org.json.JSONObject

class Movie(
    val id: Int,
    val name: String,
    val poster: String?,
    val category: String,
    val ext: String,
    val rating: String
)

class Series(
    val id: Int,
    val name: String,
    val cover: String?,
    val category: String,
    val plot: String,
    val rating: String
)

class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val ext: String,
    val plot: String,
    val durationSec: Int,
    val image: String?
)

class SeriesInfo(val series: Series, val plot: String, val seasons: Map<Int, List<Episode>>)

/** Films en series van een Xtream-aanbieder. Wordt pas geladen als je het scherm opent. */
object Vod {
    @Volatile var movies: List<Movie>? = null
    @Volatile var movieCats: List<String> = emptyList()
    @Volatile var series: List<Series>? = null
    @Volatile var seriesCats: List<String> = emptyList()
    @Volatile private var forServer: String? = null

    /** Afleveringen die na elkaar afgespeeld worden (voor "volgende aflevering"). */
    @Volatile var queue: List<Episode> = emptyList()
    @Volatile var queueSeries: Series? = null

    fun clear() {
        movies = null; series = null; movieCats = emptyList(); seriesCats = emptyList()
        queue = emptyList(); queueSeries = null; forServer = null
    }

    private fun checkAccount(acc: Account) {
        val k = acc.server + "|" + acc.user
        if (forServer != k) { clear(); forServer = k }
    }

    private fun JSONObject.s(key: String): String = with(Xtream) { str(key) }

    private fun categories(acc: Account, action: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        val arr = Xtream.array(Http.getText(Xtream.apiUrl(acc, action)))
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            map[o.s("category_id")] = o.s("category_name").ifEmpty { "Overig" }
        }
        return map
    }

    fun loadMovies(acc: Account): List<Movie> {
        checkAccount(acc)
        movies?.let { return it }
        val cats = categories(acc, "get_vod_categories")
        val arr = Xtream.array(Http.getText(Xtream.apiUrl(acc, "get_vod_streams")))
        val byCat = LinkedHashMap<String, ArrayList<Movie>>()
        for (k in cats.values) byCat[k] = ArrayList()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("stream_id", -1)
            if (id < 0) continue
            val cat = cats[o.s("category_id")] ?: "Overig"
            byCat.getOrPut(cat) { ArrayList() }.add(
                Movie(
                    id, o.s("name").ifEmpty { "Film $id" },
                    o.s("stream_icon").takeIf { it.startsWith("http") }, cat,
                    o.s("container_extension").ifEmpty { "mp4" }, o.s("rating")
                )
            )
        }
        val all = ArrayList<Movie>()
        for (l in byCat.values) all.addAll(l)
        movieCats = byCat.filterValues { it.isNotEmpty() }.keys.toList()
        movies = all
        return all
    }

    fun loadSeries(acc: Account): List<Series> {
        checkAccount(acc)
        series?.let { return it }
        val cats = categories(acc, "get_series_categories")
        val arr = Xtream.array(Http.getText(Xtream.apiUrl(acc, "get_series")))
        val byCat = LinkedHashMap<String, ArrayList<Series>>()
        for (k in cats.values) byCat[k] = ArrayList()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("series_id", -1)
            if (id < 0) continue
            val cat = cats[o.s("category_id")] ?: "Overig"
            byCat.getOrPut(cat) { ArrayList() }.add(
                Series(
                    id, o.s("name").ifEmpty { "Serie $id" },
                    o.s("cover").takeIf { it.startsWith("http") }, cat, o.s("plot"), o.s("rating")
                )
            )
        }
        val all = ArrayList<Series>()
        for (l in byCat.values) all.addAll(l)
        seriesCats = byCat.filterValues { it.isNotEmpty() }.keys.toList()
        series = all
        return all
    }

    fun seriesInfo(acc: Account, s: Series): SeriesInfo {
        val text = Http.getText(Xtream.apiUrl(acc, "get_series_info&series_id=${s.id}"))
        val obj = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
        val info = obj.optJSONObject("info")
        val plot = info?.s("plot")?.ifEmpty { null } ?: s.plot
        val seasons = sortedMapOf<Int, MutableList<Episode>>()

        fun addEpisodes(arr: JSONArray, seasonKey: Int) {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val id = e.s("id")
                if (id.isEmpty()) continue
                val season = e.optInt("season", seasonKey).let { if (it <= 0) seasonKey else it }
                val ei = e.optJSONObject("info")
                val ep = Episode(
                    id, season, e.optInt("episode_num", i + 1),
                    e.s("title").ifEmpty { "Aflevering ${i + 1}" },
                    e.s("container_extension").ifEmpty { "mp4" },
                    ei?.s("plot").orEmpty(),
                    ei?.optInt("duration_secs", 0) ?: 0,
                    ei?.s("movie_image")?.takeIf { it.startsWith("http") }
                )
                seasons.getOrPut(season) { ArrayList() }.add(ep)
            }
        }

        val eps = obj.opt("episodes")
        if (eps is JSONObject) {
            val keys = eps.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                eps.optJSONArray(k)?.let { addEpisodes(it, k.toIntOrNull() ?: 1) }
            }
        } else if (eps is JSONArray) {
            for (i in 0 until eps.length()) eps.optJSONArray(i)?.let { addEpisodes(it, i + 1) }
        }
        for (l in seasons.values) l.sortBy { it.number }
        return SeriesInfo(s, plot, seasons)
    }

    fun movieUrl(acc: Account, m: Movie) =
        "${acc.server}/movie/${Http.encPath(acc.user)}/${Http.encPath(acc.pass)}/${m.id}.${m.ext}"

    fun episodeUrl(acc: Account, e: Episode) =
        "${acc.server}/series/${Http.encPath(acc.user)}/${Http.encPath(acc.pass)}/${e.id}.${e.ext}"

    fun episodeLabel(e: Episode) = "S${e.season} A${e.number}"
}

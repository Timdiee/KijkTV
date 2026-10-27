package nl.kijktv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Films of Series bladeren: categorieën links, posters rechts. */
class MediaActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(Ui.wrap(newBase))
    }

    companion object {
        private const val KEY_RESUME = "__resume__"
        private const val KEY_ALL = "__all__"

        fun movies(ctx: Context) = Intent(ctx, MediaActivity::class.java).putExtra("series", false)
        fun series(ctx: Context) = Intent(ctx, MediaActivity::class.java).putExtra("series", true)
    }

    private var isSeries = false
    private lateinit var prefs: Prefs
    private lateinit var catsView: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var loadingBox: View
    private lateinit var loading: ProgressBar
    private lateinit var message: TextView
    private lateinit var search: EditText
    private lateinit var catAdapter: GroupAdapter
    private lateinit var posterAdapter: PosterAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var pendingCat: Runnable? = null
    private var currentCat = KEY_ALL
    private var movieList: List<Movie> = emptyList()
    private var seriesList: List<Series> = emptyList()
    /** Wat er nu in het raster staat. */
    private var shownMovies: List<Movie> = emptyList()
    private var shownSeries: List<Series> = emptyList()
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media)
        setupBackButton()
        prefs = Prefs(this)
        isSeries = intent.getBooleanExtra("series", false)

        findViewById<TextView>(R.id.title).text = if (isSeries) "Series" else "Films"
        catsView = findViewById(R.id.cats)
        grid = findViewById(R.id.grid)
        loadingBox = findViewById(R.id.loadingBox)
        loading = findViewById(R.id.loading)
        message = findViewById(R.id.message)
        search = findViewById(R.id.search)

        catAdapter = GroupAdapter(
            onFocus = { g ->
                pendingCat?.let { handler.removeCallbacks(it) }
                val r = Runnable { if (g.key != currentCat || search.text.isNotEmpty()) selectCat(g.key) }
                pendingCat = r
                handler.postDelayed(r, 150)
            },
            onClick = { g ->
                pendingCat?.let { handler.removeCallbacks(it) }
                if (g.key != currentCat || search.text.isNotEmpty()) selectCat(g.key)
                grid.post { focusGrid(0) }
            }
        )
        posterAdapter = PosterAdapter { i -> open(i) }
        catsView.layoutManager = LinearLayoutManager(this)
        catsView.adapter = catAdapter
        catsView.itemAnimator = null
        grid.layoutManager = GridLayoutManager(this, 5)
        grid.adapter = posterAdapter
        grid.itemAnimator = null

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (ready) applySearch(s?.toString().orEmpty()) }
        })

        val acc = prefs.account
        if (acc == null || !acc.isXtream) {
            loading.visibility = View.GONE
            message.text = "Films en series werken als je inlogt met server, gebruikersnaam en wachtwoord.\n\nMet een losse M3U-link staan films en series (als je aanbieder ze levert) gewoon als groepen bij Live TV."
            return
        }
        message.text = if (isSeries) "Series laden…" else "Films laden…"
        Thread {
            try {
                if (isSeries) {
                    val l = Vod.loadSeries(acc)
                    runOnUiThread { if (!isFinishing) { seriesList = l; onLoaded() } }
                } else {
                    val l = Vod.loadMovies(acc)
                    runOnUiThread { if (!isFinishing) { movieList = l; onLoaded() } }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    loading.visibility = View.GONE
                    message.text = "Laden mislukt.\n${e.message ?: ""}"
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // Na het kijken: voortgangsbalkjes en "verder kijken" bijwerken.
        if (ready) {
            rebuildCats()
            if (search.text.isEmpty()) selectCat(currentCat, keepScroll = true)
        }
    }

    private fun onLoaded() {
        ready = true
        rebuildCats()
        val empty = if (isSeries) seriesList.isEmpty() else movieList.isEmpty()
        if (empty) {
            loading.visibility = View.GONE
            message.text = if (isSeries) "Je aanbieder heeft geen series." else "Je aanbieder heeft geen films."
            return
        }
        loadingBox.visibility = View.GONE
        val start = if (resumeCount() > 0) KEY_RESUME else KEY_ALL
        selectCat(start)
        catsView.post {
            (catsView.layoutManager as LinearLayoutManager).findViewByPosition(catAdapter.indexOf(start))?.requestFocus()
        }
    }

    private fun resumeMovies(): List<Movie> {
        val byId = movieList.associateBy { "mv:${it.id}" }
        return History.resumable(this).filter { it.kind == WatchItem.MOVIE }.mapNotNull { byId[it.key] }
    }

    private fun resumeSeries(): List<Series> {
        val byId = seriesList.associateBy { it.id }
        return History.resumable(this).filter { it.kind == WatchItem.EPISODE }
            .mapNotNull { byId[it.seriesId] }.distinctBy { it.id }
    }

    private fun resumeCount() = if (isSeries) resumeSeries().size else resumeMovies().size

    private fun rebuildCats() {
        val items = ArrayList<GroupItem>()
        val rc = resumeCount()
        if (rc > 0) items.add(GroupItem(KEY_RESUME, "Verder kijken", rc))
        if (isSeries) {
            items.add(GroupItem(KEY_ALL, "Alle series", seriesList.size))
            val counts = seriesList.groupingBy { it.category }.eachCount()
            for (c in Vod.seriesCats) items.add(GroupItem(c, c, counts[c] ?: 0))
        } else {
            items.add(GroupItem(KEY_ALL, "Alle films", movieList.size))
            val counts = movieList.groupingBy { it.category }.eachCount()
            for (c in Vod.movieCats) items.add(GroupItem(c, c, counts[c] ?: 0))
        }
        catAdapter.submit(items)
        if (rc == 0 && currentCat == KEY_RESUME) currentCat = KEY_ALL
        catAdapter.setActive(currentCat)
    }

    private fun selectCat(key: String, keepScroll: Boolean = false) {
        if (search.text.isNotEmpty()) {
            ready = false
            search.setText("")
            ready = true
        }
        currentCat = key
        if (isSeries) {
            shownSeries = when (key) {
                KEY_RESUME -> resumeSeries()
                KEY_ALL -> seriesList
                else -> seriesList.filter { it.category == key }
            }
        } else {
            shownMovies = when (key) {
                KEY_RESUME -> resumeMovies()
                KEY_ALL -> movieList
                else -> movieList.filter { it.category == key }
            }
        }
        showGrid()
        if (!keepScroll) grid.scrollToPosition(0)
        catAdapter.setActive(key)
    }

    private fun applySearch(q: String) {
        val query = q.trim()
        if (query.isEmpty()) { selectCat(currentCat); return }
        if (isSeries) shownSeries = seriesList.filter { it.name.contains(query, ignoreCase = true) }
        else shownMovies = movieList.filter { it.name.contains(query, ignoreCase = true) }
        showGrid()
        grid.scrollToPosition(0)
    }

    private fun showGrid() {
        if (isSeries) {
            posterAdapter.submit(shownSeries.map { PosterItem(it.name, it.cover, 0) })
        } else {
            posterAdapter.submit(shownMovies.map { m ->
                val w = History.get(this, "mv:${m.id}")
                PosterItem(m.name, m.poster, if (w != null && w.resumable) w.progress else 0)
            })
        }
    }

    private fun focusGrid(i: Int) {
        if (posterAdapter.itemCount == 0) return
        val lm = grid.layoutManager as GridLayoutManager
        lm.scrollToPositionWithOffset(i, 0)
        grid.post { lm.findViewByPosition(i)?.requestFocus() }
    }

    private fun open(i: Int) {
        val acc = prefs.account ?: return
        if (isSeries) {
            val s = shownSeries.getOrNull(i) ?: return
            startActivity(SeriesActivity.intent(this, s.id))
        } else {
            val m = shownMovies.getOrNull(i) ?: return
            History.playWithResume(this, VodPlayerActivity.movieItem(acc, m))
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Links vanuit de eerste kolom van het raster: terug naar de actieve categorie.
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && grid.hasFocus()) {
            val f = grid.focusedChild
            val pos = if (f != null) grid.getChildAdapterPosition(f) else -1
            if (pos >= 0 && pos % 5 == 0) {
                val idx = catAdapter.indexOf(currentCat)
                val lm = catsView.layoutManager as LinearLayoutManager
                val v = lm.findViewByPosition(idx)
                if (v != null) v.requestFocus() else {
                    lm.scrollToPositionWithOffset(idx, 80)
                    catsView.post { lm.findViewByPosition(idx)?.requestFocus() }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

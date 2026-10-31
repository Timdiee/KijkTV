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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.Normalizer

/** Zoeken in alles: zenders, films en series. */
class SearchActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Ui.wrap(newBase))
    }

    companion object {
        private const val LIMIT = 60
        fun intent(ctx: Context) = Intent(ctx, SearchActivity::class.java)

        /** Kleine letters en zonder accenten, zodat "cafe" ook "Café" vindt. */
        private val MARKS = Regex("\\p{M}+")
        private val SPACES = Regex("\\s+")

        fun norm(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

        /**
         * Hoe goed een naam bij de zoekwoorden past (hoger is beter, -1 = past niet).
         * Alle woorden moeten erin staan; begint de naam ermee, dan komt hij bovenaan.
         */
        fun score(name: String, words: List<String>, whole: String): Int {
            val n = norm(name)
            for (w in words) if (!n.contains(w)) return -1
            return when {
                n == whole -> 3
                n.startsWith(whole) -> 2
                else -> {
                    val i = n.indexOf(words.first())
                    if (i <= 0 || !n[i - 1].isLetterOrDigit()) 1 else 0
                }
            }
        }

        fun <T> find(items: List<T>, q: String, name: (T) -> String): List<T> {
            val whole = norm(q.trim())
            val words = whole.split(SPACES).filter { it.isNotEmpty() }
            if (words.isEmpty()) return emptyList()
            return items.asSequence()
                .map { it to score(name(it), words, whole) }
                .filter { it.second >= 0 }
                .sortedByDescending { it.second }
                .map { it.first }
                .take(LIMIT)
                .toList()
        }
    }

    private lateinit var query: EditText
    private lateinit var message: TextView
    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    @Volatile private var vodLoading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        setupBackButton()
        query = findViewById(R.id.query)
        message = findViewById(R.id.message)
        rows = findViewById(R.id.rows)
        scroll = findViewById(R.id.scroll)

        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { handler.removeCallbacks(it) }
                val r = Runnable { search() }
                pending = r
                handler.postDelayed(r, 250)
            }
        })
        query.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                pending?.let { handler.removeCallbacks(it) }
                search()
                hideKeyboard()
                focusFirstResult()
                true
            } else false
        }
        query.requestFocus()
        query.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
        }
        loadVod()
    }

    /** Films en series worden pas geladen als je ze nodig hebt. */
    private fun loadVod() {
        val acc = Prefs(this).account ?: return
        if (!acc.isXtream || (Vod.movies != null && Vod.series != null)) return
        vodLoading = true
        Thread {
            try { Vod.loadMovies(acc) } catch (_: Exception) { }
            try { Vod.loadSeries(acc) } catch (_: Exception) { }
            vodLoading = false
            runOnUiThread { if (!isFinishing && query.text.isNotBlank()) search() }
        }.start()
    }

    private fun search() {
        val q = query.text.toString()
        rows.removeAllViews()
        if (q.isBlank()) {
            message.text = "Typ een naam van een zender, film of serie."
            message.visibility = View.VISIBLE
            return
        }
        val acc = Prefs(this).account
        val channels = find(Repo.all, q) { it.name }
        val movies = find(Vod.movies.orEmpty(), q) { it.name }
        val series = find(Vod.series.orEmpty(), q) { it.name }

        if (channels.isNotEmpty()) addRow("Zenders  ·  ${channels.size}", channels.map { ch ->
            HomeCard(
                poster = false, title = ch.name, sub = ch.group, image = null, logo = ch.logo, progress = 0,
                heroLabel = "", heroMeta = "", heroDesc = "", heroImage = null, channel = ch,
                action = {
                    Repo.playList = channels
                    startActivity(PlayerActivity.live(this, channels.indexOf(ch).coerceAtLeast(0)))
                }
            )
        })
        if (movies.isNotEmpty()) addRow("Films  ·  ${movies.size}", movies.map { m ->
            val w = History.get(this, "mv:${m.id}")
            HomeCard(
                poster = true, title = m.name, sub = "", image = m.poster, logo = null,
                progress = if (w != null && w.resumable) w.progress else 0,
                heroLabel = "", heroMeta = "", heroDesc = "", heroImage = null,
                action = { if (acc != null) History.playWithResume(this, VodPlayerActivity.movieItem(acc, m)) }
            )
        })
        if (series.isNotEmpty()) addRow("Series  ·  ${series.size}", series.map { s ->
            HomeCard(
                poster = true, title = s.name, sub = "", image = s.cover, logo = null, progress = 0,
                heroLabel = "", heroMeta = "", heroDesc = "", heroImage = null,
                action = { startActivity(SeriesActivity.intent(this, s.id)) }
            )
        })

        val none = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
        message.visibility = if (none || vodLoading) View.VISIBLE else View.GONE
        message.text = when {
            none && vodLoading -> "Zoeken…  (films en series worden nog geladen)"
            none -> "Niets gevonden voor \"${q.trim()}\"."
            else -> "Films en series worden nog geladen…"
        }
        scroll.scrollTo(0, 0)
    }

    private fun addRow(title: String, cards: List<HomeCard>) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_home_row, rows, false)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        val list = row.findViewById<RecyclerView>(R.id.rowList)
        list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        list.itemAnimator = null
        list.clipChildren = false
        (row as ViewGroup).clipChildren = false
        list.adapter = CardAdapter(cards) { }
        rows.addView(row)
    }

    private fun focusFirstResult() {
        if (rows.childCount == 0) return
        val list = rows.getChildAt(0).findViewById<RecyclerView>(R.id.rowList)
        list.post { (list.layoutManager as LinearLayoutManager).findViewByPosition(0)?.requestFocus() }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(query.windowToken, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Vanuit het zoekvak met pijltje omlaag naar de resultaten.
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && query.hasFocus() && rows.childCount > 0) {
            hideKeyboard()
            focusFirstResult()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        // Terug van een film: voortgangsbalkjes bijwerken.
        if (rows.childCount > 0) {
            for (i in 0 until rows.childCount) {
                (rows.getChildAt(i).findViewById<RecyclerView>(R.id.rowList).adapter as? CardAdapter)?.refreshAll()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

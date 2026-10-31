package nl.kijktv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Kijkgeschiedenis: alles wat je keek, verder kijken, en alles wissen. */
class HistoryActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(Ui.wrap(newBase))
    }

    private enum class Filter { ALL, RESUME, VOD, LIVE }

    private lateinit var listView: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: HistoryAdapter
    private lateinit var tabs: Map<Filter, Button>
    private var filter = Filter.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        setupBackButton()
        listView = findViewById(R.id.list)
        empty = findViewById(R.id.empty)

        tabs = mapOf(
            Filter.ALL to findViewById(R.id.fAll),
            Filter.RESUME to findViewById(R.id.fResume),
            Filter.VOD to findViewById(R.id.fVod),
            Filter.LIVE to findViewById(R.id.fLive)
        )
        for ((f, b) in tabs) {
            b.setOnClickListener { setFilter(f) }
            b.setOnFocusChangeListener { _, has -> if (has) setFilter(f) }
        }
        findViewById<Button>(R.id.clearBtn).setOnClickListener { confirmClear() }

        adapter = HistoryAdapter(onClick = { play(it) }, onLongClick = { askRemove(it) })
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter
        listView.itemAnimator = null

        if (History.resumable(this).isNotEmpty()) filter = Filter.RESUME
        tabs[filter]?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        Sync.pull(this, force = true) { if (!isFinishing) refresh() }
    }

    private fun setFilter(f: Filter) {
        if (f == filter && adapter.itemCount > 0) return
        filter = f
        refresh()
    }

    private fun refresh() {
        for ((f, b) in tabs) b.isActivated = f == filter
        val all = History.all(this)
        val list = when (filter) {
            Filter.ALL -> all
            Filter.RESUME -> all.filter { it.resumable }
            Filter.VOD -> all.filter { it.isVideo }
            Filter.LIVE -> all.filter { it.kind == WatchItem.LIVE }
        }
        adapter.submit(list)
        empty.text = when {
            all.isEmpty() -> "Nog niets bekeken."
            filter == Filter.RESUME -> "Niets om verder te kijken. Films en afleveringen die je halverwege stopt, komen hier."
            else -> "Niets in deze lijst."
        }
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun play(w: WatchItem) {
        if (w.kind == WatchItem.LIVE) {
            val all = Repo.all
            val idx = all.indexOfFirst { it.url == w.url }
            if (idx < 0) {
                Toast.makeText(this, "Deze zender staat niet meer in je lijst", Toast.LENGTH_SHORT).show()
                return
            }
            Repo.playList = all
            startActivity(PlayerActivity.live(this, idx))
            return
        }
        if (w.kind == WatchItem.EPISODE) {
            // Zodat na deze aflevering de volgende vanzelf start, als de serie al geladen is.
            val s = Vod.series?.firstOrNull { it.id == w.seriesId }
            if (s != null && Vod.queueSeries?.id != s.id) { Vod.queue = emptyList(); Vod.queueSeries = s }
        }
        History.playWithResume(this, w)
    }

    private fun askRemove(w: WatchItem) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(w.title)
            .setMessage("Verwijderen uit je geschiedenis?")
            .setPositiveButton("Verwijderen") { _, _ -> History.remove(this, w.key); refresh() }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Geschiedenis wissen?")
            .setMessage("Alles wat je bekeken hebt en alle \"verder kijken\"-punten worden gewist. Je favorieten blijven bewaard.")
            .setPositiveButton("Alles wissen") { _, _ ->
                History.clear(this)
                Toast.makeText(this, "Geschiedenis gewist", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }
}

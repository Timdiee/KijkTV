package nl.kijktv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** Eén serie: seizoenen links, afleveringen rechts, met "verder kijken". */
class SeriesActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(Ui.wrap(newBase))
    }

    companion object {
        fun intent(ctx: Context, seriesId: Int) =
            Intent(ctx, SeriesActivity::class.java).putExtra("id", seriesId)
    }

    private lateinit var prefs: Prefs
    private lateinit var seasonsView: RecyclerView
    private lateinit var episodesView: RecyclerView
    private lateinit var message: TextView
    private lateinit var continueBtn: Button
    private lateinit var seasonAdapter: GroupAdapter
    private lateinit var episodeAdapter: EpisodeAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var series: Series? = null
    private var info: SeriesInfo? = null
    private var currentSeason = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)
        setupBackButton()
        prefs = Prefs(this)

        val id = intent.getIntExtra("id", -1)
        val s = Vod.series?.firstOrNull { it.id == id }
        val acc = prefs.account
        if (s == null || acc == null) { finish(); return }
        series = s

        findViewById<TextView>(R.id.name).text = s.name
        findViewById<TextView>(R.id.meta).text =
            listOf(s.category, if (s.rating.isNotEmpty() && s.rating != "0") "Score ${s.rating}" else "")
                .filter { it.isNotEmpty() }.joinToString("  ·  ")
        findViewById<TextView>(R.id.plot).text = s.plot
        s.cover?.let { findViewById<ImageView>(R.id.cover).load(it) }
        seasonsView = findViewById(R.id.seasons)
        episodesView = findViewById(R.id.episodes)
        message = findViewById(R.id.message)
        continueBtn = findViewById(R.id.continueBtn)

        seasonAdapter = GroupAdapter(
            onFocus = { g -> handler.postDelayed({ showSeason(g.key.toInt()) }, 100) },
            onClick = { g ->
                showSeason(g.key.toInt())
                episodesView.post { (episodesView.layoutManager as LinearLayoutManager).findViewByPosition(0)?.requestFocus() }
            }
        )
        episodeAdapter = EpisodeAdapter(
            progressOf = { e -> History.get(this, "ep:${e.id}") },
            onClick = { e -> play(e) }
        )
        seasonsView.layoutManager = LinearLayoutManager(this)
        seasonsView.adapter = seasonAdapter
        seasonsView.itemAnimator = null
        episodesView.layoutManager = LinearLayoutManager(this)
        episodesView.adapter = episodeAdapter
        episodesView.itemAnimator = null

        Thread {
            try {
                val si = Vod.seriesInfo(acc, s)
                runOnUiThread { if (!isFinishing) onLoaded(si) }
            } catch (e: Exception) {
                runOnUiThread { if (!isFinishing) message.text = "Laden mislukt.\n${e.message ?: ""}" }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (info != null) {
            episodeAdapter.refreshAll()
            updateContinue()
        }
    }

    private fun allEpisodes(): List<Episode> = info?.seasons?.values?.flatten().orEmpty()

    private fun onLoaded(si: SeriesInfo) {
        info = si
        findViewById<TextView>(R.id.plot).text = si.plot
        if (si.seasons.isEmpty()) {
            message.text = "Geen afleveringen gevonden."
            return
        }
        message.visibility = View.GONE
        seasonAdapter.submit(si.seasons.map { (n, eps) -> GroupItem(n.toString(), "Seizoen $n", eps.size) })
        // Begin bij het seizoen waar je gebleven was.
        val last = lastWatched()
        val startSeason = last?.season ?: si.seasons.keys.first()
        showSeason(startSeason)
        updateContinue()
        if (continueBtn.visibility == View.VISIBLE) continueBtn.requestFocus()
        else seasonsView.post {
            (seasonsView.layoutManager as LinearLayoutManager)
                .findViewByPosition(seasonAdapter.indexOf(startSeason.toString()))?.requestFocus()
        }
    }

    /** De aflevering die je het laatst keek (of de volgende als die uitgekeken is). */
    private fun lastWatched(): Episode? {
        val eps = allEpisodes()
        val hist = History.all(this).filter { it.kind == WatchItem.EPISODE && it.seriesId == series?.id }
        val w = hist.firstOrNull() ?: return null
        val idx = eps.indexOfFirst { "ep:${it.id}" == w.key }
        if (idx < 0) return null
        return if (w.finished && idx + 1 < eps.size) eps[idx + 1] else eps[idx]
    }

    private fun updateContinue() {
        val e = lastWatched()
        if (e == null) { continueBtn.visibility = View.GONE; return }
        val w = History.get(this, "ep:${e.id}")
        continueBtn.text = if (w != null && w.resumable)
            "Verder kijken: ${Vod.episodeLabel(e)} vanaf ${History.clock(w.positionMs)}"
        else "Kijk verder: ${Vod.episodeLabel(e)}  ${e.title}"
        continueBtn.visibility = View.VISIBLE
        continueBtn.setOnClickListener { play(e) }
    }

    private fun showSeason(n: Int) {
        if (n == currentSeason) return
        currentSeason = n
        episodeAdapter.submit(info?.seasons?.get(n).orEmpty())
        episodesView.scrollToPosition(0)
        seasonAdapter.setActive(n.toString())
    }

    private fun play(e: Episode) {
        val acc = prefs.account ?: return
        val s = series ?: return
        Vod.queue = allEpisodes()
        Vod.queueSeries = s
        History.playWithResume(this, VodPlayerActivity.episodeItem(acc, s, e))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && episodesView.hasFocus()) {
            val lm = seasonsView.layoutManager as LinearLayoutManager
            lm.findViewByPosition(seasonAdapter.indexOf(currentSeason.toString()))?.requestFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

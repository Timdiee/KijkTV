package nl.kijktv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Programmagids: zenders links, de dagplanning van de gekozen zender rechts. */
class GuideActivity : Activity() {

    companion object {
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FOCUS_PROG = "focusProg"

        /** Opent de gids voor de lijst in [Repo.playList], beginnend bij [index]. */
        fun open(ctx: Context, index: Int, title: String? = null, focusProgrammes: Boolean = false) =
            Intent(ctx, GuideActivity::class.java)
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_FOCUS_PROG, focusProgrammes)
    }

    private lateinit var prefs: Prefs
    private lateinit var channelsView: RecyclerView
    private lateinit var programmesView: RecyclerView
    private lateinit var empty: TextView
    private lateinit var clock: TextView
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var programmeAdapter: ProgrammeAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var list: List<Channel> = emptyList()
    private var selected = 0
    private var seenEpg = -1
    private var pendingSelect: Runnable? = null

    private val ticker = object : Runnable {
        override fun run() {
            clock.text = Fmt.time(System.currentTimeMillis())
            if (seenEpg != Epg.version) {
                seenEpg = Epg.version
                channelAdapter.refreshAll()
                showProgrammes(selected, keepFocus = true)
            } else {
                programmeAdapter.refreshAll()
            }
            handler.postDelayed(this, 15000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        setupBackButton()
        focusProgrammesPending = intent.getBooleanExtra(EXTRA_FOCUS_PROG, false)
        prefs = Prefs(this)
        list = Repo.playList
        if (list.isEmpty()) { finish(); return }

        channelsView = findViewById(R.id.channels)
        programmesView = findViewById(R.id.programmes)
        empty = findViewById(R.id.empty)
        clock = findViewById(R.id.clock)
        findViewById<TextView>(R.id.subtitle).text =
            intent.getStringExtra(EXTRA_TITLE)?.let { "·  $it" } ?: ""

        channelAdapter = ChannelAdapter(
            onFocus = { i, _ ->
                pendingSelect?.let { handler.removeCallbacks(it) }
                val r = Runnable { if (i != selected) showProgrammes(i, keepFocus = false) }
                pendingSelect = r
                handler.postDelayed(r, 150)
            },
            onClick = { i, _ ->
                Repo.playList = list
                startActivity(PlayerActivity.live(this, i))
            },
            onLongClick = { _, c ->
                val fav = prefs.toggleFavorite(c.url)
                Toast.makeText(this, if (fav) "Toegevoegd aan favorieten" else "Verwijderd uit favorieten", Toast.LENGTH_SHORT).show()
                channelAdapter.updateFavorites(prefs.favorites())
            }
        )
        programmeAdapter = ProgrammeAdapter(
            onFocus = { },
            onClick = { p -> playProgramme(p) }
        )

        channelsView.layoutManager = LinearLayoutManager(this)
        channelsView.adapter = channelAdapter
        channelsView.itemAnimator = null
        programmesView.layoutManager = LinearLayoutManager(this)
        programmesView.adapter = programmeAdapter
        programmesView.itemAnimator = null

        channelAdapter.submit(list, prefs.favorites())
        selected = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, list.size - 1)
        showProgrammes(selected, keepFocus = false)
        val lm = channelsView.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(selected, 120)
        channelsView.post { lm.findViewByPosition(selected)?.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        seenEpg = Epg.version
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private var focusProgrammesPending = false

    /** Zet de focus op het programma dat nu loopt (zodat je met omhoog meteen terug in de tijd gaat). */
    private fun focusCurrentProgramme() {
        if (programmeAdapter.itemCount == 0) return
        val cur = programmeAdapter.currentIndex()
        val lm = programmesView.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(cur, 160)
        programmesView.post { lm.findViewByPosition(cur)?.findViewById<View>(R.id.row)?.requestFocus() }
    }

    private fun showProgrammes(i: Int, keepFocus: Boolean) {
        selected = i
        val c = list[i]
        Epg.fetchArchive(prefs.account, c) { runOnUiThread { if (!isFinishing) refreshIfSelected(c) } }
        val progs = Epg.programmes(c)
        programmeAdapter.submit(c, progs)
        if (progs.isEmpty()) {
            empty.text = when {
                Epg.loading -> "Programmagids wordt geladen…"
                Epg.needsShort(c) && prefs.account?.isXtream == true -> {
                    Epg.fetchShort(prefs.account, c) { runOnUiThread { if (!isFinishing) refreshIfSelected(c) } }
                    "Gids ophalen…"
                }
                else -> "Geen programmagegevens voor deze zender.\n\nOK = live kijken"
            }
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
            if (focusProgrammesPending) {
                focusProgrammesPending = false
                focusCurrentProgramme()
            } else if (!(keepFocus && programmesView.hasFocus())) {
                val cur = programmeAdapter.currentIndex()
                (programmesView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(cur, 160)
            }
        }
    }

    private fun refreshIfSelected(c: Channel) {
        if (list.getOrNull(selected) == c) {
            showProgrammes(selected, keepFocus = true)
            if (Epg.programmes(c).isEmpty()) {
                empty.text = "Geen programmagegevens voor deze zender.\n\nOK = live kijken"
            }
        }
        channelAdapter.refreshAll()
    }

    private fun playProgramme(p: Programme) {
        val c = list[selected]
        val t = System.currentTimeMillis()
        Repo.playList = list
        when {
            p.start > t -> Toast.makeText(this, "Dit programma begint om ${Fmt.time(p.start)}", Toast.LENGTH_SHORT).show()
            p.stop > t && !Xtream.canCatchup(c, p, t) -> startActivity(PlayerActivity.live(this, selected))
            p.stop > t -> {
                // Loopt nu en terugkijken kan: kiezen tussen live of vanaf het begin.
                android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(p.title)
                    .setItems(arrayOf("Live kijken", "Vanaf het begin kijken (${Fmt.time(p.start)})")) { _, which ->
                        startActivity(
                            if (which == 0) PlayerActivity.live(this, selected)
                            else PlayerActivity.catchup(this, selected, p)
                        )
                    }
                    .show()
            }
            Xtream.canCatchup(c, p, t) -> {
                val item = PlayerActivity.catchupItem(this, selected, p)
                if (item != null) History.playWithResume(this, item)
            }
            else -> Toast.makeText(this, "Deze uitzending is niet terug te kijken bij je aanbieder", Toast.LENGTH_LONG).show()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Naar links vanuit de programma's: terug naar de gekozen zender.
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
            programmesView.hasFocus()
        ) {
            val lm = channelsView.layoutManager as LinearLayoutManager
            val v = lm.findViewByPosition(selected)
            if (v != null) v.requestFocus() else {
                lm.scrollToPositionWithOffset(selected, 120)
                channelsView.post { lm.findViewByPosition(selected)?.requestFocus() }
            }
            return true
        }
        // Naar rechts vanuit de zenders: naar het programma dat nu loopt.
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
            channelsView.hasFocus() && programmeAdapter.itemCount > 0
        ) {
            pendingSelect?.let { handler.removeCallbacks(it); it.run() }
            val cur = programmeAdapter.currentIndex()
            val lm = programmesView.layoutManager as LinearLayoutManager
            lm.scrollToPositionWithOffset(cur, 40)
            programmesView.post {
                lm.findViewByPosition(cur)?.findViewById<View>(R.id.row)?.requestFocus()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

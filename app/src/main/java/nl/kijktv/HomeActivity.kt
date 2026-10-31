package nl.kijktv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

/** Eén kaart op het startscherm. Bij een zender ([channel]) worden "nu op tv" en de tijdbalk live berekend. */
class HomeCard(
    val poster: Boolean,
    val title: String,
    val sub: String,
    val image: String?,
    val logo: String?,
    val progress: Int,
    val heroLabel: String,
    val heroMeta: String,
    val heroDesc: String,
    val heroImage: String?,
    val channel: Channel? = null,
    val action: () -> Unit
)

class CardAdapter(
    private val cards: List<HomeCard>,
    private val onFocus: (HomeCard) -> Unit
) : RecyclerView.Adapter<CardAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.image)
        val logo: ImageView? = v.findViewById(R.id.logo)
        val title: TextView = v.findViewById(R.id.title)
        val sub: TextView? = v.findViewById(R.id.sub)
        val progress: ProgressBar = v.findViewById(R.id.progress)
    }

    override fun getItemViewType(position: Int) = if (cards[position].poster) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context)
            .inflate(if (viewType == 1) R.layout.item_card_poster else R.layout.item_card_wide, parent, false)
    )

    override fun getItemCount() = cards.size

    fun refreshAll() {
        if (cards.isNotEmpty()) notifyItemRangeChanged(0, cards.size)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = cards[position]
        val ch = c.channel
        h.title.text = c.title
        var sub = c.sub
        var progress = c.progress
        if (ch != null) {
            val now = Epg.now(ch)
            sub = now?.let { "${Fmt.time(it.start)}  ${it.title}" } ?: ch.group
            progress = now?.let { Fmt.progress(it) } ?: 0
        }
        h.sub?.text = sub
        if (progress > 0) {
            h.progress.progress = progress
            h.progress.visibility = View.VISIBLE
        } else {
            h.progress.visibility = View.GONE
        }
        if (c.image != null) h.image.load(c.image) { crossfade(true) } else { h.image.dispose(); h.image.setImageDrawable(null) }
        h.logo?.let { l ->
            if (c.logo != null) l.load(c.logo) else { l.dispose(); l.setImageDrawable(null) }
        }
        h.itemView.setOnFocusChangeListener { v, has ->
            val s = if (has) 1.08f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(140).start()
            if (has) onFocus(c)
        }
        h.itemView.setOnClickListener { c.action() }
    }
}

/** Startscherm zoals bij Ziggo GO / Canal+: uitgelicht vak bovenaan en rijen eronder. */
class HomeActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(Ui.wrap(newBase))
    }

    private lateinit var prefs: Prefs
    private lateinit var rows: LinearLayout
    private lateinit var rowsScroll: ScrollView
    private lateinit var heroImage: ImageView
    private lateinit var heroLabel: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroDesc: TextView
    private lateinit var heroProgress: ProgressBar
    private lateinit var clock: TextView
    private lateinit var message: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val adapters = ArrayList<CardAdapter>()
    private var heroCard: HomeCard? = null
    private var loading = false
    private var vodLoading = false
    private var seenEpg = -1
    private var started = false

    private val ticker = object : Runnable {
        override fun run() {
            clock.text = Fmt.time(System.currentTimeMillis())
            if (seenEpg != Epg.version) {
                seenEpg = Epg.version
                for (a in adapters) a.refreshAll()
                heroCard?.let { showHero(it) }
            }
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        prefs = Prefs(this)

        rows = findViewById(R.id.rows)
        rowsScroll = findViewById(R.id.rowsScroll)
        heroImage = findViewById(R.id.heroImage)
        heroLabel = findViewById(R.id.heroLabel)
        heroTitle = findViewById(R.id.heroTitle)
        heroMeta = findViewById(R.id.heroMeta)
        heroDesc = findViewById(R.id.heroDesc)
        heroProgress = findViewById(R.id.heroProgress)
        clock = findViewById(R.id.clock)
        message = findViewById(R.id.homeMessage)
        rows.clipChildren = false

        findViewById<Button>(R.id.navHome).isActivated = true
        findViewById<Button>(R.id.navHome).setOnClickListener { focusFirstCard() }
        findViewById<Button>(R.id.navLive).setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        findViewById<Button>(R.id.navGuide).setOnClickListener { openGuide() }
        findViewById<Button>(R.id.navCatchup).setOnClickListener { startActivity(MainActivity.catchup(this)) }
        findViewById<Button>(R.id.navMovies).setOnClickListener { startActivity(MediaActivity.movies(this)) }
        findViewById<Button>(R.id.navSeries).setOnClickListener { startActivity(MediaActivity.series(this)) }
        findViewById<Button>(R.id.navHistory).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        findViewById<View>(R.id.navSettings).setOnClickListener {
            Settings.show(
                this,
                onReloadChannels = { prefs.account?.let { refresh(it) } },
                onReloadEpg = { ensureEpg() }
            )
        }

        App.showLastCrash(this)

        val acc = prefs.account
        if (acc == null) {
            showWelcome()
            startActivity(Intent(this, LoginActivity::class.java))
            return
        }
        start(acc)
    }

    private fun start(acc: Account) {
        started = true
        if (Repo.all.isNotEmpty()) {
            buildRows()
            ensureEpg()
            loadVod()
            return
        }
        message.text = "Zenders laden…"
        Thread {
            val cached = Repo.loadCached(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (cached.isNotEmpty() && Repo.all.isEmpty()) {
                    Repo.setChannels(cached)
                    buildRows()
                    ensureEpg()
                }
                refresh(acc)
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        // Terug van inloggen, kijken of de geschiedenis: rijen bijwerken.
        val acc = prefs.account
        if (acc != null && !started) start(acc)
        else if (Repo.all.isNotEmpty()) { buildRows(); ensureEpg(); loadVod() }
        // Verder kijken van je andere apparaten ophalen.
        Sync.pull(this) { if (!isFinishing && Repo.all.isNotEmpty()) buildRows() }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ---------- Gegevens ----------

    private fun refresh(acc: Account) {
        if (loading) return
        loading = true
        Thread {
            try {
                val list = Repo.load(this, acc)
                runOnUiThread {
                    loading = false
                    if (isFinishing) return@runOnUiThread
                    Repo.setChannels(list)
                    buildRows()
                    ensureEpg()
                    loadVod()
                    // Nu de zenders er zijn kunnen ook de favorieten van je andere apparaten mee.
                    Sync.pull(this, force = true) { if (!isFinishing) buildRows() }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loading = false
                    if (isFinishing) return@runOnUiThread
                    if (Repo.all.isEmpty()) {
                        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("Zenders laden mislukt")
                            .setMessage((e.message ?: "Onbekende fout") + "\n\nControleer je gegevens en internetverbinding.")
                            .setPositiveButton("Inloggegevens wijzigen") { _, _ ->
                                startActivity(Intent(this, LoginActivity::class.java))
                            }
                            .setNegativeButton("Opnieuw") { _, _ -> refresh(acc) }
                            .show()
                    } else {
                        loadVod()
                    }
                }
            }
        }.start()
    }

    private fun ensureEpg() {
        val url = Repo.epgUrl ?: return
        if (Epg.loading || !Epg.isStale(this, url)) return
        Epg.loading = true
        val channels = Repo.all
        Thread {
            Epg.loadCached(this, url)
            if (Epg.isStale(this, url)) {
                try { Epg.refresh(this, url, channels) } catch (_: Exception) { }
            }
            Epg.loading = false
        }.start()
    }

    /** Films en series op de achtergrond laden voor de rijen "Nieuw". */
    private fun loadVod() {
        val acc = prefs.account ?: return
        if (!acc.isXtream || vodLoading) return
        if (Vod.movies != null && Vod.series != null) return
        vodLoading = true
        Thread {
            try { Vod.loadMovies(acc) } catch (_: Exception) { }
            runOnUiThread { if (!isFinishing) buildRows() }
            try { Vod.loadSeries(acc) } catch (_: Exception) { }
            runOnUiThread {
                vodLoading = false
                if (!isFinishing) buildRows()
            }
        }.start()
    }

    // ---------- Rijen ----------

    private fun channelCard(ch: Channel, list: List<Channel>, label: String) = HomeCard(
        poster = false, title = ch.name, sub = ch.group, image = null, logo = ch.logo, progress = 0,
        heroLabel = label, heroMeta = "", heroDesc = "", heroImage = null, channel = ch,
        action = {
            Repo.playList = list
            startActivity(PlayerActivity.live(this, list.indexOf(ch).coerceAtLeast(0)))
        }
    )

    private fun buildRows() {
        // Onthouden waar de focus stond, zodat die na het opnieuw opbouwen terugkomt.
        var focusRow = -1
        var focusPos = -1
        for (i in 0 until rows.childCount) {
            val list = rows.getChildAt(i).findViewById<RecyclerView>(R.id.rowList)
            val f = list.focusedChild
            if (f != null) { focusRow = i; focusPos = list.getChildAdapterPosition(f) }
        }

        rows.removeAllViews()
        adapters.clear()
        val all = Repo.all
        if (all.isEmpty()) {
            message.text = if (loading) "Zenders laden…" else ""
            return
        }
        message.text = ""

        // 1. Verder kijken
        val resume = History.resumable(this).take(20).map { w ->
            HomeCard(
                poster = false, title = w.title, sub = w.subtitle, image = w.image, logo = null, progress = w.progress,
                heroLabel = "Verder kijken",
                heroMeta = "Gestopt bij ${History.clock(w.positionMs)}" +
                    if (w.durationMs > 0) " van ${History.clock(w.durationMs)}" else "",
                heroDesc = w.subtitle, heroImage = w.image,
                action = { History.playWithResume(this, w) }
            )
        }
        addRow("Verder kijken", resume)

        // 2. Favoriete zenders (of de eerste zenders)
        val favs = prefs.favorites()
        val favList = all.filter { favs.contains(it.url) }
        if (favList.isNotEmpty()) {
            addRow("Jouw zenders", favList.take(40).map { channelCard(it, favList, "Jouw zenders") })
        } else {
            val first = all.take(40)
            addRow("Zenders", first.map { channelCard(it, all, "Live tv") })
        }

        // 3. Laatst gekeken zenders
        val byUrl = all.associateBy { it.url }
        val recent = History.all(this).filter { it.kind == WatchItem.LIVE }.mapNotNull { byUrl[it.url] }.take(15)
        addRow("Laatst gekeken", recent.map { channelCard(it, recent, "Laatst gekeken") })

        // 4. Nieuwe films
        Vod.movies?.let { ms ->
            val acc = prefs.account
            val newest = ms.sortedByDescending { it.added }.take(25)
            addRow("Nieuwe films", newest.map { m ->
                HomeCard(
                    poster = true, title = m.name, sub = "", image = m.poster, logo = null, progress = 0,
                    heroLabel = "Film",
                    heroMeta = listOf(m.category, if (m.rating.isNotEmpty() && m.rating != "0") "Score ${m.rating}" else "")
                        .filter { it.isNotEmpty() }.joinToString("  ·  "),
                    heroDesc = "", heroImage = m.poster,
                    action = { if (acc != null) History.playWithResume(this, VodPlayerActivity.movieItem(acc, m)) }
                )
            })
        }

        // 5. Nieuwe series
        Vod.series?.let { ss ->
            val newest = ss.sortedByDescending { it.added }.take(25)
            addRow("Nieuwe series", newest.map { s ->
                HomeCard(
                    poster = true, title = s.name, sub = "", image = s.cover, logo = null, progress = 0,
                    heroLabel = "Serie",
                    heroMeta = listOf(s.category, if (s.rating.isNotEmpty() && s.rating != "0") "Score ${s.rating}" else "")
                        .filter { it.isNotEmpty() }.joinToString("  ·  "),
                    heroDesc = s.plot, heroImage = s.cover,
                    action = { startActivity(SeriesActivity.intent(this, s.id)) }
                )
            })
        }

        // Focus terugzetten, of de eerste kaart kiezen.
        rows.post {
            val row = if (focusRow in 0 until rows.childCount) focusRow else -1
            if (row >= 0) {
                val list = rows.getChildAt(row).findViewById<RecyclerView>(R.id.rowList)
                val lm = list.layoutManager as LinearLayoutManager
                val pos = focusPos.coerceIn(0, (list.adapter?.itemCount ?: 1) - 1)
                lm.scrollToPositionWithOffset(pos, 40)
                list.post { lm.findViewByPosition(pos)?.requestFocus() }
            } else if (currentFocus == null || currentFocus?.id == R.id.navHome) {
                focusFirstCard()
            }
        }
    }

    private fun addRow(title: String, cards: List<HomeCard>) {
        if (cards.isEmpty()) return
        val row = LayoutInflater.from(this).inflate(R.layout.item_home_row, rows, false)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        val list = row.findViewById<RecyclerView>(R.id.rowList)
        list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        list.itemAnimator = null
        list.clipChildren = false
        (row as ViewGroup).clipChildren = false
        val adapter = CardAdapter(cards) { c -> showHero(c) }
        list.adapter = adapter
        adapters.add(adapter)
        rows.addView(row)
    }

    private fun focusFirstCard() {
        if (rows.childCount == 0) return
        val list = rows.getChildAt(0).findViewById<RecyclerView>(R.id.rowList)
        rowsScroll.scrollTo(0, 0)
        list.post { (list.layoutManager as LinearLayoutManager).findViewByPosition(0)?.requestFocus() }
    }

    private fun showHero(c: HomeCard) {
        heroCard = c
        heroLabel.text = c.heroLabel
        heroTitle.text = c.title
        val ch = c.channel
        if (ch != null) {
            val now = Epg.now(ch)
            val next = Epg.next(ch)
            heroMeta.text = if (now != null) "Nu  ${Fmt.range(now)}  ·  ${now.title}" else ch.group
            heroDesc.text = buildString {
                if (now != null && now.desc.isNotEmpty()) append(now.desc)
                if (next != null) {
                    if (isNotEmpty()) append("\n")
                    append("Straks ${Fmt.time(next.start)}  ${next.title}")
                }
            }
            val p = now?.let { Fmt.progress(it) } ?: 0
            heroProgress.progress = p
            heroProgress.visibility = if (now != null) View.VISIBLE else View.GONE
        } else {
            heroMeta.text = c.heroMeta
            heroDesc.text = c.heroDesc
            heroProgress.progress = c.progress
            heroProgress.visibility = if (c.progress > 0) View.VISIBLE else View.GONE
        }
        val img = c.heroImage
        if (img != null) heroImage.load(img) { crossfade(true) } else { heroImage.dispose(); heroImage.setImageDrawable(null) }
    }

    private fun showWelcome() {
        heroLabel.text = "Welkom"
        heroTitle.text = "KijkTV"
        heroMeta.text = "Log in om te beginnen"
        heroDesc.text = ""
    }

    private fun openGuide() {
        val all = Repo.all
        if (all.isEmpty()) return
        val favs = prefs.favorites()
        val favList = all.filter { favs.contains(it.url) }
        Repo.playList = if (favList.isNotEmpty()) favList else all
        startActivity(GuideGridActivity.open(this, 0, if (favList.isNotEmpty()) "Jouw zenders" else "Alle zenders"))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

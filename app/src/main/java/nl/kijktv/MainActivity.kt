package nl.kijktv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Live TV: groepen links, zenders in het midden, voorbeeld + gids rechts, menubalk boven. */
class MainActivity : Activity() {

    companion object {
        const val KEY_FAV = "__kijktv_fav__"
        const val KEY_ALL = "__kijktv_all__"
    }

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var groupsView: RecyclerView
    private lateinit var channelsView: RecyclerView
    private lateinit var search: EditText
    private lateinit var groupTitle: TextView
    private lateinit var preview: PlayerView
    private lateinit var previewHint: TextView
    private lateinit var nowName: TextView
    private lateinit var nowGroup: TextView
    private lateinit var epgBox: View
    private lateinit var nowProg: TextView
    private lateinit var nowProgress: ProgressBar
    private lateinit var nowDesc: TextView
    private lateinit var nextProg: TextView
    private lateinit var groupAdapter: GroupAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var pendingPreview: Runnable? = null
    private var pendingGroup: Runnable? = null
    private var previewUrl: String? = null
    private var focused: Channel? = null

    private var byGroup: Map<String, List<Channel>> = emptyMap()
    private var current: List<Channel> = emptyList()
    private var currentGroup: String = KEY_ALL
    private var loading = false
    private var ignoreSearch = false
    private var shownVersion = -1
    private var seenEpg = -1

    private val ticker = object : Runnable {
        override fun run() {
            if (seenEpg != Epg.version) {
                seenEpg = Epg.version
                channelAdapter.refreshAll()
                updateStatus()
            }
            focused?.let { showInfo(it) }
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.status)
        groupsView = findViewById(R.id.groups)
        channelsView = findViewById(R.id.channels)
        search = findViewById(R.id.search)
        groupTitle = findViewById(R.id.groupTitle)
        preview = findViewById(R.id.preview)
        previewHint = findViewById(R.id.previewHint)
        nowName = findViewById(R.id.nowName)
        nowGroup = findViewById(R.id.nowGroup)
        epgBox = findViewById(R.id.epgBox)
        nowProg = findViewById(R.id.nowProg)
        nowProgress = findViewById(R.id.nowProgress)
        nowDesc = findViewById(R.id.nowDesc)
        nextProg = findViewById(R.id.nextProg)

        groupAdapter = GroupAdapter(
            onFocus = { g ->
                // Kort uitstellen: niet tijdens een layout-pass wijzigen, en snel scrollen blijft soepel.
                pendingGroup?.let { handler.removeCallbacks(it) }
                val r = Runnable { if (g.key != currentGroup || search.text.isNotEmpty()) selectGroup(g.key) }
                pendingGroup = r
                handler.postDelayed(r, 120)
            },
            onClick = { g ->
                pendingGroup?.let { handler.removeCallbacks(it) }
                if (g.key != currentGroup || search.text.isNotEmpty()) selectGroup(g.key)
                if (current.isNotEmpty()) channelsView.post { focusChannel(0) }
            }
        )
        channelAdapter = ChannelAdapter(
            onFocus = { _, c -> focused = c; showInfo(c); schedulePreview(c) },
            onClick = { i, _ -> openPlayer(i) },
            onLongClick = { _, c -> toggleFavorite(c) }
        )
        groupsView.layoutManager = LinearLayoutManager(this)
        groupsView.adapter = groupAdapter
        groupsView.itemAnimator = null
        channelsView.layoutManager = LinearLayoutManager(this)
        channelsView.adapter = channelAdapter
        channelsView.itemAnimator = null

        findViewById<Button>(R.id.tabGuide).setOnClickListener { openGuide() }
        findViewById<Button>(R.id.tabMovies).setOnClickListener { releasePreview(); startActivity(MediaActivity.movies(this)) }
        findViewById<Button>(R.id.tabSeries).setOnClickListener { releasePreview(); startActivity(MediaActivity.series(this)) }
        findViewById<Button>(R.id.tabHistory).setOnClickListener { releasePreview(); startActivity(Intent(this, HistoryActivity::class.java)) }
        findViewById<Button>(R.id.tabAccount).setOnClickListener { openLogin() }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!ignoreSearch) applySearch(s?.toString().orEmpty())
            }
        })
        search.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            if (current.isNotEmpty()) channelsView.post { focusChannel(0) }
            true
        }

        val acc = prefs.account
        when {
            acc == null -> {
                groupTitle.text = "Log eerst in"
                openLogin()
            }
            Repo.all.isNotEmpty() -> {
                showChannels()
                ensureEpg()
            }
            else -> {
                // Eerst de opgeslagen kopie tonen (snel), daarna verversen bij de aanbieder.
                status.text = "Laden…"
                Thread {
                    val cached = Repo.loadCached(this)
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        if (cached.isNotEmpty() && Repo.all.isEmpty()) {
                            Repo.setChannels(cached)
                            showChannels()
                            ensureEpg()
                        }
                        refresh(acc)
                    }
                }.start()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Terug van het inlogscherm met nieuwe zenders?
        if (Repo.version != shownVersion && Repo.all.isNotEmpty()) {
            showChannels()
            ensureEpg()
        } else if (Repo.all.isNotEmpty()) {
            val idx = channelAdapter.indexOfUrl(prefs.lastChannelUrl)
            if (idx >= 0 && channelsView.hasFocus()) focusChannel(idx)
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ---------- Zenders laden ----------

    private fun refresh(acc: Account) {
        if (loading) return
        loading = true
        status.text = "Zenders bijwerken…"
        Thread {
            try {
                val list = Repo.load(this, acc)
                runOnUiThread {
                    loading = false
                    if (isFinishing) return@runOnUiThread
                    Repo.setChannels(list)
                    showChannels()
                    ensureEpg()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loading = false
                    if (isFinishing) return@runOnUiThread
                    if (Repo.all.isEmpty()) {
                        status.text = "Laden mislukt"
                        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("Zenders laden mislukt")
                            .setMessage((e.message ?: "Onbekende fout") + "\n\nControleer je gegevens en internetverbinding.")
                            .setPositiveButton("Inloggegevens wijzigen") { _, _ -> openLogin() }
                            .setNegativeButton("Opnieuw") { _, _ -> refresh(acc) }
                            .show()
                    } else {
                        updateStatus()
                        Toast.makeText(this, "Bijwerken mislukt, opgeslagen zenders worden getoond", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    /** Programmagids laden: eerst de opgeslagen kopie, daarna (als die oud is) vers ophalen. */
    private fun ensureEpg() {
        val url = Repo.epgUrl ?: return
        if (Epg.loading) return
        if (!Epg.isStale(this, url)) return
        Epg.loading = true
        val channels = Repo.all
        Thread {
            Epg.loadCached(this, url)
            runOnUiThread { if (!isFinishing) updateStatus() }
            if (Epg.isStale(this, url)) {
                try { Epg.refresh(this, url, channels) } catch (_: Exception) { }
            }
            Epg.loading = false
            runOnUiThread { if (!isFinishing) updateStatus() }
        }.start()
        updateStatus()
    }

    private fun updateStatus() {
        val parts = ArrayList<String>()
        parts.add("${Repo.all.size} zenders")
        Repo.accountInfo?.let { parts.add(it) }
        if (Epg.loading) parts.add("gids laden…")
        status.text = parts.joinToString("  ·  ")
    }

    private fun showChannels() {
        shownVersion = Repo.version
        val list = Repo.all
        byGroup = list.groupBy { it.group }
        updateStatus()
        rebuildGroups()
        val saved = prefs.lastGroup
        val start = when {
            saved != null && (saved == KEY_ALL || saved == KEY_FAV || byGroup.containsKey(saved)) -> saved
            prefs.favorites().isNotEmpty() -> KEY_FAV
            else -> KEY_ALL
        }
        selectGroup(start)
        val idx = groupAdapter.indexOf(start)
        groupsView.post {
            if (idx >= 0) groupsView.scrollToPosition(idx)
            val lastIdx = current.indexOfFirst { it.url == prefs.lastChannelUrl }
            focusChannel(if (lastIdx >= 0) lastIdx else 0)
        }
    }

    private fun favoriteChannels(): List<Channel> {
        val favs = prefs.favorites()
        return Repo.all.filter { favs.contains(it.url) }
    }

    private fun rebuildGroups() {
        val items = ArrayList<GroupItem>()
        items.add(GroupItem(KEY_FAV, "★  Favorieten", favoriteChannels().size))
        items.add(GroupItem(KEY_ALL, "Alle zenders", Repo.all.size))
        for ((name, chans) in byGroup) items.add(GroupItem(name, name, chans.size))
        groupAdapter.submit(items)
        groupAdapter.setActive(currentGroup)
    }

    private fun groupLabel(key: String) = when (key) {
        KEY_FAV -> "Favorieten"
        KEY_ALL -> "Alle zenders"
        else -> key
    }

    private fun selectGroup(key: String) {
        if (search.text.isNotEmpty()) {
            ignoreSearch = true
            search.setText("")
            ignoreSearch = false
        }
        currentGroup = key
        prefs.lastGroup = key
        current = when (key) {
            KEY_FAV -> favoriteChannels()
            KEY_ALL -> Repo.all
            else -> byGroup[key] ?: emptyList()
        }
        groupTitle.text = if (key == KEY_FAV && current.isEmpty())
            "Nog geen favorieten · houd OK ingedrukt op een zender"
        else "${groupLabel(key)}  ·  ${current.size} zenders"
        channelAdapter.submit(current, prefs.favorites())
        channelsView.scrollToPosition(0)
        groupAdapter.setActive(key)
    }

    private fun applySearch(q: String) {
        val query = q.trim()
        if (query.isEmpty()) {
            selectGroup(currentGroup)
            return
        }
        current = Repo.all.filter { it.name.contains(query, ignoreCase = true) }
        groupTitle.text = "Zoeken: \"$query\"  ·  ${current.size} gevonden"
        channelAdapter.submit(current, prefs.favorites())
        channelsView.scrollToPosition(0)
    }

    private fun focusChannel(index: Int) {
        if (current.isEmpty()) return
        val i = index.coerceIn(0, current.size - 1)
        val lm = channelsView.layoutManager as LinearLayoutManager
        lm.scrollToPositionWithOffset(i, 80)
        channelsView.post { lm.findViewByPosition(i)?.requestFocus() }
    }

    private fun toggleFavorite(c: Channel) {
        val nowFav = prefs.toggleFavorite(c.url)
        Toast.makeText(
            this,
            if (nowFav) "★ ${c.name} toegevoegd aan favorieten" else "${c.name} verwijderd uit favorieten",
            Toast.LENGTH_SHORT
        ).show()
        rebuildGroups()
        if (currentGroup == KEY_FAV && search.text.isEmpty()) {
            current = favoriteChannels()
            channelAdapter.submit(current, prefs.favorites())
        } else {
            channelAdapter.updateFavorites(prefs.favorites())
        }
    }

    // ---------- Rechterkant: voorbeeld + gids ----------

    private fun showInfo(c: Channel) {
        nowName.text = c.name
        nowGroup.text = if (c.archiveDays > 0) "${c.group}  ·  ⟲ ${c.archiveDays} dag(en) terugkijken" else c.group
        val now = Epg.now(c)
        val next = Epg.next(c)
        if (now == null && next == null) {
            epgBox.visibility = View.INVISIBLE
            return
        }
        epgBox.visibility = View.VISIBLE
        if (now != null) {
            nowProg.text = "Nu  ${Fmt.range(now)}\n${now.title}"
            nowProgress.progress = Fmt.progress(now)
            nowProgress.visibility = View.VISIBLE
            nowDesc.text = now.desc
        } else {
            nowProg.text = "Nu geen programmagegevens"
            nowProgress.visibility = View.GONE
            nowDesc.text = ""
        }
        nextProg.text = if (next != null) "Straks  ${Fmt.time(next.start)}  ${next.title}" else ""
    }

    private fun schedulePreview(c: Channel) {
        pendingPreview?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            startPreview(c)
            Epg.fetchShort(prefs.account, c) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (focused == c) showInfo(c)
                }
            }
        }
        pendingPreview = r
        handler.postDelayed(r, 900)
    }

    private fun startPreview(c: Channel) {
        if (previewUrl == c.url && player != null) return
        val p = player ?: Players.create(this).also { np ->
            np.volume = 0.6f
            np.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) previewHint.visibility = View.GONE
                }
                override fun onPlayerError(error: PlaybackException) {
                    previewHint.text = "Geen voorbeeld beschikbaar"
                    previewHint.visibility = View.VISIBLE
                }
            })
            preview.player = np
            player = np
        }
        previewUrl = c.url
        previewHint.text = "Laden…"
        previewHint.visibility = View.VISIBLE
        p.setMediaItem(Players.item(c.url))
        p.prepare()
        p.playWhenReady = true
    }

    private fun releasePreview() {
        pendingPreview?.let { handler.removeCallbacks(it) }
        preview.player = null
        player?.release()
        player = null
        previewUrl = null
        previewHint.text = "Ga op een zender staan voor een voorbeeld"
        previewHint.visibility = View.VISIBLE
    }

    private fun openPlayer(index: Int) {
        if (current.isEmpty()) return
        releasePreview()
        Repo.playList = current
        startActivity(PlayerActivity.live(this, index))
    }

    private fun openGuide() {
        if (current.isEmpty()) {
            Toast.makeText(this, "Kies eerst een groep met zenders", Toast.LENGTH_SHORT).show()
            return
        }
        releasePreview()
        Repo.playList = current
        val idx = focused?.let { f -> current.indexOf(f) }?.takeIf { it >= 0 } ?: 0
        val title = if (search.text.isNotEmpty()) "Zoekresultaten" else groupLabel(currentGroup)
        startActivity(GuideActivity.open(this, idx, title))
    }

    private fun openLogin() {
        releasePreview()
        startActivity(Intent(this, LoginActivity::class.java))
    }

    override fun onStop() {
        super.onStop()
        releasePreview()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Naar links vanuit de zenderlijst: altijd terug naar de actieve groep,
        // zodat je niet per ongeluk van groep wisselt.
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
            channelsView.hasFocus()
        ) {
            val idx = groupAdapter.indexOf(currentGroup)
            if (idx >= 0) {
                val lm = groupsView.layoutManager as LinearLayoutManager
                val v = lm.findViewByPosition(idx)
                if (v != null) {
                    v.requestFocus()
                } else {
                    lm.scrollToPositionWithOffset(idx, 80)
                    groupsView.post { lm.findViewByPosition(idx)?.requestFocus() }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> { openLogin(); return true }
            KeyEvent.KEYCODE_GUIDE -> { openGuide(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

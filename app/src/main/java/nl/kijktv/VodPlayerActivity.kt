package nl.kijktv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.dispose
import coil.load

/**
 * Speler voor films, afleveringen en teruggekeken uitzendingen.
 * ◀ ▶ spoelen (ingedrukt houden = sneller), OK pauze, positie wordt onthouden.
 */
class VodPlayerActivity : Activity() {

    companion object {
        fun intent(ctx: Context, w: WatchItem, startPos: Long): Intent =
            Intent(ctx, VodPlayerActivity::class.java)
                .putExtra("key", w.key).putExtra("kind", w.kind).putExtra("title", w.title)
                .putExtra("sub", w.subtitle).putExtra("img", w.image).putExtra("url", w.url)
                .putExtra("sid", w.seriesId).putExtra("start", startPos).putExtra("plot", w.plot)

        fun episodeItem(acc: Account, s: Series, e: Episode) = WatchItem(
            "ep:${e.id}", WatchItem.EPISODE, s.name,
            "${Vod.episodeLabel(e)}  ·  ${e.title}", e.image ?: s.cover, Vod.episodeUrl(acc, e),
            durationMs = e.durationSec * 1000L, seriesId = s.id, plot = e.plot.ifEmpty { s.plot }
        )

        fun movieItem(acc: Account, m: Movie) = WatchItem(
            "mv:${m.id}", WatchItem.MOVIE, m.name, m.category, m.poster, Vod.movieUrl(acc, m)
        )
    }

    private lateinit var playerView: PlayerView
    private lateinit var loadingView: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var info: View
    private lateinit var infoNumber: TextView
    private lateinit var infoLogo: ImageView
    private lateinit var infoName: TextView
    private lateinit var infoNow: TextView
    private lateinit var infoProgress: ProgressBar
    private lateinit var infoNext: TextView
    private lateinit var infoHint: TextView
    private lateinit var infoGroup: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private lateinit var item: WatchItem
    private var resumeAt = 0L
    private var retried = false
    private lateinit var pause: PauseOverlay
    @Volatile private var plotFetched = false

    private val hideInfo = Runnable { info.visibility = View.GONE }
    private val ticker = object : Runnable {
        var n = 0
        override fun run() {
            if (info.visibility == View.VISIBLE) updateInfo()
            if (pause.isShowing) player?.let { pause.update(it.currentPosition, durationOf(it)) }
            if (++n % 10 == 0) savePosition()
            handler.postDelayed(this, 1000)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            loadingView.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY) errorView.visibility = View.GONE
            if (state == Player.STATE_ENDED) { pause.hide(); onEnded() }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (!retried) {
                retried = true
                val pos = p.currentPosition
                p.prepare()
                if (pos > 0) p.seekTo(pos)
                return
            }
            loadingView.visibility = View.GONE
            errorView.text = "Dit kan nu niet afgespeeld worden.\n\nOK  opnieuw proberen     Terug  sluiten"
            errorView.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player)
        loadingView = findViewById(R.id.loading)
        errorView = findViewById(R.id.error)
        info = findViewById(R.id.info)
        infoNumber = findViewById(R.id.infoNumber)
        infoLogo = findViewById(R.id.infoLogo)
        infoName = findViewById(R.id.infoName)
        infoNow = findViewById(R.id.infoNow)
        infoProgress = findViewById(R.id.infoProgress)
        infoNext = findViewById(R.id.infoNext)
        infoHint = findViewById(R.id.infoHint)
        infoGroup = findViewById(R.id.infoGroup)
        infoNumber.visibility = View.GONE
        pause = PauseOverlay(
            this,
            onResume = { togglePause() },
            onSeek = { ms -> seekBy(ms) },
            onRestart = { player?.seekTo(0); if (player?.playWhenReady == false) togglePause() },
            onNext = { if (nextEpisode() != null) { pause.hide(); savePosition(); onEnded() } }
        )

        val i = intent
        val url = i.getStringExtra("url")
        if (url.isNullOrEmpty()) { finish(); return }
        item = WatchItem(
            i.getStringExtra("key") ?: url, i.getStringExtra("kind") ?: WatchItem.MOVIE,
            i.getStringExtra("title").orEmpty(), i.getStringExtra("sub").orEmpty(),
            i.getStringExtra("img"), url, seriesId = i.getIntExtra("sid", 0),
            plot = i.getStringExtra("plot").orEmpty()
        )
        resumeAt = savedInstanceState?.getLong("pos") ?: i.getLongExtra("start", 0)
        currentPlot = item.plot
        History.record(this, item)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let { outState.putLong("pos", it.currentPosition) }
    }

    override fun onStart() {
        super.onStart()
        if (!::item.isInitialized) return
        val p = Players.create(this)
        p.addListener(listener)
        playerView.player = p
        player = p
        startItem()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        player?.let { resumeAt = it.currentPosition }
        handler.removeCallbacks(ticker)
        playerView.player = null
        player?.release()
        player = null
    }

    private fun startItem() {
        val p = player ?: return
        retried = false
        errorView.visibility = View.GONE
        loadingView.visibility = View.VISIBLE
        p.setMediaItem(Players.item(item.url))
        p.prepare()
        if (resumeAt > 0) p.seekTo(resumeAt)
        p.playWhenReady = true
        infoName.text = item.title
        infoGroup.text = ""
        if (item.image != null) infoLogo.load(item.image) else { infoLogo.dispose(); infoLogo.setImageDrawable(null) }
        showInfo()
    }

    private fun savePosition() {
        val p = player ?: return
        if (!::item.isInitialized) return
        val dur = p.duration
        val pos = p.currentPosition
        if (pos <= 0) return
        History.updatePosition(this, item.key, pos, if (dur == C.TIME_UNSET) 0 else dur)
    }

    private fun nextEpisode(): Episode? {
        if (item.kind != WatchItem.EPISODE) return null
        val q = Vod.queue
        val idx = q.indexOfFirst { "ep:${it.id}" == item.key }
        return if (idx >= 0 && idx + 1 < q.size) q[idx + 1] else null
    }

    private fun onEnded() {
        val p = player ?: return
        val dur = p.duration
        if (dur != C.TIME_UNSET && dur > 0) History.updatePosition(this, item.key, dur, dur)
        val next = nextEpisode()
        val acc = Prefs(this).account
        val s = Vod.queueSeries
        if (next != null && acc != null && s != null) {
            item = episodeItem(acc, s, next)
            currentPlot = item.plot
            History.record(this, item)
            resumeAt = 0
            startItem()
        } else {
            finish()
        }
    }

    private fun showInfo() {
        updateInfo()
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        if (player?.playWhenReady != false) handler.postDelayed(hideInfo, 4000)
    }

    private fun updateInfo() {
        val p = player
        val pos = p?.currentPosition ?: 0L
        val d = p?.duration ?: C.TIME_UNSET
        val dur = if (d != C.TIME_UNSET && d > 0) d else item.durationMs
        val kindLabel = when (item.kind) {
            WatchItem.CATCHUP -> "⟲ Terugkijken  ·  "
            WatchItem.EPISODE -> ""
            else -> ""
        }
        infoNow.text = kindLabel + item.subtitle
        infoProgress.progress = if (dur > 0) ((pos * 1000) / dur).toInt().coerceIn(0, 1000) else 0
        infoProgress.visibility = View.VISIBLE
        val paused = p?.playWhenReady == false
        infoNext.text = "${History.clock(pos)} / ${if (dur > 0) History.clock(dur) else "--:--"}" +
            if (paused) "    ⏸ gepauzeerd" else ""
        val next = nextEpisode()
        infoHint.text = "◀ ▶  spoelen   ·   OK  pauze" +
            if (next != null) "   ·   daarna: ${Vod.episodeLabel(next)}" else ""
    }

    private fun durationOf(p: Player): Long {
        val d = p.duration
        return if (d != C.TIME_UNSET && d > 0) d else item.durationMs
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        var target = p.currentPosition + ms
        if (target < 0) target = 0
        val d = p.duration
        if (d != C.TIME_UNSET && target > d - 3000) target = (d - 3000).coerceAtLeast(0)
        p.seekTo(target)
        if (pause.isShowing) pause.update(target, durationOf(p)) else showInfo()
    }

    private fun showPause() {
        val p = player ?: return
        handler.removeCallbacks(hideInfo)
        info.visibility = View.GONE
        val heading = when (item.kind) {
            WatchItem.EPISODE -> "Je kijkt naar"
            WatchItem.CATCHUP -> "⟲ Je kijkt terug"
            else -> "Je kijkt naar"
        }
        val next = nextEpisode()
        pause.show(
            heading, item.title, item.subtitle, currentPlot, seekable = true, canRestart = true,
            nextLabel = next?.let { "Volgende: ${Vod.episodeLabel(it)}" }
        )
        pause.update(p.currentPosition, durationOf(p))
        fetchMoviePlot()
    }

    private var currentPlot = ""

    /** Voor films haalt de app de beschrijving pas op als je pauzeert (scheelt laden). */
    private fun fetchMoviePlot() {
        if (plotFetched || item.kind != WatchItem.MOVIE || currentPlot.isNotEmpty()) return
        val id = item.key.removePrefix("mv:").toIntOrNull() ?: return
        val acc = Prefs(this).account ?: return
        plotFetched = true
        Thread {
            val plot = try {
                val o = org.json.JSONObject(Http.getText(Xtream.apiUrl(acc, "get_vod_info&vod_id=$id")))
                o.optJSONObject("info")?.optString("plot").orEmpty().let { if (it == "null") "" else it.trim() }
            } catch (_: Exception) { "" }
            if (plot.isNotEmpty()) runOnUiThread {
                currentPlot = plot
                if (!isFinishing && pause.isShowing) showPause()
            }
        }.start()
    }

    /** Hoe langer je de knop ingedrukt houdt, hoe sneller het spoelen. */
    private fun step(event: KeyEvent): Long = when {
        event.repeatCount > 20 -> 120_000L
        event.repeatCount > 8 -> 60_000L
        event.repeatCount > 2 -> 30_000L
        else -> 10_000L
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (pause.isShowing) {
            // Op het pauzescherm bedien je de knoppen; mediatoetsen gaan meteen verder.
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> { togglePause(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10_000); return true }
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-step(event)); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(step(event)); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (errorView.visibility == View.VISIBLE) {
                    resumeAt = player?.currentPosition ?: resumeAt
                    startItem()
                } else togglePause()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> { showInfo(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (nextEpisode() != null) { savePosition(); onEnded() }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun togglePause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        if (!p.playWhenReady) {
            savePosition()
            showPause()
        } else {
            pause.hide()
            showInfo()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

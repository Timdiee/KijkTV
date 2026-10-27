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
 * Volledig scherm. Twee standen:
 *  - live: omhoog/omlaag zappen, links/rechts terugspoelen als de stream dat toestaat, 2x links = vanaf begin
 *  - terugkijken: links/rechts spoelen, OK pauze
 */
class PlayerActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(Ui.wrap(newBase))
    }

    companion object {
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_CU_START = "cuStart"
        private const val EXTRA_CU_STOP = "cuStop"
        private const val EXTRA_CU_TITLE = "cuTitle"
        private const val SEEK_MS = 30_000L

        fun live(ctx: Context, index: Int) =
            Intent(ctx, PlayerActivity::class.java).putExtra(EXTRA_INDEX, index)

        /** Terugkijken gaat via de film-speler (spoelen, pauze, onthouden waar je was). */
        fun catchupItem(ctx: Context, index: Int, p: Programme): WatchItem? {
            val ch = Repo.playList.getOrNull(index) ?: return null
            val acc = Prefs(ctx).account ?: return null
            val url = Xtream.catchupUrl(acc, ch, p, Repo.serverTz) ?: return null
            return WatchItem(
                "cu:${ch.streamId}:${p.start}", WatchItem.CATCHUP, p.title.ifEmpty { ch.name },
                "${ch.name}  ·  ${Fmt.day(p.start)} ${Fmt.range(p)}", ch.logo, url,
                durationMs = p.stop - p.start, plot = p.desc
            )
        }

        fun catchup(ctx: Context, index: Int, p: Programme): Intent {
            val item = catchupItem(ctx, index, p) ?: return live(ctx, index)
            return VodPlayerActivity.intent(ctx, item, 0)
        }
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
    private lateinit var prefs: Prefs

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var list: List<Channel> = emptyList()
    private var index = 0
    private var retries = 0
    private var altTried = false
    private var currentUrl = ""

    /** Programma dat teruggekeken wordt (null = live). */
    private var catchup: Programme? = null
    private var lastLeftPress = 0L
    private lateinit var pause: PauseOverlay
    private lateinit var touch: android.view.GestureDetector
    private var longPressed = false

    private val hideInfo = Runnable { info.visibility = View.GONE }
    private val playPending = Runnable { playCurrent() }
    /** Pas na 10 seconden kijken komt een zender in de geschiedenis (niet bij snel zappen). */
    private val recordLive = Runnable {
        val ch = list[index]
        History.record(
            this, WatchItem("live:${ch.url}", WatchItem.LIVE, ch.name, ch.group, ch.logo, ch.url)
        )
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (info.visibility == View.VISIBLE) updateInfo()
            if (pause.isShowing) player?.let { p ->
                val d = p.duration
                pause.update(p.currentPosition, if (d != C.TIME_UNSET && d > 0) d else 0)
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            loadingView.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY) {
                errorView.visibility = View.GONE
                retries = 0
            }
            if (state == Player.STATE_ENDED && catchup != null) {
                // Programma afgelopen: terug naar live.
                catchup = null
                playCurrent()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW || retries < 1) {
                retries++
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            // Sommige aanbieders leveren alleen .m3u8 of alleen .ts: probeer de andere vorm.
            if (!altTried && catchup == null) {
                val alt = when {
                    currentUrl.endsWith(".ts") -> currentUrl.removeSuffix(".ts") + ".m3u8"
                    currentUrl.endsWith(".m3u8") && currentUrl.contains("/live/") -> currentUrl.removeSuffix(".m3u8") + ".ts"
                    else -> null
                }
                if (alt != null) {
                    altTried = true
                    start(alt)
                    return
                }
            }
            loadingView.visibility = View.GONE
            errorView.text = if (catchup != null)
                "Dit programma is niet terug te kijken.\nDruk op OK om het opnieuw te proberen."
            else
                "Deze zender speelt nu niet af.\nDruk op OK om het opnieuw te proberen, of kies een andere zender."
            errorView.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)

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
        pause = PauseOverlay(
            this,
            onResume = { togglePause() },
            onSeek = { ms -> seekBy(ms) },
            onRestart = {
                val now = Epg.now(list[index])
                pause.hide()
                if (now != null) startActivity(catchup(this, index, now))
            },
            onNext = { },
            onSubtitles = { player?.let { TrackMenu.showSubtitles(this, it, playerView, false) } },
            onAudio = { player?.let { TrackMenu.showAudio(this, it, false) } },
            onArchive = {
                pause.hide()
                Repo.playList = list
                startActivity(GuideActivity.open(this, index, list[index].name, focusProgrammes = true))
            }
        )
        TrackMenu.applyStyle(playerView, prefs.subtitleSize)
        touch = Ui.gestures(
            this,
            onTap = { if (errorView.visibility == View.VISIBLE) playCurrent() else togglePause() },
            onSwipe = { dx, dy ->
                when {
                    dy != 0 -> zap(if (dy < 0) 1 else -1)          // omhoog vegen = volgende zender
                    liveSeekable() -> seekBy(if (dx > 0) SEEK_MS else -SEEK_MS)
                    else -> showInfo()
                }
            }
        )

        list = Repo.playList
        if (list.isEmpty()) {
            finish()
            return
        }
        index = (savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0))
            .coerceIn(0, list.size - 1)
        val cuStart = intent.getLongExtra(EXTRA_CU_START, 0)
        if (cuStart > 0 && savedInstanceState == null) {
            catchup = Programme(
                cuStart, intent.getLongExtra(EXTRA_CU_STOP, cuStart + 3600_000),
                intent.getStringExtra(EXTRA_CU_TITLE).orEmpty(), ""
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
    }

    override fun onStart() {
        super.onStart()
        if (list.isEmpty()) return
        val p = Players.create(this)
        p.addListener(listener)
        playerView.player = p
        player = p
        playCurrent()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(playPending)
        handler.removeCallbacks(recordLive)
        handler.removeCallbacks(ticker)
        playerView.player = null
        player?.release()
        player = null
    }

    private fun playCurrent() {
        val ch = list[index]
        retries = 0
        altTried = false
        val cu = catchup
        val acc = prefs.account
        val url = if (cu != null && acc != null) Xtream.catchupUrl(acc, ch, cu, Repo.serverTz) else null
        if (cu != null && url == null) catchup = null
        if (catchup == null) prefs.lastChannelUrl = ch.url
        handler.removeCallbacks(recordLive)
        if (catchup == null) handler.postDelayed(recordLive, 10_000)
        start(url ?: Players.applyFormat(ch.url))
        showInfo()
    }

    private fun start(url: String) {
        val p = player ?: return
        currentUrl = url
        errorView.visibility = View.GONE
        loadingView.visibility = View.VISIBLE
        p.setMediaItem(Players.item(url))
        p.prepare()
        p.playWhenReady = true
    }

    // ---------- Info-balk ----------

    private fun showInfo() {
        val ch = list[index]
        infoNumber.text = (index + 1).toString()
        infoName.text = ch.name
        infoGroup.text = ch.group
        if (ch.logo != null) infoLogo.load(ch.logo) else { infoLogo.dispose(); infoLogo.setImageDrawable(null) }
        Epg.fetchShort(prefs.account, ch) { runOnUiThread { if (!isFinishing) updateInfo() } }
        updateInfo()
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 5000)
    }

    private fun updateInfo() {
        val ch = list[index]
        val cu = catchup
        val p = player
        if (cu != null) {
            infoNow.text = "Terugkijken  ·  ${Fmt.day(cu.start)} ${Fmt.range(cu)}  ·  ${cu.title}"
            val dur = p?.duration ?: C.TIME_UNSET
            val pos = p?.currentPosition ?: 0L
            val total = if (dur != C.TIME_UNSET && dur > 0) dur else (cu.stop - cu.start)
            infoProgress.progress = if (total > 0) ((pos * 1000) / total).toInt().coerceIn(0, 1000) else 0
            infoProgress.visibility = View.VISIBLE
            infoNext.text = "${clock(pos)} / ${clock(total)}" + if (p?.playWhenReady == false) "   ·   Gepauzeerd" else ""
            infoHint.text = ""
            return
        }
        val now = Epg.now(ch)
        val next = Epg.next(ch)
        if (now != null) {
            infoNow.text = "Nu  ${Fmt.range(now)}   ${now.title}"
            infoProgress.progress = Fmt.progress(now)
            infoProgress.visibility = View.VISIBLE
        } else {
            infoNow.text = ""
            infoProgress.visibility = View.GONE
        }
        infoNext.text = if (next != null) "Straks  ${Fmt.time(next.start)}   ${next.title}" else ""
        val canSeek = liveSeekable()
        val canRestart = now != null && Xtream.canCatchup(ch, now)
        infoHint.text = when {
            canSeek && canRestart -> "Terugspoelen mogelijk  ·  2x links: vanaf begin"
            canSeek -> "Terugspoelen mogelijk"
            canRestart -> "Druk 2x op links om dit programma vanaf het begin te kijken"
            else -> ""
        }.let { if (it.isEmpty()) "OK lang ingedrukt  ·  pauze" else "$it   ·   OK lang = pauze" }
    }

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    /** Kan er in de live-stream teruggespoeld worden? (HLS met een terugkijkvenster) */
    private fun liveSeekable(): Boolean {
        val p = player ?: return false
        val d = p.duration
        return p.isCurrentMediaItemSeekable && d != C.TIME_UNSET && d > 2 * 60_000
    }

    // ---------- Bediening ----------

    /** Zappen: info meteen tonen, stream pas starten als je even stopt met drukken. */
    private fun zap(delta: Int) {
        pause.hide()
        if (catchup != null) {
            // Vanuit terugkijken: eerst terug naar live op dezelfde zender.
            catchup = null
            playCurrent()
            return
        }
        if (list.size < 2) return
        index = ((index + delta) % list.size + list.size) % list.size
        showInfo()
        handler.removeCallbacks(playPending)
        handler.postDelayed(playPending, 450)
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        var target = p.currentPosition + ms
        if (target < 0) target = 0
        val d = p.duration
        if (d != C.TIME_UNSET && target > d - 5000) {
            if (catchup == null) { p.seekToDefaultPosition(); showInfo(); return }
            target = (d - 5000).coerceAtLeast(0)
        }
        p.seekTo(target)
        if (pause.isShowing) pause.update(target, if (d != C.TIME_UNSET) d else 0) else showInfo()
    }

    private fun onLeft() {
        val ch = list[index]
        if (catchup != null) { seekBy(-SEEK_MS); return }
        val now = Epg.now(ch)
        val t = System.currentTimeMillis()
        val restart = now != null && Xtream.canCatchup(ch, now) && prefs.account?.isXtream == true
        if (restart && now != null && t - lastLeftPress < 1500) {
            lastLeftPress = 0
            startActivity(catchup(this, index, now))
            return
        }
        lastLeftPress = t
        if (liveSeekable()) seekBy(-SEEK_MS) else showInfo()
    }

    private fun onRight() {
        if (catchup != null || liveSeekable()) seekBy(SEEK_MS) else showInfo()
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            longPressed = true
            if (errorView.visibility != View.VISIBLE) togglePause()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!pause.isShowing && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            if (!longPressed && !event.isCanceled) {
                if (errorView.visibility == View.VISIBLE) playCurrent() else showInfo()
            }
            longPressed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (pause.isShowing) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> { togglePause(); return true }
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { onLeft(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { onRight(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Kort drukken = info, lang ingedrukt = pauze (afgehandeld bij loslaten).
                if (event.repeatCount == 0) { event.startTracking(); longPressed = false }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                togglePause(); return true
            }
            KeyEvent.KEYCODE_INFO -> { showInfo(); return true }
            KeyEvent.KEYCODE_GUIDE -> {
                startActivity(GuideGridActivity.open(this, index))
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // Pauzescherm open: gewoon de knoppen laten werken. Anders: tikken/vegen.
        if (pause.isShowing) return super.dispatchTouchEvent(ev)
        touch.onTouchEvent(ev)
        return true
    }

    private fun togglePause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        if (p.playWhenReady) {
            pause.hide()
            showInfo()
            return
        }
        handler.removeCallbacks(hideInfo)
        info.visibility = View.GONE
        val ch = list[index]
        val now = Epg.now(ch)
        val next = Epg.next(ch)
        val sub = buildString {
            append(ch.name)
            if (now != null) append("  ·  ${Fmt.range(now)}")
            if (next != null) append("  ·  straks: ${next.title}")
        }
        pause.show(
            heading = "LIVE TV",
            titleText = now?.title?.ifEmpty { null } ?: ch.name,
            subText = sub,
            description = now?.desc.orEmpty(),
            seekable = liveSeekable(),
            canRestart = now != null && Xtream.canCatchup(ch, now) && prefs.account?.isXtream == true,
            nextLabel = null,
            showSubtitles = TrackMenu.hasSubtitles(p),
            showAudio = TrackMenu.hasAudioChoice(p),
            showArchive = prefs.account?.isXtream == true && ch.streamId != null
        )
        val d = p.duration
        pause.update(p.currentPosition, if (d != C.TIME_UNSET && d > 0) d else 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

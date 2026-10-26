package nl.kijktv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

/**
 * Tv-gids als raster (zoals bij Ziggo GO): zenders onder elkaar, tijd van links naar rechts.
 * Links/rechts = door de tijd, omhoog/omlaag = andere zender, OK = kijken of terugkijken.
 */
class GuideGridActivity : Activity() {

    companion object {
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_TITLE = "title"
        private const val STEP = 30 * 60_000L
        private const val WINDOW = 2 * 60 * 60_000L

        /** Opent de gids voor de lijst in [Repo.playList], beginnend bij [index]. */
        fun open(ctx: Context, index: Int, title: String? = null) =
            Intent(ctx, GuideGridActivity::class.java)
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_TITLE, title)
    }

    /** Wat er in een vakje van het raster staat. */
    private class Cell(val row: Int, val p: Programme?, val start: Long, val stop: Long)

    private lateinit var prefs: Prefs
    private lateinit var grid: RecyclerView
    private lateinit var ruler: FrameLayout
    private lateinit var nowLine: View
    private lateinit var dayLabel: TextView
    private lateinit var clock: TextView
    private lateinit var detailTitle: TextView
    private lateinit var detailMeta: TextView
    private lateinit var detailDesc: TextView
    private lateinit var adapter: RowAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var list: List<Channel> = emptyList()
    private var windowStart = 0L
    private var minStart = 0L
    private var maxEnd = 0L
    private var pxPerMs = 0.0
    private var focusRow = 0
    private var focusTime = 0L
    private var seenEpg = -1

    private val ticker = object : Runnable {
        override fun run() {
            clock.text = Fmt.time(System.currentTimeMillis())
            placeNowLine()
            if (seenEpg != Epg.version) {
                seenEpg = Epg.version
                refreshKeepingFocus()
            }
            handler.postDelayed(this, 5000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide_grid)
        prefs = Prefs(this)
        list = Repo.playList
        if (list.isEmpty()) { finish(); return }

        grid = findViewById(R.id.grid)
        ruler = findViewById(R.id.ruler)
        nowLine = findViewById(R.id.nowLine)
        dayLabel = findViewById(R.id.day)
        clock = findViewById(R.id.clock)
        detailTitle = findViewById(R.id.detailTitle)
        detailMeta = findViewById(R.id.detailMeta)
        detailDesc = findViewById(R.id.detailDesc)
        findViewById<TextView>(R.id.subtitle).text = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        // Breedte van het tijdgedeelte: scherm min marges (2 x 24dp) en de zenderkolom (170dp).
        val trackPx = resources.displayMetrics.widthPixels - dp(48) - dp(170)
        pxPerMs = trackPx.toDouble() / WINDOW

        val now = System.currentTimeMillis()
        val maxArchive = list.maxOfOrNull { it.archiveDays }?.coerceIn(0, 7) ?: 0
        minStart = floorStep(now - if (maxArchive > 0) maxArchive * 24L * 3600_000 else 2 * 3600_000L)
        maxEnd = now + 3L * 24 * 3600_000
        windowStart = floorStep(now) - STEP
        focusRow = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, list.size - 1)
        focusTime = now

        adapter = RowAdapter()
        grid.layoutManager = LinearLayoutManager(this)
        grid.adapter = adapter
        grid.itemAnimator = null
        buildRuler()
        (grid.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(focusRow, dp(40))
        grid.post { focusCell(focusRow, focusTime) }
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

    private fun floorStep(t: Long) = t - (t % STEP)
    private val windowEnd get() = windowStart + WINDOW

    // ---------- Tijdbalk en nu-lijn ----------

    private fun buildRuler() {
        ruler.removeAllViews()
        var t = windowStart
        while (t < windowEnd) {
            val tv = TextView(this)
            tv.text = Fmt.time(t)
            tv.setTextColor(0xFF8B949E.toInt())
            tv.textSize = 12f
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            lp.leftMargin = ((t - windowStart) * pxPerMs).toInt() + dp(4)
            tv.gravity = android.view.Gravity.CENTER_VERTICAL
            ruler.addView(tv, lp)
            t += STEP
        }
        dayLabel.text = Fmt.day(windowStart)
        placeNowLine()
    }

    private fun placeNowLine() {
        val now = System.currentTimeMillis()
        if (now < windowStart || now >= windowEnd) {
            nowLine.visibility = View.GONE
        } else {
            nowLine.visibility = View.VISIBLE
            nowLine.translationX = (dp(170) + (now - windowStart) * pxPerMs).toFloat()
        }
    }

    private fun shiftWindow(delta: Long): Boolean {
        val next = windowStart + delta
        if (next < minStart || next + WINDOW > maxEnd + STEP) return false
        windowStart = next
        buildRuler()
        refreshKeepingFocus()
        return true
    }

    private fun refreshKeepingFocus() {
        val hadFocus = grid.hasFocus()
        adapter.notifyDataSetChanged()
        if (hadFocus) grid.post { focusCell(focusRow, focusTime) }
    }

    // ---------- Rijen ----------

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val number: TextView = v.findViewById(R.id.number)
            val logo: ImageView = v.findViewById(R.id.logo)
            val name: TextView = v.findViewById(R.id.name)
            val track: FrameLayout = v.findViewById(R.id.track)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_grid_row, parent, false))

        override fun getItemCount() = list.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val ch = list[position]
            h.number.text = (position + 1).toString()
            h.name.text = ch.name
            if (ch.logo != null) h.logo.load(ch.logo) else { h.logo.dispose(); h.logo.setImageDrawable(null) }
            fillTrack(h.track, position, ch)
            if (Epg.programmes(ch).isEmpty()) {
                Epg.fetchShort(prefs.account, ch) { }
            }
        }
    }

    private fun fillTrack(track: FrameLayout, row: Int, ch: Channel) {
        track.removeAllViews()
        val ws = windowStart
        val we = windowEnd
        val now = System.currentTimeMillis()
        val progs = Epg.programmes(ch).filter { it.stop > ws && it.start < we }
        val inflater = LayoutInflater.from(this)
        val gap = dp(3)

        fun addCell(cell: Cell, title: String, time: String, isNow: Boolean, isPast: Boolean) {
            val left = ((cell.start - ws) * pxPerMs).toInt()
            val right = ((cell.stop - ws) * pxPerMs).toInt()
            val width = (right - left - gap).coerceAtLeast(dp(4))
            val v = inflater.inflate(R.layout.item_grid_cell, track, false)
            v.findViewById<TextView>(R.id.title).text = title
            v.findViewById<TextView>(R.id.time).text = time
            v.isActivated = isNow
            v.alpha = if (isPast) 0.6f else 1f
            v.tag = cell
            v.setOnFocusChangeListener { _, has ->
                if (has) {
                    focusRow = cell.row
                    showDetail(ch, cell.p)
                }
            }
            v.setOnClickListener { onCellClick(ch, cell) }
            val lp = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
            lp.leftMargin = left
            track.addView(v, lp)
        }

        if (progs.isEmpty()) {
            addCell(Cell(row, null, ws, we), ch.name, "Geen programmagegevens", isNow = false, isPast = false)
            return
        }
        for (p in progs) {
            val s = maxOf(p.start, ws)
            val e = minOf(p.stop, we)
            addCell(
                Cell(row, p, s, e), p.title.ifEmpty { "Geen titel" }, Fmt.range(p),
                isNow = p.start <= now && p.stop > now, isPast = p.stop <= now
            )
        }
    }

    private fun showDetail(ch: Channel, p: Programme?) {
        if (p == null) {
            detailTitle.text = ch.name
            detailMeta.text = ch.group
            detailDesc.text = "Druk op OK om live te kijken."
            return
        }
        val now = System.currentTimeMillis()
        detailTitle.text = p.title.ifEmpty { ch.name }
        detailMeta.text = buildString {
            append(ch.name)
            append("  ·  ${Fmt.day(p.start)}  ${Fmt.range(p)}")
            if (p.start <= now && p.stop > now) append("  ·  Nu")
            if (Xtream.canCatchup(ch, p, now)) append("  ·  Terugkijken mogelijk")
        }
        detailDesc.text = p.desc
    }

    // ---------- Bediening ----------

    private fun cellsOf(row: Int): List<View>? {
        val v = (grid.layoutManager as LinearLayoutManager).findViewByPosition(row) ?: return null
        val track = v.findViewById<FrameLayout>(R.id.track)
        return (0 until track.childCount).map { track.getChildAt(it) }
    }

    /** Zet de focus op het vakje in [row] dat tijdstip [t] bevat (of het dichtstbijzijnde). */
    private fun focusCell(row: Int, t: Long) {
        if (list.isEmpty()) return
        val r = row.coerceIn(0, list.size - 1)
        val cells = cellsOf(r)
        if (cells == null) {
            (grid.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(r, dp(120))
            grid.post { cellsOf(r)?.let { pick(it, t)?.requestFocus() } }
            return
        }
        pick(cells, t)?.requestFocus()
    }

    private fun pick(cells: List<View>, t: Long): View? {
        if (cells.isEmpty()) return null
        return cells.firstOrNull { val c = it.tag as Cell; t >= c.start && t < c.stop }
            ?: cells.minByOrNull { val c = it.tag as Cell; minOf(kotlin.math.abs(c.start - t), kotlin.math.abs(c.stop - t)) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val cell = currentFocus?.tag as? Cell
        if (event.action != KeyEvent.ACTION_DOWN || cell == null) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val target = cell.stop
                if (target >= windowEnd) {
                    if (shiftWindow(STEP)) focusTime = target
                } else {
                    focusTime = target
                    focusCell(cell.row, target)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val target = cell.start - 1
                if (target < windowStart) {
                    if (shiftWindow(-STEP)) focusTime = maxOf(target, windowStart)
                } else {
                    focusTime = (cells(cell.row)?.let { pick(it, target)?.tag as? Cell }?.start ?: target)
                    focusCell(cell.row, target)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (cell.row > 0) focusCell(cell.row - 1, anchor(cell)) else return super.dispatchKeyEvent(event)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (cell.row < list.size - 1) focusCell(cell.row + 1, anchor(cell))
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                focusCell((cell.row - 8).coerceAtLeast(0), anchor(cell)); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                focusCell((cell.row + 8).coerceAtMost(list.size - 1), anchor(cell)); return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun cells(row: Int) = cellsOf(row)

    /** Het tijdstip dat bij omhoog/omlaag vastgehouden wordt (zoals in een echte tv-gids). */
    private fun anchor(cell: Cell): Long {
        if (focusTime < cell.start || focusTime >= cell.stop) focusTime = cell.start
        return focusTime
    }

    private fun onCellClick(ch: Channel, cell: Cell) {
        val row = cell.row
        val p = cell.p
        Repo.playList = list
        val now = System.currentTimeMillis()
        if (p == null || (p.start <= now && p.stop > now && !Xtream.canCatchup(ch, p, now))) {
            startActivity(PlayerActivity.live(this, row))
            return
        }
        when {
            p.start > now -> Toast.makeText(this, "${p.title} begint ${Fmt.day(p.start).lowercase()} om ${Fmt.time(p.start)}", Toast.LENGTH_SHORT).show()
            p.stop > now -> {
                AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(p.title)
                    .setItems(arrayOf("Live kijken", "Vanaf het begin kijken (${Fmt.time(p.start)})")) { _, which ->
                        if (which == 0) startActivity(PlayerActivity.live(this, row))
                        else PlayerActivity.catchupItem(this, row, p)?.let { History.playWithResume(this, it) }
                    }
                    .show()
            }
            Xtream.canCatchup(ch, p, now) ->
                PlayerActivity.catchupItem(this, row, p)?.let { History.playWithResume(this, it) }
            else -> Toast.makeText(this, "Deze uitzending is niet terug te kijken bij je aanbieder", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

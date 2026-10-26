package nl.kijktv

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Pauzescherm zoals bij streamingdiensten: beeld gedimd, titel + beschrijving,
 * tijdbalk en grote knoppen. Wordt gebruikt door beide spelers.
 */
class PauseOverlay(
    activity: Activity,
    private val onResume: () -> Unit,
    private val onSeek: (Long) -> Unit,
    private val onRestart: () -> Unit,
    private val onNext: () -> Unit
) {
    private val root: View = activity.findViewById(R.id.pauseOverlay)
    private val label: TextView = activity.findViewById(R.id.pauseLabel)
    private val title: TextView = activity.findViewById(R.id.pauseTitle)
    private val sub: TextView = activity.findViewById(R.id.pauseSub)
    private val desc: TextView = activity.findViewById(R.id.pauseDesc)
    private val pos: TextView = activity.findViewById(R.id.pausePos)
    private val left: TextView = activity.findViewById(R.id.pauseLeft)
    private val progress: ProgressBar = activity.findViewById(R.id.pauseProgress)
    private val play: Button = activity.findViewById(R.id.pausePlay)
    private val back: Button = activity.findViewById(R.id.pauseBack)
    private val fwd: Button = activity.findViewById(R.id.pauseFwd)
    private val restart: Button = activity.findViewById(R.id.pauseRestart)
    private val next: Button = activity.findViewById(R.id.pauseNext)

    val isShowing get() = root.visibility == View.VISIBLE

    init {
        play.setOnClickListener { onResume() }
        back.setOnClickListener { onSeek(-10_000) }
        fwd.setOnClickListener { onSeek(10_000) }
        restart.setOnClickListener { onRestart() }
        next.setOnClickListener { onNext() }
    }

    /**
     * @param seekable false bij live tv: dan geen spoelknoppen en geen tijdbalk.
     */
    fun show(
        heading: String,
        titleText: String,
        subText: String,
        description: String,
        seekable: Boolean,
        canRestart: Boolean,
        nextLabel: String?
    ) {
        label.text = heading
        title.text = titleText
        sub.text = subText
        sub.visibility = if (subText.isEmpty()) View.GONE else View.VISIBLE
        desc.text = description
        desc.visibility = if (description.isEmpty()) View.GONE else View.VISIBLE
        val seekVis = if (seekable) View.VISIBLE else View.GONE
        back.visibility = seekVis
        fwd.visibility = seekVis
        progress.visibility = seekVis
        pos.visibility = seekVis
        left.visibility = seekVis
        restart.visibility = if (canRestart) View.VISIBLE else View.GONE
        if (nextLabel != null) {
            next.text = "⏭  $nextLabel"
            next.visibility = View.VISIBLE
        } else {
            next.visibility = View.GONE
        }
        root.alpha = 0f
        root.visibility = View.VISIBLE
        root.animate().alpha(1f).setDuration(220).start()
        play.requestFocus()
    }

    fun update(positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) {
            pos.text = History.clock(positionMs)
            left.text = ""
            progress.progress = 0
            return
        }
        pos.text = History.clock(positionMs)
        left.text = "-" + History.clock((durationMs - positionMs).coerceAtLeast(0))
        progress.progress = ((positionMs * 1000) / durationMs).toInt().coerceIn(0, 1000)
    }

    fun hide() {
        root.animate().cancel()
        root.visibility = View.GONE
    }
}

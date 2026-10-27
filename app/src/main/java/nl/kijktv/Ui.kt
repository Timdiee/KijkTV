package nl.kijktv

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Laat de terug-pijl linksboven (als het scherm er een heeft) hetzelfde doen als de terugknop. */
fun Activity.setupBackButton() {
    findViewById<View?>(R.id.backBtn)?.setOnClickListener { onBackPressed() }
}

object Ui {
    /** Het scherm moet minstens zo hoog zijn (in dp) als waar de tv-indeling voor gemaakt is. */
    private const val MIN_DP = 500

    /**
     * Op een telefoon is het scherm te klein voor de tv-indeling. Dan schalen we alles
     * evenredig kleiner, zodat alle knoppen in beeld passen. Op een tv of tablet verandert er niets.
     */
    fun wrap(base: Context): Context {
        val res = base.resources
        val dm = res.displayMetrics
        val smallestPx = minOf(dm.widthPixels, dm.heightPixels)
        if (smallestPx <= 0) return base
        val targetDpi = (smallestPx * 160f / MIN_DP).toInt()
        val cfg = Configuration(res.configuration)
        if (targetDpi <= 0 || targetDpi >= cfg.densityDpi) return base
        cfg.densityDpi = targetDpi
        cfg.screenWidthDp = (dm.widthPixels * 160f / targetDpi).toInt()
        cfg.screenHeightDp = (dm.heightPixels * 160f / targetDpi).toInt()
        cfg.smallestScreenWidthDp = MIN_DP
        return base.createConfigurationContext(cfg)
    }

    /**
     * Vingerbediening voor de spelers: tikken, en vegen omhoog/omlaag of links/rechts.
     * @param onSwipe dx = -1 (naar links) / 1 (naar rechts) of dy = -1 (omhoog) / 1 (omlaag)
     */
    fun gestures(ctx: Context, onTap: () -> Unit, onSwipe: (dx: Int, dy: Int) -> Unit): GestureDetector {
        val minDist = 60 * ctx.resources.displayMetrics.density
        return GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (abs(dy) > abs(dx) && abs(dy) > minDist) {
                    onSwipe(0, if (dy < 0) -1 else 1)
                    return true
                }
                if (abs(dx) > minDist) {
                    onSwipe(if (dx < 0) -1 else 1, 0)
                    return true
                }
                return false
            }
        })
    }
}

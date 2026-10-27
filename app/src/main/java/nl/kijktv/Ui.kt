package nl.kijktv

import android.app.Activity
import android.view.View

/** Laat de terug-pijl linksboven (als het scherm er een heeft) hetzelfde doen als de terugknop. */
fun Activity.setupBackButton() {
    findViewById<View?>(R.id.backBtn)?.setOnClickListener { onBackPressed() }
}

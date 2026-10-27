package nl.kijktv

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import java.io.File

/** Vangt crashes op en bewaart de foutmelding, zodat die bij de volgende start getoond kan worden. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = buildString {
                    append("KijkTV ${BuildConfigInfo.version}\n")
                    append(android.util.Log.getStackTraceString(error))
                }
                File(filesDir, "crash.txt").writeText(text.take(6000))
            } catch (_: Exception) { }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        /** Toont de laatste crash (als die er is) en ruimt hem daarna op. */
        fun showLastCrash(a: Activity) {
            val f = File(a.filesDir, "crash.txt")
            if (!f.exists()) return
            val text = try { f.readText() } catch (_: Exception) { "" }
            f.delete()
            if (text.isEmpty()) return
            val lines = text.lines()
            val short = (lines.take(2) + lines.filter { it.contains("nl.kijktv") }.take(6)).joinToString("\n")
            AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("KijkTV is de vorige keer vastgelopen")
                .setMessage("Maak een foto van deze melding en stuur die door, dan kan het opgelost worden.\n\n$short")
                .setPositiveButton("Sluiten", null)
                .show()
        }
    }
}

object BuildConfigInfo {
    const val version = "3.7"
}

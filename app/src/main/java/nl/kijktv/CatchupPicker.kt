package nl.kijktv

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import java.util.Calendar

/**
 * Terugkijken zonder programma-informatie: kies zelf een dag en begintijd.
 * De aanbieder speelt de zender dan af vanaf dat moment (handig voor bv. een race op een sportzender).
 */
object CatchupPicker {

    private const val MIN15 = 15 * 60_000L
    private const val HOURS4 = 4 * 3600_000L

    private fun dialog(a: Activity) = AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)

    fun show(a: Activity, list: List<Channel>, index: Int) {
        val ch = list.getOrNull(index) ?: return
        val acc = Prefs(a).account
        if (acc == null || !acc.isXtream || ch.streamId == null) {
            Toast.makeText(a, "Terugkijken kan alleen als je inlogt met server, gebruikersnaam en wachtwoord", Toast.LENGTH_LONG).show()
            return
        }
        val days = if (ch.archiveDays > 0) ch.archiveDays.coerceAtMost(14) else 7
        val dayStarts = (0 until days).map { d ->
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -d)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        dialog(a)
            .setTitle("Terugkijken: ${ch.name}")
            .setItems(dayStarts.map { Fmt.day(it) }.toTypedArray()) { _, i -> chooseTime(a, list, index, dayStarts[i]) }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun chooseTime(a: Activity, list: List<Channel>, index: Int, dayStart: Long) {
        val now = System.currentTimeMillis()
        val times = ArrayList<Long>()
        var t = dayStart
        while (t < dayStart + 24 * 3600_000L && t < now - 5 * 60_000L) {
            times.add(t)
            t += MIN15
        }
        if (times.isEmpty()) {
            Toast.makeText(a, "Kies een eerder moment", Toast.LENGTH_SHORT).show()
            return
        }
        // Vandaag: de lijst opent bij ongeveer 2 uur geleden. Andere dagen: bij 14:00.
        val target = if (dayStart + 24 * 3600_000L > now) now - 2 * 3600_000L else dayStart + 14 * 3600_000L
        var checked = times.indexOfLast { it <= target }
        if (checked < 0) checked = 0
        dialog(a)
            .setTitle("${Fmt.day(dayStart)}: vanaf hoe laat?")
            .setSingleChoiceItems(times.map { Fmt.time(it) }.toTypedArray(), checked) { d, i ->
                d.dismiss()
                play(a, list, index, times[i])
            }
            .setNegativeButton("Terug") { _, _ -> show(a, list, index) }
            .show()
    }

    private fun play(a: Activity, list: List<Channel>, index: Int, start: Long) {
        val ch = list[index]
        val acc = Prefs(a).account ?: return
        val now = System.currentTimeMillis()
        val stop = minOf(start + HOURS4, now - 60_000L)
        val label = "${Fmt.day(start)} vanaf ${Fmt.time(start)}"
        val p = Programme(start, stop, ch.name, "")
        val url = Xtream.catchupUrl(acc, ch, p, Repo.serverTz) ?: return
        val item = WatchItem(
            "cu:${ch.streamId}:$start", WatchItem.CATCHUP, ch.name, "${ch.name}  ·  $label",
            ch.logo, url, durationMs = stop - start
        )
        History.playWithResume(a, item)
    }
}

package nl.kijktv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast

/** Het instellingenmenu: account, afspeelinstellingen en vernieuwen. Gedeeld door Home en Live tv. */
object Settings {

    private fun dialog(a: Activity) = AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)

    fun show(
        a: Activity,
        onReloadChannels: () -> Unit,
        onReloadEpg: () -> Unit,
        onPlaybackChanged: () -> Unit = {}
    ) {
        val prefs = Prefs(a)
        val mode = if (prefs.playMode == Prefs.MODE_FAST) "Snel" else "Soepel"
        val format = when (prefs.streamFormat) {
            Prefs.FORMAT_TS -> "MPEG-TS"
            Prefs.FORMAT_HLS -> "HLS"
            else -> "Automatisch"
        }
        val dec = if (prefs.decoder == Prefs.DECODER_SOFTWARE) "Software" else "Automatisch"
        val acc = prefs.account
        val who = when {
            acc == null -> "Niet ingelogd"
            acc.isXtream -> "Ingelogd als ${acc.user}" + (Repo.accountInfo?.let { "  ·  $it" } ?: "")
            else -> "M3U-playlist"
        }
        dialog(a)
            .setTitle(who)
            .setItems(
                arrayOf(
                    "Inloggegevens wijzigen",
                    "Afspelen: $mode",
                    "Streamtype: $format",
                    "Programmagids vernieuwen",
                    "Zenderlijst vernieuwen",
                    "Videodecoder: $dec"
                )
            ) { _, which ->
                when (which) {
                    0 -> a.startActivity(Intent(a, LoginActivity::class.java))
                    1 -> choosePlayMode(a, onPlaybackChanged)
                    2 -> chooseFormat(a, onPlaybackChanged)
                    3 -> {
                        if (Repo.epgUrl == null) {
                            Toast.makeText(a, "Je aanbieder levert geen programmagids", Toast.LENGTH_LONG).show()
                        } else {
                            Epg.clear()
                            try { java.io.File(a.filesDir, "epg.tsv").delete() } catch (_: Exception) { }
                            onReloadEpg()
                            Toast.makeText(a, "Programmagids wordt vernieuwd…", Toast.LENGTH_SHORT).show()
                        }
                    }
                    4 -> onReloadChannels()
                    5 -> chooseDecoder(a, onPlaybackChanged)
                }
            }
            .show()
    }

    private fun choosePlayMode(a: Activity, changed: () -> Unit) {
        val prefs = Prefs(a)
        val options = arrayOf(
            "Soepel (aanbevolen): minder haperen, beeld een paar seconden achter op live",
            "Snel: dichter op live, maar meer kans op haperen"
        )
        val cur = if (prefs.playMode == Prefs.MODE_FAST) 1 else 0
        dialog(a)
            .setTitle("Afspelen")
            .setSingleChoiceItems(options, cur) { d, which ->
                prefs.playMode = if (which == 1) Prefs.MODE_FAST else Prefs.MODE_SMOOTH
                d.dismiss()
                changed()
                Toast.makeText(a, "Opgeslagen", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun chooseDecoder(a: Activity, changed: () -> Unit) {
        val prefs = Prefs(a)
        val options = arrayOf(
            "Automatisch (aanbevolen voor de TV-box)",
            "Software: probeer dit als het geluid doorloopt maar het beeld hapert of stilstaat"
        )
        val cur = if (prefs.decoder == Prefs.DECODER_SOFTWARE) 1 else 0
        dialog(a)
            .setTitle("Videodecoder")
            .setSingleChoiceItems(options, cur) { d, which ->
                prefs.decoder = if (which == 1) Prefs.DECODER_SOFTWARE else Prefs.DECODER_AUTO
                d.dismiss()
                changed()
                Toast.makeText(a, "Opgeslagen", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun chooseFormat(a: Activity, changed: () -> Unit) {
        val prefs = Prefs(a)
        val options = arrayOf(
            "Automatisch (aanbevolen)",
            "MPEG-TS: meestal het soepelst bij IPTV",
            "HLS: probeer dit als zenders haperen of niet starten"
        )
        val cur = when (prefs.streamFormat) { Prefs.FORMAT_TS -> 1; Prefs.FORMAT_HLS -> 2; else -> 0 }
        dialog(a)
            .setTitle("Streamtype")
            .setSingleChoiceItems(options, cur) { d, which ->
                prefs.streamFormat = when (which) { 1 -> Prefs.FORMAT_TS; 2 -> Prefs.FORMAT_HLS; else -> Prefs.FORMAT_AUTO }
                d.dismiss()
                changed()
                Toast.makeText(a, "Opgeslagen", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}

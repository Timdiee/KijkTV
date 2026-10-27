package nl.kijktv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import java.util.Locale

/** Ondertiteling en audiotaal kiezen, plus de tekstgrootte van de ondertiteling. */
@androidx.annotation.OptIn(UnstableApi::class)
object TrackMenu {

    class Option(val group: Tracks.Group, val index: Int, val format: Format)

    private val nl = Locale("nl", "NL")
    private val iso3 = mapOf(
        "dut" to "nl", "nld" to "nl", "eng" to "en", "ger" to "de", "deu" to "de", "fre" to "fr",
        "fra" to "fr", "spa" to "es", "ita" to "it", "tur" to "tr", "ara" to "ar", "por" to "pt",
        "pol" to "pl", "rus" to "ru", "swe" to "sv", "dan" to "da", "nor" to "no", "fin" to "fi"
    )
    private val sizes = floatArrayOf(0.8f, 1f, 1.3f, 1.6f)
    private val sizeNames = arrayOf("Klein", "Normaal", "Groot", "Extra groot")

    fun options(p: Player, type: Int): List<Option> {
        val out = ArrayList<Option>()
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (g.isTrackSupported(i)) out.add(Option(g, i, g.getTrackFormat(i)))
            }
        }
        return out
    }

    fun hasSubtitles(p: Player?) = p != null && options(p, C.TRACK_TYPE_TEXT).isNotEmpty()
    fun hasAudioChoice(p: Player?) = p != null && options(p, C.TRACK_TYPE_AUDIO).size > 1

    fun langCode(f: Format): String? {
        val l = f.language?.lowercase()?.substringBefore('-')?.trim()
        if (l.isNullOrEmpty() || l == "und" || l == "mis") return null
        return iso3[l] ?: l
    }

    private fun langName(code: String): String {
        val n = Locale(code).getDisplayLanguage(nl)
        return if (n.isBlank() || n.equals(code, ignoreCase = true)) code.uppercase()
        else n.replaceFirstChar { it.uppercase() }
    }

    private fun label(o: Option, n: Int, audio: Boolean): String {
        val f = o.format
        val parts = ArrayList<String>()
        langCode(f)?.let { parts.add(langName(it)) }
        val own = f.label?.trim()
        if (!own.isNullOrEmpty() && parts.none { it.equals(own, ignoreCase = true) }) parts.add(own)
        if (parts.isEmpty()) parts.add((if (audio) "Audiospoor " else "Ondertiteling ") + n)
        if (!audio && (f.selectionFlags and C.SELECTION_FLAG_FORCED) != 0) parts.add("geforceerd")
        if (audio) {
            when (f.channelCount) {
                1 -> parts.add("mono")
                2 -> parts.add("stereo")
                6 -> parts.add("5.1")
                8 -> parts.add("7.1")
            }
        }
        return parts.joinToString("  ·  ")
    }

    private fun dialog(a: Activity) = AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)

    /** @param remember true bij films/series: de gekozen taal wordt de standaard voor volgende keren. */
    fun showSubtitles(a: Activity, p: Player, view: PlayerView, remember: Boolean) {
        val prefs = Prefs(a)
        val opts = options(p, C.TRACK_TYPE_TEXT)
        val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val names = ArrayList<String>()
        names.add("Uit")
        opts.forEachIndexed { i, o -> names.add(label(o, i + 1, false)) }
        var checked = 0
        if (!off) opts.forEachIndexed { i, o -> if (o.group.isTrackSelected(o.index)) checked = i + 1 }

        val b = dialog(a).setTitle("Ondertiteling")
        if (opts.isEmpty()) {
            b.setMessage("Hier is geen ondertiteling bij beschikbaar.")
            b.setPositiveButton("Sluiten", null)
        } else {
            b.setSingleChoiceItems(names.toTypedArray(), checked) { d, which ->
                if (which == 0) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    if (remember) prefs.subtitleLang = Prefs.SUB_OFF
                } else {
                    val o = opts[which - 1]
                    select(p, C.TRACK_TYPE_TEXT, o)
                    if (remember) langCode(o.format)?.let { prefs.subtitleLang = it }
                }
                d.dismiss()
            }
        }
        b.setNeutralButton("Tekstgrootte: ${sizeNames[prefs.subtitleSize.coerceIn(0, 3)]}") { _, _ ->
            chooseSize(a, view)
        }
        b.show()
    }

    fun showAudio(a: Activity, p: Player, remember: Boolean) {
        val prefs = Prefs(a)
        val opts = options(p, C.TRACK_TYPE_AUDIO)
        if (opts.isEmpty()) return
        var checked = 0
        opts.forEachIndexed { i, o -> if (o.group.isTrackSelected(o.index)) checked = i }
        val names = opts.mapIndexed { i, o -> label(o, i + 1, true) }.toTypedArray()
        dialog(a)
            .setTitle("Audio")
            .setSingleChoiceItems(names, checked) { d, which ->
                val o = opts[which]
                select(p, C.TRACK_TYPE_AUDIO, o)
                if (remember) langCode(o.format)?.let { prefs.audioLang = it }
                d.dismiss()
            }
            .show()
    }

    private fun select(p: Player, type: Int, o: Option) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
            .build()
    }

    private fun chooseSize(a: Activity, view: PlayerView) {
        val prefs = Prefs(a)
        dialog(a)
            .setTitle("Tekstgrootte ondertiteling")
            .setSingleChoiceItems(sizeNames, prefs.subtitleSize.coerceIn(0, 3)) { d, which ->
                prefs.subtitleSize = which
                applyStyle(view, which)
                d.dismiss()
            }
            .show()
    }

    /** Goed leesbare ondertiteling: witte vette letters op een half doorzichtige balk. */
    fun applyStyle(view: PlayerView, size: Int) {
        val s: SubtitleView = view.subtitleView ?: return
        s.setApplyEmbeddedStyles(false)
        s.setApplyEmbeddedFontSizes(false)
        s.setStyle(
            CaptionStyleCompat(
                Color.WHITE, 0x99000000.toInt(), Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, Typeface.DEFAULT_BOLD
            )
        )
        s.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * sizes[size.coerceIn(0, 3)])
        s.setBottomPaddingFraction(0.08f)
    }
}

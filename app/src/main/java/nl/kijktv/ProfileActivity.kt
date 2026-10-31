package nl.kijktv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** "Wie kijkt er?": profiel kiezen, toevoegen, hernoemen of verwijderen. */
class ProfileActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Ui.wrap(newBase))
    }

    companion object {
        fun intent(ctx: Context) = Intent(ctx, ProfileActivity::class.java)

        /** Rondje met de eerste letter, in de kleur van het profiel. */
        fun avatar(ctx: Context, p: Profile, sizeDp: Int, focused: Boolean = false): GradientDrawable {
            val d = ctx.resources.displayMetrics.density
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Profiles.COLORS[p.color.coerceIn(0, Profiles.COLORS.size - 1)])
                if (focused) setStroke((4 * d).toInt(), Color.WHITE)
                setSize((sizeDp * d).toInt(), (sizeDp * d).toInt())
            }
        }

        fun initial(p: Profile) = p.name.trim().take(1).uppercase().ifEmpty { "?" }
    }

    private lateinit var row: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)
        setupBackButton()
        row = findViewById(R.id.profileRow)
        build(Profiles.current(this))
        // Nieuwe profielen van je andere apparaten.
        Sync.pull(this, force = true) { if (!isFinishing) build(focusedId()) }
    }

    private fun focusedId(): String? = row.focusedChild?.tag as? String

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun build(focusId: String?) {
        row.removeAllViews()
        var toFocus: View? = null
        val list = Profiles.visible(this)
        for (p in list) {
            val tile = tile(p.name, initial(p)) { focused -> avatar(this, p, 104, focused) }
            tile.tag = p.id
            tile.setOnClickListener { choose(p.id) }
            tile.setOnLongClickListener { manage(p); true }
            row.addView(tile)
            if (p.id == focusId) toFocus = tile
        }
        if (list.size < Profiles.MAX) {
            val add = tile("Profiel toevoegen", "+") { focused ->
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF262B36.toInt())
                    if (focused) setStroke(dp(4), Color.WHITE)
                }
            }
            add.tag = "__add"
            add.setOnClickListener { askName("Nieuw profiel", "") { n -> Profiles.add(this, n)?.let { build(it.id) } } }
            row.addView(add)
            if (focusId == "__add") toFocus = add
        }
        val f = toFocus ?: row.getChildAt(0)
        f?.post { f.requestFocus() }
    }

    private fun tile(name: String, letter: String, bg: (Boolean) -> GradientDrawable): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(140), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val circle = TextView(this).apply {
            text = letter
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 40f
            typeface = Typeface.DEFAULT_BOLD
            background = bg(false)
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(104))
        }
        val label = TextView(this).apply {
            text = name
            gravity = Gravity.CENTER
            setTextColor(0xFF9AA3B2.toInt())
            textSize = 15f
            maxLines = 2
            setPadding(0, dp(10), 0, 0)
        }
        tile.addView(circle)
        tile.addView(label)
        tile.setOnFocusChangeListener { v, has ->
            circle.background = bg(has)
            label.setTextColor(if (has) Color.WHITE else 0xFF9AA3B2.toInt())
            v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(120).start()
        }
        return tile
    }

    private fun choose(id: String) {
        Profiles.setCurrent(this, id)
        Sync.pull(this, force = true)
        if (isTaskRoot) startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun manage(p: Profile) {
        val options = if (p.id == Profiles.MAIN) arrayOf("Naam wijzigen") else arrayOf("Naam wijzigen", "Profiel verwijderen")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(p.name)
            .setItems(options) { _, which ->
                if (which == 0) askName("Naam wijzigen", p.name) { n -> Profiles.rename(this, p.id, n); build(p.id) }
                else AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("${p.name} verwijderen?")
                    .setMessage("Verder kijken en de geschiedenis van dit profiel worden gewist, op al je apparaten.")
                    .setPositiveButton("Verwijderen") { _, _ -> Profiles.delete(this, p.id); build(null) }
                    .setNegativeButton("Annuleren", null)
                    .show()
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun askName(title: String, start: String, done: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(start)
            setSelection(start.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = "Naam"
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Opslaan") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty()) Toast.makeText(this, "Vul een naam in", Toast.LENGTH_SHORT).show() else done(n)
            }
            .setNegativeButton("Annuleren", null)
            .show()
        input.requestFocus()
    }

    override fun onBackPressed() {
        // Bij het opstarten: terug = gewoon het huidige profiel houden.
        Profiles.chosenThisRun = true
        if (isTaskRoot) startActivity(Intent(this, HomeActivity::class.java))
        super.onBackPressed()
    }
}

package nl.kijktv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView

/** Inlogscherm: server + gebruikersnaam + wachtwoord, een M3U-link, of via de telefoon. */
class LoginActivity : Activity() {

    companion object {
        const val DEMO_URL = "https://iptv-org.github.io/iptv/countries/nl.m3u"
    }

    private lateinit var prefs: Prefs
    private lateinit var server: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var m3u: EditText
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var buttons: List<Button>
    private var phoneServer: PhoneServer? = null
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        prefs = Prefs(this)

        server = findViewById(R.id.server)
        user = findViewById(R.id.user)
        pass = findViewById(R.id.pass)
        m3u = findViewById(R.id.m3u)
        message = findViewById(R.id.message)
        progress = findViewById(R.id.progress)
        val loginBtn = findViewById<Button>(R.id.loginBtn)
        val m3uBtn = findViewById<Button>(R.id.m3uBtn)
        val demoBtn = findViewById<Button>(R.id.demoBtn)
        buttons = listOf(loginBtn, m3uBtn, demoBtn)

        prefs.account?.let { a ->
            if (a.isXtream) {
                server.setText(a.server); user.setText(a.user); pass.setText(a.pass)
            } else {
                m3u.setText(a.m3uUrl)
            }
        }

        loginBtn.setOnClickListener {
            val acc = Account.fromInput(server.text.toString(), user.text.toString(), pass.text.toString(), "")
            if (acc == null) showError("Vul server-link, gebruikersnaam én wachtwoord in.") else tryLogin(acc)
        }
        m3uBtn.setOnClickListener {
            val acc = Account.fromInput("", "", "", m3u.text.toString())
            if (acc == null) showError("Vul eerst een link in.") else tryLogin(acc)
        }
        demoBtn.setOnClickListener { tryLogin(Account(Account.M3U, m3uUrl = DEMO_URL)) }

        val hint = findViewById<TextView>(R.id.phoneHint)
        val url = findViewById<TextView>(R.id.phoneUrl)
        val ip = PhoneServer.localIp()
        if (ip != null) {
            hint.text = "Zorg dat je telefoon op hetzelfde wifi-netwerk zit en open dit adres in de browser van je telefoon:"
            url.text = "http://$ip:${PhoneServer.PORT}"
        } else {
            hint.text = "De box heeft geen netwerk. Verbind hem met wifi of een kabel."
            url.visibility = View.GONE
        }

        phoneServer = PhoneServer { f ->
            val acc = Account.fromInput(
                f["server"].orEmpty(), f["user"].orEmpty(), f["pass"].orEmpty(),
                f["m3u"].orEmpty().ifEmpty { f["url"].orEmpty() }, f["epg"].orEmpty()
            )
            if (acc != null) {
                runOnUiThread {
                    if (acc.isXtream) {
                        server.setText(acc.server); user.setText(acc.user); pass.setText(acc.pass)
                    } else {
                        m3u.setText(acc.m3uUrl)
                    }
                    tryLogin(acc)
                }
            }
            acc != null
        }.also { it.start() }

        if (server.text.isEmpty()) server.requestFocus() else loginBtn.requestFocus()
    }

    private fun showError(msg: String) {
        progress.visibility = View.GONE
        message.setTextColor(0xFFFF7B72.toInt())
        message.text = msg
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        for (btn in buttons) btn.isEnabled = !b
    }

    private fun tryLogin(acc: Account) {
        if (busy) return
        setBusy(true)
        message.setTextColor(0xFFF0F3F6.toInt())
        message.text = if (acc.isXtream) "Inloggen en zenders ophalen…" else "Playlist laden…"
        Thread {
            try {
                val list = Repo.load(this, acc)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    prefs.account = acc
                    prefs.lastGroup = null
                    Epg.clear()
                    Repo.setChannels(list)
                    setBusy(false)
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    setBusy(false)
                    showError(e.message ?: "Er ging iets mis. Probeer het opnieuw.")
                }
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Zonder account is er nog niets te zien: dan sluit de app.
        if (prefs.account == null) finishAffinity() else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        phoneServer?.stop()
    }
}

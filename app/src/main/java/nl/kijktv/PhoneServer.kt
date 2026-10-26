package nl.kijktv

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Een piepklein webservertje op de TV-box, zodat je de playlist-link
 * vanaf je telefoon kunt plakken in plaats van met de afstandsbediening te typen.
 */
/** [onForm] krijgt de ingevulde velden en geeft true terug als ze bruikbaar zijn. */
class PhoneServer(private val onForm: (Map<String, String>) -> Boolean) {
    @Volatile private var running = false
    private var socket: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                val ss = ServerSocket(PORT)
                socket = ss
                while (running) {
                    val client = ss.accept()
                    Thread { handle(client) }.start()
                }
            } catch (_: Exception) {
                running = false
            }
        }.start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15000
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return
            var length = 0
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                if (header.lowercase().startsWith("content-length:")) {
                    length = header.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            var body = ""
            if (length in 1..100_000) {
                val buf = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(buf, read, length - read)
                    if (n < 0) break
                    read += n
                }
                body = String(buf, 0, read, Charsets.UTF_8)
            }

            val method = requestLine.substringBefore(' ')
            val html = if (method == "POST") {
                if (onForm(parseForm(body))) {
                    page("✅ Verstuurd! Kijk op je TV: daar zie je of het inloggen gelukt is.")
                } else {
                    page("⚠️ Vul server-link, gebruikersnaam én wachtwoord in (of een M3U-link).")
                }
            } else {
                page(null)
            }

            val bytes = html.toByteArray(Charsets.UTF_8)
            val out = client.getOutputStream()
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.write(bytes)
            out.flush()
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) { }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) break
        }
        return sb.toString()
    }

    private fun parseForm(body: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (pair in body.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            try {
                map[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8").trim()
            } catch (_: Exception) { }
        }
        return map
    }

    private fun page(message: String?): String {
        val msg = if (message != null) "<p class=\"msg\">$message</p>" else ""
        return """<!doctype html><html lang="nl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>KijkTV instellen</title>
<style>
body{font-family:system-ui,-apple-system,sans-serif;background:#0d1117;color:#f0f3f6;margin:0;padding:24px;max-width:520px}
h1{font-size:24px;margin:0 0 4px}p{color:#8b949e;line-height:1.5}
label{display:block;margin:14px 0 6px;font-weight:600}
input{width:100%;box-sizing:border-box;font-size:17px;padding:13px;border-radius:10px;border:1px solid #30363d;background:#161b22;color:#f0f3f6}
button{width:100%;margin-top:16px;font-size:17px;padding:14px;border:0;border-radius:10px;background:#2f81f7;color:#fff;font-weight:600}
.box{background:#161b22;border-radius:14px;padding:16px 18px;margin-top:18px}
.msg{background:#1f2630;color:#f0f3f6;padding:12px 14px;border-radius:10px}
h2{font-size:17px;margin:0}
</style></head><body>
<h1>KijkTV instellen</h1>
<p>Vul je gegevens in. Je TV logt daarna vanzelf in.</p>
$msg
<form method="post" class="box">
<h2>Inloggen bij je aanbieder</h2>
<p>De gegevens die je van je IPTV-aanbieder hebt gekregen.</p>
<label for="server">Server-link</label>
<input id="server" name="server" placeholder="http://server.nl:8080" autocomplete="off" autocapitalize="none">
<label for="user">Gebruikersnaam</label>
<input id="user" name="user" autocomplete="off" autocapitalize="none">
<label for="pass">Wachtwoord</label>
<input id="pass" name="pass" type="password" autocomplete="off">
<button type="submit">Inloggen op de TV</button>
</form>
<form method="post" class="box">
<h2>Of: één lange link (M3U)</h2>
<label for="m3u">Playlist-link</label>
<input id="m3u" name="m3u" type="url" placeholder="http://…" autocomplete="off" autocapitalize="none">
<label for="epg">Gids-link (EPG), niet verplicht</label>
<input id="epg" name="epg" type="url" placeholder="http://…/epg.xml" autocomplete="off" autocapitalize="none">
<button type="submit">Naar de TV sturen</button>
</form>
</body></html>"""
    }

    companion object {
        const val PORT = 8765

        fun localIp(): String? {
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
                for (iface in ifaces.toList()) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (addr in iface.inetAddresses.toList()) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            } catch (_: Exception) { }
            return null
        }
    }
}

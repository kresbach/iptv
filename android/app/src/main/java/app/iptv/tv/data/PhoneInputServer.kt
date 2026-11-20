package app.iptv.tv.data

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Kleiner Web-Server im Heimnetz: Auf dem Handy (gleiches WLAN) öffnet man per QR-Code eine
 * Eingabeseite und schickt M3U-Link bzw. Xtream-Zugang an den Fernseher – kein Abtippen
 * mit der Fernbedienung. Ein Zufalls-Code in der Adresse verhindert, dass andere Geräte
 * im Netz unbemerkt etwas senden.
 */
class PhoneInputServer {
    data class Input(
        val name: String = "",
        val link: String = "",
        val server: String = "",
        val user: String = "",
        val pass: String = "",
    ) {
        val isXtream: Boolean get() = link.isBlank() && server.isNotBlank()
    }

    /** Zuletzt vom Handy empfangene Eingabe (die Oberfläche übernimmt sie). */
    val received = MutableStateFlow<Input?>(null)

    /** Status, den die Handy-Seite anzeigt („Lädt …“, „Fertig“, Fehler). */
    @Volatile var status: String = ""

    val token: String = buildString {
        val random = SecureRandom()
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        repeat(6) { append(chars[random.nextInt(chars.length)]) }
    }

    private var socket: ServerSocket? = null
    val port: Int get() = socket?.localPort ?: 0

    /** Startet den Server (bevorzugt Port 8686) und liefert den Port. */
    fun start(preferredPort: Int = 8686): Int {
        if (socket != null) return port
        val server = try {
            ServerSocket(preferredPort)
        } catch (e: Exception) {
            ServerSocket(0)
        }
        socket = server
        thread(name = "PhoneInputServer", isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: SocketException) {
                    break
                }
                thread(isDaemon = true) { handle(client) }
            }
        }
        return server.localPort
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    fun url(ip: String): String = "http://$ip:$port/$token"

    private fun handle(client: Socket) {
        client.use { s ->
            runCatching {
                s.soTimeout = 15_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                var contentLength = 0
                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("content-length:", ignoreCase = true)) {
                        contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength in 1..MAX_BODY) readBytes(input, contentLength) else ""
                val (code, type, content) = respond(requestLine, body)
                val bytes = content.toByteArray(Charsets.UTF_8)
                val out = s.getOutputStream()
                out.write(
                    ("HTTP/1.1 $code\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                        "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                out.write(bytes)
                out.flush()
            }
        }
    }

    /** Beantwortet eine Anfrage (ohne Netzwerk testbar). */
    fun respond(requestLine: String, body: String): Triple<String, String, String> {
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: ""
        val path = parts.getOrNull(1)?.substringBefore('?') ?: ""
        return when {
            method == "GET" && path == "/$token" -> Triple("200 OK", "text/html", page())
            method == "GET" && path == "/$token/status" -> Triple("200 OK", "text/plain", status)
            method == "POST" && path == "/$token/send" -> {
                val form = parseForm(body)
                val input = Input(
                    name = form["name"].orEmpty().trim(),
                    link = form["link"].orEmpty().trim(),
                    server = form["server"].orEmpty().trim(),
                    user = form["user"].orEmpty().trim(),
                    pass = form["pass"].orEmpty().trim(),
                )
                if (input.link.isBlank() && (input.server.isBlank() || input.user.isBlank() || input.pass.isBlank())) {
                    Triple("400 Bad Request", "text/plain", "Bitte einen Link oder Server, Benutzername und Passwort eingeben.")
                } else {
                    status = "Übertragen – der Fernseher lädt die Playlist …"
                    received.value = input
                    Triple("200 OK", "text/plain", "ok")
                }
            }
            else -> Triple("404 Not Found", "text/plain", "Nicht gefunden. Bitte den QR-Code auf dem Fernseher erneut scannen.")
        }
    }

    private fun page(): String = PAGE.replace("{{BASE}}", "/$token")

    companion object {
        private const val MAX_BODY = 64 * 1024

        fun parseForm(body: String): Map<String, String> =
            body.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val k = pair.substringBefore('=')
                val v = pair.substringAfter('=', "")
                fun dec(x: String) = runCatching { URLDecoder.decode(x, "UTF-8") }.getOrDefault(x)
                dec(k) to dec(v)
            }

        private fun readLine(input: InputStream): String? {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
                if (b == '\n'.code) break
                if (b != '\r'.code) buffer.write(b)
                if (buffer.size() > 8192) return null
            }
            return buffer.toString("UTF-8")
        }

        private fun readBytes(input: InputStream, count: Int): String {
            val data = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = input.read(data, read, count - read)
                if (n < 0) break
                read += n
            }
            return String(data, 0, read, Charsets.UTF_8)
        }

        /** IPv4-Adresse des Fernsehers im Heimnetz (WLAN oder LAN-Kabel). */
        fun localIpAddress(): String? {
            val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { iface -> iface.inetAddresses.toList().map { iface.name to it } }
                .filter { (_, a) -> a is Inet4Address && a.isSiteLocalAddress }
            // WLAN/LAN bevorzugen, VPN-/Tunnel-Adapter zuletzt.
            return candidates.sortedBy { (name, _) ->
                when {
                    name.startsWith("wlan") || name.startsWith("eth") -> 0
                    name.startsWith("tun") || name.startsWith("ppp") -> 2
                    else -> 1
                }
            }.map { (_, a) -> (a as InetAddress).hostAddress }.firstOrNull()
        }

        private val PAGE = """
<!doctype html>
<html lang="de"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>IPTV – Playlist senden</title>
<style>
 :root{color-scheme:dark}
 body{margin:0;font-family:system-ui,-apple-system,sans-serif;background:#0E0E12;color:#F5F5F7}
 header{background:linear-gradient(135deg,#FFA81A,#F55200);padding:22px 20px;font-size:22px;font-weight:800}
 main{padding:20px;max-width:560px;margin:auto}
 .tabs{display:flex;gap:8px;margin-bottom:18px}
 .tabs button{flex:1;padding:12px;border-radius:24px;border:1px solid #333;background:#1B1B23;color:#fff;font-size:16px}
 .tabs button.on{background:linear-gradient(90deg,#FFA81A,#F55200);border:0;font-weight:700}
 label{display:block;margin:14px 0 6px;color:#B4B4BE;font-size:14px}
 input,textarea{width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #333;background:#1B1B23;color:#fff;font-size:16px}
 textarea{min-height:110px;word-break:break-all}
 input:focus,textarea:focus{outline:2px solid #FF7A00;border-color:#FF7A00}
 .send{margin-top:22px;width:100%;padding:16px;border:0;border-radius:28px;background:linear-gradient(90deg,#FFA81A,#F55200);color:#fff;font-size:18px;font-weight:700}
 .hint{color:#B4B4BE;font-size:13px;margin-top:8px}
 #msg{margin-top:18px;padding:14px;border-radius:12px;background:#1B1B23;display:none;font-size:16px}
 .hidden{display:none}
</style></head><body>
<header>IPTV · Playlist an Fernseher senden</header>
<main>
 <div class="tabs"><button id="t1" class="on" type="button">M3U-Link</button><button id="t2" type="button">Xtream-Login</button></div>
 <form id="f">
  <label>Name (optional)</label><input name="name" placeholder="z. B. Mein IPTV">
  <div id="m3u"><label>M3U-Link</label>
   <textarea name="link" placeholder="http://server.de/get.php?username=…&amp;password=…" autocapitalize="off" autocorrect="off" spellcheck="false"></textarea>
   <div class="hint">Einfach den kompletten Link einfügen. „get.php“-Links werden automatisch als Xtream erkannt.</div></div>
  <div id="xt" class="hidden">
   <label>Server</label><input name="server" placeholder="http://server.de:8080" autocapitalize="off" autocorrect="off">
   <label>Benutzername</label><input name="user" autocapitalize="off" autocorrect="off">
   <label>Passwort</label><input name="pass" type="password">
  </div>
  <button class="send" type="submit">An Fernseher senden</button>
 </form>
 <div id="msg"></div>
</main>
<script>
var xt=false,f=document.getElementById('f'),msg=document.getElementById('msg');
function mode(x){xt=x;document.getElementById('t1').className=x?'':'on';document.getElementById('t2').className=x?'on':'';
 document.getElementById('m3u').className=x?'hidden':'';document.getElementById('xt').className=x?'':'hidden';}
document.getElementById('t1').onclick=function(){mode(false)};document.getElementById('t2').onclick=function(){mode(true)};
function show(t){msg.style.display='block';msg.textContent=t;}
f.onsubmit=function(e){e.preventDefault();var d=new URLSearchParams(new FormData(f));
 if(xt)d.set('link','');else{d.set('server','');d.set('user','');d.set('pass','');}
 show('Sende …');
 fetch('{{BASE}}/send',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:d.toString()})
  .then(function(r){return r.text().then(function(t){if(!r.ok)throw new Error(t);poll();})})
  .catch(function(err){show('Fehler: '+err.message);});};
var fails=0;
function poll(){fetch('{{BASE}}/status').then(function(r){return r.text()}).then(function(t){fails=0;show(t);
 if(t.indexOf('Fertig')<0&&t.indexOf('Fehler')<0)setTimeout(poll,1500);}).catch(function(){if(++fails<5)setTimeout(poll,3000);});}
</script></body></html>
"""
    }
}

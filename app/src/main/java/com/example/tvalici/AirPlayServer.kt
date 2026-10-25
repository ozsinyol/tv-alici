package com.example.tvalici

import android.util.Log
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * AirPlay istek sunucusu (1. asama: kesif).
 * Su an yalnizca GET/POST /info isteklerini yanitlar; digerlerini loglar ve 501 doner.
 * Amac, iPhone'un TV'yi listede gostermesini saglamak ve hangi isteklerin
 * geldigini gormek. Eslesme (pair-setup/verify) ve /play sonraki asamalar.
 */
class AirPlayServer(
    private val port: Int,
    private val airplay: AirPlayInfo,
    private val onLog: (String) -> Unit
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        running = true
        thread(name = "airplay-accept", isDaemon = true) {
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                log("AirPlay sunucusu :$port")
                while (running) {
                    val socket = ss.accept()
                    thread(name = "airplay-conn", isDaemon = true) { handle(socket) }
                }
            } catch (e: Exception) {
                if (running) log("Sunucu hatasi: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // kapanirken hata onemsiz
        }
    }

    private fun log(msg: String) {
        Log.i("AirPlay", msg)
        onLog(msg)
    }

    private fun handle(socket: Socket) {
        log("Baglanti: ${socket.inetAddress.hostAddress}")
        try {
            socket.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                while (true) {
                    val requestLine = readLine(input) ?: break
                    if (requestLine.isBlank()) continue
                    val parts = requestLine.split(" ")
                    val method = parts.getOrElse(0) { "" }
                    val uri = parts.getOrElse(1) { "/" }
                    val proto = parts.getOrElse(2) { "HTTP/1.1" }

                    val headers = HashMap<String, String>()
                    while (true) {
                        val line = readLine(input) ?: return
                        if (line.isEmpty()) break
                        val i = line.indexOf(':')
                        if (i > 0) {
                            headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                        }
                    }

                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(body, read, length - read)
                        if (n < 0) return
                        read += n
                    }

                    log("$method $uri ($length B)")
                    respond(out, proto, method, uri, headers, body)
                }
            }
        } catch (e: Exception) {
            log("Baglanti kapandi: ${e.message}")
        }
    }

    private fun respond(
        out: OutputStream,
        proto: String,
        method: String,
        uri: String,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        val cseq = headers["cseq"]
        val octet = "application/octet-stream"
        if ((method == "GET" || method == "POST") && uri == "/info") {
            send(out, proto, 200, "OK", infoPlist(), "application/x-apple-binary-plist", cseq)
            return
        }
        if (method == "POST" && uri == "/fp-setup") {
            // FairPlay el sikismasi: 16 bayt -> 142 bayt, 164 bayt -> 32 bayt.
            val reply = when (body.size) {
                16 -> AirPlayFairPlay.setup(body)
                164 -> AirPlayFairPlay.handshake(body)
                else -> null
            }
            if (reply != null) {
                log("  fp-setup ${body.size} B tamam")
                send(out, proto, 200, "OK", reply, octet, cseq)
            } else {
                log("  fp-setup reddedildi (${body.size} B)")
                send(out, proto, 400, "Bad Request", ByteArray(0), null, cseq)
            }
            return
        }
        if (method == "POST" && uri == "/pair-setup") {
            // Istemci 32 bayt Ed25519 acik anahtarini yollar, biz kendimizinkini doneriz.
            send(out, proto, 200, "OK", airplay.publicKey, octet, cseq)
            return
        }
        if (method == "POST" && uri == "/pair-verify" && body.size >= 4) {
            if (body[0].toInt() == 1 && body.size == 68) {
                // Adim 1: istemcinin gecici Curve25519 anahtari (+ Ed25519 anahtari) gelir.
                val clientCurve = body.copyOfRange(4, 36)
                val ephPriv = ByteArray(32)
                SecureRandom().nextBytes(ephPriv)
                val ephPub = X25519.publicKey(ephPriv)
                val secret = X25519.scalarMult(ephPriv, clientCurve)
                val signature = Ed25519.sign(airplay.seed, ephPub + clientCurve)
                val encrypted = AirPlayCrypto.pairVerifyEncrypt(secret, signature)
                log("  pair-verify 1 tamam")
                send(out, proto, 200, "OK", ephPub + encrypted, octet, cseq)
            } else {
                // Adim 2: istemcinin imzasi. Ev agi prototipi, dogrulamayi atliyoruz.
                log("  pair-verify 2 tamam")
                send(out, proto, 200, "OK", ByteArray(0), octet, cseq)
            }
            return
        }
        val interesting = listOf("upgrade", "content-type", "user-agent", "x-apple-device-id")
            .mapNotNull { k -> headers[k]?.let { "$k=$it" } }
            .joinToString(" ")
        log("  islenmedi: $interesting")
        // Govde kucukse (ve metin/plist degilse bile) ilk baytlari logla: protokolu anlamak icin.
        if (body.isNotEmpty()) {
            val preview = body.take(48).joinToString("") { "%02x".format(it) }
            log("  govde: $preview${if (body.size > 48) "..." else ""}")
        }
        send(out, proto, 501, "Not Implemented", ByteArray(0), null, cseq)
    }

    private fun send(
        out: OutputStream,
        proto: String,
        code: Int,
        text: String,
        body: ByteArray,
        contentType: String?,
        cseq: String?
    ) {
        val sb = StringBuilder()
        sb.append("$proto $code $text\r\n")
        sb.append("Server: AirTunes/220.68\r\n")
        if (cseq != null) sb.append("CSeq: $cseq\r\n")
        if (contentType != null) sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun infoPlist(): ByteArray {
        val d = NSDictionary()
        d.put("deviceid", NSString(airplay.deviceId))
        d.put("features", NSNumber(airplay.featuresLong))
        d.put("model", NSString(airplay.model))
        d.put("name", NSString(airplay.name))
        d.put("protovers", NSString("1.1"))
        d.put("srcvers", NSString(airplay.srcvers))
        d.put("pi", NSString(airplay.pi))
        d.put("pk", NSData(airplay.publicKey))
        d.put("vv", NSNumber(2))
        d.put("statusFlags", NSNumber(4))
        d.put("keepAliveLowPower", NSNumber(true))
        d.put("keepAliveSendStatsAsBody", NSNumber(true))
        return BinaryPropertyListWriter.writeToArray(d)
    }

    /** Ikili govdeyi bozmamak icin bayt bayt satir okur. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString("ISO-8859-1")
    }
}

package com.example.tvalici

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Ekran yansitma video akisi (TCP). Paket bicimi, UxPlay'in raop_rtp_mirror.c dosyasindan:
 *  - 128 baytlik baslik: [0..3] yuk boyutu (LE), [4] tur, [8..15] zaman damgasi
 *  - tur 0: sifreli kare (AVCC bicimi, 4 bayt uzunluk onekli NAL'ler)
 *  - tur 1: sifresiz SPS+PPS
 *  - tur 2, 5: nabiz / rapor (yok sayilir)
 * Sifre: AES-128-CTR, anahtar ve IV "AirPlayStreamKey/IV<streamConnectionID>" + aeskey SHA-512'sinden.
 * Anahtar akisi paketler boyunca kesintisiz devam eder (her paket oncekinin devamidir).
 */
class MirrorReceiver(
    aesKey: ByteArray,
    streamConnectionId: Long,
    private val decoder: MirrorDecoder,
    private val log: (String) -> Unit,
    private val onEnded: () -> Unit
) {
    // Alma tamponu bind'den ONCE ayarlanmali ki kabul edilen soket de miras alsin (Wi-Fi dalgalanmasinda
    // TCP penceresi kucuk kalmasin, kareler toplu halde gelip gecikme yaratmasin).
    private val serverSocket = ServerSocket().apply {
        receiveBufferSize = 1 shl 20
        bind(InetSocketAddress(0))
    }
    val port: Int get() = serverSocket.localPort

    @Volatile
    private var running = true

    @Volatile
    private var client: Socket? = null
    private val cipher: Cipher

    init {
        val id = java.lang.Long.toUnsignedString(streamConnectionId)
        val keyHash = sha512("AirPlayStreamKey$id".toByteArray(Charsets.US_ASCII) + aesKey)
        val ivHash = sha512("AirPlayStreamIV$id".toByteArray(Charsets.US_ASCII) + aesKey)
        cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyHash.copyOf(16), "AES"),
            IvParameterSpec(ivHash.copyOf(16))
        )
    }

    fun start() {
        thread(name = "mirror-recv", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
            try {
                val s = serverSocket.accept()
                client = s
                s.tcpNoDelay = true
                log("Yansıtma bağlantısı: ${s.inetAddress.hostAddress}")
                readLoop(s)
            } catch (e: Exception) {
                if (running) log("Yansıtma hatası: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                val wasRunning = running
                stop()
                if (wasRunning) onEnded()
            }
        }
    }

    fun stop() {
        running = false
        try {
            client?.close()
        } catch (e: Exception) {
            // onemsiz
        }
        try {
            serverSocket.close()
        } catch (e: Exception) {
            // onemsiz
        }
        decoder.release()
    }

    private fun readLoop(socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 16))
        val header = ByteArray(128)
        var frames = 0
        var packets = 0
        while (running) {
            input.readFully(header)
            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val size = hb.getInt(0)
            val type = header[4].toInt() and 0xff
            if (size < 0 || size > 16 * 1024 * 1024) {
                log("Geçersiz paket boyutu: $size")
                return
            }
            val payload = ByteArray(size)
            if (size > 0) input.readFully(payload)

            packets++
            if (packets <= 6) log("Paket #$packets tür=$type boyut=$size")

            when (type) {
                0 -> {
                    // Sifreyi her zaman coz: anahtar akisi paket atlansa bile hizali kalmali.
                    // Yerinde (ayni dizi) cozulur: kare basina ikinci bir dizi ayirma + kopyalama yok.
                    val n = cipher.update(payload, 0, size, payload, 0)
                    if (n != size) {
                        log("Şifre çözme boyutu uyuşmuyor ($n != $size)")
                        return
                    }
                    val kind = toAnnexB(payload, size)
                    if (kind != FRAME_INVALID) {
                        decoder.feed(payload, size, kind == FRAME_IDR)
                        frames++
                        if (frames == 1) log("İlk kare çözücüye verildi")
                        if (frames % 300 == 0) log("Kare sayısı: $frames")
                    } else if (frames < 3) {
                        log("Kare biçimi geçersiz (anahtar yanlış olabilir)")
                    }
                }
                1 -> handleCodecPacket(header, payload)
                else -> {} // nabiz / rapor
            }
        }
    }

    /**
     * AVCC (4 bayt uzunluk) -> Annex-B (00 00 00 01), yerinde.
     * Donus: FRAME_INVALID, FRAME_DELTA (P kare) ya da FRAME_IDR (anahtar kare, NAL turu 5 iceriyor).
     */
    private fun toAnnexB(data: ByteArray, size: Int): Int {
        var pos = 0
        var idr = false
        while (pos + 4 <= size) {
            val len = ((data[pos].toInt() and 0xff) shl 24) or
                ((data[pos + 1].toInt() and 0xff) shl 16) or
                ((data[pos + 2].toInt() and 0xff) shl 8) or
                (data[pos + 3].toInt() and 0xff)
            if (len < 0 || len > size - pos - 4) return FRAME_INVALID
            if (len > 0 && (data[pos + 4].toInt() and 0x1f) == 5) idr = true
            data[pos] = 0
            data[pos + 1] = 0
            data[pos + 2] = 0
            data[pos + 3] = 1
            pos += 4 + len
        }
        if (pos != size || size <= 0) return FRAME_INVALID
        return if (idr) FRAME_IDR else FRAME_DELTA
    }

    private fun handleCodecPacket(header: ByteArray, p: ByteArray) {
        if (p.size < 11) {
            log("SPS/PPS paketi çok kısa: ${p.size}")
            return
        }
        val spsSize = ((p[6].toInt() and 0xff) shl 8) or (p[7].toInt() and 0xff)
        if (8 + spsSize + 3 > p.size) {
            log("SPS boyutu geçersiz: $spsSize")
            return
        }
        val sps = p.copyOfRange(8, 8 + spsSize)
        val ppsSize = ((p[spsSize + 9].toInt() and 0xff) shl 8) or (p[spsSize + 10].toInt() and 0xff)
        if (spsSize + 11 + ppsSize > p.size) {
            log("PPS boyutu geçersiz: $ppsSize")
            return
        }
        val pps = p.copyOfRange(spsSize + 11, spsSize + 11 + ppsSize)

        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        var w = hb.getFloat(40).toInt()
        var h = hb.getFloat(44).toInt()
        if (w <= 0 || h <= 0) {
            w = 1920
            h = 1080
        }
        log("SPS/PPS alındı (sps=$spsSize pps=$ppsSize ${w}x$h)")
        decoder.configure(sps, pps, w, h)
    }

    private fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)

    private companion object {
        const val FRAME_INVALID = 0
        const val FRAME_DELTA = 1
        const val FRAME_IDR = 2
    }
}

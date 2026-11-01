package com.example.tvalici

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * AirPlay timing (NTP-like) client. The iPhone is the timing server; the receiver
 * regularly sends 32-byte requests. We do not use the replies right now (the picture is drawn
 * as soon as it arrives), but sending requests is the safe side for keeping the iPhone's stream going.
 */
class NtpClient(
    private val remote: InetAddress,
    private val remotePort: Int,
    private val log: (String) -> Unit
) {
    private val socket = DatagramSocket()
    val localPort: Int get() = socket.localPort

    @Volatile
    private var running = true
    private var replies = 0

    fun start() {
        thread(name = "ntp-recv", isDaemon = true) {
            val buf = ByteArray(128)
            while (running) {
                try {
                    socket.receive(DatagramPacket(buf, buf.size))
                    replies++
                    if (replies == 1) log("NTP reply received")
                } catch (e: Exception) {
                    break
                }
            }
        }
        thread(name = "ntp-send", isDaemon = true) {
            var count = 0
            while (running) {
                try {
                    val req = ByteArray(32)
                    req[0] = 0x80.toByte()
                    req[1] = 0xd2.toByte()
                    req[3] = 0x07
                    val now = System.currentTimeMillis()
                    val seconds = now / 1000 + 2_208_988_800L // since 1900
                    val fraction = ((now % 1000) shl 32) / 1000
                    val bb = ByteBuffer.wrap(req)
                    bb.putInt(24, seconds.toInt())
                    bb.putInt(28, fraction.toInt())
                    socket.send(DatagramPacket(req, req.size, InetSocketAddress(remote, remotePort)))
                    count++
                    Thread.sleep(if (count < 5) 250L else 3000L)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (running) log("NTP send error: ${e.message}")
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            socket.close()
        } catch (e: Exception) {
            // unimportant
        }
    }
}

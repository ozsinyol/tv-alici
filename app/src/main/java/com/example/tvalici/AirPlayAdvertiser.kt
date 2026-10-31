package com.example.tvalici

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.security.SecureRandom

/** TV'nin AirPlay alicisi olarak kendini tanitirken kullandigi bilgiler. */
class AirPlayInfo(
    @Volatile var name: String,
    val deviceId: String,
    val seed: ByteArray
) {
    val publicKey: ByteArray = Ed25519.publicKey(seed)
    val model = "AppleTV3,2"
    val srcvers = "220.68"
    val pi = "2e388006-13ba-4041-9a67-25dd4a43d536"
    val featuresText = "0x5A7FFFF7,0x1E"
    val featuresLong: Long = (0x1EL shl 32) or 0x5A7FFFF7L
    val pkHex: String get() = publicKey.joinToString("") { "%02x".format(it) }

    companion object {
        /** Cihaz kimligi ve anahtar bir kez uretilir, sonra saklanir. */
        fun load(context: Context, name: String): AirPlayInfo {
            val prefs = context.getSharedPreferences("airplay", Context.MODE_PRIVATE)
            val savedId = prefs.getString("deviceId", null)
            val savedSeed = prefs.getString("seed", null)
            if (savedId != null && savedSeed != null) {
                return AirPlayInfo(name, savedId, hexToBytes(savedSeed))
            }
            // Ilk calistirma (ya da eski yer tutucu anahtardan gecis): yeni kimlik uret.
            val random = SecureRandom()
            val mac = ByteArray(6)
            random.nextBytes(mac)
            mac[0] = ((mac[0].toInt() and 0xFE) or 0x02).toByte() // yerel, tekil adres
            val id = mac.joinToString(":") { "%02X".format(it) }
            val seed = ByteArray(32)
            random.nextBytes(seed)
            val seedHex = seed.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("deviceId", id).putString("seed", seedHex).apply()
            return AirPlayInfo(name, id, seed)
        }

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

/** mDNS (Bonjour) ile _airplay._tcp ve _raop._tcp servislerini yayinlar. */
class AirPlayAdvertiser(
    context: Context,
    private val airplay: AirPlayInfo,
    private val servicePort: Int,
    private val onLog: (String) -> Unit
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = mutableListOf<NsdManager.RegistrationListener>()
    private val handler = Handler(Looper.getMainLooper())

    /** Ad degisince servisleri yeni adla yeniden yayinlar. */
    fun restart() {
        stop()
        handler.postDelayed({ start() }, 800)
    }

    fun start() {
        register(
            "_airplay._tcp",
            airplay.name,
            mapOf(
                "deviceid" to airplay.deviceId,
                "features" to airplay.featuresText,
                "flags" to "0x4",
                "model" to airplay.model,
                "pi" to airplay.pi,
                "pk" to airplay.pkHex,
                "srcvers" to airplay.srcvers,
                "vv" to "2"
            )
        )
        register(
            "_raop._tcp",
            airplay.deviceId.replace(":", "") + "@" + airplay.name,
            mapOf(
                "txtvers" to "1",
                "ch" to "2",
                "cn" to "0,1,2,3",
                "da" to "true",
                "et" to "0,3,5",
                "ft" to airplay.featuresText,
                "md" to "0,1,2",
                "am" to airplay.model,
                "pk" to airplay.pkHex,
                "sf" to "0x4",
                "sr" to "44100",
                "ss" to "16",
                "tp" to "UDP",
                "vn" to "65537",
                "vs" to airplay.srcvers
            )
        )
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        for (l in listeners) {
            try {
                nsd.unregisterService(l)
            } catch (e: Exception) {
                // zaten kayitli degil
            }
        }
        listeners.clear()
    }

    private fun register(type: String, serviceName: String, txt: Map<String, String>) {
        val si = NsdServiceInfo()
        si.serviceName = serviceName
        si.serviceType = type
        si.port = servicePort
        for ((k, v) in txt) si.setAttribute(k, v)

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                onLog("mDNS yayinda: ${serviceInfo.serviceName} ($type)")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                onLog("mDNS hatasi $errorCode ($type)")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        listeners.add(listener)
        nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, listener)
    }
}

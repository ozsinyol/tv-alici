package com.example.tvalici

import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** AirPlay baglantisina izin verilip verilmedigini soran kapi (baglanti is parcaciginda bloklar). */
interface ApprovalGate {
    fun check(ip: String): Boolean
}

/** Guvenilen cihaz listesini yonetme (web sayfasindan). */
interface TrustAdmin {
    fun trustedList(): List<String>
    fun forget(ip: String)
    fun forgetAll()
}

/**
 * Ayni agdaki herkesin TV'ye baglanmasini engeller: bilinmeyen bir iPhone baglanmak isteyince
 * TV'de "Izin ver / Hep izin ver / Reddet" penceresi cikar. Karar IP adresine baglidir.
 */
class Approvals(
    private val prefs: SharedPreferences,
    /** TV'de pencereyi acar; kullanici cevap verince answer cagrilir. Donen islev pencereyi kapatir. */
    private val ask: (ip: String, answer: (Result) -> Unit) -> (() -> Unit),
    private val log: (String) -> Unit
) : ApprovalGate, TrustAdmin {

    enum class Result { ONCE, ALWAYS, DENY }

    private class Pending {
        val latch = CountDownLatch(1)
    }

    private sealed class Begin {
        object Allowed : Begin()
        object Denied : Begin()
        class Wait(val pending: Pending, val owner: Boolean) : Begin()
    }

    private companion object {
        /** Bu sure hic baglanmayan "hep izin ver" cihazin guveni kendiliginden kalkar. */
        const val TRUST_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val TOUCH_EVERY_MS = 24L * 60 * 60 * 1000
    }

    private val pending = HashMap<String, Pending>()
    private val allowedNow = HashSet<String>() // bu uygulama acilisinda izin verilenler
    private val deniedUntil = HashMap<String, Long>()

    @Synchronized
    private fun begin(ip: String): Begin {
        if (ip in allowedNow) return Begin.Allowed
        if (ip in trusted()) {
            touch(ip)
            return Begin.Allowed
        }
        if ((deniedUntil[ip] ?: 0L) > System.currentTimeMillis()) return Begin.Denied
        val existing = pending[ip]
        if (existing != null) return Begin.Wait(existing, false)
        val p = Pending()
        pending[ip] = p
        return Begin.Wait(p, true)
    }

    @Synchronized
    private fun finish(ip: String, r: Result) {
        val p = pending.remove(ip) ?: return
        when (r) {
            Result.ONCE -> allowedNow.add(ip)
            Result.ALWAYS -> {
                allowedNow.add(ip)
                prefs.edit()
                    .putStringSet("trusted", trusted() + ip)
                    .putLong("trustedAt_$ip", System.currentTimeMillis())
                    .apply()
            }
            Result.DENY -> deniedUntil[ip] = System.currentTimeMillis() + 60_000L
        }
        log("Bağlantı izni $ip: $r")
        p.latch.countDown()
    }

    @Synchronized
    private fun isAllowed(ip: String): Boolean = ip in allowedNow

    /**
     * Kalici guvenilen IP'ler. Son kullanimi TRUST_TTL_MS'den eski olanlar silinir; zaman damgasi
     * olmayan (eski surumden kalan) kayitlara simdiki zaman yazilir.
     */
    @Synchronized
    private fun trusted(): Set<String> {
        val raw = prefs.getStringSet("trusted", emptySet()) ?: emptySet()
        if (raw.isEmpty()) return raw
        val now = System.currentTimeMillis()
        val live = HashSet<String>()
        val ed = prefs.edit()
        var changed = false
        for (ip in raw) {
            val t = prefs.getLong("trustedAt_$ip", 0L)
            when {
                t == 0L -> {
                    ed.putLong("trustedAt_$ip", now)
                    live.add(ip)
                    changed = true
                }
                now - t > TRUST_TTL_MS -> {
                    ed.remove("trustedAt_$ip")
                    changed = true
                    log("Güven süresi doldu: $ip")
                }
                else -> live.add(ip)
            }
        }
        if (live.size != raw.size) ed.putStringSet("trusted", live)
        if (changed) ed.apply()
        return live
    }

    /** Kullanilan guvenilen cihazin damgasini gunde en fazla bir kez tazeler. */
    private fun touch(ip: String) {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("trustedAt_$ip", 0L) > TOUCH_EVERY_MS) {
            prefs.edit().putLong("trustedAt_$ip", now).apply()
        }
    }

    /** "ip (kalici)" ya da "ip (bu acilista)" bicimli satirlar. */
    @Synchronized
    override fun trustedList(): List<String> {
        val perm = trusted()
        return (perm + allowedNow).sorted().map { ip ->
            if (ip in perm) "$ip (perm)" else "$ip (temp)"
        }
    }

    @Synchronized
    override fun forget(ip: String) {
        prefs.edit().putStringSet("trusted", trusted() - ip).remove("trustedAt_$ip").apply()
        allowedNow.remove(ip)
        deniedUntil.remove(ip)
        log("Güvenilen cihaz silindi: $ip")
    }

    @Synchronized
    override fun forgetAll() {
        val ed = prefs.edit().remove("trusted")
        for (ip in trusted()) ed.remove("trustedAt_$ip")
        ed.apply()
        allowedNow.clear()
        deniedUntil.clear()
        log("Tüm güvenilen cihazlar silindi")
    }

    override fun check(ip: String): Boolean {
        return when (val b = begin(ip)) {
            Begin.Allowed -> true
            Begin.Denied -> false
            is Begin.Wait -> {
                var dismiss: (() -> Unit)? = null
                if (b.owner) dismiss = ask(ip) { r -> finish(ip, r) }
                val answered = b.pending.latch.await(30, TimeUnit.SECONDS)
                if (!answered && b.owner) {
                    dismiss?.invoke()
                    finish(ip, Result.DENY)
                }
                isAllowed(ip)
            }
        }
    }
}

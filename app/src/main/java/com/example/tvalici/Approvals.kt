package com.example.tvalici

import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** AirPlay baglantisina izin verilip verilmedigini soran kapi (baglanti is parcaciginda bloklar). */
interface ApprovalGate {
    fun check(ip: String): Boolean
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
) : ApprovalGate {

    enum class Result { ONCE, ALWAYS, DENY }

    private class Pending {
        val latch = CountDownLatch(1)
    }

    private sealed class Begin {
        object Allowed : Begin()
        object Denied : Begin()
        class Wait(val pending: Pending, val owner: Boolean) : Begin()
    }

    private val pending = HashMap<String, Pending>()
    private val allowedNow = HashSet<String>() // bu uygulama acilisinda izin verilenler
    private val deniedUntil = HashMap<String, Long>()

    @Synchronized
    private fun begin(ip: String): Begin {
        if (ip in allowedNow || ip in trusted()) return Begin.Allowed
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
                prefs.edit().putStringSet("trusted", trusted() + ip).apply()
            }
            Result.DENY -> deniedUntil[ip] = System.currentTimeMillis() + 60_000L
        }
        log("Bağlantı izni $ip: $r")
        p.latch.countDown()
    }

    @Synchronized
    private fun isAllowed(ip: String): Boolean = ip in allowedNow

    private fun trusted(): Set<String> = prefs.getStringSet("trusted", emptySet()) ?: emptySet()

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

package com.example.tvalici

import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The gate that asks whether an AirPlay connection is allowed (blocks the connection thread). */
interface ApprovalGate {
    fun check(ip: String): Boolean
}

/** Managing the trusted-device list (from the web page). */
interface TrustAdmin {
    fun trustedList(): List<String>
    fun forget(ip: String)
    fun forgetAll()
}

/**
 * Keeps everyone on the same network from connecting to the TV: when an unknown iPhone tries to connect,
 * an "Allow once / Always allow / Deny" dialog appears on the TV. The decision is tied to the IP address.
 */
class Approvals(
    private val prefs: SharedPreferences,
    /** Opens the dialog on the TV; answer is called when the user responds. The returned function closes the dialog. */
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
        /** An "always allow" device that does not connect for this long loses its trust automatically. */
        const val TRUST_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val TOUCH_EVERY_MS = 24L * 60 * 60 * 1000
    }

    private val pending = HashMap<String, Pending>()
    private val allowedNow = HashSet<String>() // allowed during this app session
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
        log("Connection permission $ip: $r")
        p.latch.countDown()
    }

    @Synchronized
    private fun isAllowed(ip: String): Boolean = ip in allowedNow

    /**
     * Permanently trusted IPs. Entries whose last use is older than TRUST_TTL_MS are removed; entries
     * without a timestamp (left over from an older version) get the current time written to them.
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
                    log("Trust expired: $ip")
                }
                else -> live.add(ip)
            }
        }
        if (live.size != raw.size) ed.putStringSet("trusted", live)
        if (changed) ed.apply()
        return live
    }

    /** Refreshes the stamp of a trusted device that is in use, at most once per day. */
    private fun touch(ip: String) {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("trustedAt_$ip", 0L) > TOUCH_EVERY_MS) {
            prefs.edit().putLong("trustedAt_$ip", now).apply()
        }
    }

    /** Lines formatted as "ip (perm)" or "ip (temp)". */
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
        log("Trusted device removed: $ip")
    }

    @Synchronized
    override fun forgetAll() {
        val ed = prefs.edit().remove("trusted")
        for (ip in trusted()) ed.remove("trustedAt_$ip")
        ed.apply()
        allowedNow.clear()
        deniedUntil.clear()
        log("All trusted devices removed")
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

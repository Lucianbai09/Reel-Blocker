package com.reelblocker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.text.format.DateFormat
import java.util.Date

/**
 * All state, the one timer rule, and the two one-shot alarms.
 *
 * Nothing in here polls or runs on an interval. A timer is just an end timestamp
 * that gets compared against the clock at the moment something else already woke us.
 */
object Prefs {

    const val YOUTUBE = "com.google.android.youtube"
    const val INSTAGRAM = "com.instagram.android"
    const val DISCORD = "com.discord"

    /** Everything the lock can cover. Discord is lock-only; it has no feed to skip. */
    private val LOCKABLE = listOf(YOUTUBE, INSTAGRAM, DISCORD)

    private const val FILE = "reelblocker"

    const val SHORTS_ON = "shorts_on"
    const val SHORTS_UNTIL = "shorts_until"
    const val LOCK_ON = "lock_on"
    const val LOCK_UNTIL = "lock_until"
    const val LOCK_YOUTUBE = "lock_youtube"
    const val LOCK_INSTAGRAM = "lock_instagram"
    const val LOCK_DISCORD = "lock_discord"
    const val LOCK_DND = "lock_dnd"

    // Bookkeeping, not a setting: remembers whether WE turned Do Not Disturb on, so
    // turning the lock off never clears a Do Not Disturb the user set themselves.
    const val DND_SET_BY_US = "dnd_set_by_us"

    // The view id of the Instagram home feed, learned from the phone rather than
    // hardcoded. Instagram renames these, and telling the home feed apart from the DM
    // list or search results is only possible by id, so a shipped guess would quietly
    // stop working or block the wrong list. LEARNING arms a one-off capture.
    const val FEED_ID = "feed_id"
    const val LEARNING = "learning"

    // Distinct request codes keep the two alarms independent; cancelling one must
    // not cancel the other.
    private const val REQ_SHORTS = 1
    private const val REQ_LOCK = 2

    fun get(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The one rule. Every "is this on right now" question goes through here. */
    private fun active(p: SharedPreferences, onKey: String, untilKey: String): Boolean {
        if (!p.getBoolean(onKey, false)) return false
        val until = p.getLong(untilKey, 0L)
        return until == 0L || System.currentTimeMillis() < until
    }

    fun shortsActive(p: SharedPreferences) = active(p, SHORTS_ON, SHORTS_UNTIL)

    fun lockActive(p: SharedPreferences) = active(p, LOCK_ON, LOCK_UNTIL)

    fun feedId(p: SharedPreferences): String? = p.getString(FEED_ID, null)

    fun learning(p: SharedPreferences) = p.getBoolean(LEARNING, false)

    /** Stores the id captured from one scroll, and ends the capture. */
    fun learnFeed(c: Context, id: String) {
        get(c).edit().putString(FEED_ID, id).putBoolean(LEARNING, false).apply()
    }

    fun lockedApp(p: SharedPreferences, pkg: String): Boolean = when (pkg) {
        YOUTUBE -> p.getBoolean(LOCK_YOUTUBE, true)
        INSTAGRAM -> p.getBoolean(LOCK_INSTAGRAM, true)
        DISCORD -> p.getBoolean(LOCK_DISCORD, true)
        else -> false
    }

    /**
     * The apps worth being woken for right now, which the service hands to
     * setServiceInfo() alongside eventTypes.
     *
     * Discord earns a filter of its own: it is a chat app, so it fires
     * content-changed on every message, where YouTube and Instagram sitting idle do
     * not. Blocking never acts on Discord, so leaving it in the filter while only
     * that toggle was on would mean a wakeup per message for nothing. It is included
     * only when the lock is on and its box is ticked.
     */
    fun watchedPackages(p: SharedPreferences, shorts: Boolean, lock: Boolean): Array<String> {
        val pkgs = linkedSetOf<String>()
        if (shorts) {
            pkgs += YOUTUBE
            pkgs += INSTAGRAM
        }
        if (lock) LOCKABLE.filterTo(pkgs) { lockedApp(p, it) }
        // A capture in progress needs Instagram events even with both toggles off.
        if (learning(p)) pkgs += INSTAGRAM
        return pkgs.toTypedArray()
    }

    /** minutes <= 0 means no timer. */
    fun setShorts(c: Context, on: Boolean, minutes: Int) = set(c, SHORTS_ON, SHORTS_UNTIL, on, minutes)

    fun setLock(c: Context, on: Boolean, minutes: Int) = set(c, LOCK_ON, LOCK_UNTIL, on, minutes)

    private fun set(c: Context, onKey: String, untilKey: String, on: Boolean, minutes: Int) {
        val until = if (on && minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        get(c).edit().putBoolean(onKey, on).putLong(untilKey, until).apply()
        syncAlarms(c)
    }

    fun setFlag(c: Context, key: String, value: Boolean) {
        get(c).edit().putBoolean(key, value).apply()
    }

    /**
     * Turns off anything whose end time has passed. Called from the expiry alarm and
     * from the UI's onResume, so a late alarm - or one that never fires - can never
     * leave a stale flag around.
     */
    fun clearExpired(c: Context) {
        val p = get(c)
        val e = p.edit()
        var changed = false
        if (p.getBoolean(SHORTS_ON, false) && !shortsActive(p)) {
            e.putBoolean(SHORTS_ON, false).putLong(SHORTS_UNTIL, 0L)
            changed = true
        }
        if (p.getBoolean(LOCK_ON, false) && !lockActive(p)) {
            e.putBoolean(LOCK_ON, false).putLong(LOCK_UNTIL, 0L)
            changed = true
        }
        if (changed) e.apply()
    }

    /**
     * Re-points both alarms at the stored end times. Also the reboot story: there is
     * no BOOT_COMPLETED receiver, the service calls this from onServiceConnected().
     */
    fun syncAlarms(c: Context) {
        val p = get(c)
        arm(c, REQ_SHORTS, shortsActive(p), p.getLong(SHORTS_UNTIL, 0L))
        arm(c, REQ_LOCK, lockActive(p), p.getLong(LOCK_UNTIL, 0L))
    }

    private fun arm(c: Context, req: Int, active: Boolean, until: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            c, req, Intent(c, ExpiryReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // RTC, not RTC_WAKEUP, and set() rather than setExact*(): the system batches
        // this with work it was already going to do instead of waking the phone. The
        // alarm's only job is to let the service drop its event subscription early,
        // so it is allowed to be imprecise - the timestamp check above is what is
        // actually authoritative.
        if (active && until > 0L) am.set(AlarmManager.RTC, until, pi) else am.cancel(pi)
    }

    fun timeText(c: Context, millis: Long): String = DateFormat.getTimeFormat(c).format(Date(millis))

    /** " until 3:45 PM", or "" when there is no timer. Shared by the toast and the notification. */
    fun untilText(c: Context, until: Long): String =
        if (until == 0L) "" else " until " + timeText(c, until)
}

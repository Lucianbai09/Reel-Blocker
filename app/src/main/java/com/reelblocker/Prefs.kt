package com.reelblocker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.text.format.DateFormat
import java.util.Date

/**
 * All state, the one pause rule, and the one one-shot alarm.
 *
 * Nothing in here polls or runs on an interval. The pause is just an end timestamp
 * that gets compared against the clock at the moment something else already woke us.
 */
object Prefs {

    const val YOUTUBE = "com.google.android.youtube"
    const val INSTAGRAM = "com.instagram.android"
    const val DISCORD = "com.discord"

    // Two package names for one app: the worldwide build, and the build shipped in
    // some regions and as TikTok Lite. Which one is installed is not knowable from
    // here, so both are carried everywhere and both answer to the one TikTok toggle.
    const val TIKTOK = "com.zhiliaoapp.musically"
    const val TIKTOK_ALT = "com.ss.android.ugc.trill"

    // Lock-only, the way Discord is: no feed to rewind and no player to back out of, so
    // the lock is the only thing that can ever act on them. Nothing else needs adding
    // for that - feedKey() returns null for anything it does not name, and the
    // back-press path tests the view-id array, which holds no id of theirs.
    const val CHESS = "com.chess"
    const val WEBTOON = "com.naver.linewebtoon"

    /** Every app the service ever acts on, which is also the lock's full menu. */
    private val APPS =
        listOf(YOUTUBE, INSTAGRAM, DISCORD, TIKTOK, TIKTOK_ALT, CHESS, WEBTOON)

    private const val FILE = "reelblocker"

    const val SHORTS_ON = "shorts_on"
    const val LOCK_ON = "lock_on"
    const val LOCK_YOUTUBE = "lock_youtube"
    const val LOCK_INSTAGRAM = "lock_instagram"
    const val LOCK_DISCORD = "lock_discord"
    const val LOCK_TIKTOK = "lock_tiktok"
    const val LOCK_CHESS = "lock_chess"
    const val LOCK_WEBTOON = "lock_webtoon"
    const val LOCK_DND = "lock_dnd"

    /**
     * When the pause ends, or 0 for "not paused".
     *
     * This one timestamp is the whole unlock feature. The switches stay armed and
     * simply stop acting until the clock passes it, so there is no state to restore
     * afterwards and nothing to get out of step.
     */
    const val PAUSE_UNTIL = "pause_until"

    // Bookkeeping, not a setting: remembers whether WE turned Do Not Disturb on, so
    // turning the lock off never clears a Do Not Disturb the user set themselves.
    const val DND_SET_BY_US = "dnd_set_by_us"

    // The view ids of the Instagram home feed and the TikTok feed, learned from the
    // phone rather than hardcoded. Both apps rename these, and telling the feed apart
    // from the DM list or search results is only possible by id, so a shipped guess
    // would quietly stop working or block the wrong list.
    //
    // Instagram's keeps the original unprefixed key, so an id already learned on the
    // phone survives the update that added TikTok.
    const val FEED_ID = "feed_id"
    const val FEED_ID_TIKTOK = "feed_id_tiktok"

    // Which feed a one-off capture is armed for: one of the two keys above, or absent.
    // Deliberately a new key name - the single boolean this replaces is stored under
    // "learning", and reading that back as a string would throw.
    const val LEARNING_FOR = "learning_for"

    // One alarm now, so one request code. It only ever points at PAUSE_UNTIL.
    private const val REQ_PAUSE = 1

    fun get(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun watched(pkg: String) = pkg in APPS

    /** The one rule. Every "is this on right now" question goes through here. */
    fun paused(p: SharedPreferences) = p.getLong(PAUSE_UNTIL, 0L) > System.currentTimeMillis()

    /**
     * A pause that has run out but has not been cleared yet, because the alarm is
     * inexact and may not have landed. One long read, so it is cheap enough to ask on
     * every event.
     */
    fun pauseExpired(p: SharedPreferences): Boolean {
        val until = p.getLong(PAUSE_UNTIL, 0L)
        return until != 0L && until <= System.currentTimeMillis()
    }

    fun shortsActive(p: SharedPreferences) = p.getBoolean(SHORTS_ON, false) && !paused(p)

    fun lockActive(p: SharedPreferences) = p.getBoolean(LOCK_ON, false) && !paused(p)

    /** The pref key a package's feed id lives under, or null for apps with no feed to learn. */
    fun feedKey(pkg: String): String? = when (pkg) {
        INSTAGRAM -> FEED_ID
        TIKTOK, TIKTOK_ALT -> FEED_ID_TIKTOK
        else -> null
    }

    fun feedLabel(key: String) = if (key == FEED_ID_TIKTOK) "TikTok" else "Instagram"

    fun feedId(p: SharedPreferences, pkg: String): String? =
        feedKey(pkg)?.let { p.getString(it, null) }

    fun anyFeedLearned(p: SharedPreferences) =
        p.getString(FEED_ID, null) != null || p.getString(FEED_ID_TIKTOK, null) != null

    fun learningFor(p: SharedPreferences): String? = p.getString(LEARNING_FOR, null)

    /** Arms a one-off capture for one app's feed. Only ever one at a time. */
    fun startLearning(c: Context, key: String) {
        get(c).edit().putString(LEARNING_FOR, key).apply()
    }

    /** Stores the id captured from one scroll, and ends the capture. */
    fun learnFeed(c: Context, key: String, id: String) {
        get(c).edit().putString(key, id).remove(LEARNING_FOR).apply()
    }

    fun lockedApp(p: SharedPreferences, pkg: String): Boolean = when (pkg) {
        YOUTUBE -> p.getBoolean(LOCK_YOUTUBE, true)
        INSTAGRAM -> p.getBoolean(LOCK_INSTAGRAM, true)
        DISCORD -> p.getBoolean(LOCK_DISCORD, true)
        TIKTOK, TIKTOK_ALT -> p.getBoolean(LOCK_TIKTOK, true)
        CHESS -> p.getBoolean(LOCK_CHESS, true)
        WEBTOON -> p.getBoolean(LOCK_WEBTOON, true)
        else -> false
    }

    /**
     * The apps worth being woken for right now, which the service hands to
     * setServiceInfo() alongside eventTypes. Takes the switch positions, not whether
     * they are currently acting: while paused we still want to hear about these apps,
     * so that opening one is enough to notice the pause has ended.
     *
     * Discord earns a filter of its own: it is a chat app, so it fires content-changed
     * on every message, where YouTube and Instagram sitting idle do not. Blocking never
     * acts on Discord, so leaving it in the filter while only that toggle was on would
     * mean a wakeup per message for nothing. It is included only when the lock is on
     * and its box is ticked.
     */
    fun watchedPackages(p: SharedPreferences, shorts: Boolean, lock: Boolean): Array<String> {
        val pkgs = linkedSetOf<String>()
        if (shorts) {
            pkgs += YOUTUBE
            pkgs += INSTAGRAM
            // TikTok is the same kind of special case as Discord, from the other end.
            // It has no full-screen player to back out of - it IS the player - so the
            // only thing blocking can do there is rewind the feed. With no feed id
            // learned there is nothing to act on, and asking to be woken for it would
            // buy exactly nothing.
            if (p.getString(FEED_ID_TIKTOK, null) != null) {
                pkgs += TIKTOK
                pkgs += TIKTOK_ALT
            }
        }
        if (lock) APPS.filterTo(pkgs) { lockedApp(p, it) }
        // A capture in progress needs that app's events even with both toggles off.
        when (learningFor(p)) {
            FEED_ID -> pkgs += INSTAGRAM
            FEED_ID_TIKTOK -> {
                pkgs += TIKTOK
                pkgs += TIKTOK_ALT
            }
        }
        return pkgs.toTypedArray()
    }

    fun setFlag(c: Context, key: String, value: Boolean) {
        get(c).edit().putBoolean(key, value).apply()
    }

    /** minutes <= 0 ends the pause instead of starting one. */
    fun pause(c: Context, minutes: Int) {
        val until = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        get(c).edit().putLong(PAUSE_UNTIL, until).apply()
        syncAlarms(c)
    }

    fun resume(c: Context) = pause(c, 0)

    /**
     * Clears a pause whose end time has passed. Called from the expiry alarm, from the
     * UI's onResume, and from the service when an event arrives after the deadline, so
     * a late alarm - or one that never fires - can never leave blocking switched off.
     *
     * Writing the key is also what makes the service recompute, via its prefs listener.
     */
    fun clearExpired(c: Context) {
        val p = get(c)
        if (pauseExpired(p)) p.edit().putLong(PAUSE_UNTIL, 0L).apply()
    }

    /**
     * Re-points the alarm at the stored end time. Also the reboot story: there is no
     * BOOT_COMPLETED receiver, the service calls this from onServiceConnected().
     */
    fun syncAlarms(c: Context) {
        val p = get(c)
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            c, REQ_PAUSE, Intent(c, ExpiryReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // RTC, not RTC_WAKEUP, and set() rather than setExact*(): the system batches
        // this with work it was already going to do instead of waking the phone, and no
        // exact-alarm permission is needed.
        //
        // This alarm is the LAST of three layers, and the weakest on purpose. Being
        // inexact, App Standby can defer it by hours for an app opened as rarely as this
        // one, so nothing may lean on it for timeliness. What ends a pause on time is
        // BlockerService.scheduleResume(); what ends it correctly no matter what is the
        // timestamp check on every event. This alarm only covers the case where the
        // process died with a pause outstanding, so neither of those is around to run.
        val until = p.getLong(PAUSE_UNTIL, 0L)
        if (paused(p)) am.set(AlarmManager.RTC, until, pi) else am.cancel(pi)
    }

    fun timeText(c: Context, millis: Long): String = DateFormat.getTimeFormat(c).format(Date(millis))
}

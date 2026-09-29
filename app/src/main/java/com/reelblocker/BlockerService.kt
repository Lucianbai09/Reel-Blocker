package com.reelblocker

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * The whole blocker. It exists only to react to events the OS hands it; it starts
 * nothing, schedules nothing repeating, and holds nothing.
 */
class BlockerService : AccessibilityService() {

    // ---- EDIT THIS WHEN DETECTION BREAKS -----------------------------------
    // App-owned view ids, not Android APIs, so an app update can rename them. The
    // README has the uiautomator recipe for reading the current ones off the phone.
    //
    // Ids must match the full-screen player ONLY, and each one costs a lookup on
    // every event. clips_video_container and reel_recycler were both removed for
    // also matching feed-embedded Reels and the Shorts shelf, which turned scrolling
    // a home feed into an inescapable back-press loop.
    //
    // There is deliberately no TikTok id here. TikTok has no player to back out of -
    // the feed IS the player - so pressing Back would just leave the app. TikTok is
    // handled entirely by the feed rewind below, the same way the Instagram home feed
    // is, which is what leaves Inbox and Profile reachable.
    private val shortsViewIds = arrayOf(
        "com.google.android.youtube:id/reel_player_underlay",
        "com.google.android.youtube:id/reel_progress_bar",
        "com.instagram.android:id/clips_viewer_view_pager"
    )
    // ------------------------------------------------------------------------

    // Long enough to swallow the burst of events the back-press itself generates,
    // which is a battery feature as much as a correctness one. Shared with the lock
    // so a locked app cannot spam toasts either.
    private val cooldownMs = 1200L

    // One rewind per event, never a tight loop. A backward scroll is animated, so
    // firing several back to back only cancels and restarts the same animation and
    // moves about one page in total - which is exactly why a hard fling used to outrun
    // it. Our own scroll fires the next event, so the chain walks itself to the top and
    // stops there, because scrolling backward at the top does nothing.
    //
    // The backstop counts refusals, not actions. Counting actions meant a fast enough
    // thumb burned through the budget with real scrolls, and the block then switched
    // itself off for as long as scrolling continued - hardest exactly when it was
    // needed most. Only a feed that will not scroll back stops us now.
    private val maxStalls = 6
    private val scrollResetMs = 2000L

    private lateinit var prefs: SharedPreferences
    private var lastActionAt = 0L
    private var lastStallAt = 0L
    private var stalls = 0

    // Which watched app is on screen, so the event throttle can be relaxed only where
    // it is needed. Empty until the first window change.
    private var foreground = ""

    // The one scheduled thing in the app. See scheduleResume().
    private val handler = Handler(Looper.getMainLooper())
    private val resumeOnDeadline = Runnable {
        Prefs.clearExpired(this)
        // Re-armed in case that cleared nothing. The delay is measured in uptime but the
        // deadline is wall-clock, so a clock that moves backwards under us can fire this
        // early - and a callback consumed early would leave the pause with nothing left
        // to end it. Idempotent when the pause did end: scheduleResume() only cancels.
        scheduleResume()
    }

    // Held in a field on purpose: SharedPreferences keeps listeners weakly, so a
    // lambda passed inline would be collected and the subscription would silently
    // stop updating.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // applyDnd writes its own bookkeeping key; ignoring it here stops that write
        // from bouncing straight back through this listener.
        if (key != Prefs.DND_SET_BY_US) {
            applyEventTypes()
            updateNotification()
            applyDnd()
            scheduleResume()
        }
    }

    /**
     * Hands blocking back at the pause deadline, and is the only scheduled thing in this
     * app.
     *
     * It exists because nothing else could do it. While paused the subscription drops to
     * window changes only, so staying inside one app and scrolling generates nothing we
     * hear, and the expiry alarm is inexact - App Standby defers those by hours for an
     * app opened as rarely as this one. So a pause that ran out while you kept scrolling
     * never ended: the countdown went negative and scrolling kept working until you left
     * the app.
     *
     * One message in the main looper, not a poll. Nothing runs until it fires and it
     * costs nothing while pending, so this does not reintroduce a timer in the sense the
     * README rules out.
     *
     * postDelayed is uptime-based, so it does not advance through deep sleep. That is
     * fine and is why the other two layers stay: a phone that sleeps past the deadline
     * is handed back by the alarm, or by the timestamp check on the first event after it
     * wakes. This layer is the one that covers the screen-on case the other two cannot.
     */
    private fun scheduleResume() {
        handler.removeCallbacks(resumeOnDeadline)
        if (!Prefs.paused(prefs)) return
        handler.postDelayed(
            resumeOnDeadline,
            prefs.getLong(Prefs.PAUSE_UNTIL, 0L) - System.currentTimeMillis()
        )
    }

    override fun onServiceConnected() {
        prefs = Prefs.get(this)
        Prefs.clearExpired(this)
        Prefs.syncAlarms(this)   // re-arms after a reboot; no BOOT_COMPLETED receiver needed
        createChannel()
        applyEventTypes()
        updateNotification()     // notifications are cleared by a reboot, so re-post here
        applyDnd()
        scheduleResume()         // a pause can outlive the process; re-arm the hand-back
        // Registered last, on purpose. clearExpired() above writes when a pause ran out
        // while the service was off, and with the listener already attached that write
        // would run this whole block early - posting the notification before
        // createChannel() had made the channel, which silently drops it - and then again
        // here. Nothing can be missed by registering late: every write comes from the
        // main thread, and so does this.
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        // Without these, turning the accessibility service off would leave a
        // notification claiming things are still blocked, and Do Not Disturb stuck on.
        notifications()?.cancel(NOTIF_ID)
        releaseDnd()
        handler.removeCallbacks(resumeOnDeadline)
        if (this::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    /**
     * Asks the system for the minimum the currently-active features need: both the
     * event types and the list of apps. With both switches off that is 0 event types -
     * subscribed to nothing, which is the point.
     *
     * Narrowing packageNames as well as eventTypes is what keeps Discord out of the
     * filter unless the lock can actually act on it, and TikTok out until a feed id has
     * been learned; see Prefs.watchedPackages(). The flags from
     * accessibility_service_config.xml survive untouched.
     */
    private fun applyEventTypes() {
        val info = serviceInfo ?: return
        val paused = Prefs.paused(prefs)
        // The switch positions, not whether they are acting: a pause changes the tier
        // we listen at, not which apps we care about.
        val shortsOn = prefs.getBoolean(Prefs.SHORTS_ON, false)
        val lockOn = prefs.getBoolean(Prefs.LOCK_ON, false)
        val packages = Prefs.watchedPackages(prefs, shortsOn, lockOn)

        var wanted = when {
            // Nothing left to watch. Say "no events" outright rather than leaning on
            // how the framework reads an empty package filter.
            packages.isEmpty() -> 0
            // Paused. The only thing left worth hearing is that the pause has ended,
            // and a window change carries that. It costs one event per switch into a
            // blocked app, during a pause that was asked for, and buys the guarantee
            // that blocking can never stay off past its deadline just because an
            // inexact alarm ran late.
            paused -> AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            // Shorts detection needs content-changed: YouTube opens Shorts inside its
            // existing window, so entering the feed often fires no window-state event
            // at all. This is the expensive tier and it is only ever on while the
            // Shorts switch is on and nothing is paused.
            shortsOn ->
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            // Opening an app always changes the window, so the lock needs no more.
            else -> AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }

        // Scroll events fire throughout a gesture, so they are the priciest tier of
        // all and are only ever asked for while something acts on them: the one-off
        // feed capture, or feed blocking once a feed id has been learned. A capture is
        // setup rather than blocking, so a pause does not stop it.
        // Guarded on wanted != 0 for the same reason that branch above exists: never
        // hand the framework event types alongside an empty package filter. It cannot
        // currently bite, because watchedPackages() always includes the app being
        // captured - the guard is what makes "no packages" mean "no events" by
        // construction rather than by coincidence.
        val blockingFeed = !paused && shortsOn && Prefs.anyFeedLearned(prefs)
        if (wanted != 0 && (Prefs.learningFor(prefs) != null || blockingFeed)) {
            wanted = wanted or AccessibilityEvent.TYPE_VIEW_SCROLLED
        }

        // 500ms of system-side coalescing is the cheap default, but it also meant the
        // rewind landed half a second after the gesture, which read as broken.
        //
        // The timeout is per service, not per event type, so shortening it also
        // un-throttles content-changed - and that handler fetches the whole window root
        // plus id lookups. Leaving it short everywhere meant watching a YouTube video
        // cost five times the root fetches for a feature YouTube cannot even use. So it
        // is short only while the app actually on screen is one whose feed we can rewind.
        val timeout = if (blockingFeed && Prefs.feedId(prefs, foreground) != null) 100L else 500L

        if (info.eventTypes != wanted ||
            info.notificationTimeout != timeout ||
            info.packageNames?.contentEquals(packages) != true
        ) {
            info.eventTypes = wanted
            info.notificationTimeout = timeout
            info.packageNames = packages
            serviceInfo = info
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Cheapest test first. packageName is already in the event object and costs
        // nothing; nothing that costs an IPC happens above this line.
        val pkg = event.packageName?.toString() ?: return
        if (!Prefs.watched(pkg)) return

        // Track which watched app is on screen so the throttle can follow it. Costs one
        // setServiceInfo per switch between watched apps, and nothing otherwise.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != foreground) {
            foreground = pkg
            applyEventTypes()
        }

        // A pause that has run out. One long read, so it is affordable on every event,
        // and it writes exactly once because the write is what makes it stop being
        // true. The write puts the subscription and the notification back through the
        // prefs listener, so blocking resumes here without waiting for the alarm.
        if (Prefs.pauseExpired(prefs)) Prefs.clearExpired(this)

        // Scroll events take their own path. They are the only way to stop the
        // Instagram home feed or the TikTok feed, and also how a feed teaches us its
        // id. Handled before the active check below because a capture can run with both
        // switches off, or while paused.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            onScrolled(event, pkg)
            return
        }

        val shorts = Prefs.shortsActive(prefs)
        val lock = Prefs.lockActive(prefs)
        if (!shorts && !lock) {
            // Either paused, or switched off. Re-asking for the right tier is all that
            // is needed; the call is a no-op unless something actually changed.
            applyEventTypes()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastActionAt < cooldownMs) return

        if (lock && Prefs.lockedApp(prefs, pkg)) {
            lastActionAt = now
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(this, "Locked", Toast.LENGTH_SHORT).show()
            return
        }

        if (!shorts) return

        // Neither Discord nor TikTok has a full-screen player to back out of, so no id
        // above could ever match them. Asking the id list rather than naming the two
        // apps keeps this from going stale when that list changes, and stops them
        // costing a rootInActiveWindow per event. none() is inline, so this is a plain
        // loop over three strings with nothing allocated.
        if (shortsViewIds.none { it.startsWith(pkg) }) return

        // One rootInActiveWindow, then at most two indexed id lookups. These resolve
        // inside the target app's process and are not a tree walk. Never make this a
        // recursive walk of the node tree.
        val root = rootInActiveWindow ?: return
        for (id in shortsViewIds) {
            if (!id.startsWith(pkg)) continue   // free: skips the other app's ids
            // isVisibleToUser is the entire "only while it is actually full-screen"
            // rule. Both apps leave the player's views in the hierarchy after you
            // back out of it, so matching on presence alone kept firing and pushed
            // you out of the app entirely. Presence means built; visible means shown.
            if (root.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser }) {
                lastActionAt = now
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
        }
    }

    /**
     * Stops the Instagram home feed and the TikTok feed by scrolling them back, never
     * by pressing Back: Back on either exits the app, which is what made the earlier
     * attempt at this unusable.
     *
     * Both are lists like any other, so the only thing separating them from the DM
     * list, search results or a profile grid is the view id of whatever is being
     * scrolled. Those ids are the apps' to rename, so each one is learned from a single
     * real scroll rather than shipping a guess.
     *
     * On TikTok this is the whole of the block: the feed rewinds to the first video and
     * will not move off it, while Inbox, Profile and search are never touched.
     */
    private fun onScrolled(event: AccessibilityEvent, pkg: String) {
        // YouTube and Discord have no feed to rewind.
        val key = Prefs.feedKey(pkg) ?: return
        val capturing = Prefs.learningFor(prefs) == key
        val learned = if (Prefs.shortsActive(prefs)) prefs.getString(key, null) else null

        // Nothing to capture and nothing to compare against - the other app's feed is
        // set up but not this one, or blocking is paused. A few in-memory reads are what
        // buys this exit, and what it avoids is event.source below, which is a node
        // fetch over IPC and the only expensive thing on this path.
        if (!capturing && learned == null) return

        val source = event.source ?: return
        val id = source.viewIdResourceName ?: return

        if (capturing) {
            Prefs.learnFeed(this, key, id)
            Toast.makeText(this, Prefs.feedLabel(key) + " feed set up", Toast.LENGTH_SHORT).show()
            return
        }

        // Anything that is not the learned feed keeps scrolling normally, which is what
        // leaves DMs, search and profiles usable.
        if (id != learned) return

        // fromIndex is the first row still on screen, so zero means the feed is already
        // at the top - on TikTok, that it is on the first video. Cheapest possible end
        // to the chain: no action, no IPC.
        if (event.fromIndex == 0) {
            stalls = 0
            return
        }

        // Decay from the last refusal, never from the last scroll. Measuring from the
        // last scroll meant a thumb that kept moving also kept the count from ever
        // resetting, so a run of refusals could switch the block off for as long as
        // scrolling continued. That is the same trap the count itself used to be.
        val now = System.currentTimeMillis()
        if (now - lastStallAt > scrollResetMs) stalls = 0
        if (stalls >= maxStalls) return

        // A refused scroll is the only thing that counts against us, and one that works
        // clears the count. However hard the feed is scrolled, it still gets rewound.
        if (rewind(source)) {
            stalls = 0
        } else {
            stalls++
            lastStallAt = now
        }
    }

    /**
     * Moves the feed back up by one step.
     *
     * Jumping straight to the top is far better than paging when the feed offers it,
     * because there is no animation to fight and a fling cannot outrun a single jump.
     * Not every list implements it, so the paging action stays as the fallback.
     *
     * Returns whether the feed accepted the scroll. False means it will not move, which
     * is the only thing that should ever make us stop trying.
     */
    private fun rewind(source: AccessibilityNodeInfo): Boolean {
        val toTop = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION
        if (source.actionList.contains(toTop)) {
            val args = Bundle()
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, 0)
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0)
            if (source.performAction(toTop.id, args)) return true
        }
        return source.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    override fun onInterrupt() {}

    // ---- status notification -----------------------------------------------
    // A plain notification, NOT a foreground service. Once posted it is owned by the
    // system and drawn by SystemUI; this process can be killed and it stays up. So it
    // costs one IPC at the moment state changes and nothing at all while it shows.

    private fun notifications() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        // IMPORTANCE_LOW: appears in the status bar, but never makes a sound, never
        // pops up as a heads-up, and never wakes the screen.
        notifications()?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Status", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun updateNotification() {
        val nm = notifications() ?: return
        // Switch positions, so the notification keeps explaining itself through a pause
        // rather than vanishing and looking like everything was turned off.
        val shortsOn = prefs.getBoolean(Prefs.SHORTS_ON, false)
        val lockOn = prefs.getBoolean(Prefs.LOCK_ON, false)
        if (!shortsOn && !lockOn) {
            nm.cancel(NOTIF_ID)
            return
        }

        val what = listOfNotNull(
            if (shortsOn) "Blocking scrolling" else null,
            if (lockOn) "Apps locked" else null
        ).joinToString(" · ")

        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_x)
            .setContentTitle("Reel Blocker")
            .setContentIntent(tap)
            .setOngoing(true)     // not swipeable, the way a VPN notification behaves

        val until = prefs.getLong(Prefs.PAUSE_UNTIL, 0L)
        if (Prefs.paused(prefs)) {
            // The countdown is drawn and ticked by SystemUI from this one timestamp, so
            // it costs this process nothing: posted once when the pause starts, never
            // updated, and it keeps counting even if this process is killed.
            val time = Prefs.timeText(this, until)
            b.setContentText("Paused until $time")
                .setStyle(Notification.BigTextStyle().bigText("$what, paused until $time."))
                .setShowWhen(true)
                .setWhen(until)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        } else {
            // Collapsed line stays short; the expanded view carries the detail.
            b.setContentText(what)
                .setStyle(Notification.BigTextStyle().bigText("$what until you turn it off."))
                .setShowWhen(false)
        }

        nm.notify(NOTIF_ID, b.build())
    }

    // ---- do not disturb ----------------------------------------------------
    // One setInterruptionFilter call at the moment the lock turns on or off, which is
    // a moment we were already awake for. Nothing is held and nothing polls.

    /** Turns Do Not Disturb on with the lock, and back off again with it. */
    private fun applyDnd() {
        val nm = notifications() ?: return
        if (!nm.isNotificationPolicyAccessGranted) return   // not granted yet; the UI asks

        val want = Prefs.lockActive(prefs) && prefs.getBoolean(Prefs.LOCK_DND, false)
        val ours = prefs.getBoolean(Prefs.DND_SET_BY_US, false)

        if (want && !ours) {
            // Only take over when nothing else is already filtering, so a Do Not
            // Disturb the user set by hand is never overwritten or later cleared.
            if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                // PRIORITY, not NONE: alarms and starred contacts still come through.
                // Silencing alarms for a study timer would be a genuinely bad trade.
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                Prefs.setFlag(this, Prefs.DND_SET_BY_US, true)
            }
        } else if (!want && ours) {
            releaseDnd()
        }
    }

    private fun releaseDnd() {
        if (!this::prefs.isInitialized) return
        if (!prefs.getBoolean(Prefs.DND_SET_BY_US, false)) return
        val nm = notifications() ?: return
        if (!nm.isNotificationPolicyAccessGranted) return
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        Prefs.setFlag(this, Prefs.DND_SET_BY_US, false)
    }

    private companion object {
        const val NOTIF_ID = 1
        const val CHANNEL_ID = "status"
    }
}

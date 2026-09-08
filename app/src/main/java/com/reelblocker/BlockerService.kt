package com.reelblocker

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
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
    // The cap is the backstop for a feed that never reports reaching the top, and it
    // has to be generous now that each pass covers one page rather than ten. Fail open,
    // never loop.
    private val maxRewinds = 40
    private val scrollResetMs = 2000L

    private lateinit var prefs: SharedPreferences
    private var lastActionAt = 0L
    private var lastScrollAt = 0L
    private var rewinds = 0

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
        }
    }

    override fun onServiceConnected() {
        prefs = Prefs.get(this)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        Prefs.clearExpired(this)
        Prefs.syncAlarms(this)   // re-arms after a reboot; no BOOT_COMPLETED receiver needed
        createChannel()
        applyEventTypes()
        updateNotification()     // notifications are cleared by a reboot, so re-post here
        applyDnd()
    }

    override fun onDestroy() {
        // Without these, turning the accessibility service off would leave a
        // notification claiming things are still blocked, and Do Not Disturb stuck on.
        notifications()?.cancel(NOTIF_ID)
        releaseDnd()
        if (this::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    /**
     * Asks the system for the minimum the currently-active features need: both the
     * event types and the list of apps. With both toggles off that is 0 event types -
     * subscribed to nothing, which is the point.
     *
     * Narrowing packageNames as well as eventTypes is what keeps Discord out of the
     * filter unless the lock can actually act on it; see Prefs.watchedPackages(). The
     * flags from accessibility_service_config.xml survive untouched.
     */
    private fun applyEventTypes() {
        val info = serviceInfo ?: return
        val shorts = Prefs.shortsActive(prefs)
        val lock = Prefs.lockActive(prefs)
        val packages = Prefs.watchedPackages(prefs, shorts, lock)

        var wanted = when {
            // Nothing left to watch. Say "no events" outright rather than leaning on
            // how the framework reads an empty package filter.
            packages.isEmpty() -> 0
            // Shorts detection needs content-changed: YouTube opens Shorts inside its
            // existing window, so entering the feed often fires no window-state event
            // at all. This is the expensive tier and it is only ever on while the
            // Shorts toggle is active.
            shorts ->
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            // Opening an app always changes the window, so the lock needs no more.
            else -> AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }

        // Scroll events fire throughout a gesture, so they are the priciest tier of
        // all and are only ever asked for while something acts on them: the one-off
        // feed capture, or feed blocking once a feed id has been learned.
        val blockingFeed = shorts && Prefs.feedId(prefs) != null
        if (Prefs.learning(prefs) || blockingFeed) {
            wanted = wanted or AccessibilityEvent.TYPE_VIEW_SCROLLED
        }

        // 500ms of system-side coalescing is the cheap default, but it also meant the
        // rewind landed half a second after the gesture, which read as broken. Pay for
        // a shorter one only while the feed is actually being blocked.
        val timeout = if (blockingFeed) 100L else 500L

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
        if (pkg != Prefs.YOUTUBE && pkg != Prefs.INSTAGRAM && pkg != Prefs.DISCORD) return

        // Scroll events take their own path. They are the only way to stop the
        // Instagram home feed, and also how the feed teaches us its id. Handled before
        // the expiry check below because a capture can run with both toggles off.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            onScrolled(event, pkg)
            return
        }

        val shorts = Prefs.shortsActive(prefs)
        val lock = Prefs.lockActive(prefs)
        if (!shorts && !lock) {
            // A timer ran out and the inexact alarm has not landed yet. We were woken
            // anyway, so drop the subscription now rather than waiting for it.
            Prefs.clearExpired(this)
            applyEventTypes()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastActionAt < cooldownMs) return

        if (lock && Prefs.lockedApp(prefs, pkg)) {
            lastActionAt = now
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(this, lockToast(), Toast.LENGTH_SHORT).show()
            return
        }

        if (!shorts) return

        // Discord is lock-only: there is no feed to skip there, so stop before
        // spending a rootInActiveWindow on ids that could never match.
        if (pkg == Prefs.DISCORD) return

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
     * Stops the Instagram home feed by scrolling it back, never by pressing Back:
     * Back on the home feed exits Instagram, which is what made the earlier attempt at
     * this unusable.
     *
     * The home feed is a list like any other, so the only thing separating it from the
     * DM list, search results or a profile grid is the view id of whatever is being
     * scrolled. Those ids are Instagram's to rename, so the app learns the id from a
     * single real scroll rather than shipping a guess.
     */
    private fun onScrolled(event: AccessibilityEvent, pkg: String) {
        if (pkg != Prefs.INSTAGRAM) return
        val source = event.source ?: return
        val id = source.viewIdResourceName ?: return

        if (Prefs.learning(prefs)) {
            Prefs.learnFeed(this, id)
            Toast.makeText(this, "Feed set up", Toast.LENGTH_SHORT).show()
            return
        }

        if (!Prefs.shortsActive(prefs)) return
        // Anything that is not the learned feed keeps scrolling normally, which is what
        // leaves DMs, search and profiles usable.
        if (id != Prefs.feedId(prefs)) return

        val now = System.currentTimeMillis()
        if (now - lastScrollAt > scrollResetMs) rewinds = 0
        lastScrollAt = now
        if (rewinds >= maxRewinds) return
        rewinds++
        rewind(source)
    }

    /**
     * Moves the feed back up by one step.
     *
     * Jumping straight to the top is far better than paging when the feed offers it,
     * because there is no animation to fight and a fling cannot outrun a single jump.
     * Not every list implements it, so the paging action stays as the fallback.
     */
    private fun rewind(source: AccessibilityNodeInfo) {
        val toTop = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION
        if (source.actionList.contains(toTop)) {
            val args = Bundle()
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, 0)
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0)
            if (source.performAction(toTop.id, args)) return
        }
        source.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    override fun onInterrupt() {}

    private fun lockToast() = "Locked" + Prefs.untilText(this, prefs.getLong(Prefs.LOCK_UNTIL, 0L))

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
        val shorts = Prefs.shortsActive(prefs)
        val lock = Prefs.lockActive(prefs)
        if (!shorts && !lock) {
            nm.cancel(NOTIF_ID)
            return
        }

        // Collapsed line stays short. The deadlines live in the expanded view, which
        // also means a notification left stale by a late alarm explains itself.
        val summary = listOfNotNull(
            if (shorts) "Blocking scrolling" else null,
            if (lock) "Apps locked" else null
        ).joinToString(" · ")

        val detail = listOfNotNull(
            if (shorts) "Blocking scrolling" + deadline(Prefs.SHORTS_UNTIL) else null,
            if (lock) "Apps locked" + deadline(Prefs.LOCK_UNTIL) else null
        ).joinToString("\n")

        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        nm.notify(
            NOTIF_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_x)
                .setContentTitle("Reel Blocker")
                .setContentText(summary)
                .setStyle(Notification.BigTextStyle().bigText(detail))
                .setContentIntent(tap)
                .setOngoing(true)     // not swipeable, the way a VPN notification behaves
                .setShowWhen(false)
                .build()
        )
    }

    /** " until 3:45 PM", or " until you turn it off" when there is no timer. */
    private fun deadline(key: String): String {
        val until = prefs.getLong(key, 0L)
        return if (until == 0L) " until you turn it off" else Prefs.untilText(this, until)
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

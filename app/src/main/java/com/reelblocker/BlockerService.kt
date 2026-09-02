package com.reelblocker

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * The whole blocker. It exists only to react to events the OS hands it; it starts
 * nothing, schedules nothing repeating, and holds nothing.
 */
class BlockerService : AccessibilityService() {

    // ---- EDIT THIS WHEN DETECTION BREAKS -----------------------------------
    // These are app-owned view ids, not Android APIs, so a big YouTube or Instagram
    // update can rename them. Each one below is corroborated by at least one other
    // open-source blocker (see README). README "When detection breaks" has the
    // uiautomator recipe for reading the real ones off the phone.
    // Do not pad this list with guesses: every extra id is another lookup per event.
    //
    // Ids must match the full-screen player ONLY. Two were removed for being too
    // broad: clips_video_container, because Instagram also uses it for Reels embedded
    // inline in the home feed, and reel_recycler, because it covers the Shorts shelf
    // on the YouTube home feed as well as the Shorts player. Either one turns
    // scrolling a normal feed into a back press, and since re-opening the app
    // restores the same scroll position it fires again - an inescapable loop.
    // What is left is player-only: an underlay and a progress bar exist in the
    // full-screen player and nowhere else.
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

    private lateinit var prefs: SharedPreferences
    private var lastActionAt = 0L

    // Held in a field on purpose: SharedPreferences keeps listeners weakly, so a
    // lambda passed inline would be collected and the subscription would silently
    // stop updating.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyEventTypes()
        updateNotification()
    }

    override fun onServiceConnected() {
        prefs = Prefs.get(this)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        Prefs.clearExpired(this)
        Prefs.syncAlarms(this)   // re-arms after a reboot; no BOOT_COMPLETED receiver needed
        createChannel()
        applyEventTypes()
        updateNotification()     // notifications are cleared by a reboot, so re-post here
    }

    override fun onDestroy() {
        // Without this, turning the accessibility service off would leave a
        // notification claiming things are still being blocked.
        notifications()?.cancel(NOTIF_ID)
        if (this::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    /**
     * Asks the system for the minimum event set the currently-active features need.
     * With both toggles off this is 0 - subscribed to nothing, which is the point.
     *
     * Reads the live serviceInfo and mutates only eventTypes, so packageNames and the
     * flags from accessibility_service_config.xml survive untouched.
     */
    private fun applyEventTypes() {
        val info = serviceInfo ?: return
        val wanted = when {
            // Shorts detection needs content-changed: YouTube opens Shorts inside its
            // existing window, so entering the feed often fires no window-state event
            // at all. This is the expensive tier and it is only ever on while the
            // Shorts toggle is active.
            Prefs.shortsActive(prefs) ->
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            // Opening an app always changes the window, so the lock needs no more.
            Prefs.lockActive(prefs) -> AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            else -> 0
        }
        if (info.eventTypes != wanted) {
            info.eventTypes = wanted
            serviceInfo = info
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Cheapest test first. packageName is already in the event object and costs
        // nothing; nothing that costs an IPC happens above this line.
        val pkg = event.packageName?.toString() ?: return
        if (pkg != Prefs.YOUTUBE && pkg != Prefs.INSTAGRAM) return

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
            if (shorts) "Blocking Shorts" else null,
            if (lock) "Apps locked" else null
        ).joinToString(" · ")

        val detail = listOfNotNull(
            if (shorts) "Blocking Shorts" + deadline(Prefs.SHORTS_UNTIL) else null,
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

    private companion object {
        const val NOTIF_ID = 1
        const val CHANNEL_ID = "status"
    }
}

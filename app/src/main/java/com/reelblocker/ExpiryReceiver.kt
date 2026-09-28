package com.reelblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * One-shot, fired by an inexact non-wakeup alarm when a pause runs out. All it does is
 * clear the expired timestamp, and writing that is what makes the service put its event
 * subscription and its notification back, via the prefs listener.
 *
 * It is not what makes blocking resume - the timestamp check is - so it is allowed to
 * land late. See Prefs.syncAlarms().
 */
class ExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.clearExpired(context)
    }
}

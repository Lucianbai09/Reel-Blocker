package com.reelblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * One-shot, fired by an inexact non-wakeup alarm when a timer ends. It does not care
 * which timer fired - it just turns off whatever has expired. Writing the flag is
 * what makes the service recompute its event subscription, via the prefs listener.
 */
class ExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.clearExpired(context)
    }
}

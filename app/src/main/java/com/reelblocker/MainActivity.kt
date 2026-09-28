package com.reelblocker

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var warnBox: View
    private lateinit var swShorts: Switch
    private lateinit var tvShorts: TextView
    private lateinit var tvFeedInstagram: TextView
    private lateinit var btnLearnInstagram: Button
    private lateinit var tvFeedTiktok: TextView
    private lateinit var btnLearnTiktok: Button
    private lateinit var swLock: Switch
    private lateinit var tvLock: TextView
    private lateinit var cbYoutube: CheckBox
    private lateinit var cbInstagram: CheckBox
    private lateinit var cbTiktok: CheckBox
    private lateinit var cbDiscord: CheckBox
    private lateinit var cbDnd: CheckBox
    private lateinit var etPause: EditText
    private lateinit var btnPause: Button
    private lateinit var tvPause: TextView
    private lateinit var btnResume: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        warnBox = findViewById(R.id.warn_box)
        swShorts = findViewById(R.id.sw_shorts)
        tvShorts = findViewById(R.id.tv_shorts)
        tvFeedInstagram = findViewById(R.id.tv_feed_instagram)
        btnLearnInstagram = findViewById(R.id.btn_learn_instagram)
        tvFeedTiktok = findViewById(R.id.tv_feed_tiktok)
        btnLearnTiktok = findViewById(R.id.btn_learn_tiktok)
        swLock = findViewById(R.id.sw_lock)
        tvLock = findViewById(R.id.tv_lock)
        cbYoutube = findViewById(R.id.cb_youtube)
        cbInstagram = findViewById(R.id.cb_instagram)
        cbTiktok = findViewById(R.id.cb_tiktok)
        cbDiscord = findViewById(R.id.cb_discord)
        cbDnd = findViewById(R.id.cb_dnd)
        etPause = findViewById(R.id.et_pause)
        btnPause = findViewById(R.id.btn_pause)
        tvPause = findViewById(R.id.tv_pause)
        btnResume = findViewById(R.id.btn_resume)

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // setOnClickListener, not setOnCheckedChangeListener: click fires only on real
        // user input, so refresh() setting these programmatically cannot re-trigger it.
        swShorts.setOnClickListener {
            Prefs.setFlag(this, Prefs.SHORTS_ON, swShorts.isChecked)
            refresh()
        }
        swLock.setOnClickListener {
            Prefs.setFlag(this, Prefs.LOCK_ON, swLock.isChecked)
            refresh()
        }
        btnLearnInstagram.setOnClickListener { armLearning(Prefs.FEED_ID) }
        btnLearnTiktok.setOnClickListener { armLearning(Prefs.FEED_ID_TIKTOK) }
        cbYoutube.setOnClickListener {
            Prefs.setFlag(this, Prefs.LOCK_YOUTUBE, cbYoutube.isChecked)
        }
        cbInstagram.setOnClickListener {
            Prefs.setFlag(this, Prefs.LOCK_INSTAGRAM, cbInstagram.isChecked)
        }
        cbTiktok.setOnClickListener {
            Prefs.setFlag(this, Prefs.LOCK_TIKTOK, cbTiktok.isChecked)
        }
        cbDiscord.setOnClickListener {
            Prefs.setFlag(this, Prefs.LOCK_DISCORD, cbDiscord.isChecked)
        }
        cbDnd.setOnClickListener {
            // Refuse to store it until access exists, so the checkbox never claims
            // something that silently would not happen.
            if (cbDnd.isChecked && !dndAccessGranted()) {
                cbDnd.isChecked = false
                Toast.makeText(this, "Allow Do Not Disturb access, then tick this again", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            } else {
                Prefs.setFlag(this, Prefs.LOCK_DND, cbDnd.isChecked)
            }
        }
        btnPause.setOnClickListener {
            val mins = minutes(etPause)
            if (mins <= 0) {
                Toast.makeText(this, "Enter how many minutes to pause for", Toast.LENGTH_SHORT).show()
            } else {
                Prefs.pause(this, mins)
                etPause.text.clear()
                refresh()
            }
        }
        btnResume.setOnClickListener {
            Prefs.resume(this)
            refresh()
        }

        // Asked once, for the status notification. Denying it loses only the
        // notification, and with it the pause countdown; blocking is unaffected.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * Arms a one-off capture. The service saves the id of the first thing scrolled in
     * that app, which is why the instruction says to scroll the feed itself.
     */
    private fun armLearning(key: String) {
        Prefs.startLearning(this, key)
        Toast.makeText(this, "Scroll your " + Prefs.feedLabel(key) + " feed once", Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun refresh() {
        Prefs.clearExpired(this)
        val p = Prefs.get(this)

        warnBox.visibility = if (serviceEnabled()) View.GONE else View.VISIBLE

        // The switches show whether a feature is armed, not whether it is acting this
        // second. Through a pause they stay on and the status line underneath is what
        // says nothing is happening; flipping them off would read as the app having
        // forgotten, and tapping one would then arm what was already armed.
        val shortsOn = p.getBoolean(Prefs.SHORTS_ON, false)
        val lockOn = p.getBoolean(Prefs.LOCK_ON, false)
        val paused = Prefs.paused(p)
        val until = p.getLong(Prefs.PAUSE_UNTIL, 0L)

        swShorts.isChecked = shortsOn
        tvShorts.text = statusText(shortsOn, paused, until)

        swLock.isChecked = lockOn
        tvLock.text = statusText(lockOn, paused, until)
        cbYoutube.isChecked = p.getBoolean(Prefs.LOCK_YOUTUBE, true)
        cbInstagram.isChecked = p.getBoolean(Prefs.LOCK_INSTAGRAM, true)
        cbTiktok.isChecked = p.getBoolean(Prefs.LOCK_TIKTOK, true)
        cbDiscord.isChecked = p.getBoolean(Prefs.LOCK_DISCORD, true)
        // Access can be revoked in Settings, so this is re-checked rather than trusted.
        cbDnd.isChecked = p.getBoolean(Prefs.LOCK_DND, false) && dndAccessGranted()

        feedRow(p, tvFeedInstagram, btnLearnInstagram, Prefs.FEED_ID)
        feedRow(p, tvFeedTiktok, btnLearnTiktok, Prefs.FEED_ID_TIKTOK)

        val armed = shortsOn || lockOn
        etPause.isEnabled = armed && !paused
        btnPause.isEnabled = armed && !paused
        btnResume.visibility = if (paused) View.VISIBLE else View.GONE
        tvPause.text = when {
            paused -> "Paused until " + Prefs.timeText(this, until)
            !armed -> "Nothing is on, so there is nothing to pause."
            else -> "Not paused."
        }
    }

    /**
     * One feed row: its status line and its button.
     *
     * "set up" rather than "blocked" on purpose: a feed is only actually blocked while
     * the switch above is on, and this line shows either way.
     *
     * The learned id used to be printed here as a way to spot a bad capture, but it
     * reads as "list" on this Instagram, which tells nobody anything. Whether the right
     * list was captured is answered by scrolling DMs, not by reading an id.
     */
    private fun feedRow(p: SharedPreferences, status: TextView, button: Button, key: String) {
        val label = Prefs.feedLabel(key)
        val learned = p.getString(key, null)
        status.text = when {
            Prefs.learningFor(p) == key -> "Now scroll your " + label + " feed once"
            learned == null -> label + " feed not set up"
            else -> label + " feed set up"
        }
        button.text =
            if (learned == null) "Set up " + label + " feed" else "Re-learn " + label + " feed"
    }

    private fun dndAccessGranted(): Boolean =
        getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    private fun statusText(on: Boolean, paused: Boolean, until: Long): String = when {
        !on -> "Off"
        paused -> "Paused until " + Prefs.timeText(this, until)
        else -> "On until you switch it off"
    }

    /** Blank, junk, or 0 all mean "no pause". */
    private fun minutes(field: EditText): Int = field.text.toString().trim().toIntOrNull() ?: 0

    private fun serviceEnabled(): Boolean {
        val me = ComponentName(this, BlockerService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

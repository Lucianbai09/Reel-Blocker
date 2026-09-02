package com.reelblocker

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var warnBox: View
    private lateinit var swShorts: Switch
    private lateinit var etShorts: EditText
    private lateinit var tvShorts: TextView
    private lateinit var swLock: Switch
    private lateinit var etLock: EditText
    private lateinit var tvLock: TextView
    private lateinit var cbYoutube: CheckBox
    private lateinit var cbInstagram: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        warnBox = findViewById(R.id.warn_box)
        swShorts = findViewById(R.id.sw_shorts)
        etShorts = findViewById(R.id.et_shorts)
        tvShorts = findViewById(R.id.tv_shorts)
        swLock = findViewById(R.id.sw_lock)
        etLock = findViewById(R.id.et_lock)
        tvLock = findViewById(R.id.tv_lock)
        cbYoutube = findViewById(R.id.cb_youtube)
        cbInstagram = findViewById(R.id.cb_instagram)

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // setOnClickListener, not setOnCheckedChangeListener: click fires only on real
        // user input, so refresh() setting these programmatically cannot re-trigger it.
        swShorts.setOnClickListener {
            Prefs.setShorts(this, swShorts.isChecked, minutes(etShorts))
            refresh()
        }
        swLock.setOnClickListener {
            Prefs.setLock(this, swLock.isChecked, minutes(etLock))
            refresh()
        }
        cbYoutube.setOnClickListener {
            Prefs.setFlag(this, Prefs.LOCK_YOUTUBE, cbYoutube.isChecked)
        }
        cbInstagram.setOnClickListener {
            Prefs.setFlag(this, Prefs.LOCK_INSTAGRAM, cbInstagram.isChecked)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        Prefs.clearExpired(this)
        val p = Prefs.get(this)

        warnBox.visibility = if (serviceEnabled()) View.GONE else View.VISIBLE

        swShorts.isChecked = Prefs.shortsActive(p)
        tvShorts.text = statusText(p, Prefs.SHORTS_ON, Prefs.SHORTS_UNTIL)

        swLock.isChecked = Prefs.lockActive(p)
        tvLock.text = statusText(p, Prefs.LOCK_ON, Prefs.LOCK_UNTIL)
        cbYoutube.isChecked = p.getBoolean(Prefs.LOCK_YOUTUBE, true)
        cbInstagram.isChecked = p.getBoolean(Prefs.LOCK_INSTAGRAM, true)
    }

    // clearExpired() has already run, so "on" here also means "still within its timer".
    private fun statusText(p: SharedPreferences, onKey: String, untilKey: String): String {
        if (!p.getBoolean(onKey, false)) return "Off"
        val until = p.getLong(untilKey, 0L)
        return if (until == 0L) "On until you switch it off"
        else "On until " + Prefs.timeText(this, until)
    }

    /** Blank, junk, or 0 all mean "no timer". */
    private fun minutes(field: EditText): Int = field.text.toString().trim().toIntOrNull() ?: 0

    private fun serviceEnabled(): Boolean {
        val me = ComponentName(this, BlockerService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

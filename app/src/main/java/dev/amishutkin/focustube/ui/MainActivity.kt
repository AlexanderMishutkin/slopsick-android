package dev.amishutkin.focustube.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.TextUtils
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import dev.amishutkin.focustube.R
import dev.amishutkin.focustube.core.Settings
import dev.amishutkin.focustube.platform.FocusAccessibilityService
import dev.amishutkin.focustube.platform.SettingsStore

class MainActivity : Activity() {

    private lateinit var store: SettingsStore
    private lateinit var status: TextView
    private lateinit var statusHint: TextView

    private lateinit var suggested: Switch
    private lateinit var promoted: Switch
    private lateinit var activity: Switch
    private lateinit var modules: Switch
    private lateinit var stories: Switch
    private lateinit var reels: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = SettingsStore(this)

        status = findViewById(R.id.status)
        statusHint = findViewById(R.id.statusHint)
        suggested = findViewById(R.id.optSuggested)
        promoted = findViewById(R.id.optPromoted)
        activity = findViewById(R.id.optActivity)
        modules = findViewById(R.id.optModules)
        stories = findViewById(R.id.optStories)
        reels = findViewById(R.id.optReels)

        findViewById<Button>(R.id.openSettings).setOnClickListener {
            startActivity(
                Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        val current = store.load()
        suggested.isChecked = current.hideSuggested
        promoted.isChecked = current.hidePromoted
        activity.isChecked = current.hideNetworkActivity
        modules.isChecked = current.hideFeedModules
        stories.isChecked = current.hideStoriesTray
        reels.isChecked = current.hideReels

        val onToggle = CompoundButton.OnCheckedChangeListener { _, _ -> persist() }
        for (toggle in listOf(suggested, promoted, activity, modules, stories, reels)) {
            toggle.setOnCheckedChangeListener(onToggle)
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = isServiceEnabled()
        status.setText(if (enabled) R.string.status_on else R.string.status_off)
        statusHint.visibility = if (enabled) TextView.GONE else TextView.VISIBLE
    }

    private fun persist() {
        store.save(
            Settings(
                hideSuggested = suggested.isChecked,
                hidePromoted = promoted.isChecked,
                hideNetworkActivity = activity.isChecked,
                hideStoriesTray = stories.isChecked,
                hideReels = reels.isChecked,
                hideFeedModules = modules.isChecked,
            ),
        )
    }

    /** Whether the user has granted accessibility access, read from the system's own list. */
    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${FocusAccessibilityService::class.java.name}"
        val enabled = AndroidSettings.Secure.getString(
            contentResolver,
            AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }
}

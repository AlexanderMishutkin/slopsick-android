package dev.amishutkin.slopsick.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.TextUtils
import android.widget.Button
import android.widget.CompoundButton
import android.widget.NumberPicker
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.amishutkin.slopsick.R
import dev.amishutkin.slopsick.core.Settings
import dev.amishutkin.slopsick.platform.SlopsickAccessibilityService
import dev.amishutkin.slopsick.platform.SettingsStore
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private lateinit var store: SettingsStore
    private lateinit var status: TextView
    private lateinit var statusHint: TextView
    private lateinit var lockStatus: TextView
    private lateinit var lockMinutes: NumberPicker

    /** Every switch, paired with the field of [Settings] it drives. */
    private lateinit var switches: List<Pair<Switch, (Settings, Boolean) -> Settings>>

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            showLock()
            ticker.postDelayed(this, TimeUnit.SECONDS.toMillis(20))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = SettingsStore(this)

        status = findViewById(R.id.status)
        statusHint = findViewById(R.id.statusHint)
        lockStatus = findViewById(R.id.lockStatus)

        findViewById<Button>(R.id.openSettings).setOnClickListener {
            startActivity(
                Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        // One switch per app and nothing else. What each app covers is decided in
        // Settings' defaults rather than by the reader: a screen of eleven switches is a
        // screen nobody reads, and every one of them is another thing to get wrong.
        switches = listOf(
            sw(R.id.optInstagram) { s, v -> s.copy(instagram = v) },
            sw(R.id.optLinkedIn) { s, v -> s.copy(linkedIn = v) },
            sw(R.id.optYouTube) { s, v -> s.copy(youtube = v) },
            // Not one of the three, and deliberately not covered by the lock: turning
            // reporting off uncovers nothing.
            sw(R.id.optReports) { s, v -> s.copy(reportButtons = v) },
        )

        setUpLock()
        showSettings(store.load())
    }

    override fun onResume() {
        super.onResume()
        val enabled = isServiceEnabled()
        status.setText(if (enabled) R.string.status_on else R.string.status_off)
        statusHint.visibility = if (enabled) TextView.GONE else TextView.VISIBLE
        showSettings(store.load())
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    private fun sw(id: Int, apply: (Settings, Boolean) -> Settings) =
        findViewById<Switch>(id) to apply

    private fun setUpLock() {
        lockMinutes = findViewById(R.id.lockMinutes)
        val choices = intArrayOf(15, 30, 45, 60, 90, 120, 180, 240)
        lockMinutes.minValue = 0
        lockMinutes.maxValue = choices.size - 1
        lockMinutes.displayedValues = choices.map { it.toString() }.toTypedArray()
        lockMinutes.value = choices.indexOf(DEFAULT_MINUTES)
        lockMinutes.wrapSelectorWheel = false

        findViewById<Button>(R.id.lockButton).setOnClickListener {
            val minutes = choices[lockMinutes.value]
            val until = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes.toLong())
            val current = store.load()
            // Only ever extends. A lock you can cut short is not a lock.
            store.save(current.copy(lockedUntil = maxOf(current.lockedUntil, until)))
            showSettings(store.load())
        }
    }

    /** Writes the switches from [settings] without the listeners firing back at us. */
    private fun showSettings(settings: Settings) {
        val locked = settings.lockedAt(System.currentTimeMillis())
        for ((toggle, apply) in switches) {
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = apply(settings, true) == settings
            // While locked, a switch that is on cannot be turned off — but one that is
            // off can still be turned on. The only thing being prevented is backing out,
            // so a switch that covers nothing (the report button) stays live throughout:
            // the lock asks the settings themselves whether turning this off would
            // uncover anything.
            toggle.isEnabled = !locked || !settings.loosenedBy(apply(settings, false))
            toggle.setOnCheckedChangeListener(onToggle(apply))
        }
        showLock()
    }

    private fun onToggle(apply: (Settings, Boolean) -> Settings) =
        CompoundButton.OnCheckedChangeListener { _, checked ->
            val current = store.load()
            val next = apply(current, checked)
            if (current.lockedAt(System.currentTimeMillis()) && current.loosenedBy(next)) {
                Toast.makeText(this, R.string.lock_refused, Toast.LENGTH_SHORT).show()
                showSettings(current)
                return@OnCheckedChangeListener
            }
            store.save(next)
            showSettings(next)
        }

    private fun showLock() {
        val settings = store.load()
        val remaining = settings.lockedUntil - System.currentTimeMillis()
        if (remaining <= 0) {
            lockStatus.visibility = TextView.GONE
            return
        }
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining) + 1
        lockStatus.visibility = TextView.VISIBLE
        lockStatus.text = getString(R.string.lock_active, minutes.toInt())
    }

    /** Whether the user has granted accessibility access, read from the system's own list. */
    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${SlopsickAccessibilityService::class.java.name}"
        val enabled = AndroidSettings.Secure.getString(
            contentResolver,
            AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }

    private companion object {
        const val DEFAULT_MINUTES = 60
    }
}

package dev.amishutkin.slopsick.platform

import android.content.Context
import android.content.SharedPreferences
import dev.amishutkin.slopsick.core.Settings

/**
 * The switches, on local storage only.
 *
 * There is no synced counterpart on purpose: syncing settings would mean an account, and
 * an account would mean the network permission this app refuses to ask for.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    init {
        migrate()
    }

    /**
     * Brings a saved settings file up to the current build's defaults.
     *
     * A default only ever applies to someone who has never saved their settings, so
     * changing one leaves everybody who has opened the settings screen once on the old
     * value — with no way to tell, from inside the app, that they are on it. That is how
     * "hide posts your network only reacted to" could be switched on by default in one
     * build and still be off on a phone three builds later, looking for all the world
     * like an analyzer that could not read the post.
     *
     * A migration runs once per step and is not a lock: the switch is in the settings
     * screen and turning it off afterwards sticks.
     */
    private fun migrate() {
        val from = prefs.getInt(SCHEMA, 0)
        if (from >= SCHEMA_VERSION) return
        val edit = prefs.edit()
        // 1: hiding what your network merely liked or commented on became the default.
        if (from < 1) edit.putBoolean(HIDE_ACTIVITY, true)
        edit.putInt(SCHEMA, SCHEMA_VERSION).apply()
    }

    fun load() = Settings(
        instagram = prefs.getBoolean(INSTAGRAM, true),
        linkedIn = prefs.getBoolean(LINKEDIN, true),
        youtube = prefs.getBoolean(YOUTUBE, true),
        lockedUntil = prefs.getLong(LOCKED_UNTIL, 0L),
        hideSuggested = prefs.getBoolean(HIDE_SUGGESTED, true),
        hidePromoted = prefs.getBoolean(HIDE_PROMOTED, true),
        hideNetworkActivity = prefs.getBoolean(HIDE_ACTIVITY, true),
        hideStoriesTray = prefs.getBoolean(HIDE_STORIES, false),
        hideReels = prefs.getBoolean(HIDE_REELS, true),
        hideShorts = prefs.getBoolean(HIDE_SHORTS, true),
        hideExplore = prefs.getBoolean(HIDE_EXPLORE, true),
        hideFeedModules = prefs.getBoolean(HIDE_MODULES, true),
        reportButtons = prefs.getBoolean(REPORT_BUTTONS, true),
    )

    fun save(settings: Settings) {
        prefs.edit()
            .putInt(SCHEMA, SCHEMA_VERSION)
            .putBoolean(INSTAGRAM, settings.instagram)
            .putBoolean(LINKEDIN, settings.linkedIn)
            .putBoolean(YOUTUBE, settings.youtube)
            .putLong(LOCKED_UNTIL, settings.lockedUntil)
            .putBoolean(HIDE_SUGGESTED, settings.hideSuggested)
            .putBoolean(HIDE_PROMOTED, settings.hidePromoted)
            .putBoolean(HIDE_ACTIVITY, settings.hideNetworkActivity)
            .putBoolean(HIDE_STORIES, settings.hideStoriesTray)
            .putBoolean(HIDE_REELS, settings.hideReels)
            .putBoolean(HIDE_SHORTS, settings.hideShorts)
            .putBoolean(HIDE_EXPLORE, settings.hideExplore)
            .putBoolean(HIDE_MODULES, settings.hideFeedModules)
            .putBoolean(REPORT_BUTTONS, settings.reportButtons)
            .apply()
    }

    fun observe(onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun stopObserving(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val NAME = "slopsick.settings"

        /** Bump when a default changes, and add the step to [migrate]. */
        const val SCHEMA_VERSION = 1
        const val SCHEMA = "schema"
        const val INSTAGRAM = "app_instagram"
        const val LINKEDIN = "app_linkedin"
        const val YOUTUBE = "app_youtube"
        const val LOCKED_UNTIL = "locked_until"
        const val HIDE_SUGGESTED = "hide_suggested"
        const val HIDE_PROMOTED = "hide_promoted"
        const val HIDE_ACTIVITY = "hide_network_activity"
        const val HIDE_STORIES = "hide_stories_tray"
        const val HIDE_REELS = "hide_reels"
        const val HIDE_SHORTS = "hide_shorts"
        const val HIDE_EXPLORE = "hide_explore"
        const val HIDE_MODULES = "hide_feed_modules"
        const val REPORT_BUTTONS = "report_buttons"
    }
}

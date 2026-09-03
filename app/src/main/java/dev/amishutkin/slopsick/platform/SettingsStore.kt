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

    fun load() = Settings(
        instagram = prefs.getBoolean(INSTAGRAM, true),
        linkedIn = prefs.getBoolean(LINKEDIN, true),
        youtube = prefs.getBoolean(YOUTUBE, true),
        lockedUntil = prefs.getLong(LOCKED_UNTIL, 0L),
        hideSuggested = prefs.getBoolean(HIDE_SUGGESTED, true),
        hidePromoted = prefs.getBoolean(HIDE_PROMOTED, true),
        hideNetworkActivity = prefs.getBoolean(HIDE_ACTIVITY, false),
        hideStoriesTray = prefs.getBoolean(HIDE_STORIES, true),
        hideReels = prefs.getBoolean(HIDE_REELS, true),
        hideShorts = prefs.getBoolean(HIDE_SHORTS, true),
        hideExplore = prefs.getBoolean(HIDE_EXPLORE, true),
        hideFeedModules = prefs.getBoolean(HIDE_MODULES, true),
        reportButtons = prefs.getBoolean(REPORT_BUTTONS, true),
    )

    fun save(settings: Settings) {
        prefs.edit()
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

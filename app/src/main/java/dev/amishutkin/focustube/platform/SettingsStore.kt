package dev.amishutkin.focustube.platform

import android.content.Context
import android.content.SharedPreferences
import dev.amishutkin.focustube.core.Settings

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
        hideSuggested = prefs.getBoolean(HIDE_SUGGESTED, true),
        hidePromoted = prefs.getBoolean(HIDE_PROMOTED, true),
        hideNetworkActivity = prefs.getBoolean(HIDE_ACTIVITY, false),
        hideStoriesTray = prefs.getBoolean(HIDE_STORIES, false),
        hideReels = prefs.getBoolean(HIDE_REELS, true),
        hideShorts = prefs.getBoolean(HIDE_SHORTS, true),
        hideExplore = prefs.getBoolean(HIDE_EXPLORE, true),
        hideFeedModules = prefs.getBoolean(HIDE_MODULES, true),
    )

    fun save(settings: Settings) {
        prefs.edit()
            .putBoolean(HIDE_SUGGESTED, settings.hideSuggested)
            .putBoolean(HIDE_PROMOTED, settings.hidePromoted)
            .putBoolean(HIDE_ACTIVITY, settings.hideNetworkActivity)
            .putBoolean(HIDE_STORIES, settings.hideStoriesTray)
            .putBoolean(HIDE_REELS, settings.hideReels)
            .putBoolean(HIDE_SHORTS, settings.hideShorts)
            .putBoolean(HIDE_EXPLORE, settings.hideExplore)
            .putBoolean(HIDE_MODULES, settings.hideFeedModules)
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
        const val NAME = "focustube.settings"
        const val HIDE_SUGGESTED = "hide_suggested"
        const val HIDE_PROMOTED = "hide_promoted"
        const val HIDE_ACTIVITY = "hide_network_activity"
        const val HIDE_STORIES = "hide_stories_tray"
        const val HIDE_REELS = "hide_reels"
        const val HIDE_SHORTS = "hide_shorts"
        const val HIDE_EXPLORE = "hide_explore"
        const val HIDE_MODULES = "hide_feed_modules"
    }
}

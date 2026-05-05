package me.bestvibes.exiftoolwrapper

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks whether the user has acknowledged the warranty/liability disclaimer
 * (first-launch modal) and the additional warning shown when enabling the
 * advanced custom-command toggle.
 */
class DisclaimerPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var hasAcceptedDisclaimer: Boolean
        get() = prefs.getBoolean(KEY_DISCLAIMER, false)
        set(value) { prefs.edit().putBoolean(KEY_DISCLAIMER, value).apply() }

    var hasAcceptedAdvancedWarning: Boolean
        get() = prefs.getBoolean(KEY_ADVANCED, false)
        set(value) { prefs.edit().putBoolean(KEY_ADVANCED, value).apply() }

    var advancedModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADVANCED_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ADVANCED_ENABLED, value).apply() }

    companion object {
        private const val PREFS_NAME = "exiftoolwrapper_prefs"
        private const val KEY_DISCLAIMER = "accepted_disclaimer_v1"
        private const val KEY_ADVANCED = "accepted_advanced_warning_v1"
        private const val KEY_ADVANCED_ENABLED = "advanced_mode_enabled"
    }
}

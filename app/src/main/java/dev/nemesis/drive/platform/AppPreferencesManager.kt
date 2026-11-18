package dev.nemesis.drive.platform

import android.content.Context
import android.content.SharedPreferences
import dev.nemesis.drive.data.AppPreferencesStore
import dev.nemesis.drive.model.AccentColor
import dev.nemesis.drive.model.ThemeChoice

class AppPreferencesManager(context: Context) : AppPreferencesStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_preferences", Context.MODE_PRIVATE)

    override fun isShowMyLocation(): Boolean = prefs.getBoolean(KEY_SHOW_MY_LOCATION, false)
    override fun setShowMyLocation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_MY_LOCATION, enabled).apply()
    }

    override fun isAmoledMode(): Boolean = prefs.getBoolean(KEY_AMOLED_MODE, true)
    override fun setAmoledMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AMOLED_MODE, enabled).apply()
    }

    override fun isDynamicColor(): Boolean = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
    override fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }

    override fun getAccentColor(): AccentColor {
        val name = prefs.getString(KEY_ACCENT_COLOR, AccentColor.TEAL.name) ?: AccentColor.TEAL.name
        return runCatching { AccentColor.valueOf(name) }.getOrDefault(AccentColor.TEAL)
    }
    override fun setAccentColor(color: AccentColor) {
        prefs.edit().putString(KEY_ACCENT_COLOR, color.name).putLong(KEY_CUSTOM_ACCENT_COLOR, color.hexDark).apply()
    }

    override fun getCustomAccentColor(): Long = prefs.getLong(KEY_CUSTOM_ACCENT_COLOR, AccentColor.TEAL.hexDark)
    override fun setCustomAccentColor(color: Long) {
        prefs.edit().putLong(KEY_CUSTOM_ACCENT_COLOR, color).apply()
    }

    override fun getTheme(): ThemeChoice {
        val name = prefs.getString(KEY_THEME, ThemeChoice.DARK.name) ?: ThemeChoice.DARK.name
        return runCatching { ThemeChoice.valueOf(name) }.getOrDefault(ThemeChoice.DARK)
    }
    override fun setTheme(theme: ThemeChoice) {
        prefs.edit().putString(KEY_THEME, theme.name).apply()
    }

    override fun isAutoOpenVehicle(): Boolean = prefs.getBoolean(KEY_AUTO_OPEN_VEHICLE, true)
    override fun setAutoOpenVehicle(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_OPEN_VEHICLE, enabled).apply()
    }

    override fun getDefaultVehicleId(): String? = prefs.getString(KEY_DEFAULT_VEHICLE_ID, null)
    override fun setDefaultVehicleId(id: String?) {
        prefs.edit().putString(KEY_DEFAULT_VEHICLE_ID, id).apply()
    }

    companion object {
        private const val KEY_SHOW_MY_LOCATION = "show_my_location"
        private const val KEY_AMOLED_MODE = "amoled_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_CUSTOM_ACCENT_COLOR = "custom_accent_color"
        private const val KEY_THEME = "theme_choice"
        private const val KEY_AUTO_OPEN_VEHICLE = "auto_open_vehicle"
        private const val KEY_DEFAULT_VEHICLE_ID = "default_vehicle_id"
    }
}

package dev.nemesis.drive.data

import dev.nemesis.drive.model.AccentColor
import dev.nemesis.drive.model.ThemeChoice

interface AppPreferencesStore {
    fun isShowMyLocation(): Boolean = false
    fun setShowMyLocation(enabled: Boolean) {}

    fun isAmoledMode(): Boolean = true
    fun setAmoledMode(enabled: Boolean) {}

    fun isDynamicColor(): Boolean = true
    fun setDynamicColor(enabled: Boolean) {}

    fun getAccentColor(): AccentColor = AccentColor.TEAL
    fun setAccentColor(color: AccentColor) {}

    fun getCustomAccentColor(): Long = 0xFF00E5FFL
    fun setCustomAccentColor(color: Long) {}

    fun getTheme(): ThemeChoice = ThemeChoice.DARK
    fun setTheme(theme: ThemeChoice) {}

    fun isAutoOpenVehicle(): Boolean = true
    fun setAutoOpenVehicle(enabled: Boolean) {}

    fun getDefaultVehicleId(): String? = null
    fun setDefaultVehicleId(id: String?) {}
}

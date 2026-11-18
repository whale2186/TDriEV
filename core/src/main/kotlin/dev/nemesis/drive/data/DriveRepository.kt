package dev.nemesis.drive.data

import dev.nemesis.drive.model.*
import kotlinx.coroutines.flow.StateFlow

interface DriveRepository {
    val state: StateFlow<DriveUiState>
    fun continueFromWelcome()
    fun setPhone(value: String)
    fun requestDemoCode()
    fun setOtp(value: String)
    fun verifyDemoCode()
    fun selectVehicle(id: String)
    fun openGarage()
    fun setTab(tab: MainTab)
    fun setTheme(theme: ThemeChoice)
    fun setAcDuration(minutes: Int)
    fun runAction(action: DriveAction)
    fun clearHistory()
    fun signOut()
    fun cancelPendingAction()
    fun onBackground() { cancelPendingAction() }
    fun resolvePreviousRequest(vehicleId: String, acknowledged: Boolean) {}
    fun back()
    fun setDemoCableConnected(connected: Boolean) {}
    fun setSimulateUnavailable(unavailable: Boolean) {}
    fun setTermsAccepted(value: Boolean) {}
    fun setPrivacyAccepted(value: Boolean) {}
    fun refreshTelemetry() {}
    fun refreshGarage() {}
    fun prewake() {}
    fun setAutoOpenVehicle(enabled: Boolean) {}
    fun setDefaultVehicle(vehicleId: String?) {}
    fun setDynamicColor(enabled: Boolean) {}
    fun setAmoledMode(enabled: Boolean) {}
    fun setAccentColor(color: dev.nemesis.drive.model.AccentColor) {}
    fun setCustomAccentColor(color: Long) {}
    fun setShowMyLocation(enabled: Boolean) {}
    fun updateUserLocation(lat: Double, lon: Double) {}
    fun unlockApp() {}
    fun lockApp() {}
    fun setAppLockEnabled(enabled: Boolean) {}
    fun setBiometricsEnabled(enabled: Boolean) {}
    fun setPin(pin: String) {}
    fun verifyPin(pin: String): Boolean = true
    fun clearLockError() {}
}

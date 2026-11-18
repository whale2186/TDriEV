package dev.nemesis.drive

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nemesis.drive.data.*
import dev.nemesis.drive.model.*
import dev.nemesis.drive.nativeapi.NativeAuthService
import dev.nemesis.drive.nativeapi.NativeVehicleService
import dev.nemesis.drive.platform.AndroidOwnerStore
import dev.nemesis.drive.platform.AppPreferencesManager
import dev.nemesis.drive.platform.AppSecurityManager
import dev.nemesis.drive.platform.OwnerHttpsTransport

/** Separate product flavors choose the simulator or direct, owner-authenticated native services. */
class DriveViewModel(application: Application) : AndroidViewModel(application) {
    val securityManager = AppSecurityManager(application)
    val preferencesManager = AppPreferencesManager(application)
    private val repository: DriveRepository = if (BuildConfig.SIMULATOR) DemoDriveRepository(viewModelScope)
        else {
            val store = AndroidOwnerStore(application)
            val transport = OwnerHttpsTransport()
            val auth = NativeAuthService(transport, store)
            val service = NativeVehicleService(transport, auth, store, readinessAttempts = 12, readinessWaitMillis = 2_000L, statusWaitMillis = 800L, maxStatusAttempts = 20, fireAndForget = true)
            NativeDriveRepository(viewModelScope, auth, service, store, store, security = securityManager, preferences = preferencesManager)
        }
    val state = repository.state

    fun continueFromWelcome() = repository.continueFromWelcome()
    fun setPhone(value: String) = repository.setPhone(value)
    fun requestCode() = repository.requestDemoCode()
    fun requestDemoCode() = requestCode()
    fun setOtp(value: String) = repository.setOtp(value)
    fun verifyCode() = repository.verifyDemoCode()
    fun verifyDemoCode() = verifyCode()
    fun setTermsAccepted(value: Boolean) = repository.setTermsAccepted(value)
    fun setPrivacyAccepted(value: Boolean) = repository.setPrivacyAccepted(value)
    fun refreshTelemetry() = repository.refreshTelemetry()
    fun refreshGarage() = repository.refreshGarage()
    fun prewake() = repository.prewake()
    fun setAutoOpenVehicle(enabled: Boolean) = repository.setAutoOpenVehicle(enabled)
    fun setDefaultVehicle(vehicleId: String?) = repository.setDefaultVehicle(vehicleId)
    fun setDynamicColor(enabled: Boolean) = repository.setDynamicColor(enabled)
    fun setShowMyLocation(enabled: Boolean) = repository.setShowMyLocation(enabled)
    fun updateUserLocation(lat: Double, lon: Double) = repository.updateUserLocation(lat, lon)
    fun selectVehicle(id: String) = repository.selectVehicle(id)
    fun openGarage() = repository.openGarage()
    fun setTab(tab: MainTab) = repository.setTab(tab)
    fun setTheme(theme: ThemeChoice) = repository.setTheme(theme)
    fun setAcDuration(minutes: Int) = repository.setAcDuration(minutes)
    fun runAction(action: DriveAction) = repository.runAction(action)
    fun setDemoCableConnected(value: Boolean) = repository.setDemoCableConnected(value)
    fun setSimulateUnavailable(value: Boolean) = repository.setSimulateUnavailable(value)
    fun clearHistory() = repository.clearHistory()
    fun signOut() = repository.signOut()
    fun cancelPendingAction() = repository.cancelPendingAction()
    fun onBackground() = repository.onBackground()
    fun resolvePreviousRequest(vehicleId: String, acknowledged: Boolean) = repository.resolvePreviousRequest(vehicleId, acknowledged)
    fun back() = repository.back()
    fun unlockApp() = repository.unlockApp()
    fun resetSecurityAndSignOut() {
        securityManager.clearSecurity()
        repository.signOut()
    }
    fun lockApp() = repository.lockApp()
    fun setAppLockEnabled(enabled: Boolean) = repository.setAppLockEnabled(enabled)
    fun setBiometricsEnabled(enabled: Boolean) = repository.setBiometricsEnabled(enabled)
    fun setPin(pin: String) = repository.setPin(pin)
    fun verifyPin(pin: String): Boolean = repository.verifyPin(pin)
    fun clearLockError() = repository.clearLockError()
    fun setAmoledMode(enabled: Boolean) = repository.setAmoledMode(enabled)
    fun setAccentColor(color: AccentColor) = repository.setAccentColor(color)
    fun setCustomAccentColor(color: Long) = repository.setCustomAccentColor(color)
}

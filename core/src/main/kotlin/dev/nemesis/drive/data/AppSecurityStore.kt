package dev.nemesis.drive.data

interface AppSecurityStore {
    fun isAppLockEnabled(): Boolean
    fun isBiometricsEnabled(): Boolean
    fun hasPin(): Boolean
    fun setPin(pin: String)
    fun verifyPin(pin: String): Boolean
    fun setAppLockEnabled(enabled: Boolean)
    fun setBiometricsEnabled(enabled: Boolean)
    fun clearSecurity()
}

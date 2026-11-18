package dev.nemesis.drive.platform

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.os.Build
import dev.nemesis.drive.data.AppSecurityStore
import java.security.MessageDigest
import java.security.SecureRandom

class AppSecurityManager(private val context: Context) : AppSecurityStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("tdriev_security_prefs", Context.MODE_PRIVATE)

    override fun isAppLockEnabled(): Boolean = prefs.getBoolean(KEY_LOCK_ENABLED, false)

    override fun isBiometricsEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRICS_ENABLED, false)

    override fun hasPin(): Boolean = !prefs.getString(KEY_PIN_HASH, null).isNullOrEmpty()

    override fun setPin(pin: String) {
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val hashHex = hash(saltHex, pin)
        prefs.edit()
            .putString(KEY_PIN_SALT, saltHex)
            .putString(KEY_PIN_HASH, hashHex)
            .putBoolean(KEY_LOCK_ENABLED, true)
            .apply()
    }

    override fun verifyPin(pin: String): Boolean {
        val saltHex = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val expectedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val computedHash = hash(saltHex, pin)
        return MessageDigest.isEqual(computedHash.toByteArray(Charsets.UTF_8), expectedHash.toByteArray(Charsets.UTF_8))
    }

    override fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCK_ENABLED, enabled).apply()
    }

    override fun setBiometricsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRICS_ENABLED, enabled).apply()
    }

    override fun clearSecurity() {
        prefs.edit()
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_HASH)
            .putBoolean(KEY_LOCK_ENABLED, false)
            .putBoolean(KEY_BIOMETRICS_ENABLED, false)
            .apply()
    }

    fun canAuthenticateBiometrics(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bm = context.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager
            bm?.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            ) == BiometricManager.BIOMETRIC_SUCCESS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bm = context.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager
            bm?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        } else {
            false
        }
    }

    private fun hash(salt: String, input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt.toByteArray(Charsets.UTF_8))
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_BIOMETRICS_ENABLED = "biometrics_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
    }
}

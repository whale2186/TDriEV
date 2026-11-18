package dev.nemesis.drive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import dev.nemesis.drive.ui.DriveApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val driveViewModel: DriveViewModel by viewModels()
    private var locationListener: android.location.LocationListener? = null

    private val locationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            startLocationUpdates()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DriveApp(
                viewModel = driveViewModel,
                onTriggerBiometric = { promptBiometric(onSuccess = { driveViewModel.unlockApp() }) },
                onMinimize = { moveTaskToBack(true) }
            )
        }

        lifecycleScope.launch {
            driveViewModel.state.map { it.showMyLocation }.distinctUntilChanged().collect { show ->
                if (show) {
                    val hasFine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    val hasCoarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasFine || hasCoarse) {
                        startLocationUpdates()
                    } else {
                        locationPermissionLauncher.launch(arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    }
                } else {
                    stopLocationUpdates()
                }
            }
        }
    }

    private fun startLocationUpdates() {
        val lm = getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return
        val hasFine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        val listener = android.location.LocationListener { loc ->
            driveViewModel.updateUserLocation(loc.latitude, loc.longitude)
        }
        locationListener = listener
        runCatching {
            val last = if (hasFine) lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) else null
            val fallback = last ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            fallback?.let { driveViewModel.updateUserLocation(it.latitude, it.longitude) }

            if (hasFine && lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 2000L, 5f, listener)
            }
            if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 2000L, 5f, listener)
            }
        }
    }

    private fun stopLocationUpdates() {
        locationListener?.let {
            val lm = getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
            lm?.removeUpdates(it)
        }
        locationListener = null
    }

    private var biometricSignal: android.os.CancellationSignal? = null

    fun promptBiometric(
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            biometricSignal?.cancel()
            val signal = android.os.CancellationSignal()
            biometricSignal = signal

            val executor = mainExecutor
            val callback = object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    super.onAuthenticationError(errorCode, errString)
                    // Error codes: 5 (CANCELED), 10 (USER_CANCELED), 13 (NEGATIVE_BUTTON)
                    if (errorCode != 5 && errorCode != 10 && errorCode != 13) {
                        onError(errString?.toString() ?: "Biometric authentication failed")
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onError("Biometric not recognized. Please try again.")
                }
            }

            val builder = android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle("Unlock TDriEV")
                .setSubtitle("Confirm biometrics to access vehicle controls")
                .setNegativeButton("Use PIN", executor) { _, _ -> }

            builder.build().authenticate(signal, executor, callback)
        } else {
            onError("Biometrics not supported on this version of Android.")
        }
    }

    override fun onResume() {
        super.onResume()
        if (driveViewModel.state.value.isAppLocked &&
            driveViewModel.state.value.biometricsEnabled &&
            driveViewModel.securityManager.canAuthenticateBiometrics()) {
            promptBiometric(onSuccess = { driveViewModel.unlockApp() })
        }
        driveViewModel.prewake()
        driveViewModel.refreshTelemetry()
        if (driveViewModel.state.value.showMyLocation) {
            startLocationUpdates()
        }
    }

    override fun onStop() {
        biometricSignal?.cancel()
        biometricSignal = null
        stopLocationUpdates()
        if (driveViewModel.state.value.appLockEnabled && driveViewModel.state.value.hasPinSet) {
            driveViewModel.lockApp()
        }
        driveViewModel.onBackground()
        super.onStop()
    }
}

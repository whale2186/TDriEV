package dev.nemesis.drive

import dev.nemesis.drive.data.AppSecurityStore
import dev.nemesis.drive.data.DemoDriveRepository
import dev.nemesis.drive.data.NativeDriveRepository
import dev.nemesis.drive.model.*
import dev.nemesis.drive.nativeapi.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FakeSecurityStore(
    private var lockEnabled: Boolean = false,
    private var bioEnabled: Boolean = false,
    private var pin: String? = null
) : AppSecurityStore {
    override fun isAppLockEnabled(): Boolean = lockEnabled
    override fun isBiometricsEnabled(): Boolean = bioEnabled
    override fun hasPin(): Boolean = !pin.isNullOrEmpty()
    override fun setPin(pin: String) {
        this.pin = pin
        this.lockEnabled = true
    }
    override fun verifyPin(pin: String): Boolean = this.pin == pin
    override fun setAppLockEnabled(enabled: Boolean) { this.lockEnabled = enabled }
    override fun setBiometricsEnabled(enabled: Boolean) { this.bioEnabled = enabled }
    override fun clearSecurity() {
        this.lockEnabled = false
        this.bioEnabled = false
        this.pin = null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppSecurityTest {
    @Test
    fun demoRepositoryAppLockLifecycle() = runTest {
        val repo = DemoDriveRepository(this)
        assertFalse(repo.state.value.isAppLocked)
        assertFalse(repo.state.value.appLockEnabled)

        // Setting PIN enables app lock
        repo.setPin("4321")
        assertTrue(repo.state.value.appLockEnabled)
        assertTrue(repo.state.value.hasPinSet)

        // Lock app
        repo.lockApp()
        assertTrue(repo.state.value.isAppLocked)

        // Wrong PIN fails and leaves app locked
        val wrongResult = repo.verifyPin("0000")
        assertFalse(wrongResult)
        assertTrue(repo.state.value.isAppLocked)
        assertNotNull(repo.state.value.lockError)

        // Correct PIN unlocks
        val correctResult = repo.verifyPin("4321")
        assertTrue(correctResult)
        assertFalse(repo.state.value.isAppLocked)
        assertNull(repo.state.value.lockError)

        // Biometrics toggle
        repo.setBiometricsEnabled(true)
        assertTrue(repo.state.value.biometricsEnabled)
    }

    @Test
    fun nativeRepositoryStartsLockedWhenConfigured() = runTest {
        val fakeStore = FakeSecurityStore(lockEnabled = true, bioEnabled = true, pin = "8888")
        val fakeAuth = object : OwnerAuth {
            override suspend fun restore(): NativeSession? = null
            override suspend fun refresh(session: NativeSession): NativeSession = session
            override suspend fun requestCode(phone: String): OtpChallenge = error("unused")
            override suspend fun verifyCode(challenge: OtpChallenge, otp: String, terms: Boolean, privacy: Boolean): NativeSession = error("unused")
            override fun clear() {}
        }
        val fakeService = object : OwnerVehicleService {
            override suspend fun vehicles(session: NativeSession): List<OwnedVehicle> = emptyList()
            override suspend fun snapshot(session: NativeSession, vehicle: OwnedVehicle): VehicleSnapshot = error("unused")
            override suspend fun prewake(session: NativeSession, vehicle: OwnedVehicle) {}
            override suspend fun run(
                session: NativeSession,
                vehicle: OwnedVehicle,
                action: DriveAction,
                durationMinutes: Int,
                requestId: String,
                guard: suspend () -> Unit,
                progress: (OperationPhase, String) -> Unit
            ): NativeCommandResult = NativeCommandResult(OperationPhase.SUCCESS, "OK")
        }
        val fakeLedger = object : NativeSafetyLedger {
            override fun isBlocked(vehicleId: String): Boolean = false
            override fun markPending(vehicleId: String, action: DriveAction, requestId: String) {}
            override fun clearPending(vehicleId: String) {}
        }

        val repo = NativeDriveRepository(
            scope = this,
            auth = fakeAuth,
            service = fakeService,
            ledger = fakeLedger,
            security = fakeStore
        )

        // Verified: Starts in locked state because lock is enabled and PIN is set!
        assertTrue(repo.state.value.isAppLocked)
        assertTrue(repo.state.value.appLockEnabled)
        assertTrue(repo.state.value.biometricsEnabled)
        assertTrue(repo.state.value.hasPinSet)

        // Verify PIN unlock
        assertFalse(repo.verifyPin("1111"))
        assertTrue(repo.state.value.isAppLocked)

        assertTrue(repo.verifyPin("8888"))
        assertFalse(repo.state.value.isAppLocked)
    }
}

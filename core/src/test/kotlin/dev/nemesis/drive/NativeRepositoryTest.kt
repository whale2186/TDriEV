@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.nemesis.drive

import dev.nemesis.drive.data.NativeDriveRepository
import dev.nemesis.drive.model.*
import dev.nemesis.drive.nativeapi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class NativeRepositoryTest {
    private class Ledger : NativeSafetyLedger {
        val blocked = mutableSetOf<String>()
        override fun isBlocked(vehicleId: String) = vehicleId in blocked
        override fun markPending(vehicleId: String, action: DriveAction, requestId: String) { blocked.add(vehicleId) }
        override fun clearPending(vehicleId: String) { blocked.remove(vehicleId) }
    }
    private class Auth : OwnerAuth {
        var codes = 0; var verifies = 0; var cleared = 0
        var saved: NativeSession? = null
        var slowCode = false
        var slowRestore = false
        override suspend fun requestCode(phone: String): OtpChallenge {
            codes++; if (slowCode) delay(1000)
            return OtpChallenge(phone, "fixture-reference", emptyMap())
        }
        override suspend fun verifyCode(challenge: OtpChallenge, otp: String, terms: Boolean, privacy: Boolean): NativeSession {
            verifies++; return NativeSession(challenge.phone, mutableMapOf(), nativeJson("accessToken" to "fixture-token"))
        }
        override suspend fun restore(): NativeSession? {
            val captured = saved
            if (slowRestore) withContext(NonCancellable) { delay(1000) }
            return captured
        }
        override suspend fun refresh(session: NativeSession) = session
        override fun clear() { cleared++; saved = null }
    }
    private val snap = VehicleSnapshot(65, 150, 123.0, true, false, false, true, true, true, false, 1_000L)
    private inner class Vehicles(val ledger: Ledger) : OwnerVehicleService {
        val a = OwnedVehicle(DriveVehicle("a", "Fixture car", "Fixture car", "TEST", snap), "MATAA111111111111")
        val b = OwnedVehicle(DriveVehicle("b", "Fixture second", "Fixture second", "TEST2", snap), "MATAA222222222222")
        var discovery = 0; var reads = 0; var actions = 0; var delayReads = false
        var submitting = false
        var terminalProgress = false
        var batteryA = 65
        override suspend fun vehicles(session: NativeSession): List<OwnedVehicle> { discovery++; return listOf(a, b) }
        override suspend fun snapshot(session: NativeSession, vehicle: OwnedVehicle): VehicleSnapshot {
            reads++; if (delayReads) delay(5000); return snap.copy(batteryPercent = if (vehicle.vehicle.id == "a") batteryA else 22)
        }
        override suspend fun run(session: NativeSession, vehicle: OwnedVehicle, action: DriveAction, durationMinutes: Int,
            requestId: String, guard: suspend () -> Unit, progress: (OperationPhase, String) -> Unit): NativeCommandResult {
            actions++; guard()
            if (submitting) { ledger.markPending(vehicle.vehicle.id, action, requestId); progress(OperationPhase.SENDING, "Submitting fixture") }
            else progress(OperationPhase.WAKING, "Fixture reconnect")
            delay(1000); guard()
            if (submitting) ledger.clearPending(vehicle.vehicle.id)
            if (terminalProgress) progress(OperationPhase.SUCCESS, "Tata reported success")
            return NativeCommandResult(OperationPhase.SUCCESS, "Tata reported success; refresh to observe vehicle state")
        }
    }
    private fun TestScope.ready(auth: Auth, vehicles: Vehicles, ledger: Ledger): NativeDriveRepository {
        auth.saved = NativeSession("9999999999", mutableMapOf(), nativeJson("accessToken" to "fixture"))
        return NativeDriveRepository(backgroundScope, auth, vehicles, ledger, now = { 100_000L })
    }
    @Test fun noAutomaticOtpAndExplicitRequestIsSingleFlight() = runTest {
        val auth = Auth(); val ledger = Ledger(); val api = Vehicles(ledger)
        val repo = NativeDriveRepository(backgroundScope, auth, api, ledger)
        runCurrent()
        assertFalse(repo.state.value.isDemo); assertEquals("", repo.state.value.phone)
        assertEquals(0, auth.codes)
        repo.continueFromWelcome(); assertEquals(EntryStep.PHONE, repo.state.value.step)
        repo.setPhone("9999999999"); repo.requestDemoCode(); repo.requestDemoCode(); runCurrent()
        assertEquals(1, auth.codes); assertEquals(EntryStep.OTP, repo.state.value.step)
    }
    @Test fun codeVerificationRequiresBothExplicitConsents() = runTest {
        val auth = Auth(); val ledger = Ledger(); val api = Vehicles(ledger)
        val repo = NativeDriveRepository(backgroundScope, auth, api, ledger)
        runCurrent(); repo.continueFromWelcome(); repo.setPhone("9999999999"); repo.requestDemoCode(); runCurrent()
        repo.setOtp("123456"); repo.verifyDemoCode(); runCurrent(); assertEquals(0, auth.verifies)
        repo.setTermsAccepted(true); repo.setPrivacyAccepted(true); repo.verifyDemoCode(); runCurrent()
        assertEquals(1, auth.verifies); assertEquals(EntryStep.GARAGE, repo.state.value.step)
        assertEquals("", repo.state.value.otp); assertEquals(2, repo.state.value.vehicles.size)
    }
    @Test fun restoredSessionDiscoversGarageWithoutRequestingOtpOrActuation() = runTest {
        val auth = Auth(); val ledger = Ledger(); val api = Vehicles(ledger)
        val repo = ready(auth, api, ledger); runCurrent()
        assertEquals(EntryStep.GARAGE, repo.state.value.step); assertEquals(1, api.discovery)
        assertEquals(0, auth.codes); assertEquals(0, api.actions)
        repo.selectVehicle("a"); runCurrent()
        assertEquals(EntryStep.DASHBOARD, repo.state.value.step); assertEquals(1, api.reads)
        assertEquals(0, api.actions)
    }
    @Test fun duplicateTapDoesNotSubmitTwiceAndDoesNotForgeSnapshot() = runTest {
        val auth = Auth(); val ledger = Ledger(); val api = Vehicles(ledger)
        val repo = ready(auth, api, ledger); runCurrent(); repo.selectVehicle("a"); runCurrent()
        repo.runAction(DriveAction.UNLOCK); repo.runAction(DriveAction.UNLOCK); runCurrent()
        assertEquals(1, api.actions); assertTrue(repo.state.value.busy)
        advanceTimeBy(1000); runCurrent()
        assertEquals(OperationPhase.SUCCESS, repo.state.value.operation.phase)
        assertEquals(true, repo.state.value.selectedVehicle!!.snapshot.locked)
        assertEquals(1, repo.state.value.history.size)
    }
    @Test fun terminalServiceProgressStillRecordsTheValidatedFinalResult() = runTest {
        val auth = Auth(); val ledger = Ledger(); val api = Vehicles(ledger); api.terminalProgress = true
        val repo = ready(auth, api, ledger); runCurrent(); repo.selectVehicle("a"); runCurrent()
        repo.runAction(DriveAction.HORN); runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(OperationPhase.SUCCESS, repo.state.value.operation.phase)
        assertEquals(1, repo.state.value.history.size)
    }
    @Test fun changingVehicleCancelsUnsentIntentAndRejectsStaleProgress() = runTest {
        val auth = Auth(); val ledger = Ledger(); val api = Vehicles(ledger)
        val repo = ready(auth, api, ledger); runCurrent(); repo.selectVehicle("a"); runCurrent()
        repo.runAction(DriveAction.HORN); runCurrent(); repo.selectVehicle("b"); runCurrent()
        advanceTimeBy(2000); runCurrent()
        assertEquals("b", repo.state.value.selectedVehicleId)
        assertNotEquals(OperationPhase.SUCCESS, repo.state.value.operation.phase)
        assertFalse(ledger.isBlocked("a"))
    }
    @Test fun refreshingGarageMetadataDoesNotEraseTheSelectedVehiclesTelemetry() = runTest {
        val auth = Auth(); val ledger = Ledger(); val api = Vehicles(ledger); api.batteryA = 88
        val repo = ready(auth, api, ledger); runCurrent(); repo.selectVehicle("a"); runCurrent()
        assertEquals(88, repo.state.value.selectedVehicle!!.snapshot.batteryPercent)
        repo.refreshGarage(); runCurrent()
        assertEquals(88, repo.state.value.selectedVehicle!!.snapshot.batteryPercent)
        assertEquals(0, api.actions)
    }

    @Test fun unresolvedRequestRequiresExplicitVehicleBoundReviewAndNeverReplaysACommand() = runTest {
        val auth = Auth(); val ledger = Ledger(); val api = Vehicles(ledger); ledger.blocked.add("a")
        val repo = ready(auth, api, ledger); runCurrent(); repo.selectVehicle("a"); runCurrent()
        assertTrue(repo.state.value.previousRequestUnresolved)
        repo.resolvePreviousRequest("a", false)
        assertTrue(ledger.isBlocked("a"))
        repo.resolvePreviousRequest("b", true)
        assertTrue(ledger.isBlocked("a"))
        repo.resolvePreviousRequest("a", true)
        assertFalse(ledger.isBlocked("a"))
        assertFalse(repo.state.value.previousRequestUnresolved)
        assertEquals(0, api.actions)
    }

    @Test fun backgroundingAfterSubmissionKeepsOnlyTheExistingBoundedStatusMonitor() = runTest {
        val auth = Auth(); val ledger = Ledger(); val api = Vehicles(ledger); api.submitting = true
        val repo = ready(auth, api, ledger); runCurrent(); repo.selectVehicle("a"); runCurrent()
        repo.runAction(DriveAction.LOCK); runCurrent()
        assertEquals(OperationPhase.SENDING, repo.state.value.operation.phase)
        repo.onBackground()
        assertTrue(repo.state.value.busy)
        advanceTimeBy(1000); runCurrent()
        assertEquals(OperationPhase.SUCCESS, repo.state.value.operation.phase)
        assertEquals(1, api.actions)
        assertFalse(ledger.isBlocked("a"))
    }

    @Test fun backgroundingBeforeSubmissionStillCancelsTheUnsentIntent() = runTest {
        val auth = Auth(); val ledger = Ledger(); val api = Vehicles(ledger)
        val repo = ready(auth, api, ledger); runCurrent(); repo.selectVehicle("a"); runCurrent()
        repo.runAction(DriveAction.LOCK); runCurrent(); repo.onBackground()
        advanceTimeBy(1000); runCurrent()
        assertEquals(OperationPhase.CANCELLED, repo.state.value.operation.phase)
        assertFalse(ledger.isBlocked("a"))
    }

    @Test fun cancellationAfterSubmissionRetainsPersistentUnknownAndBlocksNextAction() = runTest {
        val auth = Auth(); val ledger = Ledger(); val api = Vehicles(ledger); api.submitting = true
        val repo = ready(auth, api, ledger); runCurrent(); repo.selectVehicle("a"); runCurrent()
        repo.runAction(DriveAction.HORN); runCurrent(); repo.cancelPendingAction()
        assertEquals(OperationPhase.UNKNOWN, repo.state.value.operation.phase)
        assertTrue(ledger.isBlocked("a")); repo.runAction(DriveAction.HORN); runCurrent()
        assertEquals(1, api.actions)
        repo.signOut(); assertTrue(ledger.isBlocked("a")); assertEquals(0, repo.state.value.history.size)
    }
    @Test fun signOutDuringOtpRequestCannotRestoreOldChallenge() = runTest {
        val auth = Auth(); auth.slowCode = true; val ledger = Ledger(); val api = Vehicles(ledger)
        val repo = NativeDriveRepository(backgroundScope, auth, api, ledger)
        runCurrent(); repo.continueFromWelcome(); repo.setPhone("9999999999"); repo.requestDemoCode(); runCurrent()
        repo.signOut(); advanceTimeBy(2000); runCurrent()
        assertEquals(EntryStep.WELCOME, repo.state.value.step); assertEquals("", repo.state.value.phone)
        assertFalse(repo.state.value.authBusy)
    }
    @Test fun signOutDuringRestoreCannotReintroduceTheOldSession() = runTest {
        val auth = Auth(); auth.slowRestore = true
        auth.saved = NativeSession("9999999999", mutableMapOf(), nativeJson("accessToken" to "fixture"))
        val ledger = Ledger(); val api = Vehicles(ledger)
        val repo = NativeDriveRepository(backgroundScope, auth, api, ledger)
        runCurrent(); repo.signOut(); advanceTimeBy(1000); runCurrent()
        repo.continueFromWelcome(); repo.setPhone("9999999999"); repo.requestDemoCode(); runCurrent()
        assertEquals(1, auth.codes)
        assertEquals(EntryStep.OTP, repo.state.value.step)
    }
    @Test fun oldUserIntentExpiresWithoutExecutingAfterReconnect() = runTest {
        val auth = Auth(); val ledger = Ledger(); val api = Vehicles(ledger)
        auth.saved = NativeSession("9999999999", mutableMapOf(), nativeJson("accessToken" to "fixture"))
        var clock = 100_000L
        val repo = NativeDriveRepository(backgroundScope, auth, api, ledger, now = { clock })
        runCurrent(); repo.selectVehicle("a"); runCurrent(); repo.runAction(DriveAction.HORN); runCurrent()
        clock += 121_000L; advanceTimeBy(1000); runCurrent()
        assertEquals(OperationPhase.CANCELLED, repo.state.value.operation.phase)
        assertFalse(repo.state.value.busy); assertFalse(ledger.isBlocked("a"))
    }
    @Test fun passiveSnapshotResponsesCannotCrossVehicleSelection() = runTest {
        val auth = Auth(); val ledger = Ledger(); val api = Vehicles(ledger); api.delayReads = true
        val repo = ready(auth, api, ledger); runCurrent(); repo.selectVehicle("a"); runCurrent()
        repo.selectVehicle("b"); runCurrent(); advanceTimeBy(5000); runCurrent()
        assertEquals("b", repo.state.value.selectedVehicleId)
        assertEquals(22, repo.state.value.selectedVehicle!!.snapshot.batteryPercent)
    }
}

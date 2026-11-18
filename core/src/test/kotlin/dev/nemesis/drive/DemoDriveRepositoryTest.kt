package dev.nemesis.drive

import dev.nemesis.drive.data.DemoDriveRepository
import dev.nemesis.drive.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.loggedIn(): DemoDriveRepository = DemoDriveRepository(this, 100) { testScheduler.currentTime }.also {
    it.continueFromWelcome(); it.requestDemoCode(); it.setOtp("123456"); it.verifyDemoCode(); it.selectVehicle("demo-tiago")
}

@OptIn(ExperimentalCoroutinesApi::class)
class DemoDriveRepositoryTest {
    @Test fun fastDuplicateTapsSubmitOnceWithOneWakeThenReuseConnection() = runTest {
        val r = loggedIn()
        r.runAction(DriveAction.UNLOCK)
        val id = r.state.value.operation.requestId
        repeat(10) { r.runAction(DriveAction.HORN) }
        assertEquals(OperationPhase.CONTACTING, r.state.value.operation.phase)
        assertEquals(id, r.state.value.operation.requestId)
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(OperationPhase.WAKING, r.state.value.operation.phase)
        advanceTimeBy(100); runCurrent()
        assertEquals(OperationPhase.WAITING_READY, r.state.value.operation.phase)
        advanceTimeBy(100); runCurrent()
        assertEquals(OperationPhase.SENDING, r.state.value.operation.phase)
        advanceUntilIdle()
        assertEquals(OperationPhase.SUCCESS, r.state.value.operation.phase)
        assertEquals(1, r.state.value.demoWakeCount)
        assertEquals(1, r.state.value.demoSubmissionCount)
        assertEquals(false, r.state.value.selectedVehicle!!.snapshot.locked)
        assertEquals(1, r.state.value.history.size)
        assertTrue(r.state.value.history.single().outcome.startsWith("Simulated"))
        r.runAction(DriveAction.LOCK); advanceUntilIdle()
        assertEquals(0, r.state.value.demoWakeCount)
        assertEquals(1, r.state.value.demoSubmissionCount)
        assertEquals(true, r.state.value.selectedVehicle!!.snapshot.locked)
        assertEquals(2, r.state.value.history.size)
    }

    @Test fun cableBlocksAcStartButNotStopAndDurationIsBounded() = runTest {
        val r = loggedIn()
        r.setAcDuration(4)
        assertEquals(10, r.state.value.acDurationMinutes)
        assertNotNull(r.state.value.error)
        r.setAcDuration(20); assertEquals(20, r.state.value.acDurationMinutes)
        r.setAcDuration(21); assertEquals(20, r.state.value.acDurationMinutes)
        r.setAcDuration(5); assertEquals(5, r.state.value.acDurationMinutes)
        r.setDemoCableConnected(true)
        r.runAction(DriveAction.AC_ON); advanceUntilIdle()
        assertNotNull(r.state.value.error)
        assertEquals(0, r.state.value.demoSubmissionCount)
        r.runAction(DriveAction.AC_OFF); advanceUntilIdle()
        assertEquals(OperationPhase.SUCCESS, r.state.value.operation.phase)
        r.setDemoCableConnected(false)
        r.runAction(DriveAction.AC_ON); advanceUntilIdle()
        assertEquals(true, r.state.value.selectedVehicle!!.snapshot.acOn)
    }

    @Test fun unavailableFailsBoundedlyWithoutSubmissionOrSnapshotMutation() = runTest {
        val r = loggedIn()
        val before = r.state.value.selectedVehicle!!.snapshot
        r.setSimulateUnavailable(true)
        r.runAction(DriveAction.UNLOCK); advanceUntilIdle()
        assertEquals(OperationPhase.FAILED, r.state.value.operation.phase)
        assertEquals(1, r.state.value.demoWakeCount)
        assertEquals(0, r.state.value.demoSubmissionCount)
        assertEquals(before, r.state.value.selectedVehicle!!.snapshot)
        assertTrue(r.state.value.history.single().outcome.startsWith("Simulated"))
        r.setSimulateUnavailable(false)
        advanceUntilIdle()
        assertEquals(0, r.state.value.demoSubmissionCount)
    }

    @Test fun cancelBeforeSendNeverSubmitsAndVehicleSwitchDoesNotMigrateOperation() = runTest {
        val r = loggedIn()
        r.runAction(DriveAction.UNLOCK); r.cancelPendingAction(); advanceUntilIdle()
        assertEquals(OperationPhase.CANCELLED, r.state.value.operation.phase)
        assertEquals(0, r.state.value.demoSubmissionCount)
        assertEquals(true, r.state.value.selectedVehicle!!.snapshot.locked)
        r.runAction(DriveAction.UNLOCK); runCurrent()
        r.selectVehicle("demo-nexon"); advanceUntilIdle()
        assertEquals("demo-nexon", r.state.value.selectedVehicleId)
        assertTrue(r.state.value.vehicles.all { it.snapshot.locked == true })
        assertEquals("demo-tiago", r.state.value.history.first().vehicleId)
        assertEquals(0, r.state.value.demoSubmissionCount)
    }

    @Test fun cancelAfterSendingIsUnknownAndCannotBeRetried() = runTest {
        val r = loggedIn()
        r.runAction(DriveAction.UNLOCK); runCurrent(); advanceTimeBy(300); runCurrent()
        assertEquals(OperationPhase.SENDING, r.state.value.operation.phase)
        r.cancelPendingAction(); advanceUntilIdle()
        assertEquals(OperationPhase.UNKNOWN, r.state.value.operation.phase)
        assertEquals(1, r.state.value.demoSubmissionCount)
        assertEquals(true, r.state.value.selectedVehicle!!.snapshot.locked)
        r.runAction(DriveAction.UNLOCK); advanceUntilIdle()
        assertEquals(OperationPhase.UNKNOWN, r.state.value.operation.phase)
        assertEquals(1, r.state.value.history.size)
        assertNotNull(r.state.value.error)
    }

    @Test fun switchingToUnavailableDuringContactPreventsSubmission() = runTest {
        val r = loggedIn()
        r.runAction(DriveAction.UNLOCK)
        r.setSimulateUnavailable(true)
        advanceUntilIdle()
        assertEquals(OperationPhase.FAILED, r.state.value.operation.phase)
        assertEquals(0, r.state.value.demoSubmissionCount)
        assertEquals(true, r.state.value.selectedVehicle!!.snapshot.locked)
    }

    @Test fun connectingCableWhileWaitingRechecksAcSafetyBeforeSending() = runTest {
        val r = loggedIn()
        r.runAction(DriveAction.AC_ON)
        runCurrent(); advanceTimeBy(200); runCurrent()
        r.setDemoCableConnected(true)
        advanceUntilIdle()
        assertEquals(OperationPhase.FAILED, r.state.value.operation.phase)
        assertEquals(0, r.state.value.demoSubmissionCount)
        assertEquals(false, r.state.value.selectedVehicle!!.snapshot.acOn)
    }

    @Test fun everyActionUpdatesOnlyItsBoundVehicleAndRecordsSimulatedSteps() = runTest {
        val r = loggedIn()
        val other = r.state.value.vehicles.last()
        for (action in DriveAction.values()) {
            r.runAction(action); advanceUntilIdle()
            assertEquals(OperationPhase.SUCCESS, r.state.value.operation.phase)
            assertEquals(1, r.state.value.demoSubmissionCount)
            assertEquals(other, r.state.value.vehicles.last())
            val snapshot = r.state.value.selectedVehicle!!.snapshot
            when (action) {
                DriveAction.LOCK -> assertEquals(true, snapshot.locked)
                DriveAction.UNLOCK -> assertEquals(false, snapshot.locked)
                DriveAction.AC_ON -> assertEquals(true, snapshot.acOn)
                DriveAction.AC_OFF -> assertEquals(false, snapshot.acOn)
                DriveAction.APPROACH_ON -> assertEquals(true, snapshot.approachOn)
                DriveAction.APPROACH_OFF -> assertEquals(false, snapshot.approachOn)
                DriveAction.HAZARD_ON -> assertEquals(true, snapshot.hazardOn)
                DriveAction.HAZARD_OFF -> assertEquals(false, snapshot.hazardOn)
                DriveAction.POSITION_ON -> assertEquals(true, snapshot.positionOn)
                DriveAction.POSITION_OFF -> assertEquals(false, snapshot.positionOn)
                DriveAction.HORN -> assertNotNull(snapshot.updatedAtMillis)
            }
            assertEquals(action, r.state.value.history.first().action)
            assertTrue(r.state.value.history.first().steps.all { it.contains("simulated", ignoreCase = true) })
        }
        assertEquals(DriveAction.values().size, r.state.value.history.size)
        r.selectVehicle("demo-nexon"); r.runAction(DriveAction.HORN); advanceUntilIdle()
        assertEquals(1, r.state.value.demoWakeCount)
        r.selectVehicle("demo-tiago"); r.runAction(DriveAction.HORN); advanceUntilIdle()
        assertEquals(0, r.state.value.demoWakeCount)
    }

    @Test fun garageAndBackCancelPendingActionsWithoutQueuing() = runTest {
        val r = loggedIn()
        r.runAction(DriveAction.HORN); r.openGarage(); advanceUntilIdle()
        assertEquals(EntryStep.GARAGE, r.state.value.step)
        assertEquals(OperationPhase.CANCELLED, r.state.value.operation.phase)
        r.runAction(DriveAction.HORN); advanceUntilIdle()
        assertEquals(0, r.state.value.demoSubmissionCount)
        r.back(); r.runAction(DriveAction.HORN); r.back(); advanceUntilIdle()
        assertEquals(EntryStep.GARAGE, r.state.value.step)
        assertEquals(0, r.state.value.demoSubmissionCount)
    }

    @Test fun signOutCancelsClearsSessionAndPreferencesHistoryAreUsable() = runTest {
        val r = loggedIn()
        r.setTheme(ThemeChoice.LIGHT); r.setTab(MainTab.ACTIVITY)
        assertEquals(ThemeChoice.LIGHT, r.state.value.theme)
        assertEquals(MainTab.ACTIVITY, r.state.value.tab)
        r.runAction(DriveAction.HORN); advanceUntilIdle()
        r.clearHistory(); assertTrue(r.state.value.history.isEmpty())
        r.runAction(DriveAction.UNLOCK); r.signOut(); advanceUntilIdle()
        assertEquals(EntryStep.WELCOME, r.state.value.step)
        assertEquals("0000000000", r.state.value.phone)
        assertEquals("", r.state.value.otp)
        assertNull(r.state.value.selectedVehicleId)
        assertTrue(r.state.value.history.isEmpty())
        assertTrue(r.state.value.vehicles.isEmpty())
        assertEquals(0, r.state.value.demoSubmissionCount)
        assertFalse(r.state.value.busy)
        r.runAction(DriveAction.HORN); advanceUntilIdle()
        assertEquals(0, r.state.value.demoSubmissionCount)
    }

    @Test fun demoLoginValidatesPhoneAndCodeThenShowsOwnedGarage() = runTest {
        val repository = DemoDriveRepository(backgroundScope)
        assertEquals(ThemeChoice.DARK, repository.state.value.theme)
        assertEquals("0000000000", repository.state.value.phone)
        repository.continueFromWelcome()
        assertEquals(EntryStep.PHONE, repository.state.value.step)
        repository.setPhone("123")
        repository.requestDemoCode()
        assertEquals(EntryStep.PHONE, repository.state.value.step)
        assertNotNull(repository.state.value.error)
        repository.setPhone("0000000000")
        repository.requestDemoCode()
        assertEquals(EntryStep.OTP, repository.state.value.step)
        repository.setOtp("654321")
        repository.verifyDemoCode()
        assertEquals(EntryStep.OTP, repository.state.value.step)
        assertNotNull(repository.state.value.error)
        repository.setOtp("123456")
        repository.verifyDemoCode()
        assertEquals(EntryStep.GARAGE, repository.state.value.step)
        assertEquals(listOf("demo-tiago", "demo-nexon"), repository.state.value.vehicles.map { it.id })
        assertTrue(repository.state.value.vehicles.all { it.registration.startsWith("DEMO") })
        repository.selectVehicle("unowned")
        assertNull(repository.state.value.selectedVehicleId)
        assertNotNull(repository.state.value.error)
        repository.selectVehicle("demo-tiago")
        assertEquals(EntryStep.DASHBOARD, repository.state.value.step)
        assertNull(repository.state.value.error)
        repository.openGarage()
        assertEquals("demo-tiago", repository.state.value.selectedVehicleId)
        repository.back()
        assertEquals(EntryStep.DASHBOARD, repository.state.value.step)
    }
}

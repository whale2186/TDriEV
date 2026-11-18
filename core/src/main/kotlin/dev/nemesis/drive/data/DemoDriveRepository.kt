package dev.nemesis.drive.data

import dev.nemesis.drive.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory simulator only. Never authenticates, sends SMS, or contacts a vehicle. */
class DemoDriveRepository(
    private val scope: CoroutineScope,
    private val stepMillis: Long = 300,
    private val now: () -> Long = System::currentTimeMillis
) : DriveRepository {
    private val mutableState = MutableStateFlow(DriveUiState())
    override val state: StateFlow<DriveUiState> = mutableState.asStateFlow()
    private fun change(transform: (DriveUiState) -> DriveUiState) { mutableState.value = transform(mutableState.value) }
    private fun error(message: String) = change { it.copy(error = message) }

    override fun continueFromWelcome() { if (state.value.step == EntryStep.WELCOME) change { it.copy(step = EntryStep.PHONE, error = null) } }
    override fun setPhone(value: String) { change { it.copy(phone = value, error = null) } }
    override fun requestDemoCode() {
        if (state.value.step != EntryStep.PHONE) return
        if (!state.value.phone.matches(Regex("[0-9]{10}"))) { error("Enter exactly 10 digits for this demo."); return }
        change { it.copy(step = EntryStep.OTP, otp = "", error = null) }
    }
    override fun setOtp(value: String) { change { it.copy(otp = value, error = null) } }
    override fun verifyDemoCode() {
        if (state.value.step != EntryStep.OTP) return
        if (state.value.otp != "123456") { error("Demo code is 123456. No SMS was sent."); return }
        change { it.copy(step = EntryStep.GARAGE, vehicles = demoVehicles(), otp = "", error = null) }
    }
    override fun selectVehicle(id: String) {
        if (state.value.step !in setOf(EntryStep.GARAGE, EntryStep.DASHBOARD)) return
        if (state.value.vehicles.none { it.id == id }) { error("Choose an owned demo vehicle."); return }
        if (state.value.selectedVehicleId != id) cancelPendingAction()
        change { it.copy(step = EntryStep.DASHBOARD, selectedVehicleId = id, error = null) }
    }
    override fun openGarage() {
        if (state.value.selectedVehicle != null) {
            cancelPendingAction()
            change { it.copy(step = EntryStep.GARAGE, error = null) }
        }
    }
    override fun back() {
        cancelPendingAction()
        change { it.copy(error = null, step = when (it.step) {
            EntryStep.OTP -> EntryStep.PHONE
            EntryStep.PHONE -> EntryStep.WELCOME
            EntryStep.GARAGE -> if (it.selectedVehicle != null) EntryStep.DASHBOARD else EntryStep.PHONE
            EntryStep.DASHBOARD -> EntryStep.GARAGE
            else -> EntryStep.WELCOME
        }) }
    }
    private var pendingJob: Job? = null
    private var sequence = 0L
    private val connectedVehicles = mutableSetOf<String>()
    private val uncertainVehicles = mutableSetOf<String>()
    private var operationSteps = emptyList<String>()

    override fun setTab(tab: MainTab) { change { it.copy(tab = tab) } }
    override fun setTheme(theme: ThemeChoice) { change { it.copy(theme = theme) } }
    override fun setAcDuration(minutes: Int) {
        if (minutes !in 5..20) { error("Demo AC duration must be 5–20 minutes."); return }
        change { it.copy(acDurationMinutes = minutes, error = null) }
    }
    override fun setDemoCableConnected(connected: Boolean) {
        val id = state.value.selectedVehicleId ?: return
        change { s -> s.copy(vehicles = s.vehicles.map {
            if (it.id == id) it.copy(snapshot = it.snapshot.copy(cableConnected = connected, charging = connected)) else it
        }, error = null) }
    }
    override fun setSimulateUnavailable(unavailable: Boolean) {
        connectedVehicles.clear()
        change { it.copy(simulateUnavailable = unavailable, error = null) }
    }
    override fun clearHistory() { change { it.copy(history = emptyList()) } }
    override fun signOut() {
        pendingJob?.cancel()
        pendingJob = null
        connectedVehicles.clear()
        uncertainVehicles.clear()
        operationSteps = emptyList()
        change { DriveUiState(theme = it.theme) }
    }

    private fun safetyError(snapshot: VehicleSnapshot, action: DriveAction): String? = when {
        snapshot.doorsClosed != true || snapshot.bonnetClosed != true || snapshot.bootClosed != true ->
            "Demo safety check: doors, bonnet and boot must be known closed."
        snapshot.ignitionOn != false -> "Demo safety check: ignition must be known off."
        action == DriveAction.AC_ON && snapshot.cableConnected != false ->
            "Disconnect the demo charging cable before starting AC."
        else -> null
    }

    /** Serial UI-thread API: reserves the operation synchronously before launching any work. */
    override fun runAction(action: DriveAction) {
        val current = state.value
        if (current.busy) return
        val vehicle = current.selectedVehicle
        if (current.step != EntryStep.DASHBOARD || vehicle == null) {
            error("Select an owned demo vehicle first."); return
        }
        if (vehicle.id in uncertainVehicles) {
            error("Previous simulated submission is unknown. No retry is allowed; sign out to reset the demo."); return
        }
        safetyError(vehicle.snapshot, action)?.let { error(it); return }
        val requestId = "demo-${++sequence}"
        val unavailable = current.simulateUnavailable
        val needsWake = vehicle.id !in connectedVehicles || unavailable
        operationSteps = listOf("Simulated contact with ${vehicle.model}")
        change { it.copy(error = null, demoWakeCount = 0, demoSubmissionCount = 0,
            operation = OperationState(action, OperationPhase.CONTACTING, operationSteps.last(), vehicle.id, requestId)) }
        pendingJob = scope.launch {
            try {
                pause(requestId, vehicle.id)
                if (needsWake) {
                    change { it.copy(demoWakeCount = 1) }
                    phase(OperationPhase.WAKING, "Simulated wake requested once")
                    pause(requestId, vehicle.id)
                }
                phase(OperationPhase.WAITING_READY, "Waiting for simulated readiness")
                pause(requestId, vehicle.id)
                if (unavailable || state.value.simulateUnavailable) {
                    finish(OperationPhase.FAILED, "Simulated unavailable — readiness timed out; nothing submitted")
                    return@launch
                }
                // Recheck mutable safety inputs at the last point before simulated submission.
                val snapshot = state.value.selectedVehicle?.snapshot ?: throw CancellationException()
                safetyError(snapshot, action)?.let {
                    finish(OperationPhase.FAILED, "Simulated safety refusal — $it; nothing submitted")
                    return@launch
                }
                connectedVehicles.add(vehicle.id)
                change { it.copy(demoSubmissionCount = 1) }
                phase(OperationPhase.SENDING, "Simulated submission (no network request)")
                pause(requestId, vehicle.id)
                change { s -> s.copy(vehicles = s.vehicles.map {
                    if (it.id == vehicle.id) it.copy(snapshot = applyAction(it.snapshot, action)) else it
                }) }
                finish(OperationPhase.SUCCESS, "Simulated ${action.title.lowercase()} completed — not a live vehicle result")
            } catch (_: CancellationException) {
                // Explicit cancellation/sign-out already updates state; never publish stale results.
            }
        }
    }

    private suspend fun pause(requestId: String, vehicleId: String) {
        delay(stepMillis.coerceAtLeast(1))
        val current = state.value
        if (current.operation.requestId != requestId || current.selectedVehicleId != vehicleId ||
            current.step != EntryStep.DASHBOARD || !current.busy) throw CancellationException()
    }
    private fun phase(phase: OperationPhase, message: String) {
        operationSteps = operationSteps + message
        change { it.copy(operation = it.operation.copy(phase = phase, message = message)) }
    }
    private fun finish(phase: OperationPhase, message: String) {
        val op = state.value.operation
        val vehicle = state.value.vehicles.firstOrNull { it.id == op.vehicleId } ?: return
        operationSteps = operationSteps + message
        val entry = ActivityEntry(op.requestId ?: return, vehicle.id, vehicle.model,
            op.action ?: return, now(), message, operationSteps.toList())
        change { it.copy(operation = op.copy(phase = phase, message = message), history = listOf(entry) + it.history) }
    }
    override fun cancelPendingAction() {
        if (!state.value.busy) return
        pendingJob?.cancel()
        pendingJob = null
        if (state.value.operation.phase == OperationPhase.SENDING) {
            state.value.operation.vehicleId?.let { uncertainVehicles.add(it) }
            finish(OperationPhase.UNKNOWN, "Simulated outcome unknown after submission — no retry or live observation")
        } else {
            finish(OperationPhase.CANCELLED, "Simulated operation cancelled before submission — nothing sent")
        }
    }
    private fun applyAction(snapshot: VehicleSnapshot, action: DriveAction): VehicleSnapshot {
        val updated = when (action) {
            DriveAction.LOCK -> snapshot.copy(locked = true)
            DriveAction.UNLOCK -> snapshot.copy(locked = false)
            DriveAction.AC_ON -> snapshot.copy(acOn = true)
            DriveAction.AC_OFF -> snapshot.copy(acOn = false)
            DriveAction.APPROACH_ON -> snapshot.copy(approachOn = true)
            DriveAction.APPROACH_OFF -> snapshot.copy(approachOn = false)
            DriveAction.HAZARD_ON -> snapshot.copy(hazardOn = true)
            DriveAction.HAZARD_OFF -> snapshot.copy(hazardOn = false)
            DriveAction.POSITION_ON -> snapshot.copy(positionOn = true)
            DriveAction.POSITION_OFF -> snapshot.copy(positionOn = false)
            DriveAction.HORN -> snapshot
        }
        return updated.copy(updatedAtMillis = now())
    }

    private fun demoVehicles(): List<DriveVehicle> = listOf(
        DriveVehicle("demo-tiago", "Tiago.ev", "My Tiago", "DEMO · 0001", snapshot(78, 182, 12480.0)),
        DriveVehicle("demo-nexon", "Nexon.ev", "Second vehicle", "DEMO · 0002", snapshot(86, 302, 6840.0))
    )
    private fun snapshot(battery: Int, range: Int, odometer: Double) = VehicleSnapshot(
        battery, range, odometer, true, false, false, true, true, true, false, now()
    )

    private var demoPin: String = "1234"

    override fun unlockApp() { change { it.copy(isAppLocked = false, lockError = null) } }
    override fun lockApp() { if (state.value.appLockEnabled && state.value.hasPinSet) change { it.copy(isAppLocked = true, lockError = null) } }
    override fun setAppLockEnabled(enabled: Boolean) { change { it.copy(appLockEnabled = enabled) } }
    override fun setBiometricsEnabled(enabled: Boolean) { change { it.copy(biometricsEnabled = enabled) } }
    override fun setPin(pin: String) {
        demoPin = pin
        change { it.copy(hasPinSet = pin.isNotEmpty(), appLockEnabled = true, lockError = null) }
    }
    override fun verifyPin(pin: String): Boolean {
        val ok = (pin == demoPin)
        if (ok) unlockApp() else change { it.copy(lockError = "Incorrect PIN. Try again.") }
        return ok
    }
    override fun setAmoledMode(enabled: Boolean) { change { it.copy(amoledMode = enabled) } }
    override fun setAccentColor(color: dev.nemesis.drive.model.AccentColor) { change { it.copy(accentColor = color, customAccentColor = color.hexDark) } }
    override fun setCustomAccentColor(color: Long) { change { it.copy(customAccentColor = color) } }
    override fun setShowMyLocation(enabled: Boolean) { change { it.copy(showMyLocation = enabled) } }
    override fun setDynamicColor(enabled: Boolean) { change { it.copy(dynamicColor = enabled) } }
    override fun clearLockError() { change { it.copy(lockError = null) } }
}

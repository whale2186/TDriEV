package dev.nemesis.drive.data

import dev.nemesis.drive.model.*
import dev.nemesis.drive.nativeapi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Main-thread state machine. A user gesture is the only source of OTP requests and commands. */
class NativeDriveRepository(
    private val scope: CoroutineScope,
    private val auth: OwnerAuth,
    private val service: OwnerVehicleService,
    private val ledger: NativeSafetyLedger,
    private val store: NativeSessionStore? = null,
    private val security: AppSecurityStore? = null,
    private val preferences: AppPreferencesStore? = null,
    private val now: () -> Long = System::currentTimeMillis
) : DriveRepository {
    private val mutable = MutableStateFlow(DriveUiState(isDemo = false, phone = "", authBusy = true))
    override val state: StateFlow<DriveUiState> = mutable.asStateFlow()
    private var session: NativeSession? = null
    private var challenge: OtpChallenge? = null
    private var challengeAt = 0L
    private var attempts = 0
    private var lastCodeAt: Long? = null
    private var epoch = 0L
    private var authJob: Job? = null
    private var readJob: Job? = null
    private var actionJob: Job? = null
    private var owned = emptyMap<String, OwnedVehicle>()
    private val lastReads = mutableMapOf<String, Long>()
    private var steps = emptyList<String>()

    private fun change(block: (DriveUiState) -> DriveUiState) { mutable.value = block(mutable.value) }
    private fun error(message: String) = change { it.copy(error = message) }
    private fun safeFailure(error: Exception) = if (error is NativeApiException) error.message
        ?: "Couldn't connect. Please try again." else "Couldn't connect. Please try again."

    init {
        val lockEnabled = security?.isAppLockEnabled() ?: false
        val bioEnabled = security?.isBiometricsEnabled() ?: false
        val hasPin = security?.hasPin() ?: false
        val showLoc = preferences?.isShowMyLocation() ?: false
        val amoled = preferences?.isAmoledMode() ?: true
        val dynColor = preferences?.isDynamicColor() ?: true
        val accent = preferences?.getAccentColor() ?: dev.nemesis.drive.model.AccentColor.TEAL
        val customColor = preferences?.getCustomAccentColor() ?: accent.hexDark
        val themeChoice = preferences?.getTheme() ?: dev.nemesis.drive.model.ThemeChoice.DARK
        val autoOpen = preferences?.isAutoOpenVehicle() ?: true
        val defaultVeh = preferences?.getDefaultVehicleId()

        change { it.copy(
            isAppLocked = lockEnabled && hasPin,
            appLockEnabled = lockEnabled,
            biometricsEnabled = bioEnabled,
            hasPinSet = hasPin,
            showMyLocation = showLoc,
            amoledMode = amoled,
            dynamicColor = dynColor,
            accentColor = accent,
            customAccentColor = customColor,
            theme = themeChoice,
            autoOpenVehicle = autoOpen,
            defaultVehicleId = defaultVeh
        ) }
        val generation = epoch
        authJob = scope.launch {
            try {
                val restored = auth.restore()
                currentCoroutineContext().ensureActive()
                if (generation != epoch) return@launch
                session = restored
                if (restored != null) {
                    change { it.copy(phone = restored.phone, step = EntryStep.GARAGE, authNotice = null) }
                    loadGarage(restored, generation)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (generation == epoch) error(safeFailure(failure)) }
            finally { if (generation == epoch) change { it.copy(authBusy = false, refreshing = false) } }
        }
    }

    override fun continueFromWelcome() {
        if (!state.value.authBusy && state.value.step == EntryStep.WELCOME) change { it.copy(step = EntryStep.PHONE, error = null) }
    }
    override fun setPhone(value: String) {
        if (!state.value.authBusy) change { it.copy(phone = value, error = null) }
    }
    override fun setOtp(value: String) {
        if (!state.value.authBusy) change { it.copy(otp = value, error = null) }
    }
    override fun setTermsAccepted(value: Boolean) { change { it.copy(termsAccepted = value) } }
    override fun setPrivacyAccepted(value: Boolean) { change { it.copy(privacyAccepted = value) } }

    override fun requestDemoCode() {
        val current = state.value
        if (current.step != EntryStep.PHONE || current.authBusy || session != null) return
        if (!current.phone.matches(Regex("[0-9]{10}"))) { error("Enter your registered ten-digit mobile number."); return }
        val previous = lastCodeAt
        if (previous != null && now() - previous < 60_000L) { error("Wait 60 seconds before requesting another SMS code."); return }
        lastCodeAt = now(); challenge = null; attempts = 0
        val generation = epoch
        change { it.copy(authBusy = true, error = null, authNotice = null) }
        authJob = scope.launch {
            try {
                val received = auth.requestCode(current.phone)
                currentCoroutineContext().ensureActive()
                if (generation != epoch || state.value.phone != current.phone) return@launch
                challenge = received; challengeAt = now()
                change { it.copy(step = EntryStep.OTP, otp = "", authNotice = null) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (generation == epoch) change { it.copy(error = safeFailure(failure), authNotice = null) } }
            finally { if (generation == epoch) change { it.copy(authBusy = false) } }
        }
    }

    override fun verifyDemoCode() {
        val current = state.value
        if (current.step != EntryStep.OTP || current.authBusy) return
        if (!current.termsAccepted || !current.privacyAccepted) { error("Accept the terms and privacy notice to continue."); return }
        val code = challenge
        if (code == null || code.phone != current.phone || now() - challengeAt !in 0..600_000L) {
            error("This challenge is unavailable or expired. Request a new SMS code."); return
        }
        if (!current.otp.matches(Regex("[0-9]{4,8}"))) { error("Enter the numeric SMS verification code."); return }
        if (attempts >= 3) { error("Three attempts reached. Request a fresh SMS code."); return }
        attempts++
        val generation = epoch
        change { it.copy(authBusy = true, error = null, otp = "", authNotice = null) }
        authJob = scope.launch {
            try {
                val loggedIn = auth.verifyCode(code, current.otp, current.termsAccepted, current.privacyAccepted)
                currentCoroutineContext().ensureActive()
                if (generation != epoch) return@launch
                session = loggedIn; challenge = null
                change { it.copy(step = EntryStep.GARAGE, authNotice = null) }
                loadGarage(loggedIn, generation)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (generation == epoch) {
                    // Token exchange can succeed even if the subsequent legacy integration fails.
                    val partial = runCatching { auth.restore() }.getOrNull()
                    if (generation == epoch && partial != null) {
                        session = partial; challenge = null
                        change { it.copy(step = EntryStep.GARAGE, authNotice = null, error = safeFailure(failure)) }
                    } else change { it.copy(error = safeFailure(failure), authNotice = null) }
                }
            } finally { if (generation == epoch) change { it.copy(authBusy = false, refreshing = false) } }
        }
    }

    private suspend fun loadGarage(active: NativeSession, generation: Long) {
        val vehicles = service.vehicles(active)
        currentCoroutineContext().ensureActive()
        if (generation != epoch || session !== active) return
        val grouped = vehicles.groupBy { it.vehicle.id }
        if (grouped.any { (id, entries) -> id.isBlank() || entries.size != 1 }) throw NativeApiException("Owned vehicle identity is missing or ambiguous.")
        owned = vehicles.associateBy { it.vehicle.id }
        change { state ->
            val cached = state.vehicles.associateBy { it.id }
            state.copy(vehicles = vehicles.map { entry ->
                val diskSnapshot = store?.loadSnapshot(entry.vehicle.id)?.let { json ->
                    runCatching { com.google.gson.Gson().fromJson(json, VehicleSnapshot::class.java) }.getOrNull()
                }
                val snapshotToUse = cached[entry.vehicle.id]?.snapshot?.takeIf { it.updatedAtMillis != null }
                    ?: diskSnapshot
                    ?: entry.vehicle.snapshot
                entry.vehicle.copy(snapshot = snapshotToUse)
            }, selectedVehicleId = state.selectedVehicleId?.takeIf(owned::containsKey), authNotice = null)
        }
        val targetId = if (owned.size == 1) {
            owned.keys.firstOrNull()
        } else if (state.value.autoOpenVehicle && state.value.defaultVehicleId != null) {
            state.value.defaultVehicleId?.takeIf(owned::containsKey)
        } else null
        val autoTarget = targetId?.let(owned::get)
        if (autoTarget != null) {
            scope.launch {
                service.prewake(active, autoTarget)
            }
            selectVehicle(autoTarget.vehicle.id)
        }
    }

    override fun refreshGarage() {
        val active = session ?: return
        if (state.value.authBusy || state.value.refreshing || state.value.busy) return
        val generation = epoch
        change { it.copy(refreshing = true, error = null) }
        readJob = scope.launch {
            try { loadGarage(active, generation) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (generation == epoch) error(safeFailure(failure)) }
            finally { if (generation == epoch) change { it.copy(refreshing = false) } }
        }
    }
    override fun selectVehicle(id: String) {
        if (state.value.step !in setOf(EntryStep.GARAGE, EntryStep.DASHBOARD) || id !in owned) { error("Select a vehicle returned by your authenticated owner account."); return }
        cancelPendingAction(); readJob?.cancel()
        val unresolved = runCatching { ledger.isBlocked(id) }.getOrDefault(false)
        change { it.copy(selectedVehicleId = id, step = EntryStep.DASHBOARD, operation = OperationState(), refreshing = false,
            error = null, authNotice = null, previousRequestUnresolved = unresolved) }
        refreshTelemetry()
        val active = session
        val vehicle = owned[id]
        if (active != null && vehicle != null) {
            scope.launch {
                service.prewake(active, vehicle)
            }
        }
    }
    override fun refreshTelemetry() {
        refreshTelemetryInternal(silent = false)
    }

    private fun refreshTelemetryInternal(silent: Boolean = false) {
        val active = session ?: return
        val vehicle = owned[state.value.selectedVehicleId] ?: return
        if (state.value.busy || state.value.authBusy) return
        if (readJob?.isActive == true) return
        val previous = lastReads[vehicle.vehicle.id]
        if (previous != null && now() - previous < 2_000L) { return }
        lastReads[vehicle.vehicle.id] = now()
        val generation = epoch
        if (!silent) {
            change { it.copy(refreshing = true, error = null) }
        }
        readJob = scope.launch {
            try {
                val snapshot = service.snapshot(active, vehicle)
                currentCoroutineContext().ensureActive()
                if (generation == epoch && state.value.selectedVehicleId == vehicle.vehicle.id && session === active) {
                    val serverTimestamp = snapshot.updatedAtMillis ?: 0L
                    val currentSnap = state.value.vehicles.firstOrNull { it.id == vehicle.vehicle.id }?.snapshot
                    val isStale = serverTimestamp < lastCommandSentAt && (now() - lastCommandSentAt < 60_000L)
                    val resolvedSnapshot = if (isStale && currentSnap != null) {
                        snapshot.copy(
                            approachOn = currentSnap.approachOn,
                            hazardOn = currentSnap.hazardOn,
                            positionOn = currentSnap.positionOn
                        )
                    } else snapshot

                    val json = com.google.gson.Gson().toJson(resolvedSnapshot)
                    store?.saveSnapshot(vehicle.vehicle.id, json)
                    val updatedOwnedVehicle = vehicle.copy(vehicle = vehicle.vehicle.copy(snapshot = resolvedSnapshot))
                    owned = owned + (vehicle.vehicle.id to updatedOwnedVehicle)
                    change { it.copy(vehicles = it.vehicles.map { old -> if (old.id == vehicle.vehicle.id) old.copy(snapshot = resolvedSnapshot) else old }) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (generation == epoch && state.value.selectedVehicleId == vehicle.vehicle.id && !silent) error(safeFailure(failure)) }
            finally { if (generation == epoch && state.value.selectedVehicleId == vehicle.vehicle.id && !silent) change { it.copy(refreshing = false) } }
        }
    }

    private var lastCommandSentAt = 0L
    private val commandQueue = ArrayDeque<DriveAction>()
    private var queueProcessorJob: Job? = null

    override fun runAction(action: DriveAction) {
        val current = state.value
        if (current.step != EntryStep.DASHBOARD || current.authBusy) return

        synchronized(commandQueue) {
            if (action in commandQueue || (current.busy && current.operation.action == action)) return
            commandQueue.addLast(action)
        }
        change { it.copy(queuedActions = it.queuedActions + action) }
        if (queueProcessorJob?.isActive != true) {
            queueProcessorJob = scope.launch {
                while (true) {
                    val next = synchronized(commandQueue) {
                        if (commandQueue.isNotEmpty()) commandQueue.removeFirst() else null
                    } ?: break

                    lastCommandSentAt = now()
                    executeSingleAction(next)
                    actionJob?.join()
                    change { it.copy(queuedActions = it.queuedActions - next) }
                }
            }
        }
    }

    private fun executeSingleAction(action: DriveAction) {
        val current = state.value
        val active = session ?: return
        val vehicle = owned[current.selectedVehicleId] ?: return
        if (current.step != EntryStep.DASHBOARD || current.authBusy) return
        if (state.value.refreshing) {
            readJob?.cancel()
            readJob = null
            change { it.copy(refreshing = false) }
        }
        try {
            if (ledger.isBlocked(vehicle.vehicle.id)) {
                change { it.copy(previousRequestUnresolved = true, error = "Review the previous request before sending another command.") }; return
            }
        } catch (_: Exception) { error("The command safety journal is unavailable. No command was sent."); return }
        val requestId = UUID.randomUUID().toString()
        val generation = epoch
        val intentCreatedAt = now()
        steps = listOf("Contacting the selected owned vehicle")
        change { it.copy(error = null, operation = OperationState(action, OperationPhase.CONTACTING, steps.last(), vehicle.vehicle.id, requestId)) }
        actionJob = scope.launch {
            val guard: suspend () -> Unit = {
                currentCoroutineContext().ensureActive()
                if (epoch != generation || session !== active || state.value.selectedVehicleId != vehicle.vehicle.id ||
                    state.value.step != EntryStep.DASHBOARD || state.value.operation.requestId != requestId || !state.value.busy ||
                    now() - intentCreatedAt !in 0..120_000L) throw CancellationException()
            }
            try {
                guard()
                val result = service.run(active, vehicle, action, current.acDurationMinutes, requestId, guard) { phase, message ->
                    if (generation == epoch && state.value.operation.requestId == requestId && state.value.busy &&
                        phase in setOf(OperationPhase.CONTACTING, OperationPhase.WAKING, OperationPhase.WAITING_READY, OperationPhase.SENDING)) {
                        steps = steps + message
                        change { it.copy(operation = it.operation.copy(phase = phase, message = message)) }
                    }
                }
                guard()
                finish(result.phase, result.message)
            } catch (cancel: CancellationException) {
                if (generation == epoch && state.value.operation.requestId == requestId && state.value.busy) {
                    val uncertain = runCatching { ledger.isBlocked(vehicle.vehicle.id) }.getOrDefault(true)
                    finish(if (uncertain) OperationPhase.UNKNOWN else OperationPhase.CANCELLED,
                        if (uncertain) "Outcome unknown after interruption. No request will be retried. Check the vehicle and official app."
                        else "The action expired or was cancelled before physical submission. Tap again only when you are ready.")
                }
                throw cancel
            }
            catch (failure: Exception) {
                if (generation == epoch && state.value.operation.requestId == requestId && state.value.busy) {
                    val uncertain = runCatching { ledger.isBlocked(vehicle.vehicle.id) }.getOrDefault(true)
                    finish(if (uncertain) OperationPhase.UNKNOWN else OperationPhase.FAILED,
                        if (uncertain) "Outcome unknown. No command retry will be made; inspect the vehicle and official app." else safeFailure(failure))
                }
            }
        }
    }
    private fun finish(phase: OperationPhase, message: String) {
        val operation = state.value.operation
        val vehicle = owned[operation.vehicleId] ?: return
        val action = operation.action ?: return
        steps = steps + message
        val entry = ActivityEntry(operation.requestId ?: return, vehicle.vehicle.id, vehicle.vehicle.model, action, now(), message, steps)
        val unresolved = runCatching { ledger.isBlocked(vehicle.vehicle.id) }.getOrDefault(false)

        val currentSnap = state.value.vehicles.firstOrNull { it.id == vehicle.vehicle.id }?.snapshot ?: vehicle.vehicle.snapshot
        val confirmedSnap = if (phase == OperationPhase.SUCCESS) {
            when (action) {
                DriveAction.APPROACH_ON -> currentSnap.copy(approachOn = true)
                DriveAction.APPROACH_OFF -> currentSnap.copy(approachOn = false)
                DriveAction.HAZARD_ON -> currentSnap.copy(hazardOn = true)
                DriveAction.HAZARD_OFF -> currentSnap.copy(hazardOn = false)
                DriveAction.POSITION_ON -> currentSnap.copy(positionOn = true)
                DriveAction.POSITION_OFF -> currentSnap.copy(positionOn = false)
                else -> currentSnap
            }
        } else currentSnap

        if (phase == OperationPhase.SUCCESS && confirmedSnap !== currentSnap) {
            val updatedOwned = vehicle.copy(vehicle = vehicle.vehicle.copy(snapshot = confirmedSnap))
            owned = owned + (vehicle.vehicle.id to updatedOwned)
            store?.saveSnapshot(vehicle.vehicle.id, com.google.gson.Gson().toJson(confirmedSnap))
        }

        change { it.copy(
            vehicles = it.vehicles.map { v -> if (v.id == vehicle.vehicle.id) v.copy(snapshot = confirmedSnap) else v },
            operation = operation.copy(phase = phase, message = message),
            history = (listOf(entry) + it.history).take(100),
            previousRequestUnresolved = unresolved
        ) }
        if (phase == OperationPhase.SUCCESS) {
            scope.launch {
                delay(2500)
                change {
                    if (it.operation.phase == OperationPhase.SUCCESS) {
                        it.copy(operation = OperationState())
                    } else it
                }
                delay(1000)
                if (state.value.selectedVehicleId == vehicle.vehicle.id && !state.value.busy) {
                    lastReads.remove(vehicle.vehicle.id)
                    refreshTelemetryInternal(silent = true)
                }
            }
        }
    }
    override fun resolvePreviousRequest(vehicleId: String, acknowledged: Boolean) {
        val current = state.value
        if (!acknowledged || session == null || current.step != EntryStep.DASHBOARD ||
            current.selectedVehicleId != vehicleId || vehicleId !in owned || current.busy || current.refreshing || current.authBusy) return
        if (NativeVehicleService.isActionActive(vehicleId)) { error("A request is still processing. Please wait."); return }
        try {
            ledger.clearPending(vehicleId)
            check(!ledger.isBlocked(vehicleId))
            change { it.copy(previousRequestUnresolved = false, error = null,
                operation = if (it.operation.phase == OperationPhase.UNKNOWN) OperationState() else it.operation) }
            // Owner review changes only the local guard. Never wake, preflight or replay an action.
        } catch (_: Exception) { error("Couldn't resolve the previous request. Please try again.") }
    }
    override fun onBackground() {
        // Once submitted, only bounded status reads remain. Leaving the app must not
        // fabricate an unknown result or cause a later physical-command replay.
        if (state.value.operation.phase != OperationPhase.SENDING) cancelPendingAction()
    }
    override fun cancelPendingAction() {
        synchronized(commandQueue) { commandQueue.clear() }
        queueProcessorJob?.cancel(); queueProcessorJob = null
        if (!state.value.busy) return
        actionJob?.cancel(); actionJob = null
        val id = state.value.operation.vehicleId
        val uncertain = id != null && runCatching { ledger.isBlocked(id) }.getOrDefault(true)
        finish(if (uncertain) OperationPhase.UNKNOWN else OperationPhase.CANCELLED,
            if (uncertain) "Outcome unknown after submission. The request was not retried; inspect the vehicle and official app."
            else "Pending action cancelled. No physical command was submitted.")
    }
    override fun openGarage() {
        if (session == null) return
        cancelPendingAction(); readJob?.cancel()
        change { it.copy(step = EntryStep.GARAGE, refreshing = false, error = null, authNotice = null) }
    }
    override fun setTab(tab: MainTab) { change { it.copy(tab = tab) } }
    override fun setAcDuration(minutes: Int) {
        if (state.value.busy) return
        if (minutes !in 5..20) { error("Climate duration must be 5–20 minutes."); return }
        change { it.copy(acDurationMinutes = minutes, error = null) }
    }
    override fun prewake() {
        val active = session ?: return
        val vehicle = (state.value.selectedVehicleId?.takeIf(owned::containsKey) ?: owned.keys.firstOrNull())?.let(owned::get) ?: return
        scope.launch {
            service.prewake(active, vehicle)
        }
    }
    override fun setAutoOpenVehicle(enabled: Boolean) {
        preferences?.setAutoOpenVehicle(enabled)
        change { it.copy(autoOpenVehicle = enabled) }
    }
    override fun setDefaultVehicle(vehicleId: String?) {
        preferences?.setDefaultVehicleId(vehicleId)
        change { it.copy(defaultVehicleId = vehicleId) }
    }
    override fun setDynamicColor(enabled: Boolean) {
        preferences?.setDynamicColor(enabled)
        change { it.copy(dynamicColor = enabled) }
    }
    override fun setAmoledMode(enabled: Boolean) {
        preferences?.setAmoledMode(enabled)
        change { it.copy(amoledMode = enabled) }
    }
    override fun setAccentColor(color: dev.nemesis.drive.model.AccentColor) {
        preferences?.setAccentColor(color)
        preferences?.setCustomAccentColor(color.hexDark)
        change { it.copy(accentColor = color, customAccentColor = color.hexDark) }
    }
    override fun setCustomAccentColor(color: Long) {
        preferences?.setCustomAccentColor(color)
        change { it.copy(customAccentColor = color) }
    }
    override fun setTheme(theme: dev.nemesis.drive.model.ThemeChoice) {
        preferences?.setTheme(theme)
        change { it.copy(theme = theme) }
    }
    override fun setShowMyLocation(enabled: Boolean) {
        preferences?.setShowMyLocation(enabled)
        change { it.copy(showMyLocation = enabled) }
    }
    override fun updateUserLocation(lat: Double, lon: Double) {
        change { it.copy(userLatitude = lat, userLongitude = lon) }
    }
    override fun clearHistory() { change { it.copy(history = emptyList()) } }
    override fun signOut() {
        cancelPendingAction(); epoch++
        authJob?.cancel(); readJob?.cancel(); actionJob?.cancel()
        session = null; challenge = null; owned = emptyMap(); lastReads.clear(); steps = emptyList()
        val eraseError = runCatching { auth.clear() }.exceptionOrNull()
        change { DriveUiState(isDemo = false, phone = "", theme = it.theme,
            error = if (eraseError != null) "Local session could not be erased; close the app and clear its storage before handing over the phone." else null) }
        // The safety journal is deliberately not cleared by sign-out or activity deletion.
    }
    override fun back() {
        if (state.value.authBusy) return
        cancelPendingAction(); readJob?.cancel()
        change { it.copy(error = null, refreshing = false, authNotice = null, step = when (it.step) {
            EntryStep.OTP -> EntryStep.PHONE
            EntryStep.PHONE -> EntryStep.WELCOME
            EntryStep.GARAGE -> if (it.selectedVehicle != null) EntryStep.DASHBOARD else EntryStep.GARAGE
            EntryStep.DASHBOARD -> EntryStep.GARAGE
            else -> EntryStep.WELCOME
        }) }
    }

    override fun unlockApp() {
        change { it.copy(isAppLocked = false, lockError = null) }
    }

    override fun lockApp() {
        if (state.value.appLockEnabled && state.value.hasPinSet) {
            change { it.copy(isAppLocked = true, lockError = null) }
        }
    }

    override fun setAppLockEnabled(enabled: Boolean) {
        security?.setAppLockEnabled(enabled)
        change { it.copy(appLockEnabled = enabled) }
    }

    override fun setBiometricsEnabled(enabled: Boolean) {
        security?.setBiometricsEnabled(enabled)
        change { it.copy(biometricsEnabled = enabled) }
    }

    override fun setPin(pin: String) {
        security?.setPin(pin)
        change { it.copy(hasPinSet = pin.isNotEmpty(), appLockEnabled = true, lockError = null) }
    }

    override fun verifyPin(pin: String): Boolean {
        val ok = security?.verifyPin(pin) ?: false
        if (ok) {
            unlockApp()
        } else {
            change { it.copy(lockError = "Incorrect PIN. Try again.") }
        }
        return ok
    }

    override fun clearLockError() {
        change { it.copy(lockError = null) }
    }
}

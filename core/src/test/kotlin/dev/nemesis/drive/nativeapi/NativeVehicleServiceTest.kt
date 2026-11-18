package dev.nemesis.drive.nativeapi

import com.google.gson.JsonObject
import dev.nemesis.drive.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test

class NativeVehicleServiceTest {
    private val vin = "M".repeat(17)
    private val clock = 1_800_000_000_000L
    private fun session() = NativeSession("9000000000", mutableMapOf("x_api_key" to "fixture-key", "client_secret_value" to "fixture-secret"), JsonObject(), nativeJson("accessToken" to "fixture-token"), nativeJson("fetchCustomerDetails" to mapOf("brandData" to mapOf("crmMapInfo" to listOf(mapOf("contactId" to "fixture-crm"))))))
    private class Auth : OwnerAuth {
        var refreshes = 0
        override suspend fun requestCode(phone: String): OtpChallenge = error("unused")
        override suspend fun verifyCode(challenge: OtpChallenge, otp: String, terms: Boolean, privacy: Boolean): NativeSession = error("unused")
        override suspend fun restore(): NativeSession? = null
        override suspend fun refresh(session: NativeSession): NativeSession { refreshes++; session.legacy = nativeJson("accessToken" to "refreshed"); return session }
        override fun clear() {}
    }
    private class Ledger : NativeSafetyLedger {
        val pending = mutableSetOf<String>()
        override fun isBlocked(vehicleId: String) = vehicleId in pending
        override fun markPending(vehicleId: String, action: DriveAction, requestId: String) { pending += vehicleId }
        override fun clearPending(vehicleId: String) { pending -= vehicleId }
    }
    private fun garage(vararg entries: JsonObject) = nativeJson("results" to mapOf("userVehicleList" to entries.toList()))
    private fun entry(v: String = vin, model: String = "Fixture EV") = nativeJson("chassisNumber" to v, "modelLabel" to model)
    private fun health(age: Long = 0): JsonObject = nativeJson("vehicleId" to vin,
        "eventDateTime" to java.time.Instant.ofEpochMilli(clock - age).toString(),
        "leftFrontDoorOpenFlag" to false, "rightFrontDoorOpenFlag" to false,
        "leftRearDoorOpenFlag" to false, "rightRearDoorOpenFlag" to false,
        "bonnetOpenFlag" to false, "tailGateOpenFlag" to false, "crankOn" to false, "hvChargerConnectFlag" to false)
    private fun wrap(result: JsonObject) = nativeJson("results" to result)
    @Test fun legacyRecoversWithOneTraceLinkedWakeAndOnePhysicalSubmit() = runBlocking {
        val calls = mutableListOf<NativeRequest>()
        val ledger = Ledger()
        var logs = 0
        val service = NativeVehicleService(NativeTransport {
            calls += it
            when (it.url.substringAfterLast('/')) {
                "get-user-vehicles-info-mobile" -> garage(entry())
                "vehicle-health" -> wrap(health())
                "command-log" -> { logs++; wrap(nativeJson("vehicleId" to vin, "connectedStatus" to (logs > 1), "commandTraceId" to "trace-$logs")) }
                "wake-up-device" -> { assertEquals("trace-1", it.headers["commandTraceId"]); JsonObject() }
                "check-device-status" -> wrap(nativeJson("vehicleId" to vin, "parameterValue" to true))
                "vehicle-command" -> {
                    assertTrue(ledger.isBlocked(vin))
                    assertEquals("trace-2", it.body!!.text("commandTraceId"))
                    wrap(nativeJson("vehicleId" to vin, "correlationId" to 123, "status" to "Accepted"))
                }
                "vehicle-command-status" -> wrap(nativeJson("vehicleId" to vin, "correlationId" to "123", "status" to "Success"))
                else -> error("Unexpected route")
            }
        }, Auth(), ledger, { clock }, {})
        val s = session(); val car = service.vehicles(s).single()
        var guards = 0
        val phases = mutableListOf<OperationPhase>()
        val result = service.run(s, car, DriveAction.LOCK, 10, "request-123", { guards++ }) { p, _ -> phases += p }
        assertEquals(OperationPhase.SUCCESS, result.phase)
        assertEquals("Tata reported success; refresh to observe vehicle state", result.message)
        assertFalse(ledger.isBlocked(vin))
        assertEquals(1, calls.count { it.url.endsWith("wake-up-device") })
        assertEquals(1, calls.count { it.url.endsWith("vehicle-command") })
        assertTrue(guards >= calls.size - 1)
        assertTrue(phases.containsAll(listOf(OperationPhase.WAKING, OperationPhase.WAITING_READY, OperationPhase.SENDING)))
    }
    @Test fun rcV2OldHealthRecoversWithStandaloneWakeWithoutLegacyCommandLog() = runBlocking {
        val calls = mutableListOf<NativeRequest>()
        var healthCalls = 0
        var deviceCalls = 0
        val service = NativeVehicleService(NativeTransport {
            calls += it
            when (it.url.substringAfterLast('/')) {
                "get-user-vehicles-info-mobile" -> garage(entry())
                "vehicle-health" -> { healthCalls++; wrap(health(if (healthCalls == 1) 300_000 else 0)) }
                "check-device-status" -> { deviceCalls++; wrap(nativeJson("vehicleId" to vin, "parameterValue" to (deviceCalls > 1))) }
                "wake-up-device" -> { assertEquals("null", it.headers["commandTraceId"]); JsonObject() }
                "process-remote-command" -> {
                    assertFalse(it.headers.containsKey("vehicleId"))
                    assertFalse(it.headers.containsKey("Cache-Control"))
                    assertEquals("RemoteHazardCommandLampOn", it.body!!.text("commandName"))
                    assertEquals(vin, it.body!!.text("vehicleId"))
                    wrap(nativeJson("vehicleId" to vin, "commandId" to "lamp-1", "status" to "PENDING"))
                }
                "get-remote-command-status" -> {
                    assertEquals("lamp-1", it.body!!.text("commandId"))
                    wrap(nativeJson("vehicleId" to vin, "commandId" to "lamp-1", "status" to "SUCCESS"))
                }
                else -> error("No guessed route or legacy command-log allowed")
            }
        }, Auth(), Ledger(), { clock }, {})
        val s = session(); val car = service.vehicles(s).single()
        assertEquals(OperationPhase.SUCCESS, service.run(s, car, DriveAction.HAZARD_ON, 10, "request-123", {}) { _, _ -> }.phase)
        assertEquals(1, calls.count { it.url.endsWith("wake-up-device") })
        assertEquals(1, calls.count { it.url.endsWith("process-remote-command") })
    }
    private inner class Rig(
        val h: JsonObject = health(),
        val logResult: JsonObject = nativeJson("vehicleId" to vin, "commandTraceId" to "trace-ok", "connectedStatus" to true),
        val route: suspend (NativeRequest) -> JsonObject? = { null },
        val wait: suspend (Long) -> Unit = {}
    ) {
        val calls = mutableListOf<NativeRequest>()
        val auth = Auth()
        val ledger = Ledger()
        val s = session()
        val service = NativeVehicleService(NativeTransport { request ->
            calls += request
            route(request) ?: when (request.url.substringAfterLast('/')) {
                "get-user-vehicles-info-mobile" -> garage(entry())
                "vehicle-health" -> wrap(h)
                "vehicle-state" -> wrap(nativeJson("vehicleId" to vin, "eventDateTime" to java.time.Instant.ofEpochMilli(clock - 55_000).toString()))
                "command-log" -> wrap(logResult)
                "vehicle-command" -> wrap(nativeJson("vehicleId" to vin, "correlationId" to "123", "status" to "Success"))
                "process-remote-command" -> wrap(nativeJson("vehicleId" to vin, "commandId" to "lamp-1", "status" to "SUCCESS"))
                else -> error("Unexpected request")
            }
        }, auth, ledger, { clock }, wait)
        suspend fun car() = service.vehicles(s).single()
        suspend fun run(action: DriveAction = DriveAction.LOCK, guard: suspend () -> Unit = {}): NativeCommandResult =
            service.run(s, car(), action, 10, "request-123", guard) { _, _ -> }
        fun physicalCount() = calls.count { it.url.endsWith("vehicle-command") || it.url.endsWith("process-remote-command") }
    }
    @Test fun separateServiceInstancesCannotOverlapActionsOnTheSameVehicle() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = Rig(route = { request ->
            if (request.url.endsWith("/vehicle-command")) {
                entered.complete(Unit)
                release.await()
                wrap(nativeJson("vehicleId" to vin, "correlationId" to "123", "status" to "Success"))
            } else null
        })
        val running = async(start = CoroutineStart.UNDISPATCHED) { first.run() }
        entered.await()
        val second = Rig()
        val result = second.run()
        release.complete(Unit)
        assertEquals(OperationPhase.SUCCESS, running.await().phase)
        assertEquals(OperationPhase.FAILED, result.phase)
        assertEquals(0, second.physicalCount())
    }

    @Test fun garageDisplaysRecoveredModelAndRegistrationInsteadOfBlankVehicleNames() = runBlocking {
        val api = NativeVehicleService(NativeTransport {
            garage(entry().apply { addProperty("registrationNumber", "TEST 1234") })
        }, Auth(), Ledger())
        val vehicle = api.vehicles(session()).single().vehicle
        assertEquals("Fixture EV", vehicle.nickname)
        assertEquals("TEST 1234", vehicle.registration)
    }
    @Test fun rcV2AllowsNativeResponsesWithoutOptionalVehicleIdAndBindsStatusByCommandId() = runBlocking {
        val rig = Rig(route = { request -> when (request.url.substringAfterLast('/')) {
            "process-remote-command" -> wrap(nativeJson("commandId" to "lamp-without-vin", "status" to "PENDING"))
            "get-remote-command-status" -> wrap(nativeJson("commandId" to "lamp-without-vin", "status" to "SUCCESS"))
            else -> null
        } })
        assertEquals(OperationPhase.SUCCESS, rig.run(DriveAction.HAZARD_ON).phase)
        assertEquals(1, rig.physicalCount())
        assertFalse(rig.ledger.isBlocked(vin))
    }
    @Test fun garageDoesNotInventTelemetryForUnobservedVehicle() = runBlocking {
        val state = Rig().car().vehicle.snapshot
        assertNull(state.batteryPercent)
        assertNull(state.acOn)
        assertNull(state.approachOn)
        assertNull(state.hazardOn)
        assertNull(state.positionOn)
    }
    @Test fun everySupportedActionUsesOnlyItsDocumentedProtocol() = runBlocking {
        val expected = mapOf(DriveAction.LOCK to "LockVehicleCommand", DriveAction.UNLOCK to "UnlockVehicleCommand",
            DriveAction.AC_ON to "StartAcCommand", DriveAction.AC_OFF to "StopAcCommand", DriveAction.HORN to "RemoteHonkCommand",
            DriveAction.APPROACH_ON to "RemoteApproachOnCommand", DriveAction.APPROACH_OFF to "RemoteApproachOffCommand",
            DriveAction.HAZARD_ON to "RemoteHazardCommandLampOn", DriveAction.HAZARD_OFF to "RemoteHazardCommandLampOff",
            DriveAction.POSITION_ON to "RemotePositionCommandLampOn", DriveAction.POSITION_OFF to "RemotePositionCommandLampOff")
        for ((action, command) in expected) {
            val rig = Rig()
            assertEquals(action.name, OperationPhase.SUCCESS, rig.run(action).phase)
            assertEquals(1, rig.physicalCount())
            val physical = rig.calls.last()
            val v2 = action in setOf(DriveAction.HAZARD_ON, DriveAction.HAZARD_OFF, DriveAction.POSITION_ON, DriveAction.POSITION_OFF)
            if (v2) {
                assertTrue(physical.url.endsWith("process-remote-command"))
                assertEquals(command, physical.body!!.text("commandName"))
                assertEquals(java.time.Instant.ofEpochMilli(clock).toString(), physical.body!!.text("originDateTime"))
                assertFalse(rig.calls.any { it.url.endsWith("command-log") })
            } else {
                val log = rig.calls.single { it.url.endsWith("command-log") }
                assertEquals("{$command:true}", log.body!!.text("metadata"))
                assertEquals(java.time.Instant.ofEpochMilli(clock).atZone(java.time.ZoneId.of("Asia/Kolkata")).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")), log.body!!.text("originDateTime"))
                assertEquals("fixture-secret", log.headers["client_secret"])
                assertEquals(command, physical.headers["command-name"])
                assertEquals(if (action == DriveAction.AC_ON) "10" else null, physical.body!!.objectValue("config")!!.text("settimer"))
            }
            assertFalse(rig.ledger.isBlocked(vin))
        }
    }
    @Test fun safetyRejectsUnknownClosuresCableMissingTimestampAndFutureEvent() = runBlocking {
        val cases = listOf<Pair<JsonObject, DriveAction>>(
            health().apply { remove("leftFrontDoorOpenFlag") } to DriveAction.LOCK,
            health().apply { addProperty("bonnetOpenFlag", "false") } to DriveAction.LOCK,
            health().apply { addProperty("crankOn", true) } to DriveAction.LOCK,
            health().apply { remove("hvChargerConnectFlag") } to DriveAction.AC_ON,
            health().apply { addProperty("hvChargerConnectFlag", "false") } to DriveAction.AC_ON,
            health().apply { remove("eventDateTime") } to DriveAction.LOCK,
            health(-30_001) to DriveAction.LOCK,
            health().apply { addProperty("vehicleId", "N".repeat(17)) } to DriveAction.LOCK)
        for ((h, action) in cases) {
            val rig = Rig(h = h)
            assertEquals(OperationPhase.FAILED, rig.run(action).phase)
            assertEquals(0, rig.physicalCount())
            assertFalse(rig.calls.any { it.url.endsWith("command-log") || it.url.endsWith("wake-up-device") })
        }
    }
    @Test fun unknownReadinessOrInvalidTraceNeverTriggersWakeOrPhysical() = runBlocking {
        for (value in listOf<Any?>(null, "false", 0)) {
            val rig = Rig(logResult = nativeJson("vehicleId" to vin, "connectedStatus" to value, "commandTraceId" to "trace-ok"))
            assertEquals(OperationPhase.FAILED, rig.run().phase)
            assertEquals(0, rig.physicalCount())
            assertFalse(rig.calls.any { it.url.endsWith("wake-up-device") })
        }
        for (trace in listOf<Any?>(null, "bad trace", true, "x".repeat(201))) {
            val rig = Rig(logResult = nativeJson("vehicleId" to vin, "connectedStatus" to false, "commandTraceId" to trace))
            assertEquals(OperationPhase.FAILED, rig.run().phase)
            assertFalse(rig.calls.any { it.url.endsWith("wake-up-device") })
        }
    }
    @Test fun uncertainSubmissionsAreNeverRetriedAndRemainPersistentlyBlocked() = runBlocking {
        for (failure in listOf("timeout", "401", "wrong-vehicle", "missing-id", "cancel", "server-timeout")) {
            val rig = Rig(route = {
                if (!it.url.endsWith("vehicle-command")) null else when (failure) {
                    "timeout" -> throw java.io.IOException("fixture timeout")
                    "401" -> throw NativeApiException("fixture unauthorized", 401)
                    "cancel" -> throw kotlinx.coroutines.CancellationException("fixture cancellation")
                    "wrong-vehicle" -> wrap(nativeJson("vehicleId" to "N".repeat(17), "correlationId" to "123", "status" to "Success"))
                    "missing-id" -> wrap(nativeJson("vehicleId" to vin, "status" to "Success"))
                    else -> wrap(nativeJson("vehicleId" to vin, "correlationId" to "123", "status" to "Timed out"))
                }
            })
            val car = rig.car()
            assertEquals(failure, OperationPhase.UNKNOWN, rig.service.run(rig.s, car, DriveAction.LOCK, 10, "request-123", {}) { _, _ -> }.phase)
            assertEquals(1, rig.physicalCount())
            assertTrue(rig.ledger.isBlocked(vin))
            val before = rig.calls.size
            assertEquals(OperationPhase.FAILED, rig.service.run(rig.s, car, DriveAction.LOCK, 10, "request-456", {}) { _, _ -> }.phase)
            assertEquals(before, rig.calls.size)
            assertEquals(0, rig.auth.refreshes)
        }
    }
    @Test fun wrongStatusCorrelationRetainsLedgerAndStopsPolling() = runBlocking {
        val rig = Rig(route = {
            when (it.url.substringAfterLast('/')) {
                "vehicle-command" -> wrap(nativeJson("vehicleId" to vin, "correlationId" to "123", "status" to "Accepted"))
                "vehicle-command-status" -> wrap(nativeJson("vehicleId" to vin, "correlationId" to "456", "status" to "Success"))
                else -> null
            }
        })
        assertEquals(OperationPhase.UNKNOWN, rig.run().phase)
        assertTrue(rig.ledger.isBlocked(vin))
        assertEquals(1, rig.calls.count { it.url.endsWith("vehicle-command-status") })
        assertEquals(1, rig.physicalCount())
    }
    @Test fun commandStatusPollingIsBoundedToEightFiveSecondWaits() = runBlocking {
        val waits = mutableListOf<Long>()
        val rig = Rig(route = {
            if (it.url.endsWith("vehicle-command") || it.url.endsWith("vehicle-command-status"))
                wrap(nativeJson("vehicleId" to vin, "correlationId" to "123", "status" to "In Progress")) else null
        }, wait = { waits += it })
        assertEquals(OperationPhase.UNKNOWN, rig.run().phase)
        assertEquals(List(8) { 5_000L }, waits)
        assertEquals(8, rig.calls.count { it.url.endsWith("vehicle-command-status") })
        assertTrue(rig.ledger.isBlocked(vin))
    }
    @Test fun cancellationDuringWakeNeverResumesOriginalPhysicalAction() = runBlocking {
        var cancelled = false
        val rig = Rig(logResult = nativeJson("vehicleId" to vin, "connectedStatus" to false, "commandTraceId" to "trace-ok"),
            route = { if (it.url.endsWith("wake-up-device")) JsonObject() else null }, wait = { cancelled = true })
        val outcome = rig.run { if (cancelled) throw kotlinx.coroutines.CancellationException("selection changed") }
        assertEquals(OperationPhase.CANCELLED, outcome.phase)
        assertEquals(0, rig.physicalCount())
        assertEquals(1, rig.calls.count { it.url.endsWith("wake-up-device") })
        assertFalse(rig.ledger.isBlocked(vin))
    }
    @Test fun passive401RefreshesExactlyOnceButOtherErrorsNeverRefresh() = runBlocking {
        for (code in listOf(401, 403, 500)) {
            var reads = 0
            val rig = Rig(route = {
                if (it.url.endsWith("vehicle-health")) { reads++; throw NativeApiException("fixture", code) } else null
            })
            assertEquals(OperationPhase.FAILED, rig.run().phase)
            assertEquals(if (code == 401) 2 else 1, reads)
            assertEquals(if (code == 401) 1 else 0, rig.auth.refreshes)
            assertEquals(0, rig.physicalCount())
        }
    }
    @Test fun ambiguousOwnerMappingAndConflictingDuplicateVinAreRejected() = runBlocking {
        val rig = Rig()
        rig.s.account = nativeJson("fetchCustomerDetails" to mapOf("brandData" to mapOf("crmMapInfo" to listOf(mapOf("contactId" to "a"), mapOf("contactId" to "b")))))
        try { rig.car(); fail("Ambiguous owner must fail") } catch (_: IllegalArgumentException) {}
        assertTrue(rig.calls.isEmpty())
        val conflicting = Rig(route = { if (it.url.endsWith("get-user-vehicles-info-mobile")) garage(entry(), entry(model = "Other")) else null })
        try { conflicting.car(); fail("Conflicting duplicate VIN must fail") } catch (_: IllegalArgumentException) {}
    }
    @Test fun passiveCommandStatus401RefreshesOnlyThatStatusRequest() = runBlocking {
        var statusCalls = 0
        val rig = Rig(route = {
            when (it.url.substringAfterLast('/')) {
                "vehicle-command" -> wrap(nativeJson("vehicleId" to vin, "correlationId" to "123", "status" to "Accepted"))
                "vehicle-command-status" -> {
                    statusCalls++
                    if (statusCalls == 1) throw NativeApiException("fixture", 401)
                    assertEquals("Bearer refreshed", it.headers["Authorization"])
                    wrap(nativeJson("vehicleId" to vin, "correlationId" to "123", "status" to "Success"))
                }
                else -> null
            }
        })
        assertEquals(OperationPhase.SUCCESS, rig.run().phase)
        assertEquals(1, rig.auth.refreshes)
        assertEquals(1, rig.physicalCount())
        assertEquals(2, statusCalls)
    }
    @Test fun legacyOldSampleRequiresTrueNativeLogNotRecentTimestamp() = runBlocking {
        val rig = Rig(h = health(86_400_000))
        assertEquals(OperationPhase.SUCCESS, rig.run().phase)
        assertEquals(1, rig.physicalCount())
        assertFalse(rig.calls.any { it.url.endsWith("check-device-status") || it.url.endsWith("wake-up-device") })
    }
    @Test fun rcV2OldSampleUsesStrictStandaloneReadinessWithoutGuessing() = runBlocking {
        for (value in listOf<Any?>(true, "true", null)) {
            val rig = Rig(h = health(300_000), route = {
                if (it.url.endsWith("check-device-status")) wrap(nativeJson("vehicleId" to vin, "parameterValue" to value)) else null
            })
            assertEquals(if (value == true) OperationPhase.SUCCESS else OperationPhase.FAILED, rig.run(DriveAction.POSITION_OFF).phase)
            assertEquals(if (value == true) 1 else 0, rig.physicalCount())
            assertFalse(rig.calls.any { it.url.endsWith("wake-up-device") || it.url.endsWith("command-log") })
        }
    }
    @Test fun wakeFailureHasNoRefreshRetryOrOriginalAction() = runBlocking {
        for (code in listOf(401, 500)) {
            val rig = Rig(logResult = nativeJson("vehicleId" to vin, "connectedStatus" to false, "commandTraceId" to "trace-ok"),
                route = { if (it.url.endsWith("wake-up-device")) throw NativeApiException("fixture", code) else null })
            assertEquals(OperationPhase.FAILED, rig.run().phase)
            assertEquals(1, rig.calls.count { it.url.endsWith("wake-up-device") })
            assertEquals(0, rig.physicalCount())
            assertEquals(0, rig.auth.refreshes)
        }
    }
    @Test fun wakeReadinessPollingIsBoundedAndNeverSubmitsIfStillDisconnected() = runBlocking {
        val waits = mutableListOf<Long>()
        val rig = Rig(logResult = nativeJson("vehicleId" to vin, "connectedStatus" to false, "commandTraceId" to "trace-ok"), route = {
            when (it.url.substringAfterLast('/')) {
                "wake-up-device" -> JsonObject()
                "check-device-status" -> wrap(nativeJson("vehicleId" to vin, "parameterValue" to false))
                else -> null
            }
        }, wait = { waits += it })
        assertEquals(OperationPhase.FAILED, rig.run().phase)
        assertEquals(List(6) { 5_000L }, waits)
        assertEquals(6, rig.calls.count { it.url.endsWith("check-device-status") })
        assertEquals(0, rig.physicalCount())
        assertEquals(1, rig.calls.count { it.url.endsWith("command-log") })
    }
    @Test fun unsafeFreshHealthAfterWakeStopsBeforeNewCommandLogAndPhysical() = runBlocking {
        var reads = 0
        val rig = Rig(logResult = nativeJson("vehicleId" to vin, "connectedStatus" to false, "commandTraceId" to "trace-ok"), route = {
            when (it.url.substringAfterLast('/')) {
                "vehicle-health" -> { reads++; wrap(health().apply { if (reads > 1) remove("tailGateOpenFlag") }) }
                "wake-up-device" -> JsonObject()
                "check-device-status" -> wrap(nativeJson("vehicleId" to vin, "parameterValue" to true))
                else -> null
            }
        })
        assertEquals(OperationPhase.FAILED, rig.run().phase)
        assertEquals(0, rig.physicalCount())
        assertEquals(1, rig.calls.count { it.url.endsWith("command-log") })
    }
    @Test fun invalidTimerAndStaleSelectedIdentityFailBeforeRequests() = runBlocking {
        val rig = Rig(); val car = rig.car(); val before = rig.calls.size
        for (timer in listOf(4, 21)) assertEquals(OperationPhase.FAILED,
            rig.service.run(rig.s, car, DriveAction.AC_ON, timer, "request-123", {}) { _, _ -> }.phase)
        assertEquals(OperationPhase.FAILED, rig.service.run(rig.s, car.copy(chassis = "N".repeat(17)), DriveAction.LOCK, 10, "request-123", {}) { _, _ -> }.phase)
        assertEquals(OperationPhase.CANCELLED, rig.service.run(rig.s, car, DriveAction.LOCK, 10, "request-123", { throw kotlinx.coroutines.CancellationException("fixture lifecycle") }) { _, _ -> }.phase)
        assertEquals(before, rig.calls.size)
        assertFalse(rig.ledger.isBlocked(vin))
    }
    @Test fun rcV2WrongCorrelationAndTimeoutRemainUnknown() = runBlocking {
        for (mode in listOf("mismatch", "L3_TIMEOUT", "L4_TIMEOUT")) {
            val rig = Rig(route = {
                when (it.url.substringAfterLast('/')) {
                    "process-remote-command" -> wrap(nativeJson("vehicleId" to vin, "commandId" to "lamp-1", "status" to if (mode == "mismatch") "EXECUTING" else mode))
                    "get-remote-command-status" -> wrap(nativeJson("vehicleId" to vin, "commandId" to "wrong-id", "status" to "SUCCESS"))
                    else -> null
                }
            })
            assertEquals(OperationPhase.UNKNOWN, rig.run(DriveAction.HAZARD_OFF).phase)
            assertTrue(rig.ledger.isBlocked(vin))
            assertEquals(1, rig.physicalCount())
        }
    }
    @Test fun missingLegacyTokenGetsOneAuthRepairBeforeGarageLookup() = runBlocking {
        val rig = Rig()
        rig.s.legacy = JsonObject()
        assertEquals(vin, rig.car().chassis)
        assertEquals(1, rig.auth.refreshes)
        assertEquals("Bearer refreshed", rig.calls.single().headers["Authorization"])
    }
    @Test fun receiptAndOdometerEventRemainIndependent() = runBlocking {
        val rig = Rig()
        val snap = rig.service.snapshot(rig.s, rig.car())
        assertEquals(clock, snap.receivedAtMillis)
        assertEquals(clock, snap.updatedAtMillis)
        assertEquals(clock - 55_000, snap.odometerUpdatedAtMillis)
    }
    @Test fun telemetryPreservesUnknownFalseAndLockPolarity() = runBlocking {
        val requests = mutableListOf<NativeRequest>()
        val service = NativeVehicleService(NativeTransport {
            requests += it
            when {
                it.url.endsWith("get-user-vehicles-info-mobile") -> garage(entry())
                it.url.endsWith("vehicle-health") -> nativeJson("results" to mapOf("vehicleId" to vin, "vehicleLockState" to true,
                    "hvBatterySocPercentage" to 64, "distanceToEmpty" to 203, "hvChargingState" to false,
                    "hvChargerConnectFlag" to "false", "airConditionOn" to false, "hazardLampOnFlag" to true))
                else -> nativeJson("results" to mapOf("vehicleId" to vin, "odometerInMeters" to "123456", "latitude" to 99))
            }
        }, Auth(), Ledger())
        val s = session()
        val car = service.vehicles(s).single()
        val state = service.snapshot(s, car)
        assertEquals(false, state.locked)
        assertEquals(false, state.charging)
        assertNull(state.cableConnected)
        assertNull(state.doorsClosed)
        assertNull(state.approachOn)
        assertEquals(false, state.acOn)
        assertEquals(true, state.hazardOn)
        assertEquals(64, state.batteryPercent)
        assertEquals(123.456, state.odometerKm!!, 0.000001)
        assertTrue(requests.drop(1).all { it.method == "GET" })
    }
    @Test fun discoveryUsesExactAuthenticatedSchemaAndDeduplicates() = runBlocking {
        val calls = mutableListOf<NativeRequest>()
        val service = NativeVehicleService(NativeTransport { calls += it; garage(entry(), entry(), entry("I".repeat(17))) }, Auth(), Ledger())
        val found = service.vehicles(session())
        assertEquals(listOf(vin), found.map { it.chassis })
        assertEquals("Fixture EV", found.single().vehicle.model)
        assertEquals("POST", calls.single().method)
        assertEquals("https://evcx.api.tatamotors/mobile/service/api/v3/get-user-vehicles-info-mobile", calls.single().url)
        assertEquals("fixture-crm", calls.single().body!!.text("crmId"))
        assertEquals("Bearer fixture-token", calls.single().headers["Authorization"])
    }
}

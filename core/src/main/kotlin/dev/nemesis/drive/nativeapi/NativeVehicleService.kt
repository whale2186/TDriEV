package dev.nemesis.drive.nativeapi

import com.google.gson.JsonObject
import dev.nemesis.drive.model.*
import kotlinx.coroutines.delay
import java.util.IdentityHashMap
import java.util.UUID

/** Native legacy routes only; no inferred modern routes and no physical request retries. */
class NativeVehicleService(
    private val transport: NativeTransport,
    private val auth: OwnerAuth,
    private val ledger: NativeSafetyLedger,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val readinessAttempts: Int = 6,
    private val readinessWaitMillis: Long = 5_000L,
    private val statusWaitMillis: Long = 5_000L,
    private val maxStatusAttempts: Int = 8,
    private val fireAndForget: Boolean = false
) : OwnerVehicleService {
    override val fireAndForgetEnabled: Boolean get() = fireAndForget
    private val owned = IdentityHashMap<NativeSession, Set<String>>()
    private val vinPattern = Regex("[A-HJ-NPR-Z0-9]{17}")

    override suspend fun prewake(session: NativeSession, vehicle: OwnedVehicle) {
        try {
            requireOwned(session, vehicle)
            val prepared = request(session, vehicle, "wake-up-device", extra = mapOf("commandTraceId" to "null"))
            transport.execute(prepared)
        } catch (_: Exception) {
            // Pre-wake is a best-effort background ping
        }
    }

    override suspend fun vehicles(session: NativeSession): List<OwnedVehicle> {
        synchronized(owned) { owned.remove(session) }
        if (session.legacy.text("accessToken").isNullOrBlank() || session.account.objectValue("fetchCustomerDetails") == null) {
            adopt(session, auth.refresh(session))
        }
        val mappings = session.account.objectValue("fetchCustomerDetails")?.objectValue("brandData")?.get("crmMapInfo")
        val ids = mappings?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject?.text("contactId")?.takeIf(String::isNotBlank) }?.toSet().orEmpty()
        require(ids.size == 1) { "Owner CRM mapping missing or ambiguous" }
        val request = NativeRequest("POST", "https://evcx.api.tatamotors/mobile/service/api/v3/get-user-vehicles-info-mobile", headers(session),
            nativeJson("vehicleCategory" to "TPEM", "crmId" to ids.single(), "mobile" to session.phone, "additionalDriverVC" to emptyList<String>()))
        val result = try {
            transport.execute(request).objectValue("results")
        } catch (e: NativeApiException) {
            if (e.statusCode != 401) throw e
            adopt(session, auth.refresh(session))
            val retried = NativeRequest("POST", "https://evcx.api.tatamotors/mobile/service/api/v3/get-user-vehicles-info-mobile", headers(session),
                nativeJson("vehicleCategory" to "TPEM", "crmId" to ids.single(), "mobile" to session.phone, "additionalDriverVC" to emptyList<String>()))
            transport.execute(retried).objectValue("results")
        } ?: error("Vehicle list missing")
        val list = result.get("userVehicleList")?.takeIf { it.isJsonArray }?.asJsonArray ?: error("Vehicle list missing")
        val entries = list.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }.filter { it.text("chassisNumber")?.matches(vinPattern) == true }
        val vehicles = entries.groupBy { it.text("chassisNumber")!! }.map { (vin, rows) ->
            require(rows.distinct().size == 1) { "Ambiguous owned vehicle" }
            val row = rows.first()
            val model = row.text("modelLabel")?.takeIf { it.isNotBlank() }?.take(100) ?: "Tata EV"
            val registration = row.text("registrationNumber")?.takeIf { it.isNotBlank() }?.take(100)
                ?: "VIN ••••${vin.takeLast(6)}"
            OwnedVehicle(DriveVehicle(vin, model, model, registration, emptySnapshot()), vin)
        }
        synchronized(owned) { owned[session] = vehicles.map { it.chassis }.toSet() }
        return vehicles
    }

    private fun adopt(session: NativeSession, renewed: NativeSession) {
        require(session.phone == renewed.phone) { "Refreshed owner identity mismatch" }
        session.legacy = renewed.legacy
        session.primary = renewed.primary
        session.account = renewed.account
        session.keys.putAll(renewed.keys)
    }
    private fun headers(session: NativeSession): Map<String, String> {
        val token = session.legacy.text("accessToken")?.takeIf(String::isNotBlank) ?: error("Owner login required")
        val key = session.keys["x_api_key"]?.takeIf(String::isNotBlank) ?: error("Owner login required")
        return mapOf("Content-Type" to "application/json", "client_id" to "TMLEV-ANDROID-APP", "x-api-key" to key,
            "Authorization" to "Bearer $token", "User-Agent" to "Dart/3.9 (dart:io)")
    }
    private fun emptySnapshot() = VehicleSnapshot(null, null, null, null, null, null, null, null, null, null, null,
        acOn = null, approachOn = null, hazardOn = null, positionOn = null)
    private val base = "https://evcx.api.tatamotors/mobile/cvp/api/v1/ev/"
    private val doors = listOf("leftFrontDoorOpenFlag", "rightFrontDoorOpenFlag", "leftRearDoorOpenFlag", "rightRearDoorOpenFlag")
    private fun requireOwned(session: NativeSession, vehicle: OwnedVehicle) {
        require(vehicle.chassis.matches(vinPattern) && vehicle.vehicle.id == vehicle.chassis &&
            synchronized(owned) { owned[session]?.contains(vehicle.chassis) == true }) { "Selected owned vehicle required" }
    }
    private fun request(session: NativeSession, vehicle: OwnedVehicle, path: String, body: JsonObject? = null,
        extra: Map<String, String> = emptyMap(), v2: Boolean = false): NativeRequest {
        requireOwned(session, vehicle)
        val h = headers(session).toMutableMap()
        h += mapOf("vehicleId" to vehicle.chassis, "X-Request-ID" to UUID.randomUUID().toString(),
            "Cache-Control" to "no-cache, no-store", "App-Version" to "26.9.1", "Accept" to "application/json")
        h += extra
        if (v2) { h.remove("vehicleId"); h.remove("Cache-Control") }
        return NativeRequest(if (body == null) "GET" else "POST", base + path, h, body)
    }
    /** Only read-only requests may be repeated, once and only after an explicit HTTP 401. */
    private suspend fun passiveExchange(session: NativeSession, guard: suspend () -> Unit, build: () -> NativeRequest): JsonObject {
        suspend fun send(): JsonObject { guard(); return transport.execute(build()) }
        return try { send() } catch (e: NativeApiException) {
            if (e.statusCode != 401) throw e
            guard()
            adopt(session, auth.refresh(session))
            send()
        }
    }
    private suspend fun passive(session: NativeSession, vehicle: OwnedVehicle, path: String, guard: suspend () -> Unit): JsonObject {
        val response = passiveExchange(session, guard) { request(session, vehicle, path) }
        val r = response.objectValue("results") ?: error("Passive snapshot missing")
        if (r.has("vehicleId") && !r.get("vehicleId").isJsonNull) require(r.text("vehicleId") == vehicle.chassis) { "Passive vehicle identity mismatch" }
        return r
    }
    private fun timestamp(raw: String?): Long? = try {
        if (raw == null || raw.length > 100) null else {
            val parsed = try { java.time.OffsetDateTime.parse(raw).toInstant() }
            catch (_: java.time.format.DateTimeParseException) { java.time.LocalDateTime.parse(raw).toInstant(java.time.ZoneOffset.UTC) }
            parsed.toEpochMilli()
        }
    } catch (_: Exception) { null }
    private fun number(r: JsonObject, key: String, maximum: Double = Double.MAX_VALUE): Double? =
        r.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() && it >= 0 && it <= maximum }
    private fun coord(r: JsonObject, key: String, min: Double, max: Double): Double? =
        r.get(key)?.takeIf { it.isJsonPrimitive && (it.asJsonPrimitive.isNumber || it.asJsonPrimitive.isString) }
            ?.asString?.toDoubleOrNull()?.takeIf { it.isFinite() && it in min..max && it != 0.0 }
    private fun closures(r: JsonObject, fields: List<String>): Boolean? {
        val states = fields.map { r.flag(it) }
        return when { states.any { it == true } -> false; states.all { it == false } -> true; else -> null }
    }
    override suspend fun snapshot(session: NativeSession, vehicle: OwnedVehicle): VehicleSnapshot {
        val h = passive(session, vehicle, "vehicle-health") {}
        val state = passive(session, vehicle, "vehicle-state") {}
        val odometer = state.get("odometerInMeters")?.takeIf { it.isJsonPrimitive && (it.asJsonPrimitive.isNumber || it.asJsonPrimitive.isString) }
            ?.asString?.takeIf { it.matches(Regex("[0-9]{1,24}")) }?.toBigDecimalOrNull()?.divide(java.math.BigDecimal(1000))?.toDouble()
        val stateTime = timestamp(state.text("eventDateTime"))
        val lat = coord(state, "gpsLatitude", -90.0, 90.0) ?: coord(state, "latitude", -90.0, 90.0)
        val lon = coord(state, "gpsLongitude", -180.0, 180.0) ?: coord(state, "longitude", -180.0, 180.0)
        return VehicleSnapshot(
            if (h.flag("isSocNull") == true) null else number(h, "hvBatterySocPercentage", 100.0)?.toInt(),
            number(h, "distanceToEmpty", Int.MAX_VALUE.toDouble())?.toInt(), odometer,
            h.flag("vehicleLockState")?.not(), h.flag("hvChargingState"), h.flag("hvChargerConnectFlag"),
            closures(h, doors), h.flag("bonnetOpenFlag")?.not(), h.flag("tailGateOpenFlag")?.not(), h.flag("crankOn"),
            timestamp(h.text("eventDateTime")), h.flag("airConditionOn"), h.flag("approchLampOnFlag"),
            h.flag("hazardLampOnFlag"), h.flag("positionLampOnFlag"),
            receivedAtMillis = now(), odometerUpdatedAtMillis = stateTime,
            latitude = lat, longitude = lon, locationUpdatedAtMillis = stateTime)
    }
    companion object {
        private val active = mutableSetOf<String>()
        fun isActionActive(vehicleId: String): Boolean = synchronized(active) { vehicleId in active }
    }
    private val commands = mapOf(
        DriveAction.LOCK to "LockVehicleCommand", DriveAction.UNLOCK to "UnlockVehicleCommand",
        DriveAction.AC_ON to "StartAcCommand", DriveAction.AC_OFF to "StopAcCommand", DriveAction.HORN to "RemoteHonkCommand",
        DriveAction.APPROACH_ON to "RemoteApproachOnCommand", DriveAction.APPROACH_OFF to "RemoteApproachOffCommand",
        DriveAction.HAZARD_ON to "RemoteHazardCommandLampOn", DriveAction.HAZARD_OFF to "RemoteHazardCommandLampOff",
        DriveAction.POSITION_ON to "RemotePositionCommandLampOn", DriveAction.POSITION_OFF to "RemotePositionCommandLampOff")
    private val tracePattern = Regex("[A-Za-z0-9._:-]{1,200}")
    private fun checkSafety(h: JsonObject, action: DriveAction): Long {
        require((doors + listOf("bonnetOpenFlag", "tailGateOpenFlag", "crankOn")).all { h.flag(it) == false }) { "Closure or ignition state unsafe or unknown" }
        if (action == DriveAction.AC_ON) require(h.flag("hvChargerConnectFlag") == false) { "Cable connected or unknown" }
        val stamp = timestamp(h.text("eventDateTime")) ?: error("Vehicle timestamp missing")
        require(stamp <= now() + 30_000L) { "Vehicle timestamp is in the future" }
        return stamp
    }
    private fun commandResult(response: JsonObject, vehicle: OwnedVehicle, optionalIdentity: Boolean = false): JsonObject {
        val result = response.objectValue("results") ?: error("Command response missing")
        if (!optionalIdentity || (result.has("vehicleId") && !result.get("vehicleId").isJsonNull))
            require(result.text("vehicleId") == vehicle.chassis) { "Command identity mismatch" }
        return result
    }
    private fun correlation(result: JsonObject): String? = result.get("correlationId")
        ?.takeIf { it.isJsonPrimitive && (it.asJsonPrimitive.isString || it.asJsonPrimitive.isNumber) }
        ?.asString?.takeIf { it.matches(Regex("[0-9]{1,256}")) }
    override suspend fun run(session: NativeSession, vehicle: OwnedVehicle, action: DriveAction, durationMinutes: Int, requestId: String,
        guard: suspend () -> Unit, progress: (OperationPhase, String) -> Unit): NativeCommandResult {
        var submitted = false
        var reserved = false
        fun report(phase: OperationPhase, message: String): NativeCommandResult {
            progress(phase, message)
            return NativeCommandResult(phase, message)
        }
        try {
            requireOwned(session, vehicle)
            require(requestId.matches(Regex("[A-Za-z0-9_-]{8,100}"))) { "Invalid request identity" }
            require(action != DriveAction.AC_ON || durationMinutes in 5..20) { "AC timer must be 5 to 20 minutes" }
            synchronized(active) {
                require(!ledger.isBlocked(vehicle.vehicle.id) && active.add(vehicle.vehicle.id)) { "Vehicle has an unresolved command" }
                reserved = true
            }
            report(OperationPhase.CONTACTING, "Checking vehicle safety")
            var health = passive(session, vehicle, "vehicle-health", guard)
            checkSafety(health, action)
            val command = commands.getValue(action)
            suspend fun send(path: String, body: JsonObject? = null, extra: Map<String, String> = emptyMap()): JsonObject {
                val prepared = request(session, vehicle, path, body, extra)
                guard()
                return transport.execute(prepared)
            }
            suspend fun log(): JsonObject {
                val secret = session.keys["client_secret_value"]?.takeIf(String::isNotBlank) ?: error("Owner configuration missing")
                return commandResult(send("command-log", nativeJson("vehicleId" to vehicle.chassis, "commandName" to command,
                    "originDateTime" to java.time.Instant.ofEpochMilli(now()).atZone(java.time.ZoneId.of("Asia/Kolkata"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")),
                    "metadata" to "{$command:true}"), mapOf("client_secret" to secret)), vehicle)
            }
            val isV2 = action in setOf(DriveAction.HAZARD_ON, DriveAction.HAZARD_OFF, DriveAction.POSITION_ON, DriveAction.POSITION_OFF)
            suspend fun wakeAndCheck(trace: String) {
                report(OperationPhase.WAKING, "Reconnecting vehicle")
                val wake = send("wake-up-device", extra = mapOf("commandTraceId" to trace))
                val wakeData = wake.get("results")
                if (wakeData != null && !wakeData.isJsonNull) {
                    require(wakeData.isJsonObject && wakeData.asJsonObject.text("vehicleId") == vehicle.chassis) { "Wake identity mismatch" }
                }
                report(OperationPhase.WAITING_READY, "Waiting for vehicle readiness")
                var ready = false
                for (attempt in 0 until readinessAttempts) {
                    pause(readinessWaitMillis)
                    if (passive(session, vehicle, "check-device-status", guard).flag("parameterValue") == true) { ready = true; break }
                }
                require(ready) { "Vehicle did not become ready" }
                health = passive(session, vehicle, "vehicle-health", guard)
                checkSafety(health, action)
            }
            val physical: NativeRequest
            if (isV2) {
                if (!fireAndForget && now() - checkSafety(health, action) > 120_000) {
                    when (passive(session, vehicle, "check-device-status", guard).flag("parameterValue")) {
                        true -> Unit
                        false -> wakeAndCheck("null")
                        null -> error("Vehicle readiness unknown")
                    }
                }
                physical = request(session, vehicle, "process-remote-command", nativeJson("commandName" to command,
                    "config" to emptyMap<String, String>(), "originDateTime" to java.time.Instant.ofEpochMilli(now()).toString(),
                    "vehicleId" to vehicle.chassis), v2 = true)
            } else {
                var log = log()
                var trace = log.text("commandTraceId")?.takeIf { it.matches(tracePattern) } ?: error("Invalid command trace")
                if (log.flag("connectedStatus") == false) {
                    if (fireAndForget) {
                        report(OperationPhase.WAKING, "Reconnecting vehicle")
                        send("wake-up-device", extra = mapOf("commandTraceId" to trace))
                        pause(1_000)
                    } else {
                        wakeAndCheck(trace)
                        for (attempt in 0 until 6) {
                            if (attempt > 0) pause(3_000)
                            log = log()
                            if (log.flag("connectedStatus") == true) break
                        }
                        trace = log.text("commandTraceId")?.takeIf { it.matches(tracePattern) } ?: error("Invalid command trace")
                        require(log.flag("connectedStatus") == true) { "Vehicle readiness not confirmed" }
                    }
                } else {
                    require(log.flag("connectedStatus") == true) { "Vehicle readiness not confirmed" }
                }
                val config = if (action == DriveAction.AC_ON) mapOf("settimer" to durationMinutes.toString()) else emptyMap()
                physical = request(session, vehicle, "vehicle-command", nativeJson("config" to config, "commandTraceId" to trace), mapOf("command-name" to command))
            }
            report(OperationPhase.SENDING, "Sending one command")
            guard()
            ledger.markPending(vehicle.vehicle.id, action, requestId)
            submitted = true
            var result = commandResult(transport.execute(physical), vehicle, isV2)
            val initialStatus = result.text("status")?.lowercase(java.util.Locale.ROOT)
            val isIdempotent = action != DriveAction.HORN
            val isAccepted = initialStatus in setOf("accepted", "success", "in progress", "pending", "executing", "waking_up")
            if (fireAndForget && isIdempotent && isAccepted) {
                ledger.clearPending(vehicle.vehicle.id)
                return report(OperationPhase.SUCCESS, "Command sent to vehicle")
            }
            fun commandId(r: JsonObject): String? = if (isV2) r.text("commandId")?.takeIf { it.length in 1..256 && it.none { c -> c.code < 32 || c.code == 127 } } else correlation(r)
            val id = commandId(result) ?: error("Invalid command correlation")
            for (attempt in 0..maxStatusAttempts) {
                val state = result.text("status")?.lowercase(java.util.Locale.ROOT)
                val success = state == "success"
                val failure = if (isV2) state in setOf("failed", "precondition_failed") else state == "failure"
                val pending = if (isV2) state in setOf("pending", "waking_up", "executing") else state in setOf("accepted", "in progress")
                when {
                    success -> { ledger.clearPending(vehicle.vehicle.id); return report(OperationPhase.SUCCESS, "Tata reported success; refresh to observe vehicle state") }
                    failure -> { ledger.clearPending(vehicle.vehicle.id); return report(OperationPhase.FAILED, "Tata reported command failure") }
                    !pending -> return report(OperationPhase.UNKNOWN, "Command outcome unknown; do not repeat automatically")
                }
                if (attempt == maxStatusAttempts) break
                report(OperationPhase.SENDING, "Command submitted; waiting for Tata's result")
                pause(statusWaitMillis)
                val response = passiveExchange(session, guard) {
                    if (isV2) request(session, vehicle, "get-remote-command-status", nativeJson("commandId" to id, "vehicleId" to vehicle.chassis), v2 = true)
                    else request(session, vehicle, "vehicle-command-status", extra = mapOf("correlationId" to id))
                }
                result = commandResult(response, vehicle, isV2)
                require(commandId(result) == id) { "Command correlation mismatch" }
            }
            return report(OperationPhase.UNKNOWN, "Command outcome unknown; do not repeat automatically")
        } catch (e: Exception) {
            val phase = if (submitted) OperationPhase.UNKNOWN else if (e is kotlinx.coroutines.CancellationException) OperationPhase.CANCELLED else OperationPhase.FAILED
            val message = if (submitted) "Command outcome unknown; do not repeat automatically" else if (phase == OperationPhase.CANCELLED) "Command cancelled before submission" else "Safety or connection check stopped; no physical command submitted"
            return report(phase, message)
        } finally {
            if (reserved) synchronized(active) { active.remove(vehicle.vehicle.id) }
        }
    }
}

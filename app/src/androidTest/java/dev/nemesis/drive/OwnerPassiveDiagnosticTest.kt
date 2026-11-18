package dev.nemesis.drive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.nemesis.drive.model.DriveAction
import dev.nemesis.drive.nativeapi.*
import dev.nemesis.drive.platform.AndroidOwnerStore
import dev.nemesis.drive.platform.OwnerHttpsTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Explicitly opt-in owner's read-only diagnosis. No session export, refresh, OTP, wake or control. */
@RunWith(AndroidJUnit4::class)
class OwnerPassiveDiagnosticTest {
    @Test fun inspectTelemetryWithoutActuationOrCredentialOutput() {
        assumeTrue(!BuildConfig.SIMULATOR)
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowOwnerPassiveRead") == "true")
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = AndroidOwnerStore(context)
            val session = requireNotNull(store.load()) { "No saved app session" }
            val https = OwnerHttpsTransport()
            val replies = mutableMapOf<String, JsonObject>()
            val routes = setOf("get-user-vehicles-info-mobile", "vehicle-health", "vehicle-state")
            val readOnly = NativeTransport { request ->
                val route = request.url.substringAfterLast('/')
                check(route in routes && request.method == if (route == "get-user-vehicles-info-mobile") "POST" else "GET")
                try {
                    https.execute(request).also { replies[route] = it }
                } catch (failure: NativeApiException) {
                    report("PASSIVE_DIAG route=$route http=${failure.statusCode} category=${safeAuthFailureCode(failure.serviceCode)}")
                    throw failure
                }
            }
            val noAuth = object : OwnerAuth {
                override suspend fun requestCode(phone: String): OtpChallenge = error("OTP prohibited")
                override suspend fun verifyCode(challenge: OtpChallenge, otp: String, terms: Boolean, privacy: Boolean): NativeSession = error("OTP prohibited")
                override suspend fun restore(): NativeSession? = null
                override suspend fun refresh(session: NativeSession): NativeSession = error("Session refresh prohibited")
                override fun clear() = error("Session deletion prohibited")
            }
            val noMutations = object : NativeSafetyLedger {
                override fun isBlocked(vehicleId: String) = store.isBlocked(vehicleId)
                override fun markPending(vehicleId: String, action: DriveAction, requestId: String) = error("Control prohibited")
                override fun clearPending(vehicleId: String) = error("Journal modification prohibited")
            }
            val service = NativeVehicleService(readOnly, noAuth, noMutations)
            val vehicles = service.vehicles(session)
            report("PASSIVE_DIAG garage_count=${vehicles.size}")
            check(vehicles.size == 1) { "Single owned vehicle required for this diagnostic" }
            val vehicle = vehicles.single()
            val fields = listOf("vehicleId", "eventDateTime", "hvBatterySocPercentage", "distanceToEmpty", "vehicleLockState",
                "hvChargingState", "hvChargerConnectFlag", "leftFrontDoorOpenFlag", "rightFrontDoorOpenFlag",
                "leftRearDoorOpenFlag", "rightRearDoorOpenFlag", "bonnetOpenFlag", "tailGateOpenFlag", "crankOn", "odometerInMeters")
            for (route in listOf("vehicle-health", "vehicle-state")) {
                val request = NativeRequest("GET", "https://evcx.api.tatamotors/mobile/cvp/api/v1/ev/$route",
                    mapOf("vehicleId" to vehicle.chassis, "client_id" to "TMLEV-ANDROID-APP",
                        "x-api-key" to requireNotNull(session.keys["x_api_key"]),
                        "Authorization" to "Bearer ${requireNotNull(session.legacy.text("accessToken"))}",
                        "X-Request-ID" to UUID.randomUUID().toString(), "App-Version" to "26.9.1",
                        "User-Agent" to "Dart/3.9 (dart:io)", "Content-Type" to "application/json"))
                try {
                    val response = readOnly.execute(request)
                    val result = response.objectValue("results")
                    val types = fields.associateWith { key -> type(result?.get(key)) }
                    val matches = result?.text("vehicleId")?.let { it == vehicle.chassis }
                    report("PASSIVE_DIAG route=$route result_object=${result != null} identity_matches=$matches types=$types")
                } catch (_: NativeApiException) { /* A failed optional route must not hide the other result. */ }
            }
            if (replies.keys.containsAll(routes)) {
                val replay = NativeVehicleService(NativeTransport { request ->
                    requireNotNull(replies[request.url.substringAfterLast('/')])
                }, noAuth, noMutations)
                val replayVehicle = replay.vehicles(session).single()
                try {
                    val snapshot = replay.snapshot(session, replayVehicle)
                    report("PASSIVE_DIAG parse_success=true battery_known=${snapshot.batteryPercent != null} locks_known=${snapshot.locked != null} event_known=${snapshot.updatedAtMillis != null}")
                } catch (failure: Exception) {
                    // Only this fixed code, never an exception's data/stack or response values.
                    report("PASSIVE_DIAG parse_success=false exception_type=${failure.javaClass.simpleName}")
                }
            }
        }
    }
    @Test fun inspectTheActualViewModelRefreshFlowWithoutCommands() {
        assumeTrue(!BuildConfig.SIMULATOR)
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowOwnerPassiveRead") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as android.app.Application
        val holder = androidx.lifecycle.ViewModelStore()
        lateinit var model: DriveViewModel
        try {
            instrumentation.runOnMainSync {
                model = androidx.lifecycle.ViewModelProvider(holder,
                    androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(application))[DriveViewModel::class.java]
            }
            fun await(condition: () -> Boolean) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
                while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) android.os.SystemClock.sleep(100)
                check(condition()) { "Read-only app flow timed out" }
            }
            await { !model.state.value.authBusy }
            val garage = model.state.value
            report("APP_DIAG step=${garage.step} vehicle_count=${garage.vehicles.size} has_error=${garage.error != null}")
            check(garage.vehicles.size == 1) { "Single owned vehicle required for this diagnostic" }
            instrumentation.runOnMainSync { model.selectVehicle(garage.vehicles.single().id) }
            await { !model.state.value.refreshing }
            val state = model.state.value
            val snapshot = state.selectedVehicle?.snapshot
            report("APP_DIAG selected=${state.selectedVehicle != null} refreshing=${state.refreshing} busy=${state.busy} has_error=${state.error != null} battery_known=${snapshot?.batteryPercent != null} locks_known=${snapshot?.locked != null}")
            val observed = state.selectedVehicle?.snapshot
            instrumentation.runOnMainSync { model.refreshGarage() }
            await { !model.state.value.refreshing }
            val retained = model.state.value.selectedVehicle?.snapshot == observed
            report("APP_DIAG garage_refresh_retains_observation=$retained")
            check(retained) { "Garage refresh changed the cached vehicle observation" }
            val vault = AndroidOwnerStore(application)
            report("APP_DIAG prior_command_unresolved=${vault.isBlocked(garage.vehicles.single().id)}")
        } finally { instrumentation.runOnMainSync { holder.clear() } }
    }

    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
            putString("stream", message + "\n")
        })
    }
    private fun type(value: JsonElement?): String = when {
        value == null -> "missing"
        value.isJsonNull -> "null"
        value.isJsonObject -> "object"
        value.isJsonArray -> "array"
        value.asJsonPrimitive.isBoolean -> "boolean"
        value.asJsonPrimitive.isNumber -> "number"
        else -> "string"
    }
}

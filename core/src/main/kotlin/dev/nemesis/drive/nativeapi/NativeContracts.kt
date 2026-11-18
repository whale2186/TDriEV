package dev.nemesis.drive.nativeapi

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.nemesis.drive.model.*

/** Wire data never enters logs or UI error strings. Transport implementations must not retry. */
data class NativeRequest(val method: String, val url: String,
    val headers: Map<String, String> = emptyMap(), val body: JsonObject? = null)
fun interface NativeTransport { suspend fun execute(request: NativeRequest): JsonObject }
class NativeApiException(message: String, val statusCode: Int? = null, val serviceCode: String? = null) : Exception(message)
fun safeAuthFailureCode(value: String?): String? = value?.takeIf {
    it.matches(Regex("SSO-[0-9]{4}")) || it in setOf("INVALID_REQUEST_BODY", "INVALID_OTP", "OTP_EXPIRED", "INVALID_REFERENCE",
        "APPLICATION_ERROR", "APPLICATION_REJECTED", "RESPONSE_SCHEMA", "SUCCESS_TYPE")
}
fun nativeJson(vararg fields: Pair<String, Any?>): JsonObject = Gson().toJsonTree(mapOf(*fields)).asJsonObject
fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
fun JsonObject.flag(name: String): Boolean? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
fun JsonObject.objectValue(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

class NativeSession(val phone: String, val keys: MutableMap<String, String>,
    var primary: JsonObject, var legacy: JsonObject = JsonObject(), var account: JsonObject = JsonObject())
class OtpChallenge(val phone: String, val reference: String, val keys: Map<String, String>,
    val loyalCustomer: String = "N")
interface NativeSessionStore {
    fun load(): NativeSession?
    fun save(session: NativeSession)
    fun clear()
    /** Safe stage/status identifiers only; never a phone number, code, token or response body. */
    fun recordAuthFailure(stage: String, statusCode: Int?, reason: String) {}
    fun saveSnapshot(vehicleId: String, json: String) {}
    fun loadSnapshot(vehicleId: String): String? = null
}
interface OwnerAuth {
    suspend fun requestCode(phone: String): OtpChallenge
    suspend fun verifyCode(challenge: OtpChallenge, otp: String, terms: Boolean, privacy: Boolean): NativeSession
    suspend fun restore(): NativeSession?
    suspend fun refresh(session: NativeSession): NativeSession
    fun clear()
}
data class OwnedVehicle(val vehicle: DriveVehicle, val chassis: String)
data class NativeCommandResult(val phase: OperationPhase, val message: String)
/** Persist BEFORE physical submission; retain unknowns across process death and sign-out. */
interface NativeSafetyLedger {
    fun isBlocked(vehicleId: String): Boolean
    fun markPending(vehicleId: String, action: DriveAction, requestId: String)
    fun clearPending(vehicleId: String)
}
interface OwnerVehicleService {
    val fireAndForgetEnabled: Boolean get() = false
    suspend fun vehicles(session: NativeSession): List<OwnedVehicle>
    suspend fun snapshot(session: NativeSession, vehicle: OwnedVehicle): VehicleSnapshot
    suspend fun prewake(session: NativeSession, vehicle: OwnedVehicle) {}
    suspend fun run(session: NativeSession, vehicle: OwnedVehicle, action: DriveAction, durationMinutes: Int,
        requestId: String, guard: suspend () -> Unit,
        progress: (OperationPhase, String) -> Unit): NativeCommandResult
}

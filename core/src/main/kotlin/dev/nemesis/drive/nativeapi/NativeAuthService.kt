package dev.nemesis.drive.nativeapi

import com.google.gson.JsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owner-initiated authentication only. No OTP retries, implicit refresh, membership
 * conversion or marketing consent. Primary is saved BEFORE legacy integration;
 * legacy/account is saved BEFORE after-login configuration. Later failures throw
 * safe errors without clearing any completed stage. Caller can restore() (local
 * load only), show a garage error and call refresh(session) on explicit Retry.
 * refresh repairs partial integration without requesting another OTP.
 */
class NativeAuthService(private val transport: NativeTransport, private val store: NativeSessionStore) : OwnerAuth {
    override suspend fun requestCode(phone: String): OtpChallenge {
        requirePhone(phone)
        val bootstrap = NativeBootstrap()
        val keys = bootstrap.decrypt(send(bootstrap.request()))
        key(keys, "nio_auth_x_api_key")
        val response = authPost(keys, "check-phone", nativeJson("countryCode" to "91", "phone" to phone, "sendOtp" to true))
        val reference = reference(response.text("refId"))
        if (response.has("loyalCustomer") && response.text("loyalCustomer") !in setOf("Y", "N"))
            throw NativeApiException("Verification challenge was malformed; request a fresh code")
        return OtpChallenge(phone, reference, keys.toMap(), response.text("loyalCustomer") ?: "N")
    }

    override suspend fun verifyCode(challenge: OtpChallenge, otp: String, terms: Boolean, privacy: Boolean): NativeSession {
        if (!terms || !privacy) throw NativeApiException("Explicit terms and privacy acceptance are required")
        requirePhone(challenge.phone)
        reference(challenge.reference)
        if (!otp.matches(Regex("[0-9]{4,8}"))) throw NativeApiException("Enter a valid numeric verification code")
        if (challenge.loyalCustomer !in setOf("Y", "N")) throw NativeApiException("Verification challenge was malformed")
        val (verifier, pkce) = pkce()
        val verified = authPost(challenge.keys, "verify-phone-otp", nativeJson(
            "countryCode" to "91", "phone" to challenge.phone, "otp" to otp, "refId" to challenge.reference,
            "programTnC" to true, "privacyPolicyAccepted" to true, "marketingCommunication" to false,
            "loyalCustomer" to challenge.loyalCustomer, "storeId" to "store_id", "redirectUrl" to REDIRECT,
            "codeChallenge" to pkce, "consentType" to "Explicit"))
        val code = field(verified, "authCode")
        val primary = authPost(challenge.keys, "access-token", nativeJson("authCode" to code, "codeVerifier" to verifier))
        validateTokens(primary)
        val session = NativeSession(challenge.phone, challenge.keys.toMutableMap(), primary.deepCopy())
        save(session)
        integrateLegacy(session)
        return session
    }

    override suspend fun restore(): NativeSession? = try { store.load() } catch (_: Exception) {
        throw NativeApiException("Saved owner session could not be loaded")
    }

    override suspend fun refresh(session: NativeSession): NativeSession {
        val oldPrimary = session.primary
        val primaryToken = field(oldPrimary, "accessToken")
        val repairLegacy = !usable(session.legacy.text("accessToken")) ||
            !usable(session.legacy.text("refreshToken")) || session.account.size() == 0
        val primaryRefresh = oldPrimary.text("refreshToken")
        if (usable(primaryRefresh)) {
            val updated = authPost(session.keys, "refresh-token", nativeJson("refreshToken" to primaryRefresh), primaryToken)
            validateTokens(updated)
            session.primary = merged(oldPrimary, updated)
            save(session) // Rotation must survive every later legacy/configuration failure.
        } else if (!repairLegacy) {
            throw NativeApiException("Saved refresh token is unavailable; owner authentication is required")
        }
        if (repairLegacy) {
            integrateLegacy(session)
            return session
        }
        try {
            val response = send(NativeRequest("POST", "$LEGACY_BASE/refresh-token",
                legacyHeaders(key(session.keys, "x_api_key"), key(session.keys, "client_secret_value")) +
                    ("Authorization" to "Bearer ${field(session.legacy, "accessToken")}"),
                nativeJson("refreshToken" to field(session.legacy, "refreshToken"))))
            val updated = response.objectValue("results") ?: throw NativeApiException("Legacy refresh was malformed")
            validateTokens(updated)
            session.legacy = merged(session.legacy, updated)
            save(session)
            if (!usable(session.keys["n_io_b2c_api_key"])) updateConfiguration(session)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            throw NativeApiException("Couldn't reconnect your vehicles. Please try again.",
                (error as? NativeApiException)?.statusCode)
        }
        return session
    }

    private fun merged(previous: JsonObject, updated: JsonObject): JsonObject = previous.deepCopy().apply {
        updated.entrySet().forEach { (name, value) -> add(name, value.deepCopy()) }
    }

    override fun clear() {
        try { store.clear() } catch (_: Exception) { throw NativeApiException("Saved owner session could not be cleared") }
    }

    private suspend fun integrateLegacy(session: NativeSession) {
        try {
            val primaryToken = field(session.primary, "accessToken")
            val legacyKey = key(session.keys, "x_api_key")
            val secret = key(session.keys, "client_secret_value")
            val (verifier, challenge) = pkce()
            val redirect = authPost(session.keys, "redirect-client-auth-code", nativeJson("redirectClientId" to LEGACY,
                "redirectUrl" to REDIRECT, "codeChallenge" to challenge), primaryToken)
            val response = send(NativeRequest("POST", "$LEGACY_BASE/access-token", legacyHeaders(legacyKey, secret) + mapOf(
                "authCode" to field(redirect, "authCode"), "userType" to "existing", "convertToLoyal" to "false",
                "App-Version" to "26.9.1"), nativeJson("codeVerifier" to verifier)))
            val account = response.objectValue("results") ?: throw NativeApiException("Legacy account was malformed")
            val legacy = account.objectValue("accessTokenDetails") ?: throw NativeApiException("Legacy session was malformed")
            validateTokens(legacy)
            session.legacy = legacy.deepCopy()
            session.account = account.deepCopy()
            save(session)
            updateConfiguration(session)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            throw NativeApiException("Couldn't load your vehicles. Please try again.",
                (error as? NativeApiException)?.statusCode)
        }
    }

    private suspend fun updateConfiguration(session: NativeSession) {
        val bootstrap = NativeBootstrap()
        val updated = bootstrap.decrypt(send(bootstrap.request("afterLogin", field(session.legacy, "accessToken"))))
        session.keys.putAll(updated)
        save(session)
    }

    private suspend fun authPost(keys: Map<String, String>, path: String, body: JsonObject, token: String? = null): JsonObject {
        val headers = mutableMapOf("Content-Type" to "application/json", "client_id" to MAIN,
            "x-api-key" to key(keys, "nio_auth_x_api_key"), "User-Agent" to USER_AGENT)
        if (token != null) headers["Authorization"] = "Bearer $token"
        return send(NativeRequest("POST", "$AUTH_BASE/$path", headers, body))
    }

    private suspend fun send(request: NativeRequest): JsonObject {
        var received = false
        try {
            val response = transport.execute(request)
            received = true
            val isAuthCodeReply = request.url == "$AUTH_BASE/verify-phone-otp" ||
                request.url == "$AUTH_BASE/redirect-client-auth-code"
            val validation = if (isAuthCodeReply) response.deepCopy().apply {
                val success = get("success")
                // The native combined flow uses authCode, not this optional response metadata.
                // Keep explicit boolean failure authoritative; the caller still requires authCode
                // and the server must exchange it for usable tokens before a session is accepted.
                if (success != null && !success.isJsonNull &&
                    !(success.isJsonPrimitive && success.asJsonPrimitive.isBoolean)) remove("success")
            } else response
            checkedNativeResponse(validation)
            return response
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            val stage = request.url.substringAfterLast('/')
            val status = (error as? NativeApiException)?.statusCode ?: if (received) 200 else null
            val serviceCode = safeAuthFailureCode((error as? NativeApiException)?.serviceCode)
            val reason = when {
                serviceCode != null -> "HTTP_$serviceCode"
                status != null && status >= 400 -> "HTTP"
                received || status != null -> "RESPONSE"
                error.message == "Request could not be prepared" -> "PREPARATION"
                else -> "TRANSPORT"
            }
            runCatching { store.recordAuthFailure(stage, status, reason) }
            val message = when (stage) {
                "verify-phone-otp" -> "Couldn't verify the code. Please try again."
                "check-phone" -> "Couldn't send a code. Please try again."
                "access-token" -> "Couldn't finish signing in. Please try again."
                "refresh-token" -> "Your session couldn't be renewed. Please sign in again."
                else -> "Couldn't connect. Please try again."
            }
            throw NativeApiException(message, status, serviceCode)
        }
    }

    private fun save(session: NativeSession) {
        try { store.save(session) } catch (_: Exception) { throw NativeApiException("Owner session could not be saved securely") }
    }

    private fun legacyHeaders(key: String, secret: String) = mapOf("Content-Type" to "application/json", "client_id" to LEGACY,
        "client_secret" to secret, "x-api-key" to key, "User-Agent" to USER_AGENT)

    private fun validateTokens(tokens: JsonObject) {
        checkedNativeResponse(tokens)
        field(tokens, "accessToken")
        if (tokens.has("refreshToken")) field(tokens, "refreshToken")
    }

    private fun field(json: JsonObject, name: String): String = json.text(name).takeIf(::usable)
        ?: throw NativeApiException("Authentication response was missing a valid required field")
    private fun key(keys: Map<String, String>, name: String): String = keys[name].takeIf(::usable)
        ?: throw NativeApiException("Runtime authentication configuration is unavailable")
    private fun requirePhone(phone: String) {
        if (!phone.matches(Regex("[0-9]{10}"))) throw NativeApiException("Enter the ten-digit registered phone number")
    }
    private fun reference(value: String?): String {
        if (!usable(value) || value!!.length > 2048) throw NativeApiException("Request a fresh verification challenge first")
        return value
    }
    private fun pkce(): Pair<String, String> {
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII)))
        return verifier to challenge
    }

    private companion object {
        const val AUTH_BASE = "https://customer.auth.ev.tatamotors.io/v1/auth"
        const val LEGACY_BASE = "https://evcx.api.tatamotors/mobile/customer/api/v1"
        const val MAIN = "TMLEV-NIO-ANDROID-APP"
        const val LEGACY = "TMLEV-ANDROID-APP"
        const val USER_AGENT = "Dart/3.9 (dart:io)"
        const val REDIRECT = "https://prod-ev.tatamotors.com/"
    }
}

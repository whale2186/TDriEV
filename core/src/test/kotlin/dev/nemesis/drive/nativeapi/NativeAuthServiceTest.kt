package dev.nemesis.drive.nativeapi

import com.google.gson.JsonObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

internal class AuthMemoryStore : NativeSessionStore {
    var session: NativeSession? = null
    val snapshots = mutableListOf<NativeSession>()
    val failures = mutableListOf<Triple<String, Int?, String>>()
    override fun recordAuthFailure(stage: String, statusCode: Int?, reason: String) {
        failures += Triple(stage, statusCode, reason)
    }
    override fun load() = session
    override fun save(session: NativeSession) {
        this.session = session
        snapshots += NativeSession(session.phone, session.keys.toMutableMap(), session.primary.deepCopy(), session.legacy.deepCopy(), session.account.deepCopy())
    }
    override fun clear() { session = null }
}

internal class AuthMockServer : NativeTransport {
    val requests = mutableListOf<NativeRequest>()
    var failPath: String? = null
    var replacement: JsonObject? = null
    var primaryChallenge = ""
    var legacyChallenge = ""
    var activePrimary = "primary-token"
    override suspend fun execute(request: NativeRequest): JsonObject {
        requests += request
        assertEquals("POST", request.method)
        assertEquals("Dart/3.9 (dart:io)", request.headers["User-Agent"])
        if (request.url.endsWith(failPath ?: "not-a-path")) {
            return replacement ?: throw NativeApiException("UNSAFE synthetic-token synthetic-private-body", 503)
        }
        val body = request.body!!
        if (request.url.endsWith("security/ecdh-key-exchange")) {
            if (body.text("apiType") == "afterLogin") {
                assertEquals("Bearer legacy-token", request.headers["Authorization"])
                return encryptBootstrap(request, nativeJson("n_io_b2c_api_key" to "synthetic-vehicle-key"))
            }
            return encryptBootstrap(request, nativeJson("nio_auth_x_api_key" to "synthetic-auth-key",
                "x_api_key" to "synthetic-legacy-key", "client_secret_value" to "synthetic-secret"))
        }
        if (request.url.startsWith("https://customer.auth.ev.tatamotors.io")) {
            assertEquals("TMLEV-NIO-ANDROID-APP", request.headers["client_id"])
            assertEquals("synthetic-auth-key", request.headers["x-api-key"])
            assertNull(request.headers["Accept-Encoding"]) // OkHttp owns transparent gzip and decoded size limits.
            return when (request.url.substringAfterLast('/')) {
                "check-phone" -> {
                    assertEquals(nativeJson("countryCode" to "91", "phone" to "0000000000", "sendOtp" to true), body)
                    nativeJson("refId" to "synthetic-reference", "loyalCustomer" to "Y")
                }
                "verify-phone-otp" -> {
                    assertEquals(setOf("countryCode", "phone", "otp", "refId", "programTnC", "privacyPolicyAccepted",
                        "marketingCommunication", "loyalCustomer", "storeId", "redirectUrl", "codeChallenge", "consentType"), body.keySet())
                    assertEquals("Y", body.text("loyalCustomer"))
                    assertEquals(false, body.flag("marketingCommunication"))
                    assertEquals(true, body.flag("programTnC"))
                    assertEquals(true, body.flag("privacyPolicyAccepted"))
                    assertEquals("Explicit", body.text("consentType"))
                    assertEquals("store_id", body.text("storeId"))
                    assertEquals("https://prod-ev.tatamotors.com/", body.text("redirectUrl"))
                    primaryChallenge = body.text("codeChallenge")!!
                    nativeJson("success" to true, "authCode" to "synthetic-primary-code")
                }
                "access-token" -> {
                    assertEquals(setOf("authCode", "codeVerifier"), body.keySet())
                    assertEquals("synthetic-primary-code", body.text("authCode"))
                    checkPkce(primaryChallenge, body.text("codeVerifier")!!)
                    nativeJson("accessToken" to "primary-token", "refreshToken" to "primary-refresh")
                }
                "redirect-client-auth-code" -> {
                    assertEquals("Bearer $activePrimary", request.headers["Authorization"])
                    assertEquals(setOf("redirectClientId", "redirectUrl", "codeChallenge"), body.keySet())
                    assertEquals("TMLEV-ANDROID-APP", body.text("redirectClientId"))
                    legacyChallenge = body.text("codeChallenge")!!
                    nativeJson("authCode" to "synthetic-legacy-code")
                }
                "refresh-token" -> {
                    assertEquals(nativeJson("refreshToken" to "primary-refresh"), body)
                    assertEquals("Bearer $activePrimary", request.headers["Authorization"])
                    activePrimary = "primary-updated"
                    nativeJson("accessToken" to "primary-updated")
                }
                else -> error("Unexpected synthetic request")
            }
        }
        assertEquals("TMLEV-ANDROID-APP", request.headers["client_id"])
        assertEquals("synthetic-legacy-key", request.headers["x-api-key"])
        assertEquals("synthetic-secret", request.headers["client_secret"])
        return when (request.url.substringAfterLast('/')) {
            "access-token" -> {
                assertEquals(setOf("codeVerifier"), body.keySet())
                checkPkce(legacyChallenge, body.text("codeVerifier")!!)
                assertEquals("synthetic-legacy-code", request.headers["authCode"])
                assertEquals("existing", request.headers["userType"])
                assertEquals("false", request.headers["convertToLoyal"])
                assertEquals("26.9.1", request.headers["App-Version"])
                nativeJson("results" to nativeJson("accessTokenDetails" to nativeJson("accessToken" to "legacy-token",
                    "refreshToken" to "legacy-refresh"), "fetchCustomerDetails" to nativeJson("brandData" to nativeJson("fixture" to true))))
            }
            "refresh-token" -> {
                assertEquals(nativeJson("refreshToken" to "legacy-refresh"), body)
                assertEquals("Bearer legacy-token", request.headers["Authorization"])
                nativeJson("results" to nativeJson("accessToken" to "legacy-updated"))
            }
            else -> error("Unexpected synthetic endpoint")
        }
    }
    private fun checkPkce(challenge: String, verifier: String) {
        assertTrue(verifier.matches(Regex("[a-zA-Z0-9._~-]{43,128}")))
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))), challenge)
    }
}

class NativeAuthServiceTest {
    @Test fun invalidPhoneCannotBootstrapOrSendOtp() = runTest {
        val server = AuthMockServer()
        val auth = NativeAuthService(server, AuthMemoryStore())
        for (phone in listOf("", "123", "+910000000000", "0000000000\n", "１２３４５６７８９０"))
            expectSafeError { auth.requestCode(phone) }
        assertTrue(server.requests.isEmpty())
    }

    @Test fun bothExplicitConsentsAndValidCodeAreRequiredBeforeNetwork() = runTest {
        val server = AuthMockServer()
        val auth = NativeAuthService(server, AuthMemoryStore())
        val challenge = OtpChallenge("0000000000", "synthetic-reference", emptyMap())
        for ((terms, privacy) in listOf(false to false, true to false, false to true))
            expectSafeError { auth.verifyCode(challenge, "000000", terms, privacy) }
        for (otp in listOf("123", "123456789", "１２３４", "0000\n", "abcd"))
            expectSafeError { auth.verifyCode(challenge, otp, true, true) }
        for (ref in listOf("", " ", "x".repeat(2049), "ref\n"))
            expectSafeError { auth.verifyCode(OtpChallenge("0000000000", ref, emptyMap()), "000000", true, true) }
        assertTrue(server.requests.isEmpty())
    }

    @Test fun malformedChallengeResponsesCannotBecomeOtpChallenges() = runTest {
        for (response in listOf(JsonObject(), nativeJson("refId" to 123), nativeJson("refId" to ""),
            nativeJson("refId" to "x".repeat(2049)), nativeJson("refId" to "ref", "loyalCustomer" to true),
            nativeJson("refId" to "ref", "success" to false), nativeJson("refId" to "ref", "success" to "true"),
            nativeJson("refId" to "ref", "errorData" to nativeJson("body" to "synthetic-private-body")))) {
            val server = AuthMockServer().apply { failPath = "check-phone"; replacement = response }
            expectSafeError { NativeAuthService(server, AuthMemoryStore()).requestCode("0000000000") }
            assertEquals(2, server.requests.size)
        }
    }

    @Test fun verifyFailureNeverExchangesCodeOrSavesSession() = runTest {
        for (response in listOf(JsonObject(), nativeJson("authCode" to 123), nativeJson("authCode" to " "),
            nativeJson("authCode" to "synthetic-code", "success" to false),
            nativeJson("authCode" to "synthetic-code", "errorData" to "synthetic-private-body"))) {
            val server = AuthMockServer()
            val store = AuthMemoryStore()
            val auth = NativeAuthService(server, store)
            val challenge = auth.requestCode("0000000000")
            server.failPath = "verify-phone-otp"
            server.replacement = response
            expectSafeError { auth.verifyCode(challenge, "000000", true, true) }
            assertEquals(3, server.requests.size)
            assertTrue(store.snapshots.isEmpty())
        }
    }

    @Test fun malformedPrimaryTokensNeverPersistAuthentication() = runTest {
        for (response in listOf(JsonObject(), nativeJson("accessToken" to true), nativeJson("accessToken" to "token\n"),
            nativeJson("accessToken" to "primary-token", "refreshToken" to 123))) {
            val server = AuthMockServer()
            val store = AuthMemoryStore()
            val auth = NativeAuthService(server, store)
            val challenge = auth.requestCode("0000000000")
            server.failPath = "https://customer.auth.ev.tatamotors.io/v1/auth/access-token"
            server.replacement = response
            expectSafeError { auth.verifyCode(challenge, "000000", true, true) }
            assertTrue(store.snapshots.isEmpty())
        }
    }

    @Test fun malformedLegacyTokenPreservesPrimaryOnly() = runTest {
        for (response in listOf(nativeJson("results" to "bad"), nativeJson("results" to nativeJson("accessTokenDetails" to true)),
            nativeJson("results" to nativeJson("accessTokenDetails" to nativeJson("accessToken" to "legacy-token", "refreshToken" to false))))) {
            val server = AuthMockServer()
            val store = AuthMemoryStore()
            val auth = NativeAuthService(server, store)
            val challenge = auth.requestCode("0000000000")
            server.failPath = "https://evcx.api.tatamotors/mobile/customer/api/v1/access-token"
            server.replacement = response
            expectSafeError { auth.verifyCode(challenge, "000000", true, true) }
            assertEquals("primary-token", store.session!!.primary.text("accessToken"))
            assertEquals(0, store.session!!.legacy.size())
        }
    }

    @Test fun malformedPrimaryRefreshCannotCorruptPreviouslySavedTokens() = runTest {
        val server = AuthMockServer()
        val store = AuthMemoryStore()
        val auth = NativeAuthService(server, store)
        val session = auth.verifyCode(auth.requestCode("0000000000"), "000000", true, true)
        server.failPath = "https://customer.auth.ev.tatamotors.io/v1/auth/refresh-token"
        for (response in listOf(nativeJson("accessToken" to 42), nativeJson("accessToken" to "primary-updated", "refreshToken" to false),
            nativeJson("accessToken" to "primary-updated", "success" to false))) {
            server.replacement = response
            expectSafeError { auth.refresh(session) }
            assertEquals("primary-token", session.primary.text("accessToken"))
            assertEquals("primary-refresh", session.primary.text("refreshToken"))
        }
    }

    @Test fun malformedLegacyRefreshPreservesNewPrimaryAndOldLegacy() = runTest {
        val server = AuthMockServer()
        val store = AuthMemoryStore()
        val auth = NativeAuthService(server, store)
        val session = auth.verifyCode(auth.requestCode("0000000000"), "000000", true, true)
        server.failPath = "https://evcx.api.tatamotors/mobile/customer/api/v1/refresh-token"
        server.replacement = nativeJson("results" to nativeJson("accessToken" to "legacy-updated", "refreshToken" to JsonObject()))
        expectSafeError { auth.refresh(session) }
        assertEquals("primary-updated", store.session!!.primary.text("accessToken"))
        assertEquals("legacy-token", session.legacy.text("accessToken"))
        assertEquals("legacy-refresh", session.legacy.text("refreshToken"))
    }

    @Test fun missingLegacyRefreshOrAccountRepairsViaRedirectWithoutOtp() = runTest {
        for (missing in listOf("refresh", "account", "access")) {
            val server = AuthMockServer()
            val store = AuthMemoryStore()
            val auth = NativeAuthService(server, store)
            val session = auth.verifyCode(auth.requestCode("0000000000"), "000000", true, true)
            when (missing) {
                "refresh" -> session.legacy.remove("refreshToken")
                "access" -> session.legacy.remove("accessToken")
                "account" -> session.account = JsonObject()
            }
            server.requests.clear()
            auth.refresh(session)
            assertEquals("legacy-token", session.legacy.text("accessToken"))
            assertEquals("legacy-refresh", session.legacy.text("refreshToken"))
            assertTrue(session.account.size() > 0)
            assertEquals(4, server.requests.size)
            assertTrue(server.requests.any { it.url.endsWith("redirect-client-auth-code") })
        }
    }

    @Test fun partialPrimaryWithoutRefreshCanRepairUsingExistingAccessToken() = runTest {
        val server = AuthMockServer()
        val store = AuthMemoryStore()
        val keys = NativeAuthService(server, store).requestCode("0000000000").keys
        val session = NativeSession("0000000000", keys.toMutableMap(), nativeJson("accessToken" to "primary-token"))
        server.requests.clear()
        NativeAuthService(server, store).refresh(session)
        assertEquals(3, server.requests.size)
        assertEquals("legacy-token", session.legacy.text("accessToken"))
    }

    @Test fun restoreAndClearAreLocalOnly() = runTest {
        val server = AuthMockServer()
        val store = AuthMemoryStore()
        val auth = NativeAuthService(server, store)
        assertNull(auth.restore())
        val session = NativeSession("0000000000", mutableMapOf(), nativeJson("accessToken" to "synthetic-token"))
        store.save(session)
        assertSame(session, auth.restore())
        auth.clear()
        assertNull(auth.restore())
        assertTrue(server.requests.isEmpty())
    }

    @Test fun transportCancellationIsNotConvertedIntoAuthenticationFailure() = runTest {
        val auth = NativeAuthService(NativeTransport { throw kotlinx.coroutines.CancellationException("cancel") }, AuthMemoryStore())
        try { auth.requestCode("0000000000"); fail("Expected cancellation") }
        catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun failedPersistenceIsSanitizedAndStopsLegacyIntegration() = runTest {
        val server = AuthMockServer()
        val store = object : NativeSessionStore {
            override fun load(): NativeSession? = throw IllegalStateException("synthetic-private-body")
            override fun save(session: NativeSession) { throw IllegalStateException("synthetic-token") }
            override fun clear() { throw IllegalStateException("synthetic-token") }
        }
        val auth = NativeAuthService(server, store)
        expectSafeError { auth.restore() }
        expectSafeError { auth.clear() }
        expectSafeError { auth.verifyCode(auth.requestCode("0000000000"), "000000", true, true) }
        assertTrue(server.requests.none { it.url.endsWith("redirect-client-auth-code") })
    }
    @Test fun refreshUpdatesPrimaryThenLegacyRetainingRefreshTokens() = runTest {
        val server = AuthMockServer()
        val store = AuthMemoryStore()
        val auth = NativeAuthService(server, store)
        val session = auth.verifyCode(auth.requestCode("0000000000"), "000000", true, true)
        server.requests.clear()
        store.snapshots.clear()
        assertSame(session, auth.refresh(session))
        assertEquals("primary-updated", session.primary.text("accessToken"))
        assertEquals("legacy-updated", session.legacy.text("accessToken"))
        assertEquals("primary-refresh", session.primary.text("refreshToken"))
        assertEquals("legacy-refresh", session.legacy.text("refreshToken"))
        assertEquals(2, server.requests.size)
        assertTrue(server.requests.first().url.startsWith("https://customer.auth.ev.tatamotors.io/"))
        assertEquals("primary-updated", store.snapshots.first().primary.text("accessToken"))
        assertEquals("legacy-token", store.snapshots.first().legacy.text("accessToken"))
    }

    @Test fun failedLegacyRefreshPreservesImmediatelySavedPrimaryAndSafeStatus() = runTest {
        val server = AuthMockServer()
        val store = AuthMemoryStore()
        val auth = NativeAuthService(server, store)
        val session = auth.verifyCode(auth.requestCode("0000000000"), "000000", true, true)
        server.failPath = "https://evcx.api.tatamotors/mobile/customer/api/v1/refresh-token"
        val error = expectSafeError { auth.refresh(session) }
        assertEquals(503, error.statusCode)
        assertEquals("Couldn't reconnect your vehicles. Please try again.", error.message)
        assertEquals("primary-updated", store.snapshots.last().primary.text("accessToken"))
        assertEquals("legacy-token", store.snapshots.last().legacy.text("accessToken"))
    }

    @Test fun failedLegacyLoginCanRestoreAndRepairWithoutAnotherOtp() = runTest {
        val server = AuthMockServer()
        val store = AuthMemoryStore()
        val auth = NativeAuthService(server, store)
        val challenge = auth.requestCode("0000000000")
        server.failPath = "redirect-client-auth-code"
        val error = expectSafeError { auth.verifyCode(challenge, "000000", true, true) }
        assertEquals("Couldn't load your vehicles. Please try again.", error.message)
        val before = server.requests.size
        val session = auth.restore()!!
        assertEquals(before, server.requests.size)
        assertEquals("primary-token", session.primary.text("accessToken"))
        assertEquals(0, session.legacy.size())
        server.failPath = null
        server.requests.clear()
        auth.refresh(session)
        assertEquals("legacy-token", session.legacy.text("accessToken"))
        assertEquals("synthetic-vehicle-key", session.keys["n_io_b2c_api_key"])
        assertTrue(server.requests.none { it.url.endsWith("check-phone") || it.url.endsWith("verify-phone-otp") })
        assertEquals(4, server.requests.size)
    }

    @Test fun afterLoginBootstrapFailurePreservesBothSessionsThenRetriesConfiguration() = runTest {
        val server = AuthMockServer()
        val store = AuthMemoryStore()
        val auth = NativeAuthService(server, store)
        val challenge = auth.requestCode("0000000000")
        server.failPath = "security/ecdh-key-exchange"
        expectSafeError { auth.verifyCode(challenge, "000000", true, true) }
        val session = auth.restore()!!
        assertEquals("legacy-token", session.legacy.text("accessToken"))
        assertNotNull(session.account.objectValue("fetchCustomerDetails"))
        server.failPath = null
        // Keep the fixture's legacy access token stable for the subsequent authenticated bootstrap.
        val repairTransport = NativeTransport { request ->
            if (request.url.endsWith("/refresh-token") && request.url.contains("evcx.api"))
                nativeJson("results" to nativeJson("accessToken" to "legacy-token"))
            else server.execute(request)
        }
        NativeAuthService(repairTransport, store).refresh(session)
        assertEquals("synthetic-vehicle-key", session.keys["n_io_b2c_api_key"])
    }

    @Test fun authCodeEndpointsDoNotTreatOptionalSuccessMetadataAsTheCredential() = runTest {
        val server = AuthMockServer()
        val store = AuthMemoryStore()
        val transport = NativeTransport { request ->
            server.execute(request).apply {
                if (request.url.endsWith("verify-phone-otp") || request.url.endsWith("redirect-client-auth-code"))
                    addProperty("success", "true")
            }
        }
        val auth = NativeAuthService(transport, store)
        val session = auth.verifyCode(auth.requestCode("0000000000"), "000000", true, true)
        assertEquals("primary-token", session.primary.text("accessToken"))
        assertEquals("legacy-token", session.legacy.text("accessToken"))
        assertEquals(7, server.requests.size)
        assertTrue(server.requests.any { it.url.endsWith("/access-token") })
    }

    @Test fun nullableSuccessWithAValidAuthCodeFollowsTheRecoveredNativeLoginContract() = runTest {
        val server = AuthMockServer()
        val store = AuthMemoryStore()
        val transport = NativeTransport { request ->
            server.execute(request).apply {
                if (request.url.endsWith("verify-phone-otp")) add("success", com.google.gson.JsonNull.INSTANCE)
            }
        }
        val auth = NativeAuthService(transport, store)
        val session = auth.verifyCode(auth.requestCode("0000000000"), "000000", true, true)
        assertEquals("primary-token", session.primary.text("accessToken"))
        assertEquals("legacy-token", session.legacy.text("accessToken"))
        assertEquals(7, server.requests.size)
    }

    @Test fun failedVerificationRecordsOnlyItsStageAndHttpStatus() = runTest {
        val server = AuthMockServer()
        val store = AuthMemoryStore()
        val auth = NativeAuthService(server, store)
        val challenge = auth.requestCode("0000000000")
        server.failPath = "verify-phone-otp"
        val error = expectSafeError { auth.verifyCode(challenge, "000000", true, true) }
        assertEquals(listOf(Triple("verify-phone-otp", 503, "HTTP")), store.failures)
        assertEquals("Couldn't verify the code. Please try again.", error.message)
        assertEquals(503, error.statusCode)
        assertTrue(store.snapshots.isEmpty())
    }

    private suspend fun expectSafeError(block: suspend () -> Unit): NativeApiException {
        try { block() } catch (error: NativeApiException) {
            assertFalse(error.toString().contains("synthetic-token"))
            assertFalse(error.toString().contains("synthetic-private-body"))
            assertNull(error.cause)
            return error
        }
        throw AssertionError("Expected sanitized NativeApiException")
    }
    @Test fun syntheticFullLoginSavesPrimaryLegacyAccountAndDynamicConfiguration() = runTest {
        val server = AuthMockServer()
        val store = AuthMemoryStore()
        val auth = NativeAuthService(server, store)
        val challenge = auth.requestCode("0000000000")
        assertEquals("synthetic-reference", challenge.reference)
        assertEquals("Y", challenge.loyalCustomer)
        val session = auth.verifyCode(challenge, "000000", true, true)
        assertEquals("primary-token", session.primary.text("accessToken"))
        assertEquals("legacy-token", session.legacy.text("accessToken"))
        assertNotNull(session.account.objectValue("fetchCustomerDetails"))
        assertEquals("synthetic-vehicle-key", session.keys["n_io_b2c_api_key"])
        assertSame(session, store.session)
        assertEquals(7, server.requests.size)
        assertTrue(store.snapshots.any { it.primary.text("accessToken") != null && it.legacy.size() == 0 })
        assertTrue(store.snapshots.any { it.legacy.text("accessToken") != null && !it.keys.containsKey("n_io_b2c_api_key") })
    }
}

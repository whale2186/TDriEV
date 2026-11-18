package dev.nemesis.drive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.nemesis.drive.nativeapi.*
import dev.nemesis.drive.platform.OwnerHttpsTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Opt-in diagnostic of a deliberately invalid account. Never requests an SMS or uses owner data. */
@RunWith(AndroidJUnit4::class)
class OwnerAuthDiagnosticsSmokeTest {
    @Test fun publicValidationErrorRetainsSafeServiceCodeOnAndroid() {
        assumeTrue(!BuildConfig.SIMULATOR)
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowSyntheticAuthDiagnostic") == "true")
        runBlocking {
            val https = OwnerHttpsTransport()
            val bootstrap = NativeBootstrap()
            val keys = bootstrap.decrypt(https.execute(bootstrap.request()))
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().toByteArray()))
            val request = NativeRequest("POST", "https://customer.auth.ev.tatamotors.io/v1/auth/verify-phone-otp",
                mapOf("client_id" to "TMLEV-NIO-ANDROID-APP", "x-api-key" to requireNotNull(keys["nio_auth_x_api_key"]),
                    "User-Agent" to "Dart/3.9 (dart:io)"),
                nativeJson("countryCode" to "91", "phone" to "0000000000", "otp" to "000000",
                    "refId" to UUID.randomUUID().toString(), "programTnC" to true,
                    "privacyPolicyAccepted" to true, "marketingCommunication" to false,
                    "loyalCustomer" to "N", "storeId" to "store_id", "redirectUrl" to "https://prod-ev.tatamotors.com/",
                    "codeChallenge" to challenge, "consentType" to "Explicit"))
            try { https.execute(request); fail("Invalid synthetic account was accepted") }
            catch (failure: NativeApiException) {
                assertEquals(400, failure.statusCode)
                assertEquals("SSO-1026", failure.serviceCode)
                assertNull(failure.cause)
            }
        }
    }
}

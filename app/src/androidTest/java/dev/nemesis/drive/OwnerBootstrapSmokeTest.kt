package dev.nemesis.drive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.nemesis.drive.nativeapi.NativeBootstrap
import dev.nemesis.drive.platform.OwnerHttpsTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicitly opt-in public handshake only. Never constructs an auth/vehicle service,
 * requests an OTP, reads a session, or persists/prints decrypted configuration.
 * Run separately with -e allowPublicBootstrap true on the owner test variant.
 */
@RunWith(AndroidJUnit4::class)
class OwnerBootstrapSmokeTest {
    @Test fun publicBeforeLoginExchangeDecryptsOnAndroid() {
        assumeTrue("Owner variant required", !BuildConfig.SIMULATOR)
        assumeTrue("Public bootstrap must be explicitly enabled",
            InstrumentationRegistry.getArguments().getString("allowPublicBootstrap") == "true")
        runBlocking {
            val bootstrap = NativeBootstrap()
            val request = bootstrap.request("beforeLogin")
            // Single fixed public endpoint. No auth/account or vehicle traffic.
            val envelope = OwnerHttpsTransport().execute(request)
            val keys = bootstrap.decrypt(envelope)
            for (name in listOf("nio_auth_x_api_key", "x_api_key", "client_secret_value")) {
                assertTrue("Required runtime configuration is missing", !keys[name].isNullOrBlank())
            }
        }
    }
}

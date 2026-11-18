package dev.nemesis.drive.nativeapi

import org.junit.Assert.*
import org.junit.Test

class NativeEndpointPolicyTest {
    @Test fun recoveredEndpointsAreAccepted() {
        NativeEndpointPolicy.validate("POST", "https://customer.auth.ev.tatamotors.io/v1/auth/check-phone")
    }

    @Test fun completeRecoveredSurfaceAndCrmQueryAreAccepted() {
        val auth = "https://customer.auth.ev.tatamotors.io/v1/auth/"
        listOf("check-phone", "verify-phone-otp", "access-token", "redirect-client-auth-code", "refresh-token")
            .forEach { NativeEndpointPolicy.validate("POST", auth + it) }
        val base = "https://evcx.api.tatamotors/mobile/"
        listOf("access-token", "refresh-token", "security/ecdh-key-exchange")
            .forEach { NativeEndpointPolicy.validate("POST", base + "customer/api/v1/" + it) }
        NativeEndpointPolicy.validate("POST", base + "service/api/v3/get-user-vehicles-info-mobile")
        listOf("vehicle-health", "vehicle-state", "check-device-status", "wake-up-device", "vehicle-command-status")
            .forEach { NativeEndpointPolicy.validate("GET", base + "cvp/api/v1/ev/" + it) }
        listOf("command-log", "vehicle-command", "process-remote-command", "get-remote-command-status")
            .forEach { NativeEndpointPolicy.validate("POST", base + "cvp/api/v1/ev/" + it) }
        NativeEndpointPolicy.validate("GET", "https://customer.api.ev.tatamotors.io/v1/vehicles/list")
        NativeEndpointPolicy.validate("GET", "https://customer.api.ev.tatamotors.io/v1/vehicles/list?crm-id=synthetic%2Fcrm+id")
    }

    @Test fun hostileUrlVariantsAreRejectedWithoutEchoingPrivateValues() {
        val allowed = "https://customer.auth.ev.tatamotors.io/v1/auth/check-phone"
        val bad = listOf(
            allowed.replace("https:", "http:"), allowed.replace("https:", "HTTPS:"),
            allowed.replace("customer.auth.ev.tatamotors.io", "redirect.invalid"),
            allowed.replace("customer.auth.ev.tatamotors.io", "customer.auth.ev.tatamotors.io.evil.invalid"),
            allowed.replace("https://", "https://secret@"),
            allowed.replace(".io/", ".io:443/"), allowed + "?token=secret", allowed + "#secret",
            allowed + "?", allowed + "#", allowed + "/", allowed.replace("check-phone", "%63heck-phone"),
            allowed.replace("/auth/", "/auth/../auth/"), allowed.replace("/auth/", "//auth/"),
            allowed.replace("/auth/", "/auth%2F"), " " + allowed, allowed + "\n",
            allowed.replace("https://", "https://secret:secret@"))
        bad.forEach { reject("POST", it) }
        listOf("GET", "post", "PUT", "DELETE", "HEAD", " POST", "POST\n").forEach { reject(it, allowed) }
    }

    @Test fun crmQueryIsExactlyOneBoundedNonControlValue() {
        val base = "https://customer.api.ev.tatamotors.io/v1/vehicles/list"
        listOf("?crm-id=", "?crm-id=a&crm-id=b", "?crm-id=a&other=b", "?other=a",
            "?crm-id=%00", "?crm-id=%0A", "?crm-id=%7F", "?crm-id=%C0%AF", "?crm-id=%zz",
            "?crm-id=" + "a".repeat(257), "?crm-id=a#fragment", "?crm-id=a=b").forEach { reject("GET", base + it) }
        reject("POST", base + "?crm-id=a")
    }

    private fun reject(method: String, url: String) {
        try {
            NativeEndpointPolicy.validate(method, url)
            fail("Unsafe endpoint accepted")
        } catch (error: NativeApiException) {
            assertEquals("Endpoint is not permitted", error.message)
            assertNull(error.cause)
        }
    }
}

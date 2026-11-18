package dev.nemesis.drive.nativeapi

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Recovered from client/ira_auth.py HttpsTransport; never normalize an untrusted URL.
 * Command intent, ownership and durable reservations are enforced separately by the service.
 */
object NativeEndpointPolicy {
    private const val AUTH = "https://customer.auth.ev.tatamotors.io/v1/auth/"
    private const val LEGACY = "https://evcx.api.tatamotors/mobile/"
    private const val VEHICLES = "https://customer.api.ev.tatamotors.io/v1/vehicles/list"
    private val endpoints: Set<Pair<String, String>> = buildSet {
        listOf("check-phone", "verify-phone-otp", "access-token", "redirect-client-auth-code", "refresh-token")
            .forEach { add("POST" to AUTH + it) }
        listOf("access-token", "refresh-token", "security/ecdh-key-exchange")
            .forEach { add("POST" to LEGACY + "customer/api/v1/" + it) }
        add("GET" to VEHICLES)
        add("POST" to LEGACY + "service/api/v3/get-user-vehicles-info-mobile")
        listOf("vehicle-health", "vehicle-state", "check-device-status", "wake-up-device", "vehicle-command-status")
            .forEach { add("GET" to LEGACY + "cvp/api/v1/ev/" + it) }
        listOf("command-log", "vehicle-command", "process-remote-command", "get-remote-command-status")
            .forEach { add("POST" to LEGACY + "cvp/api/v1/ev/" + it) }
    }

    fun validate(method: String, url: String) {
        if ((method to url) in endpoints) return
        if (method == "GET" && url.startsWith("$VEHICLES?crm-id=") && validCrm(url.substringAfter("?crm-id="))) return
        throw NativeApiException("Endpoint is not permitted")
    }

    private fun validCrm(encoded: String): Boolean {
        if (encoded.isEmpty() || encoded.length > 3072) return false
        return try {
            val bytes = ByteArrayOutputStream()
            var index = 0
            while (index < encoded.length) {
                val c = encoded[index++]
                when {
                    c == '%' -> {
                        if (index + 2 > encoded.length) return false
                        val value = encoded.substring(index, index + 2).toIntOrNull(16) ?: return false
                        bytes.write(value)
                        index += 2
                    }
                    c == '+' -> bytes.write(32)
                    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~" -> bytes.write(c.code)
                    else -> return false
                }
            }
            val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            value.codePointCount(0, value.length) in 1..256 && value.none { Character.isISOControl(it) }
        } catch (_: Exception) { false }
    }
}

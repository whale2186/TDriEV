package dev.nemesis.drive.nativeapi

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Entirely synthetic server; no account material or sockets. */
internal fun encryptBootstrap(request: NativeRequest, secrets: JsonObject): JsonObject {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    val server = generator.generateKeyPair()
    val client = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(
        Base64.getDecoder().decode(request.body!!.text("clientEphemeralPublicKey"))))
    val agreement = KeyAgreement.getInstance("ECDH")
    agreement.init(server.private)
    agreement.doPhase(client, true)
    val shared = agreement.generateSecret()
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(shared.copyOf(32), "AES"), IvParameterSpec(shared.copyOf(16)))
    return nativeJson("results" to nativeJson(
        "serverEphemeralPublicKey" to Base64.getEncoder().encodeToString(server.public.encoded),
        "encryptedSecrets" to Base64.getEncoder().encodeToString(cipher.doFinal(secrets.toString().toByteArray(Charsets.UTF_8)))))
}

class NativeBootstrapTest {
    @Test fun rejectsMalformedCiphertextAndWrongEphemeralKey() {
        val bootstrap = NativeBootstrap()
        val request = bootstrap.request()
        val secrets = nativeJson("nio_auth_x_api_key" to "synthetic-key-long-enough-for-multiple-blocks")
        val envelope = encryptBootstrap(request, secrets)
        safeFailure { NativeBootstrap().decrypt(envelope) }
        val results = envelope.objectValue("results")!!
        val ciphertext = Base64.getDecoder().decode(results.text("encryptedSecrets"))
        val pad = 16 - secrets.toString().toByteArray(Charsets.UTF_8).size % 16
        ciphertext[ciphertext.size - 17] = (ciphertext[ciphertext.size - 17].toInt() xor pad).toByte()
        results.addProperty("encryptedSecrets", Base64.getEncoder().encodeToString(ciphertext))
        safeFailure { bootstrap.decrypt(envelope) }
        results.addProperty("encryptedSecrets", "not-base64-private-token")
        safeFailure { bootstrap.decrypt(envelope) }
        results.addProperty("encryptedSecrets", Base64.getEncoder().encodeToString(ByteArray(17)))
        safeFailure { bootstrap.decrypt(envelope) }
    }

    @Test fun rejectsBadEnvelopeAndNonStringRuntimeFields() {
        val bootstrap = NativeBootstrap()
        val request = bootstrap.request()
        for (response in listOf(JsonObject(), nativeJson("results" to "private-value"),
            nativeJson("success" to false), nativeJson("errorData" to nativeJson("secret" to "private-value")),
            encryptBootstrap(request, nativeJson("key" to 123)), encryptBootstrap(request, nativeJson("key" to "header\r\ninjection")),
            encryptBootstrap(request, nativeJson("key" to nativeJson("nested" to true))))) {
            safeFailure { bootstrap.decrypt(response) }
        }
        val response = encryptBootstrap(request, nativeJson("key" to "synthetic"))
        response.objectValue("results")!!.addProperty("serverEphemeralPublicKey", "malformed-private-token")
        safeFailure { bootstrap.decrypt(response) }
    }

    @Test fun afterLoginRequiresOwnerTokenAndFreshInstancesHaveDifferentIdentities() {
        val first = NativeBootstrap()
        safeFailure { first.request("afterLogin") }
        safeFailure { first.request("unknown") }
        safeFailure { first.request("afterLogin", "token\r\ninjection") }
        val one = first.request("afterLogin", "synthetic-owner-token")
        val two = NativeBootstrap().request()
        assertEquals("Bearer synthetic-owner-token", one.headers["Authorization"])
        assertEquals("afterLogin", one.body!!.text("apiType"))
        assertEquals(setOf("clientEphemeralPublicKey", "signature", "deviceId", "clientNonce", "apiType"), one.body!!.keySet())
        for (field in listOf("clientEphemeralPublicKey", "deviceId", "clientNonce"))
            assertNotEquals(one.body!!.text(field), two.body!!.text(field))
        assertTrue(Base64.getDecoder().decode(one.body!!.text("signature")).size in 68..72)
    }

    private fun safeFailure(block: () -> Unit) {
        try { block() } catch (error: NativeApiException) {
            assertFalse(error.toString().contains("private"))
            assertNull(error.cause)
            return
        }
        throw AssertionError("Expected safe bootstrap rejection")
    }
    @Test fun realEcdhDecryptsMockServerConfiguration() {
        val bootstrap = NativeBootstrap()
        val request = bootstrap.request()
        assertEquals("POST", request.method)
        assertEquals("https://evcx.api.tatamotors/mobile/customer/api/v1/security/ecdh-key-exchange", request.url)
        assertEquals("beforeLogin", request.body!!.text("apiType"))
        assertEquals("TMLEV-ANDROID-APP", request.headers["client_id"])
        assertEquals("26.9.1", request.headers["App-Version"])
        assertFalse(request.headers.containsKey("Authorization"))
        assertEquals(32, Base64.getDecoder().decode(request.body!!.text("clientNonce")).size)
        assertTrue(request.body!!.text("deviceId")!!.matches(Regex("[a-f0-9]{16}")))
        assertEquals(mapOf("nio_auth_x_api_key" to "synthetic-key"), bootstrap.decrypt(
            encryptBootstrap(request, nativeJson("nio_auth_x_api_key" to "synthetic-key"))))
    }
}

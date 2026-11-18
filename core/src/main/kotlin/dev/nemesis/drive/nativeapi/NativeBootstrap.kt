package dev.nemesis.drive.nativeapi

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Fresh isolated identity and P-256 ephemeral key, never an Android/account identity.
 * The recovered protocol uses raw ECDH-derived AES-CBC, NOT authenticated encryption.
 * Padding/structure errors fail closed, but CBC cannot detect every modification;
 * server authentication and integrity depend on the supplied verified HTTPS transport.
 */
class NativeBootstrap {
    private val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
    private val ephemeral = generator.generateKeyPair()
    private val identity = generator.generateKeyPair()
    private val random = SecureRandom()
    private val deviceId = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private val nonce = ByteArray(32).also(random::nextBytes)

    fun request(stage: String = "beforeLogin", accessToken: String? = null): NativeRequest {
        if (stage !in setOf("beforeLogin", "afterLogin") ||
            (stage == "afterLogin" && !usable(accessToken))) throw NativeApiException("Invalid bootstrap stage or owner session")
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(identity.private)
            update(ephemeral.public.encoded)
            sign()
        }
        val headers = mutableMapOf("Accept" to "application/json", "Content-Type" to "application/json",
            "client_id" to "TMLEV-ANDROID-APP", "App-Version" to "26.9.1",
            "User-Agent" to "Dart/3.9 (dart:io)", "X-Request-ID" to UUID.randomUUID().toString())
        if (stage == "afterLogin") headers["Authorization"] = "Bearer $accessToken"
        return NativeRequest("POST", "https://evcx.api.tatamotors/mobile/customer/api/v1/security/ecdh-key-exchange",
            headers, nativeJson("clientEphemeralPublicKey" to encode(ephemeral.public.encoded),
                "signature" to encode(signature), "deviceId" to deviceId, "clientNonce" to encode(nonce), "apiType" to stage))
    }

    fun decrypt(envelope: JsonObject): Map<String, String> {
        try {
            checkedNativeResponse(envelope)
            val results = envelope.objectValue("results") ?: throw IllegalArgumentException()
            val publicBytes = decode(results.text("serverEphemeralPublicKey") ?: throw IllegalArgumentException())
            val public = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicBytes)) as ECPublicKey
            val local = ephemeral.public as ECPublicKey
            require(public.params.curve == local.params.curve && public.params.generator == local.params.generator &&
                public.params.order == local.params.order && public.params.cofactor == local.params.cofactor)
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(ephemeral.private)
            agreement.doPhase(public, true)
            val shared = agreement.generateSecret()
            val ciphertext = decode(results.text("encryptedSecrets") ?: throw IllegalArgumentException())
            require(ciphertext.isNotEmpty() && ciphertext.size % 16 == 0 && ciphertext.size <= 2 * 1024 * 1024)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(shared.copyOf(32), "AES"), IvParameterSpec(shared.copyOf(16)))
            val plaintext = try { cipher.doFinal(ciphertext) } finally { shared.fill(0) }
            val json = try { JsonParser.parseString(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(plaintext)).toString()) }
                finally { plaintext.fill(0) }
            require(json.isJsonObject)
            return json.asJsonObject.entrySet().associate { (key, value) ->
                require(key.isNotBlank() && value.isJsonPrimitive && value.asJsonPrimitive.isString)
                val text = value.asString
                require(usable(text))
                key to text
            }
        } catch (_: Exception) {
            throw NativeApiException("Runtime configuration could not be decoded")
        }
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(value: String): ByteArray {
        require(value.length <= 3 * 1024 * 1024 && value.isNotEmpty())
        val decoded = Base64.getDecoder().decode(value)
        require(encode(decoded) == value) // Reject whitespace, absent padding and noncanonical encodings.
        return decoded
    }
}

internal fun usable(value: String?): Boolean = value != null && value.isNotBlank() && value.length <= 16384 && value.none { it.isISOControl() }

/** Shared auth/bootstrap validation. Never surface server-supplied descriptions or bodies. */
internal fun checkedNativeResponse(response: JsonObject): JsonObject {
    val error = response.get("errorData")
    val hasError = error != null && !error.isJsonNull && when {
        error.isJsonObject -> error.asJsonObject.size() != 0
        error.isJsonArray -> error.asJsonArray.size() != 0
        error.isJsonPrimitive && error.asJsonPrimitive.isBoolean -> error.asBoolean
        error.isJsonPrimitive && error.asJsonPrimitive.isString -> error.asString.isNotEmpty()
        else -> true
    }
    if (hasError) throw NativeApiException("Server did not accept the authentication request", serviceCode = "APPLICATION_ERROR")
    val success = response.get("success")
    // Recovered native combined login gates token exchange on authCode. The response flag is nullable.
    if (success != null && !success.isJsonNull && response.flag("success") != true)
        throw NativeApiException("Server did not accept the authentication request", serviceCode =
            if (response.flag("success") == false) "APPLICATION_REJECTED" else "SUCCESS_TYPE")
    return response
}

package dev.nemesis.drive.platform

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.nemesis.drive.nativeapi.NativeApiException
import dev.nemesis.drive.nativeapi.NativeEndpointPolicy
import dev.nemesis.drive.nativeapi.NativeRequest
import dev.nemesis.drive.nativeapi.NativeTransport
import dev.nemesis.drive.nativeapi.text
import dev.nemesis.drive.nativeapi.objectValue
import dev.nemesis.drive.nativeapi.safeAuthFailureCode
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

/** No logging, proxy, redirects, automatic retries, custom trust manager or hostname verifier. */
class OwnerHttpsTransport internal constructor(private val calls: Call.Factory) : NativeTransport {
    constructor() : this(createClient())

    override suspend fun execute(request: NativeRequest): JsonObject {
        NativeEndpointPolicy.validate(request.method, request.url)
        val wire = try {
            require(request.method != "GET" || request.body == null)
            val builder = Request.Builder().url(request.url)
            request.headers.forEach { (name, value) ->
                require(name.lowercase(java.util.Locale.ROOT) !in FORBIDDEN_HEADERS)
                require(!name.startsWith("proxy-", ignoreCase = true))
                builder.header(name, value)
            }
            val body = request.body?.toString()?.toByteArray(Charsets.UTF_8)
            require(body == null || body.size <= OwnerJson.LIMIT)
            builder.method(request.method, if (request.method == "POST")
                (body ?: "{}".toByteArray()).toRequestBody("application/json; charset=utf-8".toMediaType()) else null)
                .header("Cache-Control", "no-cache, no-store")
                .build()
        } catch (_: Exception) { throw NativeApiException("Request could not be prepared") }
        return suspendCancellableCoroutine { continuation ->
            val call = try { calls.newCall(wire) }
            catch (_: Exception) {
                continuation.resumeWith(Result.failure(NativeApiException("HTTPS request could not be completed")))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { call.cancel() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(
                            NativeApiException(if (e is HttpStatusFailure) "Server returned HTTP ${e.status}" else "HTTPS request could not be completed",
                                (e as? HttpStatusFailure)?.status, (e as? HttpStatusFailure)?.serviceCode)))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val result = try {
                            response.use {
                                if (!it.isSuccessful) {
                                    val failure = httpFailure(it)
                                    throw NativeApiException("Server returned HTTP ${it.code}", it.code, failure.serviceCode)
                                }
                                val body = it.body ?: throw NativeApiException("Server returned an invalid response", it.code)
                                // OkHttp transparently decodes gzip; cap the decoded stream, not Content-Length.
                                if (it.header("Content-Encoding")?.let { encoding -> !encoding.equals("identity", true) } == true)
                                    throw NativeApiException("Server returned an invalid response", it.code)
                                val bytes = body.byteStream().use { input ->
                                    val output = ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        if (!continuation.isActive) throw IOException()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (output.size() + count > OwnerJson.LIMIT)
                                            throw NativeApiException("Server response exceeded the safety limit", it.code)
                                        output.write(buffer, 0, count)
                                    }
                                    output.toByteArray()
                                }
                                Result.success(OwnerJson.decode(bytes, it.code))
                            }
                        } catch (error: NativeApiException) { Result.failure(error) }
                        catch (_: Exception) { Result.failure(NativeApiException("HTTPS request could not be completed")) }
                        if (continuation.isActive) continuation.resumeWith(result)
                    }
                })
            } catch (_: Exception) {
                call.cancel()
                if (continuation.isActive) continuation.resumeWith(Result.failure(
                    NativeApiException("HTTPS request could not be completed")))
            }
        }
    }

    internal companion object {
        private val FORBIDDEN_HEADERS = setOf("host", "connection", "content-length", "transfer-encoding", "accept-encoding", "upgrade", "expect")
        fun createClient(): OkHttpClient = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
            // OkHttp can otherwise follow a 503 + Retry-After: 0 even with connection retries disabled.
            // Stop every non-2xx response inside the network interceptor, before follow-up handling.
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (!response.isSuccessful) {
                    val failure = httpFailure(response)
                    response.close()
                    throw failure
                }
                val body = response.body ?: return@addNetworkInterceptor response
                if (body.contentLength() > OwnerJson.LIMIT) {
                    response.close()
                    throw IOException()
                }
                // Runs before OkHttp's transparent gzip decoding: cap encoded bytes as well.
                val limited = object : ForwardingSource(body.source()) {
                    private var total = 0L
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val count = super.read(sink, minOf(byteCount, OwnerJson.LIMIT - total + 1))
                        if (count > 0) total += count
                        if (total > OwnerJson.LIMIT) throw IOException()
                        return count
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = limited
                }).build()
            }
            .build()

        private fun httpFailure(response: Response): HttpStatusFailure {
            val serviceCode = runCatching {
                fun limited(input: java.io.InputStream): ByteArray = input.use { stream ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 8192)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val encoded = limited(requireNotNull(response.body).byteStream())
                val decoded = if (response.header("Content-Encoding").equals("gzip", true))
                    limited(java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(encoded))) else encoded
                val json = OwnerJson.decode(decoded, response.code, validateEnvelope = false)
                json.text("code")?.takeIf { it.matches(Regex("SSO-[0-9]{4}")) }
                    ?: when (json.text("message")?.lowercase(java.util.Locale.ROOT)?.trim()) {
                        "invalid request body" -> "INVALID_REQUEST_BODY"
                        "invalid otp", "incorrect otp" -> "INVALID_OTP"
                        "otp expired", "otp has expired" -> "OTP_EXPIRED"
                        "invalid refid", "invalid reference" -> "INVALID_REFERENCE"
                        else -> null
                    }
            }.getOrNull()
            return HttpStatusFailure(response.code, serviceCode)
        }
    }
    private class HttpStatusFailure(val status: Int, val serviceCode: String?) : IOException()
}

/** Gson's convenience parser enables leniency; use a strict reader and reject duplicate keys. */
internal object OwnerJson {
    const val LIMIT = 2 * 1024 * 1024
    fun decode(bytes: ByteArray, status: Int? = null, validateEnvelope: Boolean = true): JsonObject {
        try {
            require(bytes.size <= LIMIT)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val json = JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                val value = read(reader, 0)
                require(reader.peek() == JsonToken.END_DOCUMENT && value.isJsonObject)
                value.asJsonObject
            }
            if (validateEnvelope) {
                val error = json.get("errorData")
                if (error != null && truthy(error)) {
                    val code = safeAuthFailureCode(json.text("code")) ?: safeAuthFailureCode(json.objectValue("errorData")?.text("code"))
                    throw NativeApiException("Server returned an application error", status, code ?: "APPLICATION_ERROR")
                }
                val success = json.get("success")
                if (success != null && success.isJsonPrimitive && success.asJsonPrimitive.isBoolean && !success.asBoolean)
                    throw NativeApiException("Server did not accept the request", status,
                        safeAuthFailureCode(json.text("code")) ?: "APPLICATION_REJECTED")
            }
            return json
        } catch (failure: NativeApiException) { throw failure }
        catch (_: Exception) { throw NativeApiException("Server returned an invalid response", status, "RESPONSE_SCHEMA") }
    }

    private fun read(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= 64)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(!has(name))
                    add(name, read(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) add(read(reader, depth + 1))
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> {
                val number = reader.nextString()
                require(number.length <= 1024)
                JsonPrimitive(BigDecimal(number))
            }
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> throw IllegalArgumentException()
        }
    }
    private fun truthy(value: JsonElement): Boolean = when {
        value.isJsonNull -> false
        value.isJsonObject -> value.asJsonObject.size() != 0
        value.isJsonArray -> value.asJsonArray.size() != 0
        value.asJsonPrimitive.isBoolean -> value.asBoolean
        value.asJsonPrimitive.isString -> value.asString.isNotEmpty()
        else -> value.asBigDecimal.compareTo(BigDecimal.ZERO) != 0
    }
}

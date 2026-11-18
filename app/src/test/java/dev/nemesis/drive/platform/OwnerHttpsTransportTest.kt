package dev.nemesis.drive.platform

import okhttp3.ResponseBody.Companion.toResponseBody
import dev.nemesis.drive.nativeapi.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class OwnerHttpsTransportTest {
    @Test fun onlyRecoveredUrlsAreAllowedBeforeAnySocketCanBeOpened() = runBlocking {
        try {
            OwnerHttpsTransport().execute(NativeRequest("POST", "http://private.invalid/secret"))
            fail("Unsafe URL accepted")
        } catch (error: NativeApiException) {
            assertEquals("Endpoint is not permitted", error.message)
        }
    }

    @Test fun nativeAuthCanReachTheRuntimeTransportWithoutForbiddenCompressionHeaders() = runBlocking {
        val call = ControlledCall(401)
        val auth = NativeAuthService(OwnerHttpsTransport(okhttp3.Call.Factory { call }), object : NativeSessionStore {
            override fun load(): NativeSession? = null
            override fun save(session: NativeSession) {}
            override fun clear() {}
        })
        try {
            auth.verifyCode(OtpChallenge("9999999999", "synthetic-reference", mapOf("nio_auth_x_api_key" to "synthetic-key")),
                "123456", true, true)
            fail("Fixture HTTP401 accepted")
        } catch (_: NativeApiException) { }
        assertEquals("Auth request must reach the cancellable call factory, not fail in header preparation", 1, call.enqueueCount)
    }

    @Test fun defaultClientHasNoProxyRedirectsOrRetries() {
        val client = OwnerHttpsTransport.createClient()
        assertEquals(java.net.Proxy.NO_PROXY, client.proxy)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertTrue(client.callTimeoutMillis in 1..60000)
        assertTrue(client.readTimeoutMillis in 1..60000)
        assertTrue(client.connectTimeoutMillis in 1..60000)
        assertTrue(client.interceptors.isEmpty())
        assertEquals(1, client.networkInterceptors.size) // Non-2xx barrier prevents 503 follow-ups.
    }

    @Test fun strictObjectParserRejectsNonJsonAndApplicationErrors() {
        listOf("[]", "null", "true", "{'key':1}", "{key:1}", "{\"key\":NaN}", "{\"a\":1,}",
            "{\"a\":1} {}", "{\"a\":1,\"a\":2}", "{\"errorData\":{\"code\":1}}", "{\"success\":false}",
            "{\"errorData\":\"backend-private-secret\"}").forEach { body ->
            try { OwnerJson.decode(body.toByteArray()); fail("Unsafe JSON accepted") }
            catch (error: NativeApiException) {
                assertFalse(error.message.orEmpty().contains("backend-private-secret"))
                assertNull(error.cause)
            }
        }
    }

    @Test fun emptyErrorDataMatchesRecoveredPythonFalseyBehavior() {
        listOf("{}", "{\"errorData\":null}", "{\"errorData\":{}}", "{\"errorData\":[]}",
            "{\"errorData\":false}", "{\"errorData\":0}", "{\"errorData\":\"\"}", "{\"success\":true}")
            .forEach { assertTrue(OwnerJson.decode(it.toByteArray()).isJsonObject) }
    }

    @Test fun cancellationCancelsTheExactInFlightCall() = runBlocking {
        val call = ControlledCall()
        val transport = OwnerHttpsTransport(okhttp3.Call.Factory { call })
        val job = launch(start = CoroutineStart.UNDISPATCHED) { transport.execute(readRequest()) }
        assertTrue(call.enqueued)
        job.cancel()
        job.join()
        assertTrue(call.cancelled)
        // A late failure after cancellation must neither resume nor expose its private message.
        call.callback!!.onFailure(call, java.io.IOException("SYNTHETIC_PRIVATE_DETAILS"))
    }

    @Test fun responseStatusesAreSanitizedAndNeverRetried() = runBlocking {
        for (status in listOf(301, 401, 429, 503)) {
            val call = ControlledCall(status)
            try { OwnerHttpsTransport(okhttp3.Call.Factory { call }).execute(readRequest()); fail("HTTP error accepted") }
            catch (error: NativeApiException) {
                assertEquals(status, error.statusCode)
                assertEquals("Server returned HTTP $status", error.message)
                assertNull(error.cause)
            }
            assertEquals(1, call.enqueueCount)
        }
    }

    @Test fun successfulResponseIsReadAndClosed() = runBlocking {
        val call = ControlledCall(200)
        val json = OwnerHttpsTransport(okhttp3.Call.Factory { call }).execute(readRequest())
        assertEquals("SYNTHETIC", json.get("result").asString)
        assertTrue(call.closed)
    }

    private fun readRequest() = NativeRequest("GET", "https://evcx.api.tatamotors/mobile/cvp/api/v1/ev/vehicle-health")

    @Test fun errorResponsesRetainOnlyAllowlistedServiceCodes() = runBlocking {
        for ((wire, expected) in listOf(
            "{\"code\":\"SSO-1026\",\"message\":\"SYNTHETIC_PRIVATE_DETAILS\"}" to "SSO-1026",
            "{\"code\":\"SYNTHETIC_PRIVATE_DETAILS\"}" to null,
            "{\"message\":\"Invalid request body\"}" to "INVALID_REQUEST_BODY",
            "{\"code\":\"SSO-1026\",\"code\":\"SSO-1111\"}" to null)) {
            val call = ControlledCall(400, wire)
            try { OwnerHttpsTransport(okhttp3.Call.Factory { call }).execute(readRequest()); fail("Error accepted") }
            catch (error: NativeApiException) {
                assertEquals(400, error.statusCode)
                assertEquals(expected, error.serviceCode)
                assertFalse(error.message.orEmpty().contains("PRIVATE"))
            }
            assertEquals(1, call.enqueueCount)
        }
    }

    private class ControlledCall(private val status: Int? = null, private val wire: String = "{\"result\":\"SYNTHETIC\"}") : okhttp3.Call {
        var callback: okhttp3.Callback? = null
        var cancelled = false
        var enqueued = false
        var enqueueCount = 0
        var closed = false
        override fun request() = okhttp3.Request.Builder().url("https://synthetic.invalid/").build()
        override fun execute(): okhttp3.Response = throw AssertionError("Must use enqueue")
        override fun enqueue(responseCallback: okhttp3.Callback) {
            callback = responseCallback
            enqueued = true
            enqueueCount++
            if (status != null) {
                val source = object : okio.ForwardingSource(okio.Buffer().writeUtf8(wire)) {
                    override fun close() { closed = true; super.close() }
                }
                val buffered = source.buffer()
                val body = object : okhttp3.ResponseBody() {
                    override fun contentType(): okhttp3.MediaType? = null
                    override fun contentLength(): Long = -1
                    override fun source(): okio.BufferedSource = buffered
                }
                responseCallback.onResponse(this, okhttp3.Response.Builder().request(request())
                    .protocol(okhttp3.Protocol.HTTP_1_1).code(status).message("SYNTHETIC_PRIVATE_DETAILS").body(body).build())
            }
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = enqueued
        override fun isCanceled() = cancelled
        override fun timeout() = okio.Timeout()
        override fun clone(): okhttp3.Call = ControlledCall(status)
    }

    @Test fun non2xxBarrierStopsOkHttp503RetryAfterFollowUps() {
        val response = okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("https://synthetic.invalid/").build())
            .protocol(okhttp3.Protocol.HTTP_1_1).code(503).message("SYNTHETIC_PRIVATE_DETAILS")
            .header("Retry-After", "0").body("SYNTHETIC_PRIVATE_DETAILS".toResponseBody()).build()
        var proceeded = 0
        val chain = java.lang.reflect.Proxy.newProxyInstance(okhttp3.Interceptor.Chain::class.java.classLoader,
            arrayOf(okhttp3.Interceptor.Chain::class.java)) { _, method, _ ->
            when (method.name) {
                "request" -> response.request
                "proceed" -> { proceeded++; response }
                else -> throw AssertionError("Unexpected chain method")
            }
        } as okhttp3.Interceptor.Chain
        try { OwnerHttpsTransport.createClient().networkInterceptors.single().intercept(chain); fail("503 escaped barrier") }
        catch (error: java.io.IOException) { assertNull(error.message); assertNull(error.cause) }
        assertEquals(1, proceeded)
    }

    @Test fun encodedBodyIsBoundedEvenWithoutContentLength() {
        val body = object : okhttp3.ResponseBody() {
            private val data = okio.Buffer().write(ByteArray(OwnerJson.LIMIT + 1))
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): okio.BufferedSource = data
        }
        val response = okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("https://synthetic.invalid/").build())
            .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").header("Content-Encoding", "gzip").body(body).build()
        val chain = java.lang.reflect.Proxy.newProxyInstance(okhttp3.Interceptor.Chain::class.java.classLoader,
            arrayOf(okhttp3.Interceptor.Chain::class.java)) { _, method, _ ->
            when (method.name) {
                "request" -> response.request
                "proceed" -> response
                else -> throw AssertionError("Unexpected chain method")
            }
        } as okhttp3.Interceptor.Chain
        OwnerHttpsTransport.createClient().networkInterceptors.single().intercept(chain).use { bounded ->
            try { bounded.body!!.bytes(); fail("Unbounded encoded body accepted") }
            catch (error: java.io.IOException) { assertNull(error.message) }
        }
    }

    @Test fun decodedBodyAndNestingAreBounded() {
        try { OwnerJson.decode(ByteArray(2 * 1024 * 1024 + 1)); fail("Oversize accepted") }
        catch (_: NativeApiException) { }
        val nested = "{\"x\":".repeat(80) + "0" + "}".repeat(80)
        try { OwnerJson.decode(nested.toByteArray()); fail("Deep nesting accepted") }
        catch (_: NativeApiException) { }
        try { OwnerJson.decode(byteArrayOf(123, 34, -64, -81, 34, 58, 49, 125)); fail("Malformed UTF8 accepted") }
        catch (_: NativeApiException) { }
    }
}

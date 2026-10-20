package dev.epok.rum

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivacyPropagationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun webPrivacyCorpusAndMalformedUrlsFailClosed() {
        val cases = mapOf(
            "https://user:password@app.example/api/v2/users/42/profile?q=secret#private" to "https://app.example/api/v2/users/<redacted>/profile?<redacted>#<redacted>",
            "/api/token/V2/GraphQL/:id" to "/api/token/<redacted>/<redacted>/<redacted>",
            "/reset-password/short" to "/reset-password/<redacted>",
            "/api/%25761/users/%2534%2532" to "/api/v1/users/<redacted>",
            "/u/:id/:uuid/:hash" to "/u/:id/:uuid/:hash",
            "/health-check/GraphQL/v2" to "/health-check/GraphQL/v2",
            "//user:password@app.example:8080/cart" to "//app.example:8080/cart",
            "https://app.example/#/route?secret=x" to "https://app.example/#<redacted>",
            "https://[invalid" to "<redacted>",
            "http:///" to "<redacted>",
            "javascript:secret" to "<redacted>",
            "https://app.example/hello\\secret" to "<redacted>",
            "/bad/%ZZ" to "<redacted>"
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, Privacy.scrubUrl(input)) }
        listOf("999", "alice%2540example.com", "eyJhbGciOi.payload.sig", "abcdef0123456789",
            "a1b2c3d4e5f6g7h8", "a".repeat(33), "part%2Fprivate").forEach {
            assertEquals("/files/<redacted>", Privacy.scrubUrl("/files/$it"))
        }
        assertEquals("/search?page=2&<redacted>", Privacy.scrubUrl("/search?q=secret&page=2", setOf("page")))
        val once = Privacy.scrubUrl("https://app.example/users/42")
        assertEquals(once, Privacy.scrubUrl(once))
    }

    @Test fun exactOriginDeniesLookalikesSchemePortsCredentialsAndPathAllowlist() {
        val allow = setOf("https://api.getepok.dev:443")
        fun allows(url: String) = Propagation.permits(Request.Builder().url(url).build().url, allow)
        assertTrue(allows("https://API.getepok.dev/users"))
        listOf("https://api.evil-getepok.dev/", "https://getepok.dev.evil.com/", "https://sub.api.getepok.dev/",
            "http://api.getepok.dev/", "https://api.getepok.dev:444/", "https://user:pass@api.getepok.dev/").forEach {
            assertFalse(it, allows(it))
        }
        val target = Request.Builder().url("https://api.getepok.dev/path").build().url
        listOf("https://api.getepok.dev/path", "https://api.getepok.dev?x=1", "https://api.getepok.dev#x",
            "https://user@api.getepok.dev", ".*getepok.dev", "https:\\api.getepok.dev").forEach {
            assertFalse(it, Propagation.permits(target, setOf(it)))
        }
    }

    @Test fun inboundContextAndTracestatePreservedAndInjectionIdempotent() {
        val h = Harness(File(temp.root, "queue.json"))
        h.rum.grantConsent()
        val header = "00-${"1".repeat(32)}-${"2".repeat(16)}-00"
        val request = Request.Builder().url("https://api.getepok.dev/users")
            .header("traceparent", header).header("tracestate", "vendor=value")
            .header("baggage", "other=ok,session.id=stale,user.id=private").build()
        val first = h.rum.prepare(request)
        val second = h.rum.prepare(first.request)
        assertEquals(header, first.request.header("traceparent"))
        assertEquals(header, second.request.header("traceparent"))
        assertEquals("vendor=value", second.request.header("tracestate"))
        assertEquals(first.request.header("baggage"), second.request.header("baggage"))
        assertEquals("other=ok,session.id=${h.rum.snapshot().sessionId}", second.request.header("baggage"))
        assertEquals("1".repeat(32), second.capture!!.context.traceId)
        val malformed = request.newBuilder().header("traceparent", "00-${"0".repeat(32)}-${"2".repeat(16)}-01").build()
        val repaired = h.rum.prepare(malformed)
        assertNotNull(TraceContext.parse(repaired.request.header("traceparent")))
        assertNull(repaired.request.header("tracestate"))
        h.rum.withdrawConsent()
        val after = h.rum.prepare(first.request)
        assertNull(after.capture)
        assertEquals(header, after.request.header("traceparent")) // Host-owned context remains host-owned.
        assertEquals(request.header("baggage"), after.request.header("baggage"))
    }

    @Test fun generatedHeadersDisappearOnWithdrawalAndRealCrossOriginRedirect() {
        val allowed = MockWebServer()
        val denied = MockWebServer()
        allowed.start(); denied.start()
        try {
            val h = Harness(File(temp.root, "queue.json"), config = Harness.config().copy(
                allowedOrigins = setOf(allowed.url("/").toString())))
            h.rum.grantConsent()
            val request = Request.Builder().url(allowed.url("/start")).build()
            val prepared = h.rum.prepare(request)
            val twice = h.rum.prepare(prepared.request)
            assertEquals(prepared.request.header("traceparent"), twice.request.header("traceparent"))
            allowed.enqueue(MockResponse().setResponseCode(302).addHeader("Location", denied.url("/finish")))
            denied.enqueue(MockResponse().setResponseCode(200))
            OkHttpClient.Builder().addNetworkInterceptor(h.rum.networkInterceptor()).build()
                .newCall(request).execute().use { assertEquals(200, it.code) }
            assertNotNull(allowed.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("traceparent"))
            val redirected = denied.takeRequest(3, TimeUnit.SECONDS)!!
            assertNull(redirected.getHeader("traceparent"))
            assertNull(redirected.getHeader("baggage"))
            h.rum.withdrawConsent()
            assertNull(h.rum.prepare(prepared.request).request.header("traceparent"))
        } finally { allowed.shutdown(); denied.shutdown() }
    }
}

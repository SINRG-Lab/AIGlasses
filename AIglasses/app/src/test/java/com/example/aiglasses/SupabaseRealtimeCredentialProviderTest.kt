package com.example.aiglasses

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SupabaseRealtimeCredentialProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemorySessionStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = MemorySessionStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `anonymous sign-in is persisted then function receives user bearer token`() = runTest {
        server.enqueue(json(200, authJson("user-access", "user-refresh", 2_000L)))
        server.enqueue(json(200, credentialJson("ek_short_lived", 1_600L, "gpt-realtime-2.1-mini")))

        val credential = provider().fetchCredential()

        assertEquals("ek_short_lived", credential.clientSecret)
        assertEquals("gpt-realtime-2.1-mini", credential.model)
        assertEquals("user-refresh", store.session?.refreshToken)

        val signup = server.takeRequest()
        assertEquals("/auth/v1/signup", signup.path)
        assertEquals("publishable-test-key", signup.getHeader("apikey"))

        val function = server.takeRequest()
        assertEquals("/functions/v1/realtime-session", function.path)
        assertEquals("Bearer user-access", function.getHeader("Authorization"))
        assertEquals("publishable-test-key", function.getHeader("apikey"))
        val requestBody = function.body.readUtf8()
        assertTrue(requestBody.contains("\"platform\":\"android\""))
        assertTrue(requestBody.contains("\"device_id\":\"android-test-install\""))
    }

    @Test
    fun `function 401 forces one refresh and retries once`() = runTest {
        store.session = StoredSupabaseSession("old-access", "refresh-one", 2_000L)
        server.enqueue(json(401, "{\"error\":\"unauthorized\"}"))
        server.enqueue(json(200, authJson("new-access", "refresh-two", 2_500L)))
        server.enqueue(json(200, credentialJson("ek_after_refresh", 1_700L, "gpt-realtime-2.1")))

        val credential = provider().fetchCredential()

        assertEquals("ek_after_refresh", credential.clientSecret)
        assertEquals("Bearer old-access", server.takeRequest().getHeader("Authorization"))
        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
        assertEquals("refresh-two", store.session?.refreshToken)
    }

    @Test
    fun `second 401 stops without an authentication loop`() = runTest {
        store.session = StoredSupabaseSession("old-access", "refresh-one", 2_000L)
        server.enqueue(json(401, "{}"))
        server.enqueue(json(200, authJson("new-access", "refresh-two", 2_500L)))
        server.enqueue(json(401, "{}"))

        val thrown = runCatching { provider().fetchCredential() }.exceptionOrNull()

        assertTrue(thrown is CredentialFailure.Authentication)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `rate limit enforces retry-after cooldown without another HTTP request`() = runTest {
        store.session = StoredSupabaseSession("valid-access", "valid-refresh", 2_000L)
        var now = 1_000L
        server.enqueue(
            json(429, "{\"error\":\"rate_limited\"}")
                .setHeader("Retry-After", "37")
        )

        val client = provider { now }
        val thrown = runCatching { client.fetchCredential() }.exceptionOrNull()
        val duringCooldown = runCatching { client.fetchCredential() }.exceptionOrNull()

        assertTrue(thrown is CredentialFailure.RateLimited)
        assertEquals(37L, (thrown as CredentialFailure.RateLimited).retryAfterSeconds)
        assertTrue(duringCooldown is CredentialFailure.RateLimited)
        assertEquals(37L, (duringCooldown as CredentialFailure.RateLimited).retryAfterSeconds)
        assertEquals(1, server.requestCount)

        now += 37L
        server.enqueue(json(200, credentialJson("ek_after_cooldown", 1_600L, "gpt-realtime-2.1")))
        assertEquals("ek_after_cooldown", client.fetchCredential().clientSecret)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `server failures use exponential local backoff`() = runTest {
        store.session = StoredSupabaseSession("valid-access", "valid-refresh", 2_000L)
        var now = 1_000L
        val client = provider { now }
        server.enqueue(json(503, "{}"))

        assertTrue(runCatching { client.fetchCredential() }.exceptionOrNull()
            is CredentialFailure.ServiceUnavailable)
        assertTrue(runCatching { client.fetchCredential() }.exceptionOrNull()
            is CredentialFailure.RetryDeferred)
        assertEquals(1, server.requestCount)

        now += 2L
        server.enqueue(json(503, "{}"))
        assertTrue(runCatching { client.fetchCredential() }.exceptionOrNull()
            is CredentialFailure.ServiceUnavailable)
        now += 3L
        assertTrue(runCatching { client.fetchCredential() }.exceptionOrNull()
            is CredentialFailure.RetryDeferred)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `expired client secret is rejected before websocket use`() = runTest {
        store.session = StoredSupabaseSession("valid-access", "valid-refresh", 2_000L)
        server.enqueue(json(200, credentialJson("ek_expired", 1_005L, "gpt-realtime-2.1")))

        val thrown = runCatching { provider().fetchCredential() }.exceptionOrNull()

        assertTrue(thrown is CredentialFailure.ServiceUnavailable)
    }

    @Test
    fun `missing backend model is rejected without an app fallback`() = runTest {
        store.session = StoredSupabaseSession("valid-access", "valid-refresh", 2_000L)
        server.enqueue(json(200, """{"client_secret":"ek_valid","expires_at":1600,"session":{}}"""))

        val thrown = runCatching { provider().fetchCredential() }.exceptionOrNull()

        assertTrue(thrown is CredentialFailure.ServiceUnavailable)
    }

    @Test
    fun `unapproved backend model is rejected`() = runTest {
        store.session = StoredSupabaseSession("valid-access", "valid-refresh", 2_000L)
        server.enqueue(json(200, credentialJson("ek_valid", 1_600L, "unapproved-model")))

        val thrown = runCatching { provider().fetchCredential() }.exceptionOrNull()

        assertTrue(thrown is CredentialFailure.ServiceUnavailable)
    }

    private fun provider(nowEpochSeconds: () -> Long = { 1_000L }) =
        SupabaseRealtimeCredentialProvider(
        baseUrl = server.url("/").toString(),
        publishableKey = "publishable-test-key",
        appVersion = "1.0",
        deviceId = "android-test-install",
        sessionStore = store,
        httpClient = OkHttpClient(),
        nowEpochSeconds = nowEpochSeconds
    )

    private fun json(status: Int, body: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun authJson(access: String, refresh: String, expiresAt: Long) =
        """{"access_token":"$access","refresh_token":"$refresh","expires_at":$expiresAt}"""

    private fun credentialJson(secret: String, expiresAt: Long, model: String) =
        """{"client_secret":"$secret","expires_at":$expiresAt,"session":{"model":"$model"}}"""

    private class MemorySessionStore : SupabaseSessionStore {
        var session: StoredSupabaseSession? = null
        override fun load(): StoredSupabaseSession? = session
        override fun save(session: StoredSupabaseSession) {
            this.session = session
        }
        override fun clear() {
            session = null
        }
    }
}

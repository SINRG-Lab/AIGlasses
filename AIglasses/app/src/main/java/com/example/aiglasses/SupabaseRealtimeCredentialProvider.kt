package com.example.aiglasses

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A short-lived credential that may be used only to open an OpenAI Realtime session. */
data class RealtimeCredential(
    val clientSecret: String,
    val expiresAtEpochSeconds: Long,
    val model: String
)

/** User-safe failures from anonymous authentication or credential issuance. */
sealed class CredentialFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : CredentialFailure(
        "The phone is offline. Hold the glasses button to try again.", cause
    )

    class RateLimited(val retryAfterSeconds: Long) : CredentialFailure(
        "Too many assistant sessions. Try again in ${retryAfterSeconds.coerceAtLeast(1)} seconds."
    )

    class Authentication : CredentialFailure(
        "The assistant could not authenticate. Hold the glasses button to try again."
    )

    class ServiceUnavailable : CredentialFailure(
        "The assistant service is temporarily unavailable. Hold the glasses button to try again."
    )

    class RetryDeferred(val retryAfterSeconds: Long) : CredentialFailure(
        "The assistant is recovering. Try again in ${retryAfterSeconds.coerceAtLeast(1)} seconds."
    )
}

internal data class StoredSupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long
)

internal interface SupabaseSessionStore {
    fun load(): StoredSupabaseSession?
    fun save(session: StoredSupabaseSession)
    fun clear()
}

private class SharedPreferencesSessionStore(context: Context) : SupabaseSessionStore {
    companion object {
        private const val PREFS = "supabase_auth"
        private const val ACCESS_TOKEN = "access_token"
        private const val REFRESH_TOKEN = "refresh_token"
        private const val EXPIRES_AT = "expires_at"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): StoredSupabaseSession? {
        val access = prefs.getString(ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val refresh = prefs.getString(REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val expiry = prefs.getLong(EXPIRES_AT, 0L).takeIf { it > 0L } ?: return null
        return StoredSupabaseSession(access, refresh, expiry)
    }

    override fun save(session: StoredSupabaseSession) {
        // Commit before returning so a process death immediately after a physical-button
        // interaction cannot strand the newly rotated refresh token.
        val saved = prefs.edit()
            .putString(ACCESS_TOKEN, session.accessToken)
            .putString(REFRESH_TOKEN, session.refreshToken)
            .putLong(EXPIRES_AT, session.expiresAtEpochSeconds)
            .commit()
        if (!saved) throw CredentialFailure.ServiceUnavailable()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }
}

/**
 * Authenticates this app installation anonymously with Supabase, persists and refreshes that
 * session, and exchanges it for a ten-minute OpenAI Realtime client secret.
 *
 * Only the Supabase session is persisted. The `ek_` credential is kept in memory just long
 * enough to open a WebSocket and is never logged or written to disk.
 */
class SupabaseRealtimeCredentialProvider internal constructor(
    private val baseUrl: String,
    private val publishableKey: String,
    private val appVersion: String,
    private val deviceId: String,
    private val sessionStore: SupabaseSessionStore,
    private val httpClient: OkHttpClient,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L }
) {
    companion object {
        const val SUPABASE_URL = "https://pnwdupcncssdldrnqsld.supabase.co"
        const val SUPABASE_PUBLISHABLE_KEY =
            "sb_publishable_00dREe2TfYQDMTpgAM_SOw_y15LyQf7"
        private const val FUNCTION_NAME = "realtime-session"
        private const val AUTH_EXPIRY_SKEW_SECONDS = 60L
        private const val CREDENTIAL_EXPIRY_SKEW_SECONDS = 10L
        private const val MAX_TRANSIENT_BACKOFF_SECONDS = 30L
        private val APPROVED_MODELS = setOf("gpt-realtime-2.1", "gpt-realtime-2.1-mini")
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun installationId(context: Context): String {
            val prefs = context.getSharedPreferences("aiglasses_installation", Context.MODE_PRIVATE)
            val existing = prefs.getString("installation_id", null)
            if (!existing.isNullOrBlank()) return existing
            val created = "android-${UUID.randomUUID()}"
            prefs.edit().putString("installation_id", created).commit()
            return created
        }
    }

    constructor(context: Context) : this(
        baseUrl = SUPABASE_URL,
        publishableKey = SUPABASE_PUBLISHABLE_KEY,
        appVersion = BuildConfig.VERSION_NAME,
        deviceId = installationId(context.applicationContext),
        sessionStore = SharedPreferencesSessionStore(context.applicationContext),
        httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    )

    private data class HttpResult(
        val code: Int,
        val body: String,
        val retryAfterSeconds: Long?
    )

    private class AuthRejected : Exception()

    private val authMutex = Mutex()
    private val credentialMutex = Mutex()
    /** Both values are accessed only while [credentialMutex] is held. */
    private var rateLimitNotBeforeEpochSeconds = 0L
    private var transientRetryNotBeforeEpochSeconds = 0L
    private var consecutiveTransientFailures = 0

    /** Serializes calls so one physical interaction can never spend multiple quota slots. */
    suspend fun fetchCredential(): RealtimeCredential = withContext(Dispatchers.IO) {
        credentialMutex.withLock {
            val now = nowEpochSeconds()
            if (now < rateLimitNotBeforeEpochSeconds) {
                throw CredentialFailure.RateLimited(rateLimitNotBeforeEpochSeconds - now)
            }
            if (now < transientRetryNotBeforeEpochSeconds) {
                throw CredentialFailure.RetryDeferred(transientRetryNotBeforeEpochSeconds - now)
            }
            try {
                val firstToken = accessToken(forceRefresh = false)
                val first = requestCredential(firstToken)
                val credential = if (first.code != 401) {
                    parseCredentialResponse(first)
                } else {
                    // A gateway 401 normally means the locally persisted access token was revoked
                    // or raced refresh-token rotation. Refresh/re-authenticate and retry once.
                    val retryToken = accessToken(forceRefresh = true)
                    val retry = requestCredential(retryToken)
                    if (retry.code == 401) throw CredentialFailure.Authentication()
                    parseCredentialResponse(retry)
                }
                consecutiveTransientFailures = 0
                transientRetryNotBeforeEpochSeconds = 0L
                rateLimitNotBeforeEpochSeconds = 0L
                credential
            } catch (failure: CredentialFailure.RateLimited) {
                rateLimitNotBeforeEpochSeconds = maxOf(
                    rateLimitNotBeforeEpochSeconds,
                    nowEpochSeconds() + failure.retryAfterSeconds.coerceAtLeast(1L)
                )
                throw failure
            } catch (failure: CredentialFailure.Network) {
                deferAfterTransientFailure()
                throw failure
            } catch (failure: CredentialFailure.ServiceUnavailable) {
                deferAfterTransientFailure()
                throw failure
            }
        }
    }

    /** Prevent continuous incoming mic frames from hammering a failing backend. */
    private fun deferAfterTransientFailure() {
        consecutiveTransientFailures = (consecutiveTransientFailures + 1).coerceAtMost(5)
        val delay = (1L shl consecutiveTransientFailures).coerceAtMost(MAX_TRANSIENT_BACKOFF_SECONDS)
        transientRetryNotBeforeEpochSeconds = maxOf(
            transientRetryNotBeforeEpochSeconds,
            nowEpochSeconds() + delay
        )
    }

    private suspend fun accessToken(forceRefresh: Boolean): String = authMutex.withLock {
        val stored = sessionStore.load()
        if (!forceRefresh && stored != null &&
            stored.expiresAtEpochSeconds > nowEpochSeconds() + AUTH_EXPIRY_SKEW_SECONDS
        ) {
            return@withLock stored.accessToken
        }

        if (stored != null) {
            try {
                return@withLock refresh(stored.refreshToken).accessToken
            } catch (_: AuthRejected) {
                sessionStore.clear()
                // A rejected refresh token is unrecoverable for an anonymous account. Create a
                // new anonymous identity; network failures do not clear a potentially valid one.
            }
        }
        signInAnonymously().accessToken
    }

    private suspend fun signInAnonymously(): StoredSupabaseSession {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/auth/v1/signup")
            .header("apikey", publishableKey)
            .header("Accept", "application/json")
            .post("{}".toRequestBody(JSON))
            .build()
        val result = execute(request)
        if (result.code == 429) {
            throw CredentialFailure.RateLimited(result.retryAfterSeconds ?: 60L)
        }
        if (result.code >= 500) throw CredentialFailure.ServiceUnavailable()
        if (result.code !in 200..299) throw CredentialFailure.Authentication()
        return parseAndSaveAuthSession(result.body)
    }

    private suspend fun refresh(refreshToken: String): StoredSupabaseSession {
        val body = JSONObject().put("refresh_token", refreshToken).toString()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/auth/v1/token?grant_type=refresh_token")
            .header("apikey", publishableKey)
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        val result = execute(request)
        if (result.code == 400 || result.code == 401) throw AuthRejected()
        if (result.code == 429) {
            throw CredentialFailure.RateLimited(result.retryAfterSeconds ?: 60L)
        }
        if (result.code !in 200..299) throw CredentialFailure.ServiceUnavailable()
        return parseAndSaveAuthSession(result.body)
    }

    private suspend fun requestCredential(accessToken: String): HttpResult {
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("platform", "android")
            .put("app_version", appVersion)
            .toString()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/functions/v1/$FUNCTION_NAME")
            .header("apikey", publishableKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        return execute(request)
    }

    private fun parseAndSaveAuthSession(raw: String): StoredSupabaseSession {
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw CredentialFailure.ServiceUnavailable()
        }
        val access = json.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw CredentialFailure.ServiceUnavailable()
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: throw CredentialFailure.ServiceUnavailable()
        val explicitExpiry = json.optLong("expires_at", 0L)
        val expiry = if (explicitExpiry > 0L) explicitExpiry
        else nowEpochSeconds() + json.optLong("expires_in", 3600L).coerceAtLeast(1L)
        return StoredSupabaseSession(access, refresh, expiry).also(sessionStore::save)
    }

    private fun parseCredentialResponse(result: HttpResult): RealtimeCredential {
        when (result.code) {
            401 -> throw CredentialFailure.Authentication()
            429 -> throw CredentialFailure.RateLimited(result.retryAfterSeconds ?: 60L)
        }
        if (result.code !in 200..299) throw CredentialFailure.ServiceUnavailable()
        val json = try {
            JSONObject(result.body)
        } catch (_: Exception) {
            throw CredentialFailure.ServiceUnavailable()
        }
        val secret = json.optString("client_secret").takeIf { it.startsWith("ek_") }
            ?: throw CredentialFailure.ServiceUnavailable()
        val expiresAt = json.optLong("expires_at", 0L)
        if (expiresAt <= nowEpochSeconds() + CREDENTIAL_EXPIRY_SKEW_SECONDS) {
            throw CredentialFailure.ServiceUnavailable()
        }
        // The Edge Function owns model selection. A missing or unexpected model is a contract
        // failure, never an invitation for the app to silently choose its own fallback.
        val model = json.optJSONObject("session")
            ?.optString("model")
            ?.takeIf(APPROVED_MODELS::contains)
            ?: throw CredentialFailure.ServiceUnavailable()
        return RealtimeCredential(secret, expiresAt, model)
    }

    /** Cancels the in-flight OkHttp call when its owning service coroutine is cancelled. */
    private suspend fun execute(request: Request): HttpResult = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        try {
            call.execute().use { response ->
                val result = HttpResult(
                    code = response.code,
                    body = response.body?.string().orEmpty(),
                    retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                )
                if (continuation.isActive) continuation.resume(result)
            }
        } catch (e: IOException) {
            if (continuation.isActive) {
                continuation.resumeWithException(CredentialFailure.Network(e))
            }
        }
    }
}

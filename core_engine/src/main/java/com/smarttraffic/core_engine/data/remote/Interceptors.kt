package com.smarttraffic.core_engine.data.remote

import com.smarttraffic.coreengine.BuildConfig
import com.smarttraffic.core_engine.security.SecureTokenStore
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthHeaderInterceptor @Inject constructor(
    private val tokenStore: SecureTokenStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStore.getIdToken()
        val request = chain.request().newBuilder().apply {
            if (!token.isNullOrBlank()) {
                header("Authorization", "Bearer $token")
            }
        }.build()
        return chain.proceed(request)
    }
}

@Singleton
class SignatureInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val bodyBytes = request.body?.let { body ->
            val buffer = okio.Buffer()
            body.writeTo(buffer)
            buffer.readByteArray()
        } ?: ByteArray(0)

        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val signature = hmac(timestamp, bodyBytes)
        val signatureId = sha256Hex(signature + timestamp).take(32)

        val signed = request.newBuilder()
            .header("X-Signature-Timestamp", timestamp)
            .header("X-Signature", signature)
            .header("X-Signature-Id", signatureId)
            .build()

        return chain.proceed(signed)
    }

    private fun hmac(timestamp: String, body: ByteArray): String {
        val secretKey = SecretKeySpec(BuildConfig.SIGNING_SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKey)
        mac.update("$timestamp.".toByteArray(StandardCharsets.UTF_8))
        val digest = mac.doFinal(body)
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

@Singleton
class ResilienceInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return runCatching { interceptWithRetry(chain, request) }
            .getOrElse { failure -> syntheticFailureResponse(request, failure) }
    }

    private fun interceptWithRetry(chain: Interceptor.Chain, request: Request): Response {
        var attempt = 0
        var lastException: Throwable? = null
        var lastRetriableResponse: Response? = null

        while (attempt < MAX_ATTEMPTS) {
            try {
                val response = chain.proceed(request)
                if (response.isSuccessful || response.code !in RETRIABLE_STATUS_CODES) {
                    closeQuietly(lastRetriableResponse)
                    return response
                }
                closeQuietly(lastRetriableResponse)
                lastRetriableResponse = response
            } catch (ex: Exception) {
                lastException = ex
            }

            attempt += 1
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep((BASE_BACKOFF_MS * (1 shl attempt)).coerceAtMost(MAX_BACKOFF_MS))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        return lastRetriableResponse ?: syntheticFailureResponse(request, lastException)
    }

    private fun closeQuietly(response: Response?) {
        runCatching { response?.close() }
    }

    private fun syntheticFailureResponse(request: Request, cause: Throwable?): Response {
        val message = cause?.message?.takeIf { it.isNotBlank() } ?: "Request failed after retries"
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(SYNTHETIC_NETWORK_FAILURE_CODE)
            .message("Request failed after retries")
            .body(message.toResponseBody())
            .build()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BASE_BACKOFF_MS = 120L
        const val MAX_BACKOFF_MS = 1200L
        const val SYNTHETIC_NETWORK_FAILURE_CODE = 599
        val RETRIABLE_STATUS_CODES = setOf(408, 429, 500, 502, 503, 504)
    }
}

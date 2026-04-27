package com.smarttraffic.core_engine

import com.smarttraffic.core_engine.data.remote.ResilienceInterceptor
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ResilienceInterceptorTest {

    @Test
    fun retries_on_transient_failure() {
        val interceptor = ResilienceInterceptor()
        val attempts = AtomicInteger(0)

        val chain = testChain { request ->
            if (attempts.incrementAndGet() < 3) {
                return@testChain Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("unavailable")
                    .body("x".toResponseBody())
                    .build()
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("ok")
                .body("ok".toResponseBody())
                .build()
        }

        val response = interceptor.intercept(chain)
        assertEquals(200, response.code)
        assertEquals(3, attempts.get())
    }

    @Test
    fun returns_last_retriable_response_after_retry_budget_exhausted() {
        val interceptor = ResilienceInterceptor()
        val attempts = AtomicInteger(0)
        val chain = testChain { request ->
            attempts.incrementAndGet()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("unavailable")
                .body("still failing".toResponseBody())
                .build()
        }

        val response = interceptor.intercept(chain)
        assertEquals(503, response.code)
        assertEquals(3, attempts.get())
    }

    @Test
    fun returns_synthetic_failure_response_when_all_attempts_throw() {
        val interceptor = ResilienceInterceptor()
        val attempts = AtomicInteger(0)
        val chain = testChain {
            attempts.incrementAndGet()
            throw SocketTimeoutException("timeout")
        }

        val response = interceptor.intercept(chain)
        assertEquals(599, response.code)
        assertEquals("timeout", response.body?.string())
        assertEquals(3, attempts.get())
    }

    private fun testChain(proceedHandler: (Request) -> Response): Interceptor.Chain {
        return object : Interceptor.Chain {
            override fun call() = throw UnsupportedOperationException()
            override fun connectTimeoutMillis() = 0
            override fun proceed(request: Request): Response = proceedHandler(request)
            override fun readTimeoutMillis() = 0
            override fun request(): Request = Request.Builder().url("http://localhost/").build()
            override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
            override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
            override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
            override fun writeTimeoutMillis() = 0
            override fun connection() = null
        }
    }
}


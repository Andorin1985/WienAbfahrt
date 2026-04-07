package com.rwa.wienerlinien.data.api

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicLong

/**
 * Ensures at most 1 request per second to the Wiener Linien OGD API.
 */
class RateLimitInterceptor : Interceptor {
    private val lastRequestTime = AtomicLong(0L)

    override fun intercept(chain: Interceptor.Chain): Response {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRequestTime.get()
        if (elapsed < 1000L) {
            Thread.sleep(1000L - elapsed)
        }
        lastRequestTime.set(System.currentTimeMillis())
        return chain.proceed(chain.request())
    }
}

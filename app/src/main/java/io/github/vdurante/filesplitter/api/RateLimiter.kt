package io.github.vdurante.filesplitter.api

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sliding-window limiter shared by every API call. The public API allows 4 requests per minute
 * across all endpoints: hash lookups and result checks count the same as uploads.
 */
class RateLimiter(private val maxRequests: Int = 4, private val windowMs: Long = 60_000) {
    private val mutex = Mutex()
    private val stamps = ArrayDeque<Long>()

    /** Suspends until a request slot is free. [onWait] receives the wall-clock time the wait ends. */
    suspend fun acquire(onWait: (untilMillis: Long) -> Unit = {}) = mutex.withLock {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) stamps.removeFirst()
            if (stamps.size < maxRequests) {
                stamps.addLast(now)
                return@withLock
            }
            val wait = windowMs - (now - stamps.first()) + 500
            onWait(System.currentTimeMillis() + wait)
            delay(wait)
        }
    }
}

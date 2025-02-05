package net.postchain.client.request

import java.time.Clock
import java.time.Instant
import kotlin.time.Duration

class Endpoint(url: String, private val clock: Clock = Clock.systemUTC()) {

    val url: String = sanitizeUrl(url)

    companion object {
        fun sanitizeUrl(url: String) = url.trim().trimEnd('/')
    }

    @Volatile
    private var whenReachable: Instant = Instant.EPOCH

    fun isReachable() = clock.instant().isAfter(whenReachable)

    fun setUnreachable(duration: Duration) {
        whenReachable = clock.instant().plusMillis(duration.inWholeMilliseconds)
    }

    fun setReachable() {
        whenReachable = Instant.EPOCH
    }

    override fun toString(): String = "Endpoint(url='$url')"
}

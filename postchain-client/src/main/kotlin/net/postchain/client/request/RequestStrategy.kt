package net.postchain.client.request

import org.http4k.core.Request
import org.http4k.core.Response
import java.io.Closeable

interface RequestStrategy : Closeable {
    /**
     * The [Response] passed to [success] or [failure] is closed after the callback returns.
     * Callbacks must consume the response body before returning and must not retain it.
     */
    fun <R> request(createRequest: (Endpoint) -> Request,
                    success: (Response, Endpoint) -> R,
                    failure: (Response, Endpoint) -> R,
                    queryMultiple: Boolean): R

    /**
     * The [Response] passed to [success] or [failure] is closed after the callback returns.
     * Callbacks must consume the response body before returning and must not retain it.
     */
    fun <R> request(endpoint: Endpoint,
                    request: Request,
                    success: (Response, Endpoint) -> R,
                    failure: (Response, Endpoint) -> R): R
}

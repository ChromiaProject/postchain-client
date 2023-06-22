package net.postchain.client.impl

import net.postchain.client.request.RequestStrategy
import org.http4k.core.HttpHandler
import org.http4k.core.MemoryResponse
import org.http4k.core.Request
import org.http4k.core.Status
import javax.net.ssl.SSLException

abstract class SynchronousRequestStrategy(protected val httpClient: HttpHandler) : RequestStrategy {
    protected fun makeRequest(request: Request) = try {
        httpClient(request)
    } catch (e: SSLException) {
        MemoryResponse(Status.CONNECTION_REFUSED)
    }
}

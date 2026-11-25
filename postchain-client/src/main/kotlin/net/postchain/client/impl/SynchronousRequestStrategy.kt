package net.postchain.client.impl

import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.request.Endpoint
import net.postchain.client.request.RequestStrategy
import org.http4k.core.HttpHandler
import org.http4k.core.MemoryResponse
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import javax.net.ssl.SSLException

abstract class SynchronousRequestStrategy(
        protected val config: PostchainClientConfig,
        protected val httpClient: HttpHandler) : RequestStrategy {

    override fun <R> request(endpoint: Endpoint, request: Request, success: (Response, Endpoint) -> R, failure: (Response, Endpoint) -> R): R {
        var response: Response? = null
        (1..config.failOverConfig.attemptsPerEndpoint).forEach {
            response?.close()
            val currentResponse = makeRequest(request)
            response = currentResponse
            when {
                isSuccess(currentResponse.status) -> return currentResponse.use { success(it, endpoint) }

                isClientFailure(currentResponse.status) -> return currentResponse.use { failure(it, endpoint) }

                isServerFailure(currentResponse.status) -> {
                    endpoint.setUnreachable(unreachableDuration(currentResponse.status))
                    return currentResponse.use { failure(it, endpoint) }
                }

                // else retry the same endpoint
            }
            Thread.sleep(config.failOverConfig.attemptInterval.toMillis())
        }
        return response!!.use { failure(it, endpoint) }
    }

    protected fun makeRequest(request: Request) = try {
        httpClient(request)
    } catch (_: SSLException) {
        MemoryResponse(Status.CONNECTION_REFUSED)
    }
}

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
        (1..config.failOverConfig.attemptsPerEndpoint).forEach { i ->
            response = makeRequest(request)
            when {
                isSuccess(response.status) -> return success(response, endpoint)

                isClientFailure(response.status) -> return failure(response, endpoint)

                isServerFailure(response.status) -> {
                    endpoint.setUnreachable(unreachableDuration(response.status))
                    return failure(response, endpoint)
                }

                // else retry the same endpoint
            }
            Thread.sleep(config.failOverConfig.attemptInterval.toMillis())
        }
        return failure(response!!, endpoint)
    }

    protected fun makeRequest(request: Request) = try {
        httpClient(request)
    } catch (_: SSLException) {
        MemoryResponse(Status.CONNECTION_REFUSED)
    }
}

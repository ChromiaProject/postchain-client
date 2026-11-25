package net.postchain.client.impl

import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.request.Endpoint
import net.postchain.client.request.RequestStrategy
import net.postchain.client.request.RequestStrategyFactory
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response

class AbortOnErrorRequestStrategy(config: PostchainClientConfig, httpClient: HttpHandler)
    : SynchronousRequestStrategy(config, httpClient) {
    override fun <R> request(createRequest: (Endpoint) -> Request,
                             success: (Response, Endpoint) -> R,
                             failure: (Response, Endpoint) -> R,
                             queryMultiple: Boolean): R {
        var response: Response? = null
        var endpoint: Endpoint? = null
        val iterator = config.endpointPool.iterator()
        while (iterator.hasNext()) {
            response?.close()
            response = null
            endpoint = iterator.next()
            val request = createRequest(endpoint)
            endpoint@ for (i in 1..config.failOverConfig.attemptsPerEndpoint) {
                response?.close()
                val currentResponse = makeRequest(request)
                response = currentResponse
                when {
                    isSuccess(currentResponse.status) -> return currentResponse.use { success(it, endpoint) }

                    isClientFailure(currentResponse.status) -> return currentResponse.use { failure(it, endpoint) }

                    isServerFailure(currentResponse.status) -> {
                        endpoint.setUnreachable(unreachableDuration(currentResponse.status))
                        break@endpoint
                    }

                    // else retry same endpoint
                }
                Thread.sleep(config.failOverConfig.attemptInterval.toMillis())
            }
        }
        return response!!.use { failure(it, endpoint!!) }
    }

    override fun close() {}
}

class AbortOnErrorRequestStrategyFactory : RequestStrategyFactory {
    override fun create(config: PostchainClientConfig, httpClient: HttpHandler): RequestStrategy =
            AbortOnErrorRequestStrategy(config, httpClient)
}

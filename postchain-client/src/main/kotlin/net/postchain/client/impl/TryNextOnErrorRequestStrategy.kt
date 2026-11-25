package net.postchain.client.impl

import mu.KLogging
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.request.Endpoint
import net.postchain.client.request.RequestStrategy
import net.postchain.client.request.RequestStrategyFactory
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response

class TryNextOnErrorRequestStrategy(config: PostchainClientConfig, httpClient: HttpHandler)
    : SynchronousRequestStrategy(config, httpClient) {
    companion object : KLogging()

    override fun <R> request(createRequest: (Endpoint) -> Request,
                             success: (Response, Endpoint) -> R,
                             failure: (Response, Endpoint) -> R,
                             queryMultiple: Boolean): R {
        var lastResponse: Response? = null
        var endpoint: Endpoint? = null
        var lastProcessingFailure: Exception? = null
        val iterator = config.endpointPool.iterator()
        while (iterator.hasNext()) {
            lastResponse?.close()
            lastResponse = null
            endpoint = iterator.next()
            val request = createRequest(endpoint)
            endpoint@ for (i in 1..config.failOverConfig.attemptsPerEndpoint) {
                val currentResponse = makeRequest(request)
                when {
                    isSuccess(currentResponse.status) -> try {
                        return currentResponse.use { success(it, endpoint) }
                    } catch (e: Exception) {
                        lastProcessingFailure = e
                        logger.debug { "Got exception processing response from ${endpoint.url}, trying next: ${e.message}" }
                        break@endpoint
                    }

                    isClientFailure(currentResponse.status) -> {
                        logger.debug { "Got ${currentResponse.status} response from ${endpoint.url}, trying next" }
                        lastResponse = currentResponse
                        break@endpoint
                    }

                    isServerFailure(currentResponse.status) -> {
                        endpoint.setUnreachable(unreachableDuration(currentResponse.status))
                        lastResponse = currentResponse
                        break@endpoint
                    }

                    i == config.failOverConfig.attemptsPerEndpoint -> lastResponse = currentResponse

                    else -> {
                        currentResponse.close()
                        Thread.sleep(config.failOverConfig.attemptInterval.toMillis())
                    }
                }
            }
        }
        lastResponse?.let { return it.use { response -> failure(response, endpoint!!) } }
        lastProcessingFailure?.let { throw it }
        throw IllegalStateException("No endpoint available")
    }

    override fun close() {}
}

class TryNextOnErrorRequestStrategyFactory : RequestStrategyFactory {
    override fun create(config: PostchainClientConfig, httpClient: HttpHandler): RequestStrategy =
            TryNextOnErrorRequestStrategy(config, httpClient)
}

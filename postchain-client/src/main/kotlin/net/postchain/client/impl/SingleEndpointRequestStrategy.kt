package net.postchain.client.impl

import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.request.Endpoint
import net.postchain.client.request.RequestStrategy
import net.postchain.client.request.RequestStrategyFactory
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response

class SingleEndpointRequestStrategy(config: PostchainClientConfig, httpClient: HttpHandler)
    : SynchronousRequestStrategy(config, httpClient) {
    override fun <R> request(createRequest: (Endpoint) -> Request,
                             success: (Response, Endpoint) -> R,
                             failure: (Response, Endpoint) -> R,
                             queryMultiple: Boolean): R {
        val endpoint = config.endpointPool.iterator().next()
        val request = createRequest(endpoint)
        return request(endpoint, request, success, failure)
    }

    override fun close() {}
}

class SingleEndpointRequestStrategyFactory : RequestStrategyFactory {
    override fun create(config: PostchainClientConfig, httpClient: HttpHandler): RequestStrategy =
            SingleEndpointRequestStrategy(config, httpClient)
}

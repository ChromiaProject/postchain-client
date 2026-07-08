package net.postchain.client

import net.postchain.client.config.PostchainClientConfig
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.cookie.StandardCookieSpec
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy
import org.apache.hc.client5.http.impl.async.HttpAsyncClients
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder
import org.apache.hc.core5.http.message.BasicHeader
import org.apache.hc.core5.util.TimeValue
import org.apache.hc.core5.util.Timeout
import org.http4k.client.ApacheAsyncClient
import org.http4k.client.ApacheClient
import org.http4k.core.HttpHandler
import org.http4k.core.then
import org.http4k.filter.GzipCompressionMode
import org.http4k.filter.RequestFilters

fun bftMajority(n: Int) = n - (n - 1) / 3

fun defaultHttpHandler(config: PostchainClientConfig): HttpHandler {
    val httpClient = ApacheClient(HttpClients.custom()
            .setDefaultHeaders(listOf(BasicHeader("accept-encoding", "gzip")))
            .setRetryStrategy(DefaultHttpRequestRetryStrategy(0, TimeValue.ZERO_MILLISECONDS)) // no retries
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofMilliseconds(config.connectTimeout.toMillis()))
                            .build())
                    .build())
            .setDefaultRequestConfig(RequestConfig.custom()
                    .setRedirectsEnabled(false)
                    .setCookieSpec(StandardCookieSpec.IGNORE)
                    .setResponseTimeout(Timeout.ofMilliseconds(config.responseTimeout.toMillis()))
                    .build())
            .build())

    return if (config.compressRequestBodies) {
        RequestFilters.GZip(GzipCompressionMode.Streaming()).then(httpClient)
    } else httpClient
}

fun defaultAsyncHttpHandler(config: PostchainClientConfig) =
        ApacheAsyncClient(HttpAsyncClients.custom()
                .setDefaultHeaders(listOf(BasicHeader("accept-encoding", "gzip")))
                .setRetryStrategy(DefaultHttpRequestRetryStrategy(0, TimeValue.ZERO_MILLISECONDS)) // no retries
                .setConnectionManager(PoolingAsyncClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofMilliseconds(config.connectTimeout.toMillis()))
                                .build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setRedirectsEnabled(false)
                        .setCookieSpec(StandardCookieSpec.IGNORE)
                        .setResponseTimeout(Timeout.ofMilliseconds(config.responseTimeout.toMillis()))
                        .build())
                .build()
                .apply { start() })

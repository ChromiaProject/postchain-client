package net.postchain.client

import net.postchain.client.config.PostchainClientConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.cookie.StandardCookieSpec
import org.apache.hc.client5.http.impl.async.HttpAsyncClients
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.message.BasicHeader
import org.apache.hc.core5.util.Timeout
import org.http4k.client.ApacheAsyncClient
import org.http4k.client.ApacheClient
import org.http4k.core.HttpHandler
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.filter.GzipCompressionMode

fun bftMajority(n: Int) = n - (n - 1) / 3

fun defaultHttpHandler(config: PostchainClientConfig): HttpHandler {
    val compressionFilter = if (config.compressRequestBodies) {
        ClientFilters.GZip(GzipCompressionMode.Streaming())
    } else ClientFilters.AcceptGZip(GzipCompressionMode.Streaming())

    return compressionFilter.then(ApacheClient(HttpClients.custom()
            .setDefaultRequestConfig(RequestConfig.custom()
                    .setRedirectsEnabled(false)
                    .setCookieSpec(StandardCookieSpec.IGNORE)
                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(config.connectTimeout.toMillis()))
                    .setResponseTimeout(Timeout.ofMilliseconds(config.responseTimeout.toMillis()))
                    .build()).build()))
}

fun defaultAsyncHttpHandler(config: PostchainClientConfig) =
        ApacheAsyncClient(HttpAsyncClients.custom()
                .setDefaultHeaders(listOf(BasicHeader("accept-encoding", "gzip")))
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setRedirectsEnabled(false)
                        .setCookieSpec(StandardCookieSpec.IGNORE)
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(config.connectTimeout.toMillis()))
                        .setResponseTimeout(Timeout.ofMilliseconds(config.responseTimeout.toMillis()))
                        .build()).build().apply { start() })

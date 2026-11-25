package net.postchain.client

import net.postchain.client.config.PostchainClientConfig
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.cookie.StandardCookieSpec
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient
import org.apache.hc.client5.http.impl.async.HttpAsyncClients
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder
import org.apache.hc.core5.concurrent.FutureCallback
import org.apache.hc.core5.http.EntityDetails
import org.apache.hc.core5.http.Header
import org.apache.hc.core5.http.HttpResponse
import org.apache.hc.core5.http.io.entity.ByteArrayEntity
import org.apache.hc.core5.http.message.BasicClassicHttpRequest
import org.apache.hc.core5.http.message.BasicHeader
import org.apache.hc.core5.http.message.BasicHttpRequest
import org.apache.hc.core5.http.nio.AsyncRequestProducer
import org.apache.hc.core5.http.nio.AsyncResponseConsumer
import org.apache.hc.core5.http.nio.CapacityChannel
import org.apache.hc.core5.http.nio.entity.BasicAsyncEntityProducer
import org.apache.hc.core5.http.nio.support.BasicRequestProducer
import org.apache.hc.core5.http.protocol.HttpContext
import org.apache.hc.core5.util.TimeValue
import org.apache.hc.core5.util.Timeout
import org.http4k.client.AsyncHttpHandler
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.filter.GzipCompressionMode
import org.http4k.filter.RequestFilters
import org.http4k.util.SizeLimitExceededException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

fun bftMajority(n: Int) = n - (n - 1) / 3

internal class SizeLimitedResponseInputStream(
        private val delegate: InputStream,
        private val maxSize: Long) : InputStream() {
    private var count = 0L

    override fun read(): Int {
        val result = delegate.read()
        if (result != -1) increment(1)
        return result
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val result = delegate.read(b, off, len)
        if (result != -1) increment(result)
        return result
    }

    override fun close() = delegate.close()

    private fun increment(bytesRead: Int) {
        count += bytesRead
        if (count > maxSize) throw SizeLimitExceededException(maxSize)
    }
}

internal class DefaultHttpHandler(private val config: PostchainClientConfig) : HttpHandler, Closeable {
    private val closeableHttpClient: CloseableHttpClient = HttpClients.custom()
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
            .build()

    private val handler: HttpHandler = if (config.compressRequestBodies) {
        RequestFilters.GZip(GzipCompressionMode.Streaming()).then { request -> invokeClassic(request) }
    } else {
        { request -> invokeClassic(request) }
    }

    private val lock = Any()
    private var references = 1
    private var closed = false

    fun acquire() {
        synchronized(lock) {
            check(!closed) { "HTTP client is closed" }
            references++
        }
    }

    fun release() {
        val shouldClose = synchronized(lock) {
            references--
            if (references == 0 && !closed) {
                closed = true
                true
            } else false
        }
        if (shouldClose) closeableHttpClient.close()
    }

    override fun invoke(request: Request): Response = handler(request)

    private fun invokeClassic(request: Request): Response {
        val apacheRequest = toClassicRequest(request)
        return try {
            closeableHttpClient.execute(apacheRequest) { response ->
                val status = Status(response.code, response.reasonPhrase)
                val headers = response.getHeaders().map { it.name to it.value }
                val entity = response.entity
                val body = if (entity == null) ByteArray(0) else readLimitedBytes(entity.content, config.maxResponseSize.toLong())
                Response(status).headers(headers).body(ByteArrayInputStream(body), body.size.toLong())
            }
        } catch (e: SizeLimitExceededException) {
            throw e
        } catch (e: Exception) {
            Response(failureStatus(e))
        }
    }

    override fun close() = release()
}

private fun toClassicRequest(request: Request): BasicClassicHttpRequest {
    val bodyBytes = ByteArray(request.body.payload.remaining())
    request.body.payload.get(bodyBytes)
    val apacheRequest = BasicClassicHttpRequest(request.method.name, java.net.URI.create(request.uri.toString()))
    request.headers
            .filter { !it.first.equals("content-length", ignoreCase = true) }
            .forEach { (name, value) -> apacheRequest.addHeader(name, value) }
    if (bodyBytes.isNotEmpty() || request.method.name in setOf("POST", "PUT", "PATCH")) {
        apacheRequest.setEntity(ByteArrayEntity(bodyBytes, null))
    }
    return apacheRequest
}

private fun readLimitedBytes(stream: InputStream, maxSize: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val bufferSize = if (maxSize > 0) minOf(8192L, maxSize + 1).toInt() else 8192
    val buffer = ByteArray(bufferSize)
    stream.use { input ->
        var count = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (maxSize > 0 && count + read > maxSize) throw SizeLimitExceededException(maxSize)
            output.write(buffer, 0, read)
            count += read
        }
    }
    return output.toByteArray()
}

private fun failureStatus(exception: Throwable): Status {
    var current: Throwable? = exception
    while (current != null) {
        when (current) {
            is org.apache.hc.client5.http.ConnectTimeoutException, is java.net.SocketTimeoutException -> return Status.CLIENT_TIMEOUT
        }
        current = current.cause
    }
    return Status.SERVICE_UNAVAILABLE
}

fun defaultHttpHandler(config: PostchainClientConfig): HttpHandler = DefaultHttpHandler(config)

private class LimitedAsyncResponseData(
        val status: Status,
        val headers: List<Pair<String, String>>,
        val body: ByteArray) {
    fun toResponse(): Response = Response(status)
            .headers(headers)
            .body(ByteArrayInputStream(body), body.size.toLong())
}

private class LimitedAsyncResponseState(private val maxSize: Long) {
    private val limited = maxSize > 0
    private val storeLimit = if (limited) maxSize + 1 else Long.MAX_VALUE
    private val body = ByteArrayOutputStream()
    private var count = 0L

    var status: Status? = null
        private set
    var headers: List<Pair<String, String>> = emptyList()
        private set
    var limitExceeded = false
        private set

    fun consumeResponse(response: HttpResponse) {
        status = Status(response.code, response.reasonPhrase)
        headers = response.getHeaders().map { it.name to it.value }
    }

    @Throws(IOException::class)
    fun consume(buffer: ByteBuffer) {
        val remaining = buffer.remaining()
        if (!limited || count + remaining <= storeLimit) {
            val bytes = ByteArray(remaining)
            buffer.get(bytes)
            body.write(bytes)
            count += remaining
            return
        }

        val allowed = (storeLimit - count).toInt().coerceAtLeast(0)
        if (allowed > 0) {
            val bytes = ByteArray(allowed)
            buffer.get(bytes, 0, allowed)
            body.write(bytes)
            count += allowed
        }
        limitExceeded = true
        throw SizeLimitExceededException(maxSize)
    }

    fun bytes(): ByteArray = body.toByteArray()

    fun response(): Response {
        val bytes = bytes()
        return Response(status ?: Status.SERVICE_UNAVAILABLE)
                .headers(headers)
                .body(SizeLimitedResponseInputStream(ByteArrayInputStream(bytes), maxSize), bytes.size.toLong())
    }

    fun failureResponse(exception: Exception): Response {
        if (limitExceeded) return response()
        val failureStatus = when (exception) {
            is org.apache.hc.client5.http.ConnectTimeoutException -> Status.CLIENT_TIMEOUT
            is java.net.SocketTimeoutException -> Status.CLIENT_TIMEOUT
            else -> Status.SERVICE_UNAVAILABLE
        }
        return Response(failureStatus)
    }
}

private class LimitedAsyncResponseConsumer(
        private val state: LimitedAsyncResponseState) : AsyncResponseConsumer<LimitedAsyncResponseData> {
    private var resultCallback: FutureCallback<LimitedAsyncResponseData>? = null

    override fun consumeResponse(response: HttpResponse, entityDetails: EntityDetails?, context: HttpContext, resultCallback: FutureCallback<LimitedAsyncResponseData>) {
        state.consumeResponse(response)
        this.resultCallback = resultCallback
    }

    override fun informationResponse(response: HttpResponse, context: HttpContext) {}

    override fun updateCapacity(channel: CapacityChannel) {
        channel.update(8192)
    }

    override fun consume(buffer: ByteBuffer) {
        state.consume(buffer)
    }

    override fun streamEnd(trailers: List<Header>?) {
        resultCallback?.completed(LimitedAsyncResponseData(state.status ?: Status.SERVICE_UNAVAILABLE, state.headers, state.bytes()))
    }

    override fun failed(exception: Exception) {
        resultCallback?.failed(exception)
    }

    override fun releaseResources() {}
}

private fun toAsyncRequestProducer(request: Request): AsyncRequestProducer {
    val bodyBytes = ByteArray(request.body.payload.remaining())
    request.body.payload.get(bodyBytes)
    val basicRequest = BasicHttpRequest(request.method.name, request.uri.toString())
    request.headers
            .filter { !it.first.equals("content-length", ignoreCase = true) }
            .forEach { (name, value) -> basicRequest.addHeader(name, value) }
    val entityProducer = if (bodyBytes.isNotEmpty() || request.method.name in setOf("POST", "PUT", "PATCH")) {
        BasicAsyncEntityProducer(bodyBytes)
    } else null
    return BasicRequestProducer(basicRequest, entityProducer)
}

fun defaultAsyncHttpHandler(config: PostchainClientConfig): AsyncHttpHandler {
    val client: CloseableHttpAsyncClient = HttpAsyncClients.custom()
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
            .apply { start() }

    return object : AsyncHttpHandler {
        override fun invoke(request: Request, fn: (Response) -> Unit) {
            val state = LimitedAsyncResponseState(config.maxResponseSize.toLong())
            val delivered = AtomicBoolean(false)
            fun deliver(response: Response) {
                if (delivered.compareAndSet(false, true)) fn(response)
            }
            val callback = object : FutureCallback<LimitedAsyncResponseData> {
                override fun completed(result: LimitedAsyncResponseData) {
                    deliver(result.toResponse())
                }

                override fun failed(exception: Exception) {
                    deliver(state.failureResponse(exception))
                }

                override fun cancelled() {
                    deliver(state.failureResponse(CancellationException()))
                }
            }
            client.execute(toAsyncRequestProducer(request), LimitedAsyncResponseConsumer(state), callback)
        }

        override fun close() = client.close()
    }
}

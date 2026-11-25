package net.postchain.client

import net.postchain.client.config.PostchainClientConfig
import org.apache.hc.client5.http.ConnectTimeoutException
import org.apache.hc.client5.http.HttpHostConnectException
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
import org.apache.hc.core5.http.MalformedChunkCodingException
import org.apache.hc.core5.http.NoHttpResponseException
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
import org.apache.hc.core5.io.CloseMode
import org.apache.hc.core5.io.ModalCloseable
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
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal const val MAX_RESPONSE_SIZE_HEADER = "x-postchain-client-max-response-size"

fun bftMajority(n: Int) = n - (n - 1) / 3

internal class SizeLimitedResponseInputStream(
        private val delegate: InputStream,
        private val maxSize: Long) : InputStream() {
    private val limited = maxSize >= 0
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
        if (limited && count > maxSize) throw SizeLimitExceededException(maxSize)
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
        val maxResponseSize = effectiveMaxResponseSize(request)
        val apacheRequest = toClassicRequest(request)
        return try {
            closeableHttpClient.execute(apacheRequest) { response ->
                val status = Status(response.code, response.reasonPhrase)
                val headers = response.getHeaders().map { it.name to it.value }
                val entity = response.entity
                val body = if (entity == null) ByteArray(0) else try {
                    readLimitedBytes(entity.content, maxResponseSize)
                } catch (e: SizeLimitExceededException) {
                    if (response is ModalCloseable) response.close(CloseMode.IMMEDIATE)
                    response.setEntity(null)
                    throw e
                }
                Response(status).headers(headers).body(ByteArrayInputStream(body), body.size.toLong())
            }
        } catch (e: ConnectTimeoutException) {
            Response(Status.CLIENT_TIMEOUT)
        } catch (e: SocketTimeoutException) {
            Response(Status.CLIENT_TIMEOUT)
        } catch (e: HttpHostConnectException) {
            Response(Status.CONNECTION_REFUSED)
        } catch (e: UnknownHostException) {
            Response(Status.UNKNOWN_HOST)
        } catch (e: SocketException) {
            Response(Status.SERVICE_UNAVAILABLE)
        } catch (e: NoHttpResponseException) {
            Response(Status.SERVICE_UNAVAILABLE)
        } catch (e: MalformedChunkCodingException) {
            Response(Status.SERVICE_UNAVAILABLE)
        }
    }

    private fun effectiveMaxResponseSize(request: Request): Long =
            request.headers
                    .firstOrNull { it.first.equals(MAX_RESPONSE_SIZE_HEADER, ignoreCase = true) }
                    ?.second
                    ?.toLongOrNull()
                    ?: config.maxResponseSize.toLong()

    override fun close() = release()
}

private fun toClassicRequest(request: Request): BasicClassicHttpRequest {
    val payload = request.body.payload.duplicate()
    val bodyBytes = ByteArray(payload.remaining())
    payload.get(bodyBytes)
    val apacheRequest = BasicClassicHttpRequest(request.method.name, java.net.URI.create(request.uri.toString()))
    request.headers
            .filter { !it.first.equals("content-length", ignoreCase = true) }
            .filter { !it.first.equals(MAX_RESPONSE_SIZE_HEADER, ignoreCase = true) }
            .forEach { (name, value) -> apacheRequest.addHeader(name, value) }
    if (bodyBytes.isNotEmpty() || request.method.name in setOf("POST", "PUT", "PATCH")) {
        apacheRequest.setEntity(ByteArrayEntity(bodyBytes, null))
    }
    return apacheRequest
}

private fun readLimitedBytes(stream: InputStream, maxSize: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val limited = maxSize >= 0
    val bufferSize = if (limited) minOf(8192L, maxSize + 1).toInt() else 8192
    val buffer = ByteArray(bufferSize)
    var count = 0L
    try {
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            if (limited && count + read > maxSize) throw SizeLimitExceededException(maxSize)
            output.write(buffer, 0, read)
            count += read
        }
        stream.close()
    } catch (e: SizeLimitExceededException) {
        throw e
    } catch (e: Exception) {
        stream.close()
        throw e
    }
    return output.toByteArray()
}

private fun failureStatus(exception: Exception): Status = when {
    exception is ConnectTimeoutException || exception is SocketTimeoutException -> Status.CLIENT_TIMEOUT
    else -> Status.SERVICE_UNAVAILABLE
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
    private val limited = maxSize >= 0
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
        if (!limited || count + remaining <= maxSize) {
            val bytes = ByteArray(remaining)
            buffer.get(bytes)
            body.write(bytes)
            count += remaining
            return
        }

        val allowed = (maxSize + 1 - count).toInt().coerceAtLeast(0)
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
        if (limitExceeded) {
            val bytes = bytes()
            return Response(Status.SERVICE_UNAVAILABLE)
                    .body(SizeLimitedResponseInputStream(ByteArrayInputStream(bytes), maxSize), bytes.size.toLong())
        }
        return Response(failureStatus(exception))
    }
}

private class LimitedAsyncResponseConsumer(
        private val state: LimitedAsyncResponseState) : AsyncResponseConsumer<LimitedAsyncResponseData> {
    private var resultCallback: FutureCallback<LimitedAsyncResponseData>? = null
    private val streamEnded = AtomicBoolean(false)

    override fun consumeResponse(response: HttpResponse, entityDetails: EntityDetails?, context: HttpContext, resultCallback: FutureCallback<LimitedAsyncResponseData>) {
        state.consumeResponse(response)
        this.resultCallback = resultCallback
        if (entityDetails == null) streamEnd(null)
    }

    override fun informationResponse(response: HttpResponse, context: HttpContext) {}

    override fun updateCapacity(channel: CapacityChannel) {
        channel.update(8192)
    }

    override fun consume(buffer: ByteBuffer) {
        state.consume(buffer)
    }

    override fun streamEnd(trailers: List<Header>?) {
        if (streamEnded.compareAndSet(false, true)) {
            resultCallback?.completed(LimitedAsyncResponseData(state.status ?: Status.SERVICE_UNAVAILABLE, state.headers, state.bytes()))
        }
    }

    override fun failed(exception: Exception) {
        resultCallback?.failed(exception)
    }

    override fun releaseResources() {}
}

private fun toAsyncRequestProducer(request: Request): AsyncRequestProducer {
    val payload = request.body.payload.duplicate()
    val bodyBytes = ByteArray(payload.remaining())
    payload.get(bodyBytes)
    val basicRequest = BasicHttpRequest(request.method.name, request.uri.toString())
    request.headers
            .filter { !it.first.equals("content-length", ignoreCase = true) }
            .filter { !it.first.equals(MAX_RESPONSE_SIZE_HEADER, ignoreCase = true) }
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
            val maxResponseSize = request.headers
                    .firstOrNull { it.first.equals(MAX_RESPONSE_SIZE_HEADER, ignoreCase = true) }
                    ?.second
                    ?.toLongOrNull()
                    ?: config.maxResponseSize.toLong()
            val state = LimitedAsyncResponseState(maxResponseSize)
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

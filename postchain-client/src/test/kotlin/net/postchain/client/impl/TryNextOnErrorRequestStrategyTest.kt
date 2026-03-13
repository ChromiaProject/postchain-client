package net.postchain.client.impl

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import net.postchain.client.DeterministicEndpointPool
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.exception.ClientError
import net.postchain.common.BlockchainRid
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvEncoder.encodeGtv
import net.postchain.gtv.GtvFactory.gtv
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class TryNextOnErrorRequestStrategyTest {
    private val urls = listOf("http://localhost:1", "http://localhost:2", "http://localhost:3")

    private var requestCounter = 0

    @BeforeEach
    fun setup() {
        requestCounter = 0
    }

    @Test
    fun `first node succeeds`() {
        val result: Gtv = makeQuery { _ ->
            requestCounter++
            Response(Status.OK).body(encodeGtv(gtv("query_response")).inputStream())
        }
        assertThat(result.asString()).isEqualTo("query_response")
        assertThat(requestCounter).isEqualTo(1)
    }

    @Test
    fun `first node returns invalid GTV, second node succeeds`() {
        val result: Gtv = makeQuery { request ->
            requestCounter++
            if (request.uri.port == 1)
                Response(Status.OK).body("not valid gtv".toByteArray().inputStream())
            else
                Response(Status.OK).body(encodeGtv(gtv("query_response")).inputStream())
        }
        assertThat(result.asString()).isEqualTo("query_response")
        assertThat(requestCounter).isEqualTo(2)
    }

    @Test
    fun `all nodes return invalid GTV, throws ClientError`() {
        assertFailure {
            makeQuery { _ ->
                requestCounter++
                Response(Status.OK).body("not valid gtv".toByteArray().inputStream())
            }
        }.isInstanceOf(ClientError::class)
        assertThat(requestCounter).isEqualTo(3)
    }

    @Test
    fun `first node returns client error, second node succeeds`() {
        val result: Gtv = makeQuery { request ->
            requestCounter++
            if (request.uri.port == 1)
                Response(Status.BAD_REQUEST).body(encodeGtv(gtv("error")).inputStream())
            else
                Response(Status.OK).body(encodeGtv(gtv("query_response")).inputStream())
        }
        assertThat(result.asString()).isEqualTo("query_response")
        assertThat(requestCounter).isEqualTo(2)
    }

    @Test
    fun `all nodes return client error, throws ClientError`() {
        assertFailure {
            makeQuery { _ ->
                requestCounter++
                Response(Status.BAD_REQUEST).body(encodeGtv(gtv("error")).inputStream())
            }
        }.isInstanceOf(ClientError::class)
        assertThat(requestCounter).isEqualTo(3)
    }

    @Test
    fun `first node returns server error, second node succeeds`() {
        val result: Gtv = makeQuery { request ->
            requestCounter++
            if (request.uri.port == 1)
                Response(Status.BAD_GATEWAY)
            else
                Response(Status.OK).body(encodeGtv(gtv("query_response")).inputStream())
        }
        assertThat(result.asString()).isEqualTo("query_response")
        assertThat(requestCounter).isEqualTo(2)
    }

    private fun makeQuery(httpHandler: HttpHandler): Gtv =
            PostchainClientImpl(
                    PostchainClientConfig(
                            BlockchainRid.buildFromHex(BLOCKCHAIN_RID),
                            DeterministicEndpointPool(urls),
                            requestStrategy = TryNextOnErrorRequestStrategyFactory(),
                            merkleHashVersion = 2),
                    httpClient = httpHandler
            ).query("test_query", gtv("arg"))
}

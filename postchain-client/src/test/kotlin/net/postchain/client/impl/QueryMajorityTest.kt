package net.postchain.client.impl

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import net.postchain.client.DeterministicEndpointPool
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.BlockDetail
import net.postchain.client.core.TransactionResult
import net.postchain.client.core.TxRid
import net.postchain.client.exception.ClientError
import net.postchain.client.exception.NodesDisagree
import net.postchain.common.BlockchainRid
import net.postchain.common.tx.TransactionStatus
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvEncoder.encodeGtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import org.http4k.client.AsyncHttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.net.ssl.SSLException

internal class QueryMajorityTest {
    private val urls = listOf("http://localhost:1", "http://localhost:2", "http://localhost:3", "http://localhost:4")

    private var requestCounter = 0

    @BeforeEach
    fun setup() {
        requestCounter = 0
    }

    @Test
    fun `nodes agree`() {
        val queryResponse: Gtv = makeQuery(object : AsyncHttpHandler {
            override fun invoke(request: Request, fn: (Response) -> Unit) {
                requestCounter++
                fn(Response(Status.OK).body(encodeGtv(gtv("query_response")).inputStream()))
            }
        })
        assertThat(queryResponse.asString()).isEqualTo("query_response")
        assertThat(requestCounter).isEqualTo(4)
    }

    @Test
    fun `all nodes disagree`() {
        assertFailure {
            makeQuery(object : AsyncHttpHandler {
                override fun invoke(request: Request, fn: (Response) -> Unit) {
                    requestCounter++
                    fn(Response(Status.OK).body(encodeGtv(gtv("query_response ${request.uri.port}")).inputStream()))
                }
            })
        }.isInstanceOf(NodesDisagree::class)
        assertThat(requestCounter).isEqualTo(4)
    }

    @Test
    fun `one node disagree`() {
        val queryResponse: Gtv = makeQuery(object : AsyncHttpHandler {
            override fun invoke(request: Request, fn: (Response) -> Unit) {
                requestCounter++
                fn(Response(Status.OK).body(encodeGtv(gtv(
                        if (request.uri.port == 1) "bogus_response" else "query_response"
                )).inputStream()))
            }
        })
        assertThat(queryResponse.asString()).isEqualTo("query_response")
        assertThat(requestCounter).isEqualTo(4)
    }

    @Test
    fun `first node fails and rest disagree`() {
        assertFailure {
            makeQuery(object : AsyncHttpHandler {
                override fun invoke(request: Request, fn: (Response) -> Unit) {
                    requestCounter++
                    fn(if ((request.uri.port ?: 0) > 1)
                        Response(Status.OK).body(encodeGtv(gtv("query_response ${request.uri.port}")).inputStream())
                    else
                        Response(Status.BAD_REQUEST).body(encodeGtv(gtv("the error")).inputStream()))
                }
            })
        }.isInstanceOf(NodesDisagree::class)
        assertThat(requestCounter).isEqualTo(4)
    }

    @Test
    fun `first node fails and rest agree`() {
        assertThat(nodesFail(1).asString()).isEqualTo("query_response")
        assertThat(requestCounter).isEqualTo(4)
    }

    @Test
    fun `two nodes fail`() {
        assertFailure { nodesFail(2) }.isInstanceOf(ClientError::class)
        assertThat(requestCounter).isEqualTo(4)
    }

    @Test
    fun `three nodes fail`() {
        assertFailure { nodesFail(3) }.isInstanceOf(ClientError::class)
        assertThat(requestCounter).isEqualTo(4)
    }

    @Test
    fun `all nodes fail`() {
        assertFailure { nodesFail(4) }.isInstanceOf(ClientError::class)
        assertThat(requestCounter).isEqualTo(4)
    }

    private fun nodesFail(failingNodes: Int): Gtv =
            makeQuery(object : AsyncHttpHandler {
                override fun invoke(request: Request, fn: (Response) -> Unit) {
                    requestCounter++
                    fn(if ((request.uri.port ?: 0) > failingNodes)
                        Response(Status.OK).body(encodeGtv(gtv("query_response")).inputStream())
                    else
                        Response(Status.BAD_REQUEST).body(encodeGtv(gtv("the error")).inputStream()))
                }
            })

    private fun makeQuery(asyncHttpHandler: AsyncHttpHandler): Gtv =
            PostchainClientImpl(
                    PostchainClientConfig(
                            BlockchainRid.buildFromHex(BLOCKCHAIN_RID),
                            DeterministicEndpointPool(urls),
                            requestStrategy = QueryMajorityRequestStrategyFactory(asyncHttpHandler),
                            merkleHashVersion = 2)
            ).query("test_query", gtv("arg"))

    @Test
    fun `blockAtHeight found`() {
        val someBlock: BlockDetail? = PostchainClientImpl(PostchainClientConfig(
                BlockchainRid.buildFromHex(BLOCKCHAIN_RID),
                DeterministicEndpointPool(urls),
                requestStrategy = QueryMajorityRequestStrategyFactory(object : AsyncHttpHandler {
                    override fun invoke(request: Request, fn: (Response) -> Unit) {
                        requestCounter++
                        assertThat(request.header("Accept")).isEqualTo("application/octet-stream")
                        fn(Response(Status.OK).body(encodeGtv(validBlockDetail(1)).inputStream()))
                    }
                }),
                merkleHashVersion = 2)).blockAtHeight(1L)
        assertThat(someBlock!!.height).isEqualTo(1L)
        assertThat(requestCounter).isEqualTo(4)
    }

    @Test
    fun `blockAtHeight not found`() {
        val someBlock: BlockDetail? = PostchainClientImpl(PostchainClientConfig(
                BlockchainRid.buildFromHex(BLOCKCHAIN_RID),
                DeterministicEndpointPool(urls),
                requestStrategy = QueryMajorityRequestStrategyFactory(object : AsyncHttpHandler {
                    override fun invoke(request: Request, fn: (Response) -> Unit) {
                        requestCounter++
                        assertThat(request.header("Accept")).isEqualTo("application/octet-stream")
                        fn(Response(Status.OK).body(encodeGtv(GtvNull).inputStream()))
                    }
                }),
                merkleHashVersion = 2)).blockAtHeight(1L)
        assertThat(someBlock).isNull()
        assertThat(requestCounter).isEqualTo(4)
    }

    @Test
    fun `blockAtHeight disagree`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(
                    BlockchainRid.buildFromHex(BLOCKCHAIN_RID),
                    DeterministicEndpointPool(urls),
                    requestStrategy = QueryMajorityRequestStrategyFactory(object : AsyncHttpHandler {
                        override fun invoke(request: Request, fn: (Response) -> Unit) {
                            requestCounter++
                            assertThat(request.header("Accept")).isEqualTo("application/octet-stream")
                            fn(Response(Status.OK).body(encodeGtv(validBlockDetail(request.uri.port?.toByte() ?: 0)).inputStream()))
                        }
                    }),
                    merkleHashVersion = 2)).blockAtHeight(1L)
        }.isInstanceOf(NodesDisagree::class)
        assertThat(requestCounter).isEqualTo(4)
    }

    @Test
    fun `blockAtHeight disagree found or not found`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(
                    BlockchainRid.buildFromHex(BLOCKCHAIN_RID),
                    DeterministicEndpointPool(urls),
                    requestStrategy = QueryMajorityRequestStrategyFactory(object : AsyncHttpHandler {
                        override fun invoke(request: Request, fn: (Response) -> Unit) {
                            requestCounter++
                            assertThat(request.header("Accept")).isEqualTo("application/octet-stream")
                            fn(if ((request.uri.port ?: 0) > 2)
                                Response(Status.OK).body(encodeGtv(GtvNull).inputStream())
                            else
                                Response(Status.OK).body(encodeGtv(validBlockDetail(1)).inputStream()))
                        }
                    }),
                    merkleHashVersion = 2)).blockAtHeight(1L)
        }.isInstanceOf(NodesDisagree::class)
        assertThat(requestCounter).isEqualTo(4)
    }

    @Test
    fun `txStatus agree`() {
        val txStatus: TransactionResult = PostchainClientImpl(PostchainClientConfig(
                BlockchainRid.buildFromHex(BLOCKCHAIN_RID),
                DeterministicEndpointPool(urls),
                requestStrategy = QueryMajorityRequestStrategyFactory(object : AsyncHttpHandler {
                    override fun invoke(request: Request, fn: (Response) -> Unit) {
                        requestCounter++
                        fn(Response(Status.OK).body("""{"status":"CONFIRMED"}"""))
                    }
                }),
                merkleHashVersion = 2)).checkTxStatus(TxRid("62F71D71BA63D03FA0C6741DE22B116A3A8022893E7977DDC2A9CD981BBADE29"))
        assertThat(txStatus.status).isEqualTo(TransactionStatus.CONFIRMED)
        assertThat(requestCounter).isEqualTo(4)
    }

    @Test
    fun `SSLExceptions should be handled`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(
                    BlockchainRid.buildFromHex(BLOCKCHAIN_RID),
                    DeterministicEndpointPool(urls),
                    requestStrategy = QueryMajorityRequestStrategyFactory(object : AsyncHttpHandler {
                        override fun invoke(request: Request, fn: (Response) -> Unit) {
                            requestCounter++
                            throw SSLException("Bad SSL")
                        }
                    }),
                    merkleHashVersion = 2)).blockAtHeight(1L)
        }.isInstanceOf(SSLException::class)
        assertThat(requestCounter).isEqualTo(4)
    }
}

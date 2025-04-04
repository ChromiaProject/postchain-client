package net.postchain.client.impl

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import assertk.isContentEqualTo
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import mu.KLogging
import net.postchain.client.config.FailOverConfig
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.config.STATUS_POLL_COUNT
import net.postchain.client.core.BlockDetail
import net.postchain.client.core.BlockRid
import net.postchain.client.core.TransactionInfo
import net.postchain.client.core.TxRid
import net.postchain.client.exception.ClientError
import net.postchain.client.exception.NotFoundError
import net.postchain.client.impl.PostchainClientImpl.CurrentBlockHeight
import net.postchain.client.impl.PostchainClientImpl.ErrorResponse
import net.postchain.client.impl.PostchainClientImpl.TxStatus
import net.postchain.client.request.EndpointPool
import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.common.rest.AnchoringChainCheck
import net.postchain.common.rest.HighestBlockHeightAnchoringCheck
import net.postchain.common.toHex
import net.postchain.common.tx.TransactionStatus
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvEncoder.encodeGtv
import net.postchain.gtv.GtvException
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.mapper.toObject
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV1
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV2
import net.postchain.gtx.GtxQuery
import org.apache.commons.io.input.InfiniteCircularInputStream
import org.http4k.core.Body
import org.http4k.core.ContentType
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.ServerSocketChannel
import java.time.Duration
import java.util.concurrent.CompletionException
import javax.net.ssl.SSLException

internal class PostchainClientImplTest {
    private var url = "http://localhost:7740"
    private lateinit var httpClient: HttpHandler
    private var requestCounter = 0

    companion object : KLogging()

    @BeforeEach
    fun setup() {
        httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                requestCounter++
                return Response(Status.OK, "")
            }
        }
    }

    private fun driveTestCorrectNumberOfAttempts(client: PostchainClientImpl, numberExpected: Int) {
        client.transactionBuilder()
                .addNop()
                .postAwaitConfirmation()

        // Verify
        assertThat(requestCounter).isEqualTo(numberExpected + 1)
    }

    @Test
    fun `Max number of attempts by default`() {
        driveTestCorrectNumberOfAttempts(
                PostchainClientImpl(PostchainClientConfig(
                        BlockchainRid.buildFromHex(BLOCKCHAIN_RID),
                        EndpointPool.singleUrl(url),
                        statusPollInterval = Duration.ZERO,
                        failOverConfig = FailOverConfig(1),
                        merkleHashVersion = 2
                ), httpClient = httpClient),
                // If I didn't pass a max value, it defaults to RETRIEVE_TX_STATUS_ATTEMPTS = 20
                numberExpected = STATUS_POLL_COUNT)
    }

    @Test
    fun `Max number of attempts parameterized`() {
        driveTestCorrectNumberOfAttempts(
                PostchainClientImpl(PostchainClientConfig(
                        BlockchainRid.buildFromHex(BLOCKCHAIN_RID),
                        EndpointPool.singleUrl(url),
                        statusPollCount = 10,
                        statusPollInterval = Duration.ZERO,
                        failOverConfig = FailOverConfig(1),
                        merkleHashVersion = 2
                ), httpClient = httpClient),
                // If I pass a custom max value, verify it uses it
                numberExpected = 10
        )
    }

    @Test
    fun `Post transaction should properly encode transaction`() {
        val client = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.body.stream.readAllBytes().toHex())
                        .isEqualTo("A5363034A52E302CA1220420EC03EDC6959E358B80D226D16A5BB6BC8EDE80EC17BD8BD0F21846C244AE7E8FA5023000A5023000A5023000")
                return Response(Status.OK).body(Body.EMPTY)
            }
        })
        val txResult = client.transactionBuilder().finish().build().post()
        assertThat(txResult.status).isEqualTo(TransactionStatus.WAITING)
    }

    @Test
    fun `Post transaction should parse JSON error response`() {
        val client = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.body.stream.readAllBytes().toHex())
                        .isEqualTo("A5363034A52E302CA1220420EC03EDC6959E358B80D226D16A5BB6BC8EDE80EC17BD8BD0F21846C244AE7E8FA5023000A5023000A5023000")
                return Response(Status.CONFLICT).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(gtv("Message!")).inputStream())
            }
        })
        val txResult = client.transactionBuilder().finish().build().post()
        assertThat(txResult.status).isEqualTo(TransactionStatus.REJECTED)
        assertThat(txResult.rejectReason).isEqualTo("Message!")
    }

    @Test
    fun `Post transaction should handle HTML response which can come from proxy`() {
        val client = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.body.stream.readAllBytes().toHex())
                        .isEqualTo("A5363034A52E302CA1220420EC03EDC6959E358B80D226D16A5BB6BC8EDE80EC17BD8BD0F21846C244AE7E8FA5023000A5023000A5023000")
                return Response(Status.REQUEST_ENTITY_TOO_LARGE).header(Header.ContentType, "text/html").body("<html><body><h1>413 Request Entity Too Large</h1></body></html>")
            }
        })
        val txResult = client.transactionBuilder().finish().build().post()
        assertThat(txResult.status).isEqualTo(TransactionStatus.REJECTED)
        assertThat(txResult.httpStatusCode).isEqualTo(Status.REQUEST_ENTITY_TOO_LARGE.code)
    }

    @Test
    fun `Query response without body should throw IOException`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request) = Response(Status.OK).body(Body.EMPTY)
            }).query("test_query", gtv(mapOf()))
        }.isInstanceOf(IOException::class)
    }

    @Test
    fun `Query error without body should throw ClientError`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request) = Response(Status.BAD_REQUEST).body(Body.EMPTY)
            }).query("test_query", gtv(mapOf()))
        }.isInstanceOf(ClientError::class)
    }

    @Test
    fun `Tx status retrieves underlying error`() {
        val result = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request) =
                    Response(Status.OK).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body(Gson().toJson(TxStatus("rejected", "Message!")))
        }).checkTxStatus(TxRid(""))
        assertThat(result.rejectReason).isEqualTo("Message!")
    }

    @Test
    fun `too big tx status response is rejected`() {
        assertFailure {
            try {
                PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), maxResponseSize = 1024, merkleHashVersion = 2), httpClient = object : HttpHandler {
                    override fun invoke(request: Request) = Response(Status.OK)
                            .header(Header.ContentType, ContentType.APPLICATION_JSON.value)
                            .body(InfiniteCircularInputStream("{${" ".repeat(2000)}".toByteArray()))
                }).checkTxStatus(TxRid(""))
            } catch (e: CompletionException) {
                throw e.cause ?: e
            }
        }.isInstanceOf(IOException::class)
    }

    @Test
    fun `unknown Rell operation tx is immediately rejected with proper reject reason`() {
        val client = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                val error = GsonBuilder().create().toJson(ErrorResponse("Unknown operation: add_node"))
                return Response(Status.BAD_REQUEST).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body(error)
            }
        })
        val txResult = client.transactionBuilder().finish().build().postAwaitConfirmation()
        assertThat(txResult.httpStatusCode).isEqualTo(Status.BAD_REQUEST.code)
        assertThat(txResult.status).isEqualTo(TransactionStatus.REJECTED)
        assertThat(txResult.rejectReason).isEqualTo("Unknown operation: add_node")
    }

    @Test
    fun `Await aborts if rejected`() {
        var nCalls = 0
        PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                nCalls++
                return Response(Status.OK).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body(Gson().toJson(TxStatus("rejected", "Message!")))
            }
        }).awaitConfirmation(TxRid(""), 10, Duration.ZERO)
        assertThat(nCalls).isEqualTo(1)
    }

    @Test
    fun `Query by chainId instead of BlockchainRid`() {
        val config = PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), queryByChainId = 10, merkleHashVersion = 2)
        assertQueryUrlEndsWith(config, "iid_10")
    }

    @Test
    fun `Query by blockchainRid instead of chainId`() {
        val config = PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2)
        assertQueryUrlEndsWith(config, BLOCKCHAIN_RID)
    }

    @Test
    fun `blockAtHeight found`() {
        val someBlock: BlockDetail? = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.header(Header.Accept)).isEqualTo(ContentType.OCTET_STREAM.value)
                return Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(validBlockDetail(1)).inputStream())
            }
        }).blockAtHeight(1L)
        assertThat(someBlock!!.height).isEqualTo(1L)
        assertThat(someBlock.rid.data).isContentEqualTo(blockRid)
        assertThat(someBlock.transactions[0].rid.data).isContentEqualTo("62F71D71BA63D03FA0C6741DE22B116A3A8022893E7977DDC2A9CD981BBADE29".hexStringToByteArray())
        assertThat(someBlock.transactions[0].data).isNull()
    }

    @Test
    fun `blockAtHeight invalid`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request): Response {
                    assertThat(request.header(Header.Accept)).isEqualTo(ContentType.OCTET_STREAM.value)
                    return Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(validBlockDetail(1)).inputStream())
                }
            }).blockAtHeight(2L)
        }.isInstanceOf(ClientError::class).messageContains(
                "Block height mismatch, got 1 but expected 2")
    }

    @Test
    fun `blockAtHeight missing`() {
        val noBlock: BlockDetail? = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request) =
                    Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(GtvNull).inputStream())
        }).blockAtHeight(Long.MAX_VALUE)
        assertThat(noBlock).isNull()
    }

    @Test
    fun `blockByRid found`() {
        val someBlock: BlockDetail? = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.header(Header.Accept)).isEqualTo(ContentType.OCTET_STREAM.value)
                return Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(validBlockDetail(1)).inputStream())
            }
        }).blockByRid(BlockRid(blockRid.toHex()))
        assertThat(someBlock!!.height).isEqualTo(1L)
        assertThat(someBlock.rid.data).isContentEqualTo(blockRid)
        assertThat(someBlock.transactions[0].rid.data).isContentEqualTo("62F71D71BA63D03FA0C6741DE22B116A3A8022893E7977DDC2A9CD981BBADE29".hexStringToByteArray())
        assertThat(someBlock.transactions[0].data).isNull()
    }

    @Test
    fun `blockByRid with invalid RID should be detected`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request): Response {
                    assertThat(request.header(Header.Accept)).isEqualTo(ContentType.OCTET_STREAM.value)
                    return Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(validBlockDetail(1)).inputStream())
                }
            }).blockByRid(BlockRid("34ED10678AAE0414562340E8754A7CCD174B435B52C7F0A4E69470537AEE47E7"))
        }.isInstanceOf(ClientError::class).messageContains(
                "Block RID mismatch, got ${blockRid.toHex()} but expected 34ED10678AAE0414562340E8754A7CCD174B435B52C7F0A4E69470537AEE47E7")
    }

    @Test
    fun `blockByRid with invalid block header should be detected`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request): Response {
                    assertThat(request.header(Header.Accept)).isEqualTo(ContentType.OCTET_STREAM.value)
                    return Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(invalidBlockDetail).inputStream())
                }
            }).blockByRid(BlockRid(blockRid.toHex()))
        }.isInstanceOf(ClientError::class).messageContains(
                "Invalid block header, got with RID ${invalidBlockHeader.blockRid().toHex()} but expected RID ${blockRid.toHex()}")
    }

    @Test
    fun `blockByRid missing`() {
        val noBlock: BlockDetail? = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request) =
                    Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(GtvNull).inputStream())
        }).blockByRid(BlockRid(""))
        assertThat(noBlock).isNull()
    }

    @Test
    fun `query without args is sent with GET`() {
        val queryResponse: Gtv = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.method).isEqualTo(Method.GET)
                assertThat(request.uri.path).isEqualTo("/query_gtv/$BLOCKCHAIN_RID")
                assertThat(request.uri.query).isEqualTo("type=test_query")
                return Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(gtv("query_response")).inputStream())
            }
        }).query("test_query", gtv(mapOf()))
        assertThat(queryResponse.asString()).isEqualTo("query_response")
    }

    @Test
    fun `query with small args is sent with GET`() {
        val queryArgs = gtv(mapOf("arg1" to gtv("value"), "arg2" to gtv("value2")))
        val queryResponse: Gtv = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.method).isEqualTo(Method.GET)
                assertThat(request.uri.path).isEqualTo("/query_gtv/$BLOCKCHAIN_RID")
                assertThat(request.uri.query).isEqualTo("type=test_query&%7Eargs=${encodeGtv(queryArgs).toHex()}")
                return Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(gtv("query_response")).inputStream())
            }
        }).query("test_query", queryArgs)
        assertThat(queryResponse.asString()).isEqualTo("query_response")
    }

    @Test
    fun `query with large args is sent with POST`() {
        val queryArgs = gtv(mapOf("arg1" to gtv("ab".repeat(1000)), "arg2" to gtv(17)))
        val queryResponse: Gtv = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.method).isEqualTo(Method.POST)
                assertThat(request.uri.path).isEqualTo("/query_gtv/$BLOCKCHAIN_RID")
                assertThat(request.uri.query).isEmpty()
                assertThat(request.header(Header.ContentType)).isEqualTo(ContentType.OCTET_STREAM.value)
                assertThat(request.body.stream.use { it.readAllBytes() }).isContentEqualTo(GtxQuery("test_query", queryArgs).encode())
                return Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(gtv("query_response")).inputStream())
            }
        }).query("test_query", queryArgs)
        assertThat(queryResponse.asString()).isEqualTo("query_response")
    }

    @Test
    fun `too big response will be rejected`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), maxResponseSize = 1024, merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request) =
                        Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(gtv(ByteArray(2 * 1024))).inputStream())
            }).query("test_query", gtv(mapOf()))
        }.isInstanceOf(GtvException::class)
    }

    @Test
    fun `invalid GTV response will throw IOException`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), maxResponseSize = 1024), httpClient = object : HttpHandler {
                override fun invoke(request: Request) =
                        Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(ByteArray(100).inputStream())
            }).query("test_query", gtv(mapOf()))
        }.isInstanceOf(IOException::class)
    }

    @Test
    fun `binary GTV error will be parsed`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), maxResponseSize = 1024, merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request) =
                        Response(Status.BAD_REQUEST).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(gtv("the error")).inputStream())
            }).query("test_query", gtv(mapOf()))
        }.isInstanceOf(ClientError::class)
    }

    @Test
    fun `current block height can be parsed`() {
        val currentBlockHeight: Long = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request) =
                    Response(Status.OK).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body(Gson().toJson(CurrentBlockHeight(0)))
        }).currentBlockHeight()
        assertThat(currentBlockHeight).isEqualTo(0)
    }

    @Test
    fun `current block height from the specific container can be parsed`() {
        val currentBlockHeight: Long = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request) =
                    Response(Status.OK).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body(Gson().toJson(
                            CurrentBlockHeight(request.query("container")?.toLong() ?: 0L))
                    )
        }).currentBlockHeight("123")
        assertThat(currentBlockHeight).isEqualTo(123)
    }

    @Test
    fun `too big block height response will be rejected`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), maxResponseSize = 1024, merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request) =
                        Response(Status.OK).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body("""{"blockHeight":${" ".repeat(1024)}1}""")
            }).currentBlockHeight()
        }.isInstanceOf(IOException::class)
    }

    @Test
    fun `Can handle empty error body`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), maxResponseSize = 1024, merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request) = Response(Status.BAD_REQUEST).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body("")
            }).currentBlockHeight()
        }.isInstanceOf(ClientError::class)
    }

    @Test
    fun `Can handle too big error body`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), maxResponseSize = 1024, merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request) =
                        Response(Status.BAD_REQUEST).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body("""{"error":"${"e".repeat(1024)}"}""")
            }).currentBlockHeight()
        }.isInstanceOf(IOException::class)
    }

    @Test
    fun `Client generated errors can be handled`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl("http://invalidhost"), merkleHashVersion = 2)).currentBlockHeight()
        }.isInstanceOf(ClientError::class)
    }

    @Test
    fun `JSON confirmation proof can be parsed`() {
        val proof: ByteArray = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request) =
                    Response(Status.OK).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body("""{"proof":"${encodedConfirmationProof.toHex()}"}""")
        }).confirmationProof(TxRid("42"))
        assertThat(proof).isContentEqualTo(encodedConfirmationProof)
    }

    @Test
    fun `binary confirmation proof can be parsed`() {
        val proof: ByteArray = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request) =
                    Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodedConfirmationProof.inputStream())
        }).confirmationProof(TxRid("42"))
        assertThat(proof).isContentEqualTo(encodedConfirmationProof)
    }

    @Test
    fun `confirmation proof not found`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request) =
                        Response(Status.NOT_FOUND).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body("""{"error":"Can't find tx with hash 42"}""")
            }).confirmationProof(TxRid("42"))
        }.isInstanceOf(ClientError::class).messageContains("404")
    }

    @Test
    fun `JSON transaction data can be parsed`() {
        val txString = "A58209213082091DA582091530820911A12204208F77E7DC903AE184A1569E60F8097CAFFB105F741D"
        val transaction: ByteArray = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request) =
                    Response(Status.OK).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body("""{"tx":"$txString"}""")
        }).getTransaction(TxRid("42"))
        assertThat(transaction).isContentEqualTo(txString.hexStringToByteArray())
    }

    @Test
    fun `Binary transaction data can be handled`() {
        val tx = "A58209213082091DA582091530820911A12204208F77E7DC903AE184A1569E60F8097CAFFB105F741D".hexStringToByteArray()
        val transaction: ByteArray = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request) =
                    Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(tx.inputStream())
        }).getTransaction(TxRid("42"))
        assertThat(transaction).isContentEqualTo(tx)
    }

    @Test
    fun `transaction not found`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request) =
                        Response(Status.NOT_FOUND).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body("""{"error":"Can't find tx with hash 42"}""")
            }).getTransaction(TxRid("42"))
        }.isInstanceOf(ClientError::class)
    }

    @Test
    fun `Transaction count can be parsed`() {
        val transactionsCount = 42L
        val count: Long = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request) =
                    Response(Status.OK).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body("""{"transactionsCount":$transactionsCount}""")
        }).getTransactionsCount()
        assertThat(count).isEqualTo(transactionsCount)
    }

    @Test
    fun `Blockchain RID not found`() {
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url)), httpClient = object : HttpHandler {
                override fun invoke(request: Request) =
                        Response(Status.NOT_FOUND).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body("{\"error\":\"Can\\u0027t find blockchain with blockchainRID: 9E6CB107E0DF8D9872336B845FF7919775158EA3715E58F3BDE880C883EC6F0A\"}")
            }).getBlockchainRID(0)
        }.isInstanceOf(NotFoundError::class)
    }

    @Test
    fun `Blockchain RID can be parsed`() {
        val bcRid = "9E6CB107E0DF8D9872336B845FF7919775158EA3715E58F3BDE880C883EC6F00"
        val blockchainRid: BlockchainRid = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request) =
                    Response(Status.OK).header(Header.ContentType, ContentType.TEXT_PLAIN.value).body(bcRid)
        }).getBlockchainRID(0)
        assertThat(blockchainRid.toHex()).isEqualTo(bcRid)
    }

    @Test
    fun `current configuration height can be fetched`() {
        val expectedConfig = gtv(mapOf("foo" to gtv("bar"), "baz" to gtv(17)))
        val config: Gtv = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.uri.path).isEqualTo("/config/${BlockchainRid.buildFromHex(BLOCKCHAIN_RID)}")
                assertThat(request.uri.query).isEqualTo("")
                return Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(expectedConfig).inputStream())
            }
        }).getConfiguration()
        assertThat(config).isEqualTo(expectedConfig)
    }

    @Test
    fun `custom configuration height can be fetched`() {
        val expectedConfig = gtv(mapOf("foo" to gtv("bar"), "baz" to gtv(17)))
        val config: Gtv = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.uri.path).isEqualTo("/config/${BlockchainRid.buildFromHex(BLOCKCHAIN_RID)}")
                assertThat(request.uri.query).isEqualTo("height=17")
                return Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(expectedConfig).inputStream())
            }
        }).getConfiguration(height = 17)
        assertThat(config).isEqualTo(expectedConfig)
    }

    @Test
    fun `Validation of blockchain configuration succeeds`() {
        assertDoesNotThrow {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request) =
                        Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(Body.EMPTY)
            }).validateConfiguration(gtv(mapOf()))
        }
    }

    @Test
    fun `Validation of blockchain configuration fails`() {
        assertThrows<ClientError> {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request) =
                        Response(Status.BAD_REQUEST).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body("{\"error\":\"Something wrong in config\"}")
            }).validateConfiguration(gtv(mapOf()))
        }
    }

    @Test
    fun `merkle hash version config`() {
        val client = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2))
        assertThat(client.merkleHashCalculator is GtvMerkleHashCalculatorV2).isTrue()
        assertThat(client.config.merkleHashVersion).isEqualTo(2)
    }

    @Test
    fun `auto detect merkle hash version`() {
        val client = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url)), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.uri.path).isEqualTo("/config/${BlockchainRid.buildFromHex(BLOCKCHAIN_RID)}/features")
                assertThat(request.uri.query).isEqualTo("")
                return Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(gtv(mapOf("merkle_hash_version" to gtv(2)))).inputStream())
            }
        })
        assertThat(client.merkleHashCalculator is GtvMerkleHashCalculatorV2).isTrue()
        assertThat(client.config.merkleHashVersion).isEqualTo(0)
    }

    @Test
    fun `given features does not contain merkle_hash_version then auto detect merkle hash version fallback to version 1`() {
        val client = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url)), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.uri.path).isEqualTo("/config/${BlockchainRid.buildFromHex(BLOCKCHAIN_RID)}/features")
                assertThat(request.uri.query).isEqualTo("")
                return Response(Status.OK).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body("""{}""")
            }
        })
        assertThat(client.merkleHashCalculator is GtvMerkleHashCalculatorV1).isTrue()
        assertThat(client.config.merkleHashVersion).isEqualTo(0)
    }

    @Test
    fun `failed auto detect merkle hash version fallback to version 1`() {
        val client = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url)), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.uri.path).isEqualTo("/config/${BlockchainRid.buildFromHex(BLOCKCHAIN_RID)}/features")
                assertThat(request.uri.query).isEqualTo("")
                return Response(Status.NOT_FOUND).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body("")
            }
        })
        assertThat(client.merkleHashCalculator is GtvMerkleHashCalculatorV1).isTrue()
        assertThat(client.config.merkleHashVersion).isEqualTo(0)
    }

    @Nested
    inner class GetTransactionInfo {
        @Test
        fun `Transaction info can be parsed`() {
            val body = """{
            "blockRID": "4242424242",
            "blockHeight": 54,
            "blockHeader": "ABBAABBAABBAABBAABBA",
            "witness": "AABBAABB",
            "timestamp": 42,
            "txRID": "42",
            "txHash": "432143214321",
            "txData": "DABA1234DABA1234"
            }""".trimIndent()
            val info = Gson().fromJson(body, TransactionInfo.Json::class.java)
            val result = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request) =
                        Response(Status.OK).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body(Gson().toJson(info))
            }).getTransactionInfo(TxRid("42"))
            assertThat(result.blockRID.toHex()).isEqualTo("4242424242")
            assertThat(result.blockHeight).isEqualTo(54)
            assertThat(result.blockHeader.toHex()).isEqualTo("ABBAABBAABBAABBAABBA")
            assertThat(result.witness.toHex()).isEqualTo("AABBAABB")
            assertThat(result.timestamp).isEqualTo(42)
            assertThat(result.txRID.toHex()).isEqualTo("42")
            assertThat(result.txHash.toHex()).isEqualTo("432143214321")
            assertThat(result.txData.toHex()).isEqualTo("DABA1234DABA1234")
        }

        @Test
        fun `Failed to find blockchain should throw ClientError`() {
            assertFailure {
                PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
                    override fun invoke(request: Request) = Response(Status.BAD_REQUEST).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body("""
                        {
                        "error": "Can't find blockchain with blockchainRID: <hex-string>"
                        }
                    """.trimIndent())
                }).getTransactionInfo(TxRid("42"))
            }.isInstanceOf(ClientError::class).messageContains("Can't find blockchain with blockchainRID")
        }
    }

    @Nested
    inner class GetTransactionsInfo {
        @Test
        fun `Transactions info can be parsed`() {
            val infos = listOf(
                    TransactionInfo.Json("4242424242", 54, "ABBAABBAABBAABBAABBA", "AABBAABB", 42, "123412341234", "432143214321", "DABA1234DABA1234"),
                    TransactionInfo.Json("4141414141", 54, "ABBAABBAABBAABBAABBA", "AABBAABB", 42, "123412341234", "432143214321", "DABA1234DABA1234")
            )
            val response = Gson().toJson(infos)
            val result = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request): Response {
                    assertThat(request.query("limit")).isNull()
                    assertThat(request.query("before-time")).isNull()
                    assertThat(request.query("signer")).isNull()
                    return Response(Status.OK).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body(response)
                }
            }).getTransactionsInfo()
            assertThat(result[0].blockRID.toHex()).isEqualTo("4242424242")
            assertThat(result[1].blockRID.toHex()).isEqualTo("4141414141")
        }

        @Test
        fun `Transactions info with query params should be valid request`() {
            val infos = listOf(
                    TransactionInfo.Json("4242424242", 54, "ABBAABBAABBAABBAABBA", "AABBAABB", 42, "123412341234", "432143214321", "DABA1234DABA1234"),
                    TransactionInfo.Json("4141414141", 54, "ABBAABBAABBAABBAABBA", "AABBAABB", 42, "123412341234", "432143214321", "DABA1234DABA1234")
            )
            val response = Gson().toJson(infos)
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request): Response {
                    assertThat(request.query("limit")).isEqualTo("123")
                    assertThat(request.query("before-time")).isEqualTo("132")
                    assertThat(request.query("signer")).isEqualTo("ABBA")
                    return Response(Status.OK).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body(response)
                }
            }).getTransactionsInfo(123, 132, "ABBA")
        }

        @Test
        fun `Failed to find blockchain should throw ClientError`() {
            assertFailure {
                PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
                    override fun invoke(request: Request) = Response(Status.BAD_REQUEST).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body("""
                        {
                        "error": "Can't find blockchain with blockchainRID: <hex-string>"
                        }
                    """.trimIndent())
                }).getTransactionsInfo()
            }.isInstanceOf(ClientError::class)
        }
    }

    @Test
    fun `get waiting transactions`() {
        val txRids = listOf(
                TxRid(ByteArray(32) { 1 }.toHex()),
                TxRid(ByteArray(32) { 2 }.toHex()),
                TxRid(ByteArray(32) { 3 }.toHex()),
        )
        val waitingTxRids = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                return Response(Status.OK).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body(Gson().toJson(txRids.map { it.rid }))
            }
        }).getWaitingTransactions()
        assertThat(waitingTxRids).isEqualTo(txRids)
    }

    @Test
    fun `Assert trailing slashes are trimmed from endpoint URL`() {
        val defaultEndpointPool = EndpointPool.default(listOf("http://localhost:7740/", "http://localhost:7741/"))
        assertThat(defaultEndpointPool.map { it.url }.toSet()).isEqualTo(setOf("http://localhost:7740", "http://localhost:7741"))

        val singleEndpointPool = EndpointPool.singleUrl("http://localhost:7740/")
        assertThat(singleEndpointPool.first().url).isEqualTo("http://localhost:7740")
    }

    @Test
    fun `SSLException should skip to next endpoint and finally fail with server failure`() {
        val endpointPool = EndpointPool.default(listOf("http://localhost:7740/", "http://localhost:7741/"))
        assertFailure {
            PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID), endpointPool, merkleHashVersion = 2), httpClient = object : HttpHandler {
                override fun invoke(request: Request) = throw SSLException("Bad SSL")
            }).query("test_query", gtv(mapOf()))
        }.isInstanceOf(ClientError::class)
    }

    @Test
    @Disabled // for manual testing
    @Timeout(5)
    fun `connect timeout`() {
        val client = PostchainClientImpl(PostchainClientConfig(
                BlockchainRid.buildFromHex(BLOCKCHAIN_RID),
                EndpointPool.singleUrl("http://example.com:1234"),
                failOverConfig = FailOverConfig(1),
                connectTimeout = Duration.ofSeconds(1),
                merkleHashVersion = 2
        ))
        try {
            logger.info("Start")
            client.getVersion()
            logger.info("Done")
        } catch (e: Exception) {
            logger.info(e) { "Error" }
        }
    }

    @Test
    @Timeout(5)
    fun `request timeout`() {
        ServerSocketChannel.open(StandardProtocolFamily.INET).use { serverSocketChannel ->
            serverSocketChannel.configureBlocking(false)
            serverSocketChannel.bind(null)
            serverSocketChannel.accept()
            val localAddress = (serverSocketChannel.localAddress as InetSocketAddress)
            logger.info("Listening on: $localAddress")
            val client = PostchainClientImpl(PostchainClientConfig(
                    BlockchainRid.buildFromHex(BLOCKCHAIN_RID),
                    EndpointPool.singleUrl("http://${localAddress.hostName}:${localAddress.port}"),
                    failOverConfig = FailOverConfig(1),
                    responseTimeout = Duration.ofSeconds(1),
                    merkleHashVersion = 2
            ))
            assertFailure {
                client.getVersion()
            }.isInstanceOf(ClientError::class)
        }
    }

    @Test
    fun `generic get GTV request`() {
        val path = "/blocks/$BLOCKCHAIN_RID/height/1"

        val someBlock = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID),
                EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.uri.path).isEqualTo(path)
                assertThat(request.uri.query).isEqualTo("")
                return Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(validBlockDetail(1)).inputStream())
            }
        }).genericGetGtv(path).toObject<BlockDetail>()
        assertThat(someBlock.height).isEqualTo(1L)
        assertThat(someBlock.rid.data).isContentEqualTo(blockRid)
        assertThat(someBlock.transactions[0].rid.data).isContentEqualTo("62F71D71BA63D03FA0C6741DE22B116A3A8022893E7977DDC2A9CD981BBADE29".hexStringToByteArray())
        assertThat(someBlock.transactions[0].data).isNull()
    }

    @Test
    fun `generic get JSON request`() {
        val path = "/highest_block_height_anchoring_check/${BlockchainRid.buildFromHex(BLOCKCHAIN_RID)}"
        val expected = HighestBlockHeightAnchoringCheck(AnchoringChainCheck(height = 17), AnchoringChainCheck(height = 5, match = true), null)

        val res: String = PostchainClientImpl(PostchainClientConfig(BlockchainRid.buildFromHex(BLOCKCHAIN_RID),
                EndpointPool.singleUrl(url), merkleHashVersion = 2), httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.uri.path).isEqualTo(path)
                assertThat(request.uri.query).isEqualTo("")
                return Response(Status.OK).header(Header.ContentType, ContentType.APPLICATION_JSON.value).body(Gson().toJson(expected))
            }
        }).genericGetJson(path)
        assertThat(Gson().fromJson(res, HighestBlockHeightAnchoringCheck::class.java)).isEqualTo(expected)
    }

    private fun assertQueryUrlEndsWith(config: PostchainClientConfig, suffix: String) {
        PostchainClientImpl(config, httpClient = object : HttpHandler {
            override fun invoke(request: Request): Response {
                assertThat(request.uri.path.endsWith(suffix))
                return Response(Status.OK).header(Header.ContentType, ContentType.OCTET_STREAM.value).body(encodeGtv(gtv("foobar")).inputStream())
            }
        }).query("test_query", gtv(mapOf()))
    }
}

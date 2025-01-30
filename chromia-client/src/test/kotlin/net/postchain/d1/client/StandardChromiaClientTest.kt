package net.postchain.d1.client

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.configureFor
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.ok
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.TransactionResult
import net.postchain.client.core.TxRid
import net.postchain.client.request.EndpointPool
import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.common.tx.TransactionStatus
import net.postchain.crypto.sha256Digest
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV1
import net.postchain.gtx.Gtx
import net.postchain.gtx.GtxBody
import net.postchain.gtx.GtxOp
import net.postchain.gtx.GtxQuery
import org.http4k.core.ContentType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeoutException

class StandardChromiaClientTest {

    private val directoryChainBrid = BlockchainRid.buildRepeat(1)
    private val anchorChainBrid = BlockchainRid.buildRepeat(2)
    private val txBlockRid = BlockchainRid.buildRepeat(3)

    private val server = WireMockServer(wireMockConfig().dynamicPort())

    @BeforeEach
    fun beforeEach() {
        server.start()
        configureFor("localhost", server.port())
    }

    @AfterEach
    fun afterEach() {
        server.shutdown()
    }

    @Test
    fun `test anchoring happy path`() {

        val dappBrid = BlockchainRid.buildFromHex("335C75E08AFAC7D6678263F1A13D5AFED9CD009344B6349107D7CEEA3A40EA08")
        val dappTxRid = TxRid("3DE7FC7BCF6DAF2FFD8564D46D73F42C069818DDC249835535AD50D8D9270FF3")

        setupMock("cluster-1", dappBrid, dappTxRid)

        stubFor(post("/query_gtv/${anchorChainBrid}")
                .inScenario("first false, second true")
                .whenScenarioStateIs(STARTED)
                .withRequestBody(binaryEqualTo(
                        GtxQuery("is_block_anchored", gtv(
                                "blockchain_rid" to gtv(dappBrid),
                                "block_rid" to gtv(txBlockRid)
                        )).encode()
                ))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(false))))
                .willSetStateTo("Second Call")
        )

        stubFor(post("/query_gtv/${anchorChainBrid}")
                .inScenario("first false, second true")
                .whenScenarioStateIs("Second Call")
                .withRequestBody(binaryEqualTo(
                        GtxQuery("is_block_anchored", gtv(
                                "blockchain_rid" to gtv(dappBrid),
                                "block_rid" to gtv(txBlockRid)
                        )).encode()
                ))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(true))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        cc.awaitClusterAnchoredTx(dappBrid, dappTxRid)
        assertThat(cc.isTxClusterAnchored(dappBrid, dappTxRid)).isEqualTo(true)
    }

    @Test
    fun `test anchoring timeout`() {

        val dappBrid = BlockchainRid.buildFromHex("335C75E08AFAC7D6678263F1A13D5AFED9CD009344B6349107D7CEEA3A40EA08")
        val dappTxRid = TxRid("3DE7FC7BCF6DAF2FFD8564D46D73F42C069818DDC249835535AD50D8D9270FF3")

        setupMock("cluster-2", dappBrid, dappTxRid)

        mockPostQuery(anchorChainBrid, "is_block_anchored",
                mapOf("blockchain_rid" to gtv(dappBrid), "block_rid" to gtv(txBlockRid)), gtv(false))

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        val e = assertThrows<TimeoutException> {
            cc.awaitClusterAnchoredTx(dappBrid, dappTxRid, retries = 1)
        }
        assertThat(e.message).isEqualTo(
                "Timeout while waiting for transaction to be anchored"
        )
        assertThat(cc.isTxClusterAnchored(dappBrid, dappTxRid)).isEqualTo(false)
    }

    @Test
    fun `specify directory chain RID`() {
        mockGetQuery(directoryChainBrid, "cm_get_blockchain_api_urls",
                mapOf<String, GtvByteArray>("blockchain_rid" to gtv(directoryChainBrid)), gtv(gtv("http://localhost:${server.port()}")))

        val cc = StandardChromiaClient(PostchainClientConfig(
                blockchainRid = directoryChainBrid,
                endpointPool = EndpointPool.singleUrl("http://localhost:${server.port()}"))
        )
        assertThat(cc.directoryChainRid).isEqualTo(directoryChainBrid)
    }

    @Test
    fun `lookup directory chain RID`() {
        stubFor(get("/brid/iid_0").willReturn(ok(directoryChainBrid.toHex())))

        mockGetQuery(directoryChainBrid, "cm_get_blockchain_api_urls",
                mapOf<String, GtvByteArray>("blockchain_rid" to gtv(directoryChainBrid)), gtv(gtv("http://localhost:${server.port()}")))

        val cc = StandardChromiaClient(PostchainClientConfig(
                blockchainRid = BlockchainRid.ZERO_RID,
                endpointPool = EndpointPool.singleUrl("http://localhost:${server.port()}"))
        )
        assertThat(cc.directoryChainRid).isEqualTo(directoryChainBrid)
    }

    @Test
    fun `no replica directory chain`() {
        val tx = Gtx(GtxBody(directoryChainBrid, listOf(GtxOp("my_op")), listOf()), listOf())

        stubFor(get("/brid/iid_0").willReturn(ok(directoryChainBrid.toHex())))

        mockGetQuery(directoryChainBrid, "cm_get_blockchain_api_urls",
                mapOf<String, GtvByteArray>("blockchain_rid" to gtv(directoryChainBrid)), gtv(gtv("http://localhost:${server.port()}")))

        stubFor(get("/query_gtv/${directoryChainBrid}?type=my_query&%7Eargs=${GtvEncoder.encodeGtv(gtv(mapOf("param" to gtv(17)))).toHex()}")
                .inScenario("main")
                .whenScenarioStateIs(STARTED)
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv("foobar"))))
        )

        stubFor(post("/tx/${directoryChainBrid}")
                .inScenario("main")
                .whenScenarioStateIs(STARTED)
                .withRequestBody(binaryEqualTo(tx.encode()))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(mapOf()))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        val pc = cc.getDirectoryChainClient()
        assertThat(pc.query("my_query", gtv(mapOf("param" to gtv(17)))).asString()).isEqualTo("foobar")

        assertThat(pc.postTransaction(tx)).isEqualTo(
                TransactionResult(TxRid(tx.calculateTxRid(GtvMerkleHashCalculatorV1(::sha256Digest)).toHex()),
                        TransactionStatus.WAITING, 200, "OK"))
    }

    @Test
    fun `query replica directory chain`() {
        val tx = Gtx(GtxBody(directoryChainBrid, listOf(GtxOp("my_op")), listOf()), listOf())

        stubFor(get("/brid/iid_0").willReturn(ok(directoryChainBrid.toHex())))

        mockGetQuery(directoryChainBrid, "cm_get_blockchain_api_urls",
                mapOf<String, GtvByteArray>("blockchain_rid" to gtv(directoryChainBrid)), gtv(gtv("http://localhost:${server.port()}")))

        stubFor(get("/replica/query_gtv/${directoryChainBrid}?type=my_query&%7Eargs=${GtvEncoder.encodeGtv(gtv(mapOf("param" to gtv(17)))).toHex()}")
                .inScenario("main")
                .whenScenarioStateIs(STARTED)
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv("foobar"))))
        )

        stubFor(post("/tx/${directoryChainBrid}")
                .inScenario("main")
                .whenScenarioStateIs(STARTED)
                .withRequestBody(binaryEqualTo(tx.encode()))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(mapOf()))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        val pc = cc.getDirectoryChainClientForQueryReplica(EndpointPool.singleUrl("http://localhost:${server.port()}/replica"))
        assertThat(pc.query("my_query", gtv(mapOf("param" to gtv(17)))).asString()).isEqualTo("foobar")

        assertThat(pc.postTransaction(tx)).isEqualTo(
                TransactionResult(TxRid(tx.calculateTxRid(GtvMerkleHashCalculatorV1(::sha256Digest)).toHex()),
                        TransactionStatus.WAITING, 200, "OK"))
    }

    @Test
    fun `no replica dapp chain`() {
        val dappBrid = BlockchainRid.buildFromHex("335C75E08AFAC7D6678263F1A13D5AFED9CD009344B6349107D7CEEA3A40EA08")
        val dappTxRid = TxRid("3DE7FC7BCF6DAF2FFD8564D46D73F42C069818DDC249835535AD50D8D9270FF3")
        val tx = Gtx(GtxBody(dappBrid, listOf(GtxOp("my_op")), listOf()), listOf())

        setupMock("cluster-1", dappBrid, dappTxRid)

        stubFor(get("/query_gtv/${dappBrid}?type=my_query&%7Eargs=${GtvEncoder.encodeGtv(gtv(mapOf("param" to gtv(17)))).toHex()}")
                .inScenario("main")
                .whenScenarioStateIs(STARTED)
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv("foobar"))))
        )

        stubFor(post("/tx/${dappBrid}")
                .inScenario("main")
                .whenScenarioStateIs(STARTED)
                .withRequestBody(binaryEqualTo(tx.encode()))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(mapOf()))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        val pc = cc.getClient(dappBrid)
        assertThat(pc.query("my_query", gtv(mapOf("param" to gtv(17)))).asString()).isEqualTo("foobar")

        assertThat(pc.postTransaction(tx)).isEqualTo(
                TransactionResult(TxRid(tx.calculateTxRid(GtvMerkleHashCalculatorV1(::sha256Digest)).toHex()),
                        TransactionStatus.WAITING, 200, "OK"))
    }

    @Test
    fun `query replica dapp chain`() {
        val dappBrid = BlockchainRid.buildFromHex("335C75E08AFAC7D6678263F1A13D5AFED9CD009344B6349107D7CEEA3A40EA08")
        val dappTxRid = TxRid("3DE7FC7BCF6DAF2FFD8564D46D73F42C069818DDC249835535AD50D8D9270FF3")
        val tx = Gtx(GtxBody(dappBrid, listOf(GtxOp("my_op")), listOf()), listOf())

        setupMock("cluster-1", dappBrid, dappTxRid)

        stubFor(get("/replica/query_gtv/${dappBrid}?type=my_query&%7Eargs=${GtvEncoder.encodeGtv(gtv(mapOf("param" to gtv(17)))).toHex()}")
                .inScenario("main")
                .whenScenarioStateIs(STARTED)
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv("foobar"))))
        )

        stubFor(post("/tx/${dappBrid}")
                .inScenario("main")
                .whenScenarioStateIs(STARTED)
                .withRequestBody(binaryEqualTo(tx.encode()))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(mapOf()))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        val pc = cc.getClientForQueryReplica(dappBrid, queryNodes = EndpointPool.singleUrl("http://localhost:${server.port()}/replica"))
        assertThat(pc.query("my_query", gtv(mapOf("param" to gtv(17)))).asString()).isEqualTo("foobar")

        assertThat(pc.postTransaction(tx)).isEqualTo(
                TransactionResult(TxRid(tx.calculateTxRid(GtvMerkleHashCalculatorV1(::sha256Digest)).toHex()),
                        TransactionStatus.WAITING, 200, "OK"))
    }

    private fun buildGetQueryGtv(brid: BlockchainRid, name: String, args: Map<String, Gtv>): MappingBuilder {
        return get("/query_gtv/${brid}?type=${name}&%7Eargs=${GtvEncoder.encodeGtv(gtv(args)).toHex()}")
    }

    private fun mockGetQuery(brid: BlockchainRid, name: String, args: Map<String, Gtv>, response: Gtv) {
        stubFor(
                buildGetQueryGtv(brid, name, args)
                        .willReturn(ok(ContentType.OCTET_STREAM.value)
                                .withBody(GtvEncoder.encodeGtv(response)))
        )
    }

    private fun mockPostQuery(brid: BlockchainRid, name: String, args: Map<String, Gtv>, response: Gtv) {
        stubFor(post("/query_gtv/${brid}")
                .withRequestBody(binaryEqualTo(GtxQuery(name, gtv(args)).encode()))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(response)))
        )
    }

    private fun setupMock(cluster: String, dappBrid: BlockchainRid, txRid: TxRid) {

        stubFor(get("/brid/iid_0").willReturn(ok(directoryChainBrid.toHex())))

        mockGetQuery(directoryChainBrid, "cm_get_blockchain_api_urls",
                mapOf("blockchain_rid" to gtv(directoryChainBrid)), gtv(gtv("http://localhost:${server.port()}")))

        stubFor(get("/transactions/$dappBrid/${txRid.rid}")
                .willReturn(okJson("""{
                    |"blockRID": "$txBlockRid", "blockHeight": 1, "blockHeader": "", "witness": "", "timestamp": 1, "txRID": "", "txHash": "", "txData": "FF"}
                    |""".trimMargin())))

        mockGetQuery(directoryChainBrid, "cm_get_blockchain_cluster",
                mapOf("brid" to gtv(dappBrid.data)), gtv(cluster))

        mockGetQuery(directoryChainBrid, "cm_get_blockchain_cluster",
                mapOf("brid" to gtv(anchorChainBrid.data)), gtv(cluster))

        mockGetQuery(directoryChainBrid, "cm_get_cluster_info",
                mapOf("name" to gtv(cluster)), gtv(
                "name" to gtv(cluster),
                "anchoring_chain" to gtv(anchorChainBrid),
                "peers" to gtv(listOf(gtv(
                        "pubkey" to gtv("00".hexStringToByteArray()),
                        "api_url" to gtv("http://localhost:${server.port()}")
                )))
        ))
    }

    // For manual testing
    @Disabled
    @Test
    fun test() {
//        val cc = StandardChromiaClient(EndpointPool.singleUrl("https://system.chromaway.com:7740"))
//        val cc = StandardChromiaClient(EndpointPool.singleUrl("https://dapps0.chromaway.com:7740"))
        val cc = StandardChromiaClient(EndpointPool.singleUrl("https://replica0.chromaway.com:7740"))

        println("System node endpoints:")
        cc.directoryChainClient.config.endpointPool.forEach { println(it.url) }

        println(cc.directoryChainClient.config)

        // Test dapp cluster
        val filechainBerylliumBrid = BlockchainRid.buildFromHex("C171CCA03FF959F8D56D8B4E8410AB555CE899BBC40C5293FE5BB2E359914014")
        val filechainBerylliumTx = TxRid("BF67200D0F71504ADEC617ADF8512EA2DA6CD19ADED13152711E18C41F2D17F9") // filechain_beryllium
        val filechainBerylliumBlock = "D68DFD7C9BDA57FD3CD1B6E15C8033528C3D11B1C9A8713A0E9F2E0BCD93858C".hexStringToByteArray()

        val filechainBerylliumConfig = cc.getClusterAnchoringClient(filechainBerylliumBrid).config
        println("filechainBeryllium Cluster anchoring brid: ${filechainBerylliumConfig.blockchainRid}")
        println("dapp cluster nodes:")
        filechainBerylliumConfig.endpointPool.forEach { println(it.url) }

        cc.isTxClusterAnchored(filechainBerylliumBrid, filechainBerylliumTx)
        cc.isBlockClusterAnchored(filechainBerylliumBrid, filechainBerylliumBlock)
        cc.awaitClusterAnchoredTx(
                filechainBerylliumBrid,
                filechainBerylliumTx)


        // Test system cluster
        val ecBrid = BlockchainRid.buildFromHex("15C0CA99BEE60A3B23829968771C50E491BD00D2E3AE448580CD48A8D71E7BBA")
        val ecTx = TxRid("C78C6520FFAD51A3AB04600ECC5191571DBA6AB6612A2F7F82AA8D34367E3E54") // filechain_beryllium
        val ecBlock = "EA97734CE765C53AEDE13F6C141C8831AF285F415BD8AF139D4BADCAE1262FA1".hexStringToByteArray()

        val ecConfig = cc.getClusterAnchoringClient(ecBrid).config
        println("EC Cluster anchoring brid: ${ecConfig.blockchainRid}")
        println("system cluster nodes:")
        ecConfig.endpointPool.forEach { println(it.url) }

        cc.isTxClusterAnchored(ecBrid, ecTx)
        cc.isBlockClusterAnchored(ecBrid, ecBlock)
        cc.awaitClusterAnchoredTx(
                ecBrid,
                ecTx)
        println()
    }
}
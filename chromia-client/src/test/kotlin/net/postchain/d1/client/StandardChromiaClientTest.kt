package net.postchain.d1.client

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
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
import net.postchain.common.wrap
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
    private val clusterAnchorChainBrid = BlockchainRid.buildRepeat(2)
    private val systemAnchorChainBrid = BlockchainRid.buildRepeat(3)
    private val dappChainBrid = BlockchainRid.buildRepeat(4)

    private val dappTxRid = TxRid(ByteArray(32) { 5 }.toHex())
    private val txBlockRid = ByteArray(32) { 6 }.wrap()
    private val clusterAnchorBlockRid = ByteArray(32) { 7 }.wrap()
    private val clusterAnchorTxRid = TxRid(ByteArray(32) { 8 }.toHex())

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
    fun `cluster anchoring happy path`() {

        setupMock("cluster-1", dappChainBrid)

        mockGetQuery(directoryChainBrid, "cm_get_blockchain_cluster",
                mapOf("brid" to gtv(dappChainBrid.data)), gtv("cluster-1"))

        stubFor(get("/transactions/$dappChainBrid/${dappTxRid.rid}")
                .willReturn(okJson("""{
                    |"blockRID": "$txBlockRid", "blockHeight": 1, "blockHeader": "", "witness": "", "timestamp": 1, "txRID": "", "txHash": "", "txData": "FF"}
                    |""".trimMargin())))

        stubFor(post("/query_gtv/${clusterAnchorChainBrid}")
                .inScenario("first false, second true")
                .whenScenarioStateIs(STARTED)
                .withRequestBody(binaryEqualTo(
                        GtxQuery("is_block_anchored", gtv(
                                "blockchain_rid" to gtv(dappChainBrid),
                                "block_rid" to gtv(txBlockRid)
                        )).encode()
                ))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(false))))
                .willSetStateTo("Second Call")
        )

        stubFor(post("/query_gtv/${clusterAnchorChainBrid}")
                .inScenario("first false, second true")
                .whenScenarioStateIs("Second Call")
                .withRequestBody(binaryEqualTo(
                        GtxQuery("is_block_anchored", gtv(
                                "blockchain_rid" to gtv(dappChainBrid),
                                "block_rid" to gtv(txBlockRid)
                        )).encode()
                ))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(true))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        cc.awaitClusterAnchoredTx(dappChainBrid, dappTxRid)
        assertThat(cc.isTxClusterAnchored(dappChainBrid, dappTxRid)).isEqualTo(true)
    }

    @Test
    fun `cluster anchoring timeout`() {

        setupMock("cluster-2", dappChainBrid)

        stubFor(get("/transactions/$dappChainBrid/${dappTxRid.rid}")
                .willReturn(okJson("""{
                    |"blockRID": "$txBlockRid", "blockHeight": 1, "blockHeader": "", "witness": "", "timestamp": 1, "txRID": "", "txHash": "", "txData": "FF"}
                    |""".trimMargin())))

        mockGetQuery(directoryChainBrid, "cm_get_blockchain_cluster",
                mapOf("brid" to gtv(dappChainBrid.data)), gtv("cluster-2"))

        mockPostQuery(clusterAnchorChainBrid, "is_block_anchored",
                mapOf("blockchain_rid" to gtv(dappChainBrid), "block_rid" to gtv(txBlockRid)), gtv(false))

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        val e = assertThrows<TimeoutException> {
            cc.awaitClusterAnchoredTx(dappChainBrid, dappTxRid, retries = 1)
        }
        assertThat(e.message).isEqualTo(
                "Timeout while waiting for transaction to be cluster anchored"
        )
        assertThat(cc.isTxClusterAnchored(dappChainBrid, dappTxRid)).isFalse()
    }

    @Test
    fun `system anchoring happy path`() {

        setupMock("cluster-1", dappChainBrid)

        mockGetQuery(directoryChainBrid, "cm_get_blockchain_cluster",
                mapOf("brid" to gtv(dappChainBrid.data)), gtv("cluster-1"))

        stubFor(get("/transactions/$dappChainBrid/${dappTxRid.rid}")
                .willReturn(okJson("""{
                    |"blockRID": "$txBlockRid", "blockHeight": 1, "blockHeader": "", "witness": "", "timestamp": 1, "txRID": "", "txHash": "", "txData": "FF"}
                    |""".trimMargin())))

        stubFor(post("/query_gtv/${clusterAnchorChainBrid}")
                .withRequestBody(binaryEqualTo(
                        GtxQuery("get_anchoring_transaction_for_block_rid", gtv(
                                "blockchain_rid" to gtv(dappChainBrid),
                                "block_rid" to gtv(txBlockRid)
                        )).encode()
                ))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(mapOf(
                                "tx_rid" to gtv(clusterAnchorTxRid.rid.hexStringToByteArray()),
                                "tx_data" to gtv("".hexStringToByteArray()),
                                "tx_op_index" to gtv(1)
                        )))))
        )

        stubFor(get("/transactions/$clusterAnchorChainBrid/${clusterAnchorTxRid.rid}")
                .willReturn(okJson("""{
                    |"blockRID": "$clusterAnchorBlockRid", "blockHeight": 1, "blockHeader": "", "witness": "", "timestamp": 1, "txRID": "", "txHash": "", "txData": "FF"}
                    |""".trimMargin())))

        stubFor(post("/query_gtv/${systemAnchorChainBrid}")
                .withRequestBody(binaryEqualTo(
                        GtxQuery("is_block_anchored", gtv(
                                "blockchain_rid" to gtv(clusterAnchorChainBrid),
                                "block_rid" to gtv(clusterAnchorBlockRid)
                        )).encode()
                ))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(true))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        cc.awaitSystemAnchoredTx(dappChainBrid, dappTxRid)
        assertThat(cc.isTxSystemAnchored(dappChainBrid, dappTxRid)).isTrue()
    }

    @Test
    fun `system anchoring timeout`() {

        setupMock("cluster-1", dappChainBrid)

        mockGetQuery(directoryChainBrid, "cm_get_blockchain_cluster",
                mapOf("brid" to gtv(dappChainBrid.data)), gtv("cluster-1"))

        stubFor(get("/transactions/$dappChainBrid/${dappTxRid.rid}")
                .willReturn(okJson("""{
                    |"blockRID": "$txBlockRid", "blockHeight": 1, "blockHeader": "", "witness": "", "timestamp": 1, "txRID": "", "txHash": "", "txData": "FF"}
                    |""".trimMargin())))

        stubFor(post("/query_gtv/${clusterAnchorChainBrid}")
                .withRequestBody(binaryEqualTo(
                        GtxQuery("get_anchoring_transaction_for_block_rid", gtv(
                                "blockchain_rid" to gtv(dappChainBrid),
                                "block_rid" to gtv(txBlockRid)
                        )).encode()
                ))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(mapOf(
                                "tx_rid" to gtv(clusterAnchorTxRid.rid.hexStringToByteArray()),
                                "tx_data" to gtv("".hexStringToByteArray()),
                                "tx_op_index" to gtv(1)
                        )))))
        )

        stubFor(get("/transactions/$clusterAnchorChainBrid/${clusterAnchorTxRid.rid}")
                .willReturn(okJson("""{
                    |"blockRID": "$clusterAnchorBlockRid", "blockHeight": 1, "blockHeader": "", "witness": "", "timestamp": 1, "txRID": "", "txHash": "", "txData": "FF"}
                    |""".trimMargin())))

        stubFor(post("/query_gtv/${systemAnchorChainBrid}")
                .withRequestBody(binaryEqualTo(
                        GtxQuery("is_block_anchored", gtv(
                                "blockchain_rid" to gtv(clusterAnchorChainBrid),
                                "block_rid" to gtv(clusterAnchorBlockRid)
                        )).encode()
                ))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(false))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        val e = assertThrows<TimeoutException> {
            cc.awaitSystemAnchoredTx(dappChainBrid, dappTxRid, retries = 1)
        }
        assertThat(e.message).isEqualTo(
                "Timeout while waiting for transaction to be system anchored"
        )
        assertThat(cc.isTxSystemAnchored(dappChainBrid, dappTxRid)).isFalse()
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
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv("foobar"))))
        )

        stubFor(post("/tx/${directoryChainBrid}")
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
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv("foobar"))))
        )

        stubFor(post("/tx/${directoryChainBrid}")
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
        val tx = Gtx(GtxBody(dappChainBrid, listOf(GtxOp("my_op")), listOf()), listOf())

        setupMock("cluster-1", dappChainBrid)

        stubFor(get("/query_gtv/${dappChainBrid}?type=my_query&%7Eargs=${GtvEncoder.encodeGtv(gtv(mapOf("param" to gtv(17)))).toHex()}")
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv("foobar"))))
        )

        stubFor(post("/tx/${dappChainBrid}")
                .withRequestBody(binaryEqualTo(tx.encode()))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(mapOf()))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        val pc = cc.getClient(dappChainBrid)
        assertThat(pc.query("my_query", gtv(mapOf("param" to gtv(17)))).asString()).isEqualTo("foobar")

        assertThat(pc.postTransaction(tx)).isEqualTo(
                TransactionResult(TxRid(tx.calculateTxRid(GtvMerkleHashCalculatorV1(::sha256Digest)).toHex()),
                        TransactionStatus.WAITING, 200, "OK"))
    }

    @Test
    fun `query replica dapp chain`() {
        val tx = Gtx(GtxBody(dappChainBrid, listOf(GtxOp("my_op")), listOf()), listOf())

        setupMock("cluster-1", dappChainBrid)

        stubFor(get("/replica/query_gtv/${dappChainBrid}?type=my_query&%7Eargs=${GtvEncoder.encodeGtv(gtv(mapOf("param" to gtv(17)))).toHex()}")
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv("foobar"))))
        )

        stubFor(post("/tx/${dappChainBrid}")
                .withRequestBody(binaryEqualTo(tx.encode()))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(mapOf()))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        val pc = cc.getClientForQueryReplica(dappChainBrid, queryNodes = EndpointPool.singleUrl("http://localhost:${server.port()}/replica"))
        assertThat(pc.query("my_query", gtv(mapOf("param" to gtv(17)))).asString()).isEqualTo("foobar")

        assertThat(pc.postTransaction(tx)).isEqualTo(
                TransactionResult(TxRid(tx.calculateTxRid(GtvMerkleHashCalculatorV1(::sha256Digest)).toHex()),
                        TransactionStatus.WAITING, 200, "OK"))
    }

    private fun buildGetQueryGtv(brid: BlockchainRid, name: String, args: Map<String, Gtv>): MappingBuilder =
            get("/query_gtv/${brid}?type=${name}&%7Eargs=${GtvEncoder.encodeGtv(gtv(args)).toHex()}")


    private fun buildGetQueryGtv(brid: BlockchainRid, name: String): MappingBuilder =
            get("/query_gtv/${brid}?type=${name}")

    private fun mockGetQuery(brid: BlockchainRid, name: String, args: Map<String, Gtv>, response: Gtv) {
        stubFor(
                buildGetQueryGtv(brid, name, args)
                        .willReturn(ok(ContentType.OCTET_STREAM.value)
                                .withBody(GtvEncoder.encodeGtv(response)))
        )
    }

    private fun mockGetQuery(brid: BlockchainRid, name: String, response: Gtv) {
        stubFor(
                buildGetQueryGtv(brid, name)
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

    private fun setupMock(cluster: String, brid: BlockchainRid) {

        stubFor(get("/brid/iid_0").willReturn(ok(directoryChainBrid.toHex())))

        mockGetQuery(directoryChainBrid, "cm_get_system_anchoring_chain", gtv(systemAnchorChainBrid))

        mockGetQuery(directoryChainBrid, "cm_get_blockchain_api_urls",
                mapOf("blockchain_rid" to gtv(directoryChainBrid)), gtv(gtv("http://localhost:${server.port()}")))

        mockGetQuery(directoryChainBrid, "cm_get_blockchain_api_urls",
                mapOf("blockchain_rid" to gtv(brid)), gtv(gtv("http://localhost:${server.port()}")))

        mockGetQuery(directoryChainBrid, "cm_get_blockchain_cluster",
                mapOf("brid" to gtv(clusterAnchorChainBrid.data)), gtv(cluster))

        mockGetQuery(directoryChainBrid, "cm_get_cluster_info",
                mapOf("name" to gtv(cluster)), gtv(
                "name" to gtv(cluster),
                "anchoring_chain" to gtv(clusterAnchorChainBrid),
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

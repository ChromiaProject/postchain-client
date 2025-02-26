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
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.BlockHeaderData
import net.postchain.client.core.TransactionResult
import net.postchain.client.core.TxRid
import net.postchain.client.impl.ConfirmationProofData
import net.postchain.client.impl.Header
import net.postchain.client.request.EndpointPool
import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.common.tx.TransactionStatus
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.crypto.Signature
import net.postchain.crypto.sha256Digest
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.generateProof
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV2
import net.postchain.gtv.merkle.proof.GtvMerkleProofTree
import net.postchain.gtv.merkle.proof.merkleHash
import net.postchain.gtv.merkleHash
import net.postchain.gtx.Gtx
import net.postchain.gtx.GtxBody
import net.postchain.gtx.GtxOp
import org.http4k.core.ContentType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeoutException

class StandardChromiaClientTest {

    val cryptoSystem = Secp256K1CryptoSystem()
    val hashCalculator = GtvMerkleHashCalculatorV2(::sha256Digest)
    val peer = cryptoSystem.generateKeyPair()

    val directoryChainBrid = BlockchainRid.buildRepeat(1)
    val clusterAnchorChainBrid = BlockchainRid.buildRepeat(2)
    val systemAnchorChainBrid = BlockchainRid.buildRepeat(3)
    val dappChainBrid = BlockchainRid.buildRepeat(4)

    val dappTxRid = TxRid(ByteArray(32) { 5 }.toHex())
    val dappTxHash = ByteArray(32) { 6 }
    val dappMerkleProofTree = gtv(gtv(dappTxHash)).generateProof(listOf(0), hashCalculator)
    val dappBlockHeader = BlockHeaderData(
            blockchainRid = dappChainBrid.data,
            previousBlockRid = ByteArray(32) { 7 },
            merkleRootHash = dappMerkleProofTree.merkleHash(hashCalculator),
            timestamp = 17L,
            height = 1L,
            dependencies = GtvNull,
            extra = emptyMap<String, Gtv>()
    )
    val dappBlockRid = dappBlockHeader.blockRid()

    val clusterAnchorTxRid = TxRid(ByteArray(32) { 8 }.toHex())
    val clusterAnchorTxHash = ByteArray(32) { 9 }
    val clusterAnchorMerkleProofTree = gtv(gtv(clusterAnchorTxHash)).generateProof(listOf(0), hashCalculator)
    val clusterAnchorBlockHeader = BlockHeaderData(
            blockchainRid = clusterAnchorChainBrid.data,
            previousBlockRid = ByteArray(32) { 10 },
            merkleRootHash = gtv(gtv(clusterAnchorTxHash)).merkleHash(hashCalculator),
            timestamp = 4711L,
            height = 2L,
            dependencies = GtvNull,
            extra = emptyMap<String, Gtv>()
    )
    val clusterAnchorBlockRid = clusterAnchorBlockHeader.blockRid()

    val server = WireMockServer(wireMockConfig().dynamicPort())

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

        setupStubs("cluster-1", dappChainBrid)

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_blockchain_cluster",
                mapOf("brid" to gtv(dappChainBrid.data)), gtv("cluster-1"))

        stubConfirmationProof(dappChainBrid, dappTxRid, dappTxHash, dappBlockHeader, dappMerkleProofTree,
                arrayOf(cryptoSystem.buildSigMaker(peer).signDigest(dappBlockRid)))

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_peer_info",
                mapOf("brid" to gtv(dappChainBrid.data), "height" to gtv(1)), gtv(gtv(peer.pubKey.data)))

        stubFor(stubQueryBuilder(baseUrl = "", clusterAnchorChainBrid, "is_block_anchored", mapOf(
                "blockchain_rid" to gtv(dappChainBrid),
                "block_rid" to gtv(dappBlockRid)
        ), gtv(false))
                .inScenario("first false, second true")
                .whenScenarioStateIs(STARTED)
                .willSetStateTo("Second Call"))

        stubFor(stubQueryBuilder(baseUrl = "", clusterAnchorChainBrid, "is_block_anchored", mapOf(
                "blockchain_rid" to gtv(dappChainBrid),
                "block_rid" to gtv(dappBlockRid)
        ), gtv(true))
                .inScenario("first false, second true")
                .whenScenarioStateIs("Second Call"))


        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        cc.awaitClusterAnchoredTx(dappChainBrid, dappTxRid)
        assertThat(cc.isTxClusterAnchored(dappChainBrid, dappTxRid)).isEqualTo(true)
    }

    @Test
    fun `cluster anchoring timeout`() {

        setupStubs("cluster-2", dappChainBrid)

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_blockchain_cluster",
                mapOf("brid" to gtv(dappChainBrid.data)), gtv("cluster-2"))

        stubConfirmationProof(dappChainBrid, dappTxRid, dappTxHash, dappBlockHeader, dappMerkleProofTree,
                arrayOf(cryptoSystem.buildSigMaker(peer).signDigest(dappBlockRid)))

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_peer_info",
                mapOf("brid" to gtv(dappChainBrid.data), "height" to gtv(1)), gtv(gtv(peer.pubKey.data)))

        stubQuery(baseUrl = "", clusterAnchorChainBrid, "is_block_anchored",
                mapOf("blockchain_rid" to gtv(dappChainBrid), "block_rid" to gtv(dappBlockRid)), gtv(false))

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

        setupStubs("cluster-1", dappChainBrid)

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_blockchain_cluster",
                mapOf("brid" to gtv(dappChainBrid.data)), gtv("cluster-1"))

        stubConfirmationProof(dappChainBrid, dappTxRid, dappTxHash, dappBlockHeader, dappMerkleProofTree,
                arrayOf(cryptoSystem.buildSigMaker(peer).signDigest(dappBlockRid)))

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_peer_info",
                mapOf("brid" to gtv(dappChainBrid.data), "height" to gtv(1)), gtv(gtv(peer.pubKey.data)))

        stubQuery(baseUrl = "", clusterAnchorChainBrid, "get_anchoring_transaction_for_block_rid",
                mapOf(
                        "blockchain_rid" to gtv(dappChainBrid),
                        "block_rid" to gtv(dappBlockRid)
                ),
                gtv(mapOf(
                        "tx_rid" to gtv(clusterAnchorTxRid.rid.hexStringToByteArray()),
                        "tx_data" to gtv("".hexStringToByteArray()),
                        "tx_op_index" to gtv(1)
                )))

        stubConfirmationProof(clusterAnchorChainBrid, clusterAnchorTxRid, clusterAnchorTxHash, clusterAnchorBlockHeader, clusterAnchorMerkleProofTree,
                arrayOf(cryptoSystem.buildSigMaker(peer).signDigest(clusterAnchorBlockRid)))

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_peer_info",
                mapOf("brid" to gtv(clusterAnchorChainBrid.data), "height" to gtv(2)), gtv(gtv(peer.pubKey.data)))

        stubQuery(baseUrl = "", systemAnchorChainBrid, "is_block_anchored",
                mapOf(
                        "blockchain_rid" to gtv(clusterAnchorChainBrid),
                        "block_rid" to gtv(clusterAnchorBlockRid)
                ),
                gtv(true)
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        cc.awaitSystemAnchoredTx(dappChainBrid, dappTxRid)
        assertThat(cc.isTxSystemAnchored(dappChainBrid, dappTxRid)).isTrue()
    }

    @Test
    fun `system anchoring timeout`() {

        setupStubs("cluster-1", dappChainBrid)

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_blockchain_cluster",
                mapOf("brid" to gtv(dappChainBrid.data)), gtv("cluster-1"))

        stubConfirmationProof(dappChainBrid, dappTxRid, dappTxHash, dappBlockHeader, dappMerkleProofTree,
                arrayOf(cryptoSystem.buildSigMaker(peer).signDigest(dappBlockRid)))

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_peer_info",
                mapOf("brid" to gtv(dappChainBrid.data), "height" to gtv(1)), gtv(gtv(peer.pubKey.data)))

        stubQuery(baseUrl = "", clusterAnchorChainBrid, "get_anchoring_transaction_for_block_rid",
                mapOf(
                        "blockchain_rid" to gtv(dappChainBrid),
                        "block_rid" to gtv(dappBlockRid)
                ),
                gtv(mapOf(
                        "tx_rid" to gtv(clusterAnchorTxRid.rid.hexStringToByteArray()),
                        "tx_data" to gtv("".hexStringToByteArray()),
                        "tx_op_index" to gtv(1)
                ))
        )

        stubConfirmationProof(clusterAnchorChainBrid, clusterAnchorTxRid, clusterAnchorTxHash, clusterAnchorBlockHeader, clusterAnchorMerkleProofTree,
                arrayOf(cryptoSystem.buildSigMaker(peer).signDigest(clusterAnchorBlockRid)))

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_peer_info",
                mapOf("brid" to gtv(clusterAnchorChainBrid.data), "height" to gtv(2)), gtv(gtv(peer.pubKey.data)))

        stubQuery(baseUrl = "", systemAnchorChainBrid, "is_block_anchored",
                mapOf(
                        "blockchain_rid" to gtv(clusterAnchorChainBrid),
                        "block_rid" to gtv(clusterAnchorBlockRid)
                ),
                gtv(false)
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
        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_blockchain_api_urls",
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

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_blockchain_api_urls",
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

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_blockchain_api_urls",
                mapOf<String, GtvByteArray>("blockchain_rid" to gtv(directoryChainBrid)), gtv(gtv("http://localhost:${server.port()}")))

        stubQuery(baseUrl = "", directoryChainBrid, "my_query", mapOf("param" to gtv(17)), gtv("foobar"))

        stubFor(post("/tx/${directoryChainBrid}")
                .withRequestBody(binaryEqualTo(tx.encode()))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(mapOf()))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        val pc = cc.getDirectoryChainClient()
        assertThat(pc.query("my_query", gtv(mapOf("param" to gtv(17)))).asString()).isEqualTo("foobar")

        assertThat(pc.postTransaction(tx)).isEqualTo(
                TransactionResult(TxRid(tx.calculateTxRid(hashCalculator).toHex()),
                        TransactionStatus.WAITING, 200, "OK"))
    }

    @Test
    fun `query replica directory chain`() {
        val tx = Gtx(GtxBody(directoryChainBrid, listOf(GtxOp("my_op")), listOf()), listOf())

        stubFor(get("/brid/iid_0").willReturn(ok(directoryChainBrid.toHex())))

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_blockchain_api_urls",
                mapOf<String, GtvByteArray>("blockchain_rid" to gtv(directoryChainBrid)), gtv(gtv("http://localhost:${server.port()}")))

        stubQuery(baseUrl = "/replica", directoryChainBrid, "my_query", mapOf("param" to gtv(17)), gtv("foobar"))

        stubFor(post("/tx/${directoryChainBrid}")
                .withRequestBody(binaryEqualTo(tx.encode()))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(mapOf()))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        val pc = cc.getDirectoryChainClientForQueryReplica(EndpointPool.singleUrl("http://localhost:${server.port()}/replica"))
        assertThat(pc.query("my_query", gtv(mapOf("param" to gtv(17)))).asString()).isEqualTo("foobar")

        assertThat(pc.postTransaction(tx)).isEqualTo(
                TransactionResult(TxRid(tx.calculateTxRid(hashCalculator).toHex()),
                        TransactionStatus.WAITING, 200, "OK"))
    }

    @Test
    fun `forwarding replica directory chain`() {
        val tx = Gtx(GtxBody(directoryChainBrid, listOf(GtxOp("my_op")), listOf()), listOf())

        stubFor(get("/brid/iid_0").willReturn(ok(directoryChainBrid.toHex())))

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_blockchain_api_urls",
                mapOf<String, GtvByteArray>("blockchain_rid" to gtv(directoryChainBrid)), gtv(gtv("http://localhost:${server.port()}")))

        stubQuery(baseUrl = "/replica", directoryChainBrid, "my_query", mapOf("param" to gtv(17)), gtv("foobar"))

        stubFor(post("/replica/tx/${directoryChainBrid}")
                .withRequestBody(binaryEqualTo(tx.encode()))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(mapOf()))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        val pc = cc.getDirectoryChainClientForForwardingReplica(EndpointPool.singleUrl("http://localhost:${server.port()}/replica"))
        assertThat(pc.query("my_query", gtv(mapOf("param" to gtv(17)))).asString()).isEqualTo("foobar")

        assertThat(pc.postTransaction(tx)).isEqualTo(
                TransactionResult(TxRid(tx.calculateTxRid(hashCalculator).toHex()),
                        TransactionStatus.WAITING, 200, "OK"))
    }

    @Test
    fun `no replica dapp chain`() {
        val tx = Gtx(GtxBody(dappChainBrid, listOf(GtxOp("my_op")), listOf()), listOf())

        setupStubs("cluster-1", dappChainBrid)

        stubQuery(baseUrl = "", dappChainBrid, "my_query", mapOf("param" to gtv(17)), gtv("foobar"))

        stubFor(post("/tx/${dappChainBrid}")
                .withRequestBody(binaryEqualTo(tx.encode()))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(mapOf()))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        val pc = cc.getClient(dappChainBrid)
        assertThat(pc.query("my_query", gtv(mapOf("param" to gtv(17)))).asString()).isEqualTo("foobar")

        assertThat(pc.postTransaction(tx)).isEqualTo(
                TransactionResult(TxRid(tx.calculateTxRid(hashCalculator).toHex()),
                        TransactionStatus.WAITING, 200, "OK"))
    }

    @Test
    fun `query replica dapp chain`() {
        val tx = Gtx(GtxBody(dappChainBrid, listOf(GtxOp("my_op")), listOf()), listOf())

        setupStubs("cluster-1", dappChainBrid)

        stubQuery(baseUrl = "/replica", dappChainBrid, "my_query", mapOf("param" to gtv(17)), gtv("foobar"))

        stubFor(post("/tx/${dappChainBrid}")
                .withRequestBody(binaryEqualTo(tx.encode()))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(mapOf()))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        val pc = cc.getClientForQueryReplica(dappChainBrid, queryNodes = EndpointPool.singleUrl("http://localhost:${server.port()}/replica"))
        assertThat(pc.query("my_query", gtv(mapOf("param" to gtv(17)))).asString()).isEqualTo("foobar")

        assertThat(pc.postTransaction(tx)).isEqualTo(
                TransactionResult(TxRid(tx.calculateTxRid(hashCalculator).toHex()),
                        TransactionStatus.WAITING, 200, "OK"))
    }

    @Test
    fun `forwarding replica dapp chain`() {
        val tx = Gtx(GtxBody(dappChainBrid, listOf(GtxOp("my_op")), listOf()), listOf())

        setupStubs("cluster-1", dappChainBrid)

        stubQuery(baseUrl = "/replica", dappChainBrid, "my_query", mapOf("param" to gtv(17)), gtv("foobar"))

        stubFor(post("/replica/tx/${dappChainBrid}")
                .withRequestBody(binaryEqualTo(tx.encode()))
                .willReturn(ok(ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(gtv(mapOf()))))
        )

        val cc = StandardChromiaClient(EndpointPool.singleUrl("http://localhost:${server.port()}"))
        val pc = cc.getClientForForwardingReplica(dappChainBrid, nodes = EndpointPool.singleUrl("http://localhost:${server.port()}/replica"))
        assertThat(pc.query("my_query", gtv(mapOf("param" to gtv(17)))).asString()).isEqualTo("foobar")

        assertThat(pc.postTransaction(tx)).isEqualTo(
                TransactionResult(TxRid(tx.calculateTxRid(hashCalculator).toHex()),
                        TransactionStatus.WAITING, 200, "OK"))
    }

    fun stubConfirmationProof(
            blockchainRid: BlockchainRid,
            txRid: TxRid,
            txHash: ByteArray,
            blockHeader: BlockHeaderData,
            merkleProofTree: GtvMerkleProofTree,
            witness: Array<Signature>) {
        stubFor(get("/tx/$blockchainRid/${txRid.rid}/confirmationProof")
                .willReturn(ok().withHeader(Header.ContentType, ContentType.OCTET_STREAM.value)
                        .withBody(GtvEncoder.encodeGtv(GtvObjectMapper.toGtvDictionary(ConfirmationProofData(
                                hash = txHash,
                                blockHeader = blockHeader.toBinary(),
                                witness = encodeWitness(witness),
                                merkleProofTree = merkleProofTree,
                                txIndex = 0
                        ))))))
    }

    fun stubQuery(baseUrl: String, brid: BlockchainRid, name: String, args: Map<String, Gtv>, response: Gtv) {
        stubFor(
                stubQueryBuilder(baseUrl, brid, name, args, response)
        )
    }

    /*
    fun stubQueryBuilder(baseUrl: String, brid: BlockchainRid, name: String, args: Map<String, Gtv>, response: Gtv): MappingBuilder =
            post("$baseUrl/query_gtv/${brid}")
                    .withRequestBody(binaryEqualTo(GtvEncoder.encodeGtv(gtv(gtv(name), gtv(args)))))
                    .willReturn(ok(ContentType.OCTET_STREAM.value).withBody(GtvEncoder.encodeGtv(response)))
     */

    fun stubQueryBuilder(baseUrl: String, brid: BlockchainRid, name: String, args: Map<String, Gtv>, response: Gtv): MappingBuilder =
            get("$baseUrl/query_gtv/${brid}?type=${name}&%7Eargs=${GtvEncoder.encodeGtv(gtv(args)).toHex()}")
                    .willReturn(ok(ContentType.OCTET_STREAM.value).withBody(GtvEncoder.encodeGtv(response)))

    fun stubQuery(baseUrl: String, brid: BlockchainRid, name: String, response: Gtv) {
        stubFor(
                get("$baseUrl/query_gtv/${brid}?type=$name")
                        .willReturn(ok(ContentType.OCTET_STREAM.value).withBody(GtvEncoder.encodeGtv(response)))
        )
    }

    fun setupStubs(cluster: String, brid: BlockchainRid) {

        stubFor(get("/brid/iid_0").willReturn(ok(directoryChainBrid.toHex())))

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_system_anchoring_chain", gtv(systemAnchorChainBrid))

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_blockchain_api_urls",
                mapOf("blockchain_rid" to gtv(directoryChainBrid)), gtv(gtv("http://localhost:${server.port()}")))

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_blockchain_api_urls",
                mapOf("blockchain_rid" to gtv(brid)), gtv(gtv("http://localhost:${server.port()}")))

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_blockchain_cluster",
                mapOf("brid" to gtv(clusterAnchorChainBrid.data)), gtv(cluster))

        stubQuery(baseUrl = "", directoryChainBrid, "cm_get_cluster_info",
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

    @Test
    @Disabled
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

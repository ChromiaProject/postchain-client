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
import net.postchain.client.core.TxRid
import net.postchain.common.BlockchainRid
import net.postchain.common.toHex
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
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

        val cc = StandardChromiaClient("http://localhost:${server.port()}")
        cc.awaitAnchoredTx(dappBrid, dappTxRid)
        assertThat(cc.isTxAnchored(dappBrid, dappTxRid)).isEqualTo(true)
    }

    @Test
    fun `test anchoring timeout`() {

        val dappBrid = BlockchainRid.buildFromHex("335C75E08AFAC7D6678263F1A13D5AFED9CD009344B6349107D7CEEA3A40EA08")
        val dappTxRid = TxRid("3DE7FC7BCF6DAF2FFD8564D46D73F42C069818DDC249835535AD50D8D9270FF3")

        setupMock("cluster-2", dappBrid, dappTxRid)

        mockPostQuery(anchorChainBrid, "is_block_anchored",
                mapOf("blockchain_rid" to gtv(dappBrid), "block_rid" to gtv(txBlockRid)), gtv(false))

        val cc = StandardChromiaClient("http://localhost:${server.port()}")
        val e = assertThrows<TimeoutException> {
            cc.awaitAnchoredTx(dappBrid, dappTxRid, retries = 1)
        }
        assertThat(e.message).isEqualTo(
                "Timeout while waiting for transaction to be anchored"
        )
        assertThat(cc.isTxAnchored(dappBrid, dappTxRid)).isEqualTo(false)
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

        mockGetQuery(directoryChainBrid, "cm_get_cluster_info",
                mapOf("name" to gtv(cluster)), gtv(
                "name" to gtv(cluster),
                "anchoring_chain" to gtv(anchorChainBrid),
                "peers" to GtvArray(arrayOf())
        ))
    }

    // For manual testing
    @Disabled
    @Test
    fun test() {
//        val cc = StandardChromiaClient("https://system.chromaway.com:7740")
        val cc = StandardChromiaClient("https://dapps0.chromaway.com:7740")

        println("Node endpoints:")
        cc.managementPostchainClient.config.endpointPool.forEach { println(it.url) }

        println(cc.managementPostchainClient.config)

//        cc.awaitAnchoredTx(
//                BlockchainRid.buildFromHex("335C75E08AFAC7D6678263F1A13D5AFED9CD009344B6349107D7CEEA3A40EA08"), // token chain
//                TxRid("3DE7FC7BCF6DAF2FFD8564D46D73F42C069818DDC249835535AD50D8D9270FF3")) // a nop
        cc.awaitAnchoredTx(
                BlockchainRid.buildFromHex("15C0CA99BEE60A3B23829968771C50E491BD00D2E3AE448580CD48A8D71E7BBA"), // token chain
//                TxRid("D2B984DAF51829FD4ADADB5A037CE3F4CA4E1DFBDE7E96016D343A0267763BEA")) // a nop
                TxRid("4CF22C0262C4C7FD4599819D6E80AC8BF91AD0DF59E6D73118C71EDA6F91C90E")) // a nop
        println()
    }
}
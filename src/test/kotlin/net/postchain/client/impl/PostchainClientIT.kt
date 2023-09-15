// Copyright (c) 2020 ChromaWay AB. See README for license information.

package net.postchain.client.impl

import net.postchain.api.rest.controller.Model
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.PostchainClient
import net.postchain.client.core.TxRid
import net.postchain.client.exception.NodesDisagree
import net.postchain.client.request.EndpointPool
import net.postchain.client.transaction.TransactionBuilder
import net.postchain.common.BlockchainRid
import net.postchain.common.tx.TransactionStatus
import net.postchain.crypto.KeyPair
import net.postchain.crypto.PrivKey
import net.postchain.crypto.PubKey
import net.postchain.crypto.devtools.KeyPairHelper
import net.postchain.devtools.IntegrationTestSetup
import net.postchain.devtools.PostchainTestNode
import net.postchain.devtools.utils.configuration.system.SystemSetupFactory
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.GtxQuery
import org.awaitility.Awaitility.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.any
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import java.time.Instant
import java.util.Random

class PostchainClientIT : IntegrationTestSetup() {

    private val configFileName = "/net/postchain/client/impl/blockchain_config.xml"
    private val configFileName1 = "/net/postchain/client/impl/blockchain_config_1.xml"
    private val configFileNameMaxTransactions = "/net/postchain/client/impl/blockchain_config_max_transactions.xml"
    private val blockchainRIDStr = "ABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABABAB"
    private val blockchainRID = BlockchainRid.buildFromHex(blockchainRIDStr)
    private val pubKey0 = PubKey(KeyPairHelper.pubKey(0))
    private val privKey0 = PrivKey(KeyPairHelper.privKey(0))
    private val sigMaker0 = cryptoSystem.buildSigMaker(KeyPair(pubKey0, privKey0))

    private fun randomStr() = "hello${Random().nextLong()}"

    private fun createTestNodes(nodesCount: Int, configFileName: String): Array<PostchainTestNode> {
        val sysSetup = SystemSetupFactory.buildSystemSetup(mapOf(1 to configFileName))
        sysSetup.needRestApi = true
        assertEquals(nodesCount, sysSetup.nodeMap.size)
        createNodesFromSystemSetup(sysSetup)
        return nodes.toTypedArray()
    }

    private fun createSignedNopTx(client: PostchainClient, bcRid: BlockchainRid, randomStr: String = randomStr()): TransactionBuilder.PostableTransaction {
        return TransactionBuilder(client, bcRid, listOf(pubKey0.data), listOf(), cryptoSystem)
                .addOperation("gtx_test", gtv(1L), gtv(randomStr))
                .sign(sigMaker0)
    }

    private fun createPostChainClient(bcRid: BlockchainRid): PostchainClient {
        return PostchainClientProviderImpl().createClient(
                PostchainClientConfig(
                        bcRid,
                        EndpointPool.singleUrl("http://127.0.0.1:${nodes[0].getRestApiHttpPort()}"),
                        listOf(KeyPair(pubKey0, privKey0))
                ))
    }

    @Test
    fun makingAndPostingTransaction_UnsignedTransactionGiven_throws_Exception() {
        // Mock
        createTestNodes(1, configFileName1)
        val client = createPostChainClient(blockchainRID)
        assertThrows<IllegalArgumentException> {
            client.transactionBuilder().finish().build()
        }

        // When
        //assertEquals(TransactionStatus.REJECTED, txBuilder.postSync().status)
    }

    @Test
    fun makingAndPostingTransaction_SignedTransactionGiven_PostsSuccessfully() {
        // Mock
        createTestNodes(1, configFileName1)
        val client = spy(createPostChainClient(blockchainRID))
        val txBuilder = client.transactionBuilder()

        txBuilder.addOperation("nop")
        txBuilder.addOperation("nop", gtv(Instant.now().toEpochMilli()))
        val tx = txBuilder.sign(sigMaker0)

        // When
        tx.post()

        // Then
        verify(client).postTransaction(any())
    }

    @Test
    fun testPostTransactionApiConfirmLevelNoWait() {
        createTestNodes(1, configFileName1)
        val blockchainRid = systemSetup.blockchainMap[1]!!.rid
        val client = createPostChainClient(blockchainRid)
        val builder = createSignedNopTx(client, blockchainRid)
        val result = builder.post()
        assertEquals(TransactionStatus.WAITING, result.status)
    }

    @Test
    fun testPostTransactionApiConfirmLevelUnverified() {
        createTestNodes(4, configFileName)
        val blockchainRid = systemSetup.blockchainMap[1]!!.rid
        val client = createPostChainClient(blockchainRid)
        val builder = createSignedNopTx(client, blockchainRid)
        val result = builder.postAwaitConfirmation()
        assertEquals(TransactionStatus.CONFIRMED, result.status)
    }

    @Test
    fun testQueryGtxClientApi() {
        createTestNodes(4, configFileName)
        val blockchainRid = systemSetup.blockchainMap[1]!!.rid
        val client = createPostChainClient(blockchainRid)
        val rndStr = randomStr()
        val builder = createSignedNopTx(client, blockchainRid, rndStr)
        val result = builder.postAwaitConfirmation()
        val gtv = gtv("txRID" to gtv(result.txRid.rid))

        await().untilCallTo {
            client.query("gtx_test_get_value", gtv)
        } matches { resp ->
            resp?.asString() == rndStr
        }
    }

    @Test
    fun testTransactionsCount() {
        createTestNodes(4, configFileName)
        val blockchainRid = systemSetup.blockchainMap[1]!!.rid
        val client = createPostChainClient(blockchainRid)
        val builder = createSignedNopTx(client, blockchainRid)
        val result = builder.postAwaitConfirmation()
        assertEquals(TransactionStatus.CONFIRMED, result.status)
        assertEquals(1, client.getTransactionsCount())
    }

    @Test
    fun testTransactionInfo() {
        createTestNodes(4, configFileName)
        val blockchainRid = systemSetup.blockchainMap[1]!!.rid
        val client = createPostChainClient(blockchainRid)
        val builder = createSignedNopTx(client, blockchainRid)
        val result = builder.postAwaitConfirmation()
        assertEquals(TransactionStatus.CONFIRMED, result.status)
        val info = client.getTransactionInfo(result.txRid)
        assertEquals(1, info.blockHeight)
        assertEquals(result.txRid.rid, info.txRID.toHex())
    }

    @Test
    fun testTransactionsInfo() {
        createTestNodes(4, configFileName)
        val blockchainRid = systemSetup.blockchainMap[1]!!.rid
        val client = createPostChainClient(blockchainRid)
        val builder = createSignedNopTx(client, blockchainRid)
        val result = builder.postAwaitConfirmation()
        assertEquals(TransactionStatus.CONFIRMED, result.status)
        val info = client.getTransactionsInfo()
        assertEquals(1, info[0].blockHeight)
        assertEquals(result.txRid.rid, info[0].txRID.toHex())
    }

    @Test
    fun testTransactionsInfoPagination() {
        // setup 5 transactions in 2 blocks (3 + 2)
        createTestNodes(4, configFileNameMaxTransactions)
        val blockchainRid = systemSetup.blockchainMap[1]!!.rid
        val client = createPostChainClient(blockchainRid)
        addTransactions(client, blockchainRid, 3)
        addTransactions(client, blockchainRid, 2)
        val info = client.getTransactionsInfo()
        assertEquals(5, info.size)
        assertEquals(info[0].timestamp, info[1].timestamp)
        assertNotEquals(info[1].timestamp, info[2].timestamp)
        assertEquals(info[2].timestamp, info[3].timestamp)
        assertEquals(info[3].timestamp, info[4].timestamp)
        // fetch by limit
        val infoByLimit = client.getTransactionsInfo(4)
        assertEquals(4, infoByLimit.size)
        // fetch by timestamp
        val infoByTimestampNone = client.getTransactionsInfo(beforeTime = info[4].timestamp)
        assertEquals(0, infoByTimestampNone.size)
        val infoByTimestampSome = client.getTransactionsInfo(beforeTime = info[1].timestamp)
        assertEquals(3, infoByTimestampSome.size)
        val infoByTimestampAll = client.getTransactionsInfo(beforeTime = info[1].timestamp + 1)
        assertEquals(5, infoByTimestampAll.size)
    }

    private fun addTransactions(client: PostchainClient, blockchainRid: BlockchainRid, txCount: Int): List<TxRid> {
        val txRids = mutableListOf<TxRid>()
        for (i in 0 until txCount) {
            val builder = createSignedNopTx(client, blockchainRid)
            val post = builder.post()
            txRids.add(post.txRid)
            assertEquals(TransactionStatus.WAITING, post.status)
        }
        for (txRid in txRids) {
            val result = client.awaitConfirmation(txRid, client.config.statusPollCount, client.config.statusPollInterval)
            assertEquals(TransactionStatus.CONFIRMED, result.status)
        }
        return txRids
    }

    @ParameterizedTest(name = "majority query with {0} disagreeing nodes should work {1}")
    @CsvSource(
            "0,true",
            "1,true",
            "2,false"
    )
    fun testQueryMajority(disagreeingNodes: Int, shouldWork: Boolean) {
        createTestNodes(4, configFileName)
        val blockchainRid = systemSetup.blockchainMap[1]!!.rid
        val urls = nodes.map { "http://127.0.0.1:${it.getRestApiHttpPort()}" }
        val client = PostchainClientProviderImpl().createClient(
                PostchainClientConfig(
                        blockchainRid,
                        EndpointPool.default(urls),
                        listOf(KeyPair(pubKey0, privKey0)),
                        requestStrategy = QueryMajorityRequestStrategyFactory()
                ))
        val rndStr = randomStr()
        val builder = createSignedNopTx(client, blockchainRid, rndStr)
        val result = builder.postAwaitConfirmation()
        val gtv = gtv("txRID" to gtv(result.txRid.rid))

        for (n in 0 until disagreeingNodes) {
            nodes[n].overrideRestApiModel(MockModel(nodes[n].getRestApiModel()))
        }

        if (shouldWork) {
            assertEquals(rndStr, client.query("gtx_test_get_value", gtv).asString())
        } else {
            assertThrows<NodesDisagree> {
                client.query("gtx_test_get_value", gtv)
            }
        }
    }

    class MockModel(delegate: Model) : Model by delegate {
        override fun query(query: GtxQuery): Gtv = gtv("bogus")
    }
}

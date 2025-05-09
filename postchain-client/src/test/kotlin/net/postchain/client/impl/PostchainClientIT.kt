// Copyright (c) 2020 ChromaWay AB. See README for license information.

package net.postchain.client.impl

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.postchain.api.rest.controller.Model
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.AsyncQueryResponseStatus
import net.postchain.client.core.BlockDetail
import net.postchain.client.core.BlockRid
import net.postchain.client.core.PostchainClient
import net.postchain.client.core.TransactionInfo
import net.postchain.client.core.TxRid
import net.postchain.client.exception.ClientError
import net.postchain.client.exception.NodesDisagree
import net.postchain.client.request.EndpointPool
import net.postchain.client.transaction.TransactionBuilder
import net.postchain.client.transaction.signTransaction
import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.common.rest.HighestBlockHeightAnchoringCheck
import net.postchain.common.tx.TransactionStatus
import net.postchain.crypto.KeyPair
import net.postchain.crypto.PrivKey
import net.postchain.crypto.PubKey
import net.postchain.crypto.devtools.KeyPairHelper
import net.postchain.crypto.sha256Digest
import net.postchain.devtools.IntegrationTestSetup
import net.postchain.devtools.PostchainTestNode
import net.postchain.devtools.utils.configuration.system.SystemSetupFactory
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtv.mapper.toObject
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV2
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
import java.io.File
import java.time.Instant
import java.util.Random

class PostchainClientIT : IntegrationTestSetup() {

    private val configFileName = "/net/postchain/client/impl/blockchain_config.xml"
    private val configFileName1 = "/net/postchain/client/impl/blockchain_config_1.xml"
    private val configFileNameMaxTransactions = "/net/postchain/client/impl/blockchain_config_max_transactions.xml"
    private val pubKey0 = PubKey(KeyPairHelper.pubKey(0))
    private val privKey0 = PrivKey(KeyPairHelper.privKey(0))
    private val sigMaker0 = cryptoSystem.buildSigMaker(KeyPair(pubKey0, privKey0))

    private val alicePubkey = "038f888dec563b5bc253e87abc90afd26c3287021d10236ea19d248043dc39e0b8".hexStringToByteArray()
    private val alicePrivkey = "71b5b7f8de0661af934a5e4612f3d0ba183e639bdf4e7452fb6457ed3cfbc825".hexStringToByteArray()
    private val aliceKeyPair = KeyPair(alicePubkey, alicePrivkey)

    private val bobPubkey = "02E0A8A3C79C9F18B7CEAD2493435AC926B4A527EF670B873F5F1410084EFF9C80".hexStringToByteArray()
    private val bobPrivkey = "B31AB878C62B0E940B345C659A456D3573CF25960823C34C7BEEB5D1F813BEFD".hexStringToByteArray()
    private val bobKeyPair = KeyPair(bobPubkey, bobPrivkey)

    private val charliePubkey = "02620EB55BF0E3116F95D4D21771313AE5A477D5D166787DD8586D4413E3405D7E".hexStringToByteArray()
    private val charliePrivkey = "944D38EA36E0FA2D9862D77F99874D88FD172559E56913DA55578740573B19ED".hexStringToByteArray()
    private val charlieKeyPair = KeyPair(charliePubkey, charliePrivkey)

    private val hashCalculator = GtvMerkleHashCalculatorV2(::sha256Digest)

    private fun randomStr() = "hello${Random().nextLong()}"

    private fun createTestNodes(nodesCount: Int, configFileName: String): Array<PostchainTestNode> {
        val sysSetup = SystemSetupFactory.buildSystemSetup(mapOf(1 to configFileName))
        sysSetup.needRestApi = true
        assertEquals(nodesCount, sysSetup.nodeMap.size)
        createNodesFromSystemSetup(sysSetup)
        return nodes.toTypedArray()
    }

    private fun createSignedNopTx(client: PostchainClient, bcRid: BlockchainRid, randomStr: String = randomStr()): TransactionBuilder.PostableTransaction {
        return TransactionBuilder(client, bcRid, listOf(pubKey0.data), hashCalculator, listOf(), cryptoSystem)
                .addOperation("gtx_test", gtv(1L), gtv(randomStr))
                .sign(sigMaker0)
    }

    private fun createPostChainClient(bcRid: BlockchainRid): PostchainClient {
        return PostchainClientProviderImpl().createClient(
                PostchainClientConfig(
                        bcRid,
                        EndpointPool.default(nodes.map { "http://127.0.0.1:${it.getRestApiHttpPort()}" }),
                        listOf(KeyPair(pubKey0, privKey0)),
                ))
    }

    private fun loadConfig(fileName: String): Gtv {
        return GtvMLParser.parseGtvML(File(this::class.java.getResource(fileName)!!.toURI()).readText())
    }

    @Test
    fun makingAndPostingTransaction_UnsignedTransactionGiven_throws_Exception() {
        // Mock
        createTestNodes(1, configFileName1)
        val client = createPostChainClient(systemSetup.blockchainMap[1]!!.rid)
        assertThrows<IllegalArgumentException> {
            client.transactionBuilder().addNop().finish().build()
        }

        // When
        //assertEquals(TransactionStatus.REJECTED, txBuilder.postSync().status)
    }

    @Test
    fun makingAndPostingTransaction_SignedTransactionGiven_PostsSuccessfully() {
        // Mock
        createTestNodes(1, configFileName1)
        val client = spy(createPostChainClient(systemSetup.blockchainMap[1]!!.rid))
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
    fun signingMultiSigTransaction() {
        val alice = listOf(aliceKeyPair)
        val bob = listOf(bobKeyPair)
        createTestNodes(1, configFileName1)
        val client = spy(createPostChainClient(systemSetup.blockchainMap[1]!!.rid))
        val partiallySignedTx = client.transactionBuilder(alice, listOf(bobKeyPair.pubKey)).addOperation("nop").build()

        val signedTx = signTransaction(partiallySignedTx, bob, hashCalculator)

        client.transactionBuilder().postTransaction(signedTx)

        verify(client).postTransaction(any())
    }

    @Test
    fun signingMultiSigTransaction_postTransactionAwaitConfirmation() {
        val alice = listOf(aliceKeyPair)
        val bob = listOf(bobKeyPair)
        createTestNodes(1, configFileName1)
        val client = spy(createPostChainClient(systemSetup.blockchainMap[1]!!.rid))
        val partiallySignedTx = client.transactionBuilder(alice, listOf(bobKeyPair.pubKey)).addOperation("nop").build()

        val signedTx = signTransaction(partiallySignedTx, bob, hashCalculator)

        client.transactionBuilder().postTransactionAwaitConfirmation(signedTx)

        verify(client).postTransactionAwaitConfirmation(any())
    }

    @Test
    fun signingMultiSigTransactionWithAnySigningOrder() {
        val alice = listOf(aliceKeyPair)
        val bob = listOf(bobKeyPair)
        val charlie = listOf(charlieKeyPair)
        createTestNodes(1, configFileName1)
        val client = spy(createPostChainClient(systemSetup.blockchainMap[1]!!.rid))
        val signerTransactionCharlie = client
                .transactionBuilder(charlie, listOf(bobKeyPair.pubKey, aliceKeyPair.pubKey))
                .addOperation("nop")
                .build()

        val signedTxAlice = signTransaction(signerTransactionCharlie, alice, hashCalculator)

        val signedTxBob = signTransaction(signedTxAlice, bob, hashCalculator)

        client.transactionBuilder().postTransaction(signedTxBob)

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
            resp?.asArray()?.first()?.asString() == rndStr
        }

        val (endpoint, queryRid) = client.asyncQuery("gtx_test_get_value", gtv)
        await().untilCallTo {
            client.fetchAsyncQueryResponse(endpoint, queryRid)
        } matches { resp ->
            resp?.status == AsyncQueryResponseStatus.COMPLETED
                    && resp.queryResponse.asArray().first().asString() == rndStr
        }

        val wrongEndpoint = client.config.endpointPool.filterNot { it.url == endpoint.url }.first()
        assertThat(client.fetchAsyncQueryResponse(wrongEndpoint, queryRid).status)
                .isEqualTo(AsyncQueryResponseStatus.NOT_FOUND)
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
    fun testTransaction() {
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
    fun testBlockDetail() {
        createTestNodes(4, configFileName)
        val blockchainRid = systemSetup.blockchainMap[1]!!.rid
        val client = createPostChainClient(blockchainRid)
        val builder = createSignedNopTx(client, blockchainRid)
        val result = builder.postAwaitConfirmation()
        assertEquals(TransactionStatus.CONFIRMED, result.status)

        val blockDetail1 = client.blockAtHeight(1)!!
        assertThat(blockDetail1.transactions.size).isEqualTo(1)

        // we cannot compare witness since it will be different on different nodes
        val blockDetail2 = client.blockByRid(BlockRid(blockDetail1.rid.toHex()))!!
        assertThat(blockDetail2.rid).isEqualTo(blockDetail1.rid)
        assertThat(blockDetail2.prevBlockRID).isEqualTo(blockDetail1.prevBlockRID)
        assertThat(blockDetail2.header).isEqualTo(blockDetail1.header)
        assertThat(blockDetail2.height).isEqualTo(blockDetail1.height)
        assertThat(blockDetail2.transactions).isEqualTo(blockDetail1.transactions)
        assertThat(blockDetail2.timestamp).isEqualTo(blockDetail1.timestamp)
        
        // we cannot compare witness since it will be different on different nodes
        val blockDetail3 = client.genericGetGtv("/blocks/${client.config.blockchainRid}/height/1").toObject<BlockDetail>()
        assertThat(blockDetail3.rid).isEqualTo(blockDetail1.rid)
        assertThat(blockDetail3.prevBlockRID).isEqualTo(blockDetail1.prevBlockRID)
        assertThat(blockDetail3.header).isEqualTo(blockDetail1.header)
        assertThat(blockDetail3.height).isEqualTo(blockDetail1.height)
        assertThat(blockDetail3.transactions).isEqualTo(blockDetail1.transactions)
        assertThat(blockDetail3.timestamp).isEqualTo(blockDetail1.timestamp)
    }

    @Test
    fun testTransactionsInfo() {
        createTestNodes(4, configFileName)
        val blockchainRid = systemSetup.blockchainMap[1]!!.rid
        val client = createPostChainClient(blockchainRid)
        val builder = createSignedNopTx(client, blockchainRid)
        val result = builder.postAwaitConfirmation()
        assertEquals(TransactionStatus.CONFIRMED, result.status)
        val txInfo = client.getTransactionsInfo()
        assertEquals(1, txInfo[0].blockHeight)
        assertEquals(result.txRid.rid, txInfo[0].txRID.toHex())
        val rawTxInfo2 = client.genericGetJson("/transactions/${client.config.blockchainRid}")
        val txInfo2 = Gson().fromJson(rawTxInfo2, object : TypeToken<ArrayList<TransactionInfo.Json>>() {})
                .map { TransactionInfo.fromJson(it) }
        assertThat(txInfo2.size).isEqualTo(txInfo.size)
        txInfo2.forEachIndexed { i, info ->
            // we cannot compare witness since it will be different on different nodes
            assertThat(info.blockRID).isEqualTo(txInfo[i].blockRID)
            assertThat(info.blockHeight).isEqualTo(txInfo[i].blockHeight)
            assertThat(info.blockHeader).isEqualTo(txInfo[i].blockHeader)
            assertThat(info.timestamp).isEqualTo(txInfo[i].timestamp)
            assertThat(info.txRID).isEqualTo(txInfo[i].txRID)
            assertThat(info.txHash).isEqualTo(txInfo[i].txHash)
            assertThat(info.txData).isEqualTo(txInfo[i].txData)
        }
    }

    @Test
    fun testValidateBlockchainConfigFails() {
        createTestNodes(4, configFileName)
        val blockchainRid = systemSetup.blockchainMap[1]!!.rid
        val client = createPostChainClient(blockchainRid)

        client.validateConfiguration(loadConfig(configFileName1))
        val err = assertThrows<ClientError> {
            client.validateConfiguration(loadConfig("blockchain_config_invalid.xml"))
        }
        assertThat(err.errorMessage).contains("GTX module class not found: net.postchain.gtx.NonExistent")
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
        (0 until txCount).forEach { i ->
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
                        requestStrategy = QueryMajorityRequestStrategyFactory(),
                ))
        val rndStr = randomStr()
        val builder = createSignedNopTx(client, blockchainRid, rndStr)
        val result = builder.postAwaitConfirmation()
        val gtv = gtv("txRID" to gtv(result.txRid.rid))

        for (n in 0 until disagreeingNodes) {
            nodes[n].overrideRestApiModel(MockModel(nodes[n].getRestApiModel()))
        }

        if (shouldWork) {
            assertEquals(rndStr, client.query("gtx_test_get_value", gtv).asArray().first().asString())
        } else {
            assertThrows<NodesDisagree> {
                client.query("gtx_test_get_value", gtv)
            }
        }
    }

    @Test
    fun testGetVerifiedAnchoredBlockHeights() {
        createTestNodes(4, configFileName)
        val blockchainRid = systemSetup.blockchainMap[1]!!.rid
        val client = createPostChainClient(blockchainRid)
        assertEquals(HighestBlockHeightAnchoringCheck(null, null, null), client.getHighestBlockHeightAnchoringCheck())
    }

    class MockModel(delegate: Model) : Model by delegate {
        override fun query(query: GtxQuery): Gtv = gtv("bogus")
    }
}

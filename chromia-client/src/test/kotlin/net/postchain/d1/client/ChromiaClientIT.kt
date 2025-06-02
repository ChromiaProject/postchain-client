// Copyright (c) 2020 ChromaWay AB. See README for license information.

package net.postchain.d1.client

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.BlockRid
import net.postchain.client.core.PostchainClient
import net.postchain.client.impl.PostchainClientProviderImpl
import net.postchain.client.request.EndpointPool
import net.postchain.client.transaction.TransactionBuilder
import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.common.tx.TransactionStatus
import net.postchain.common.wrap
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
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV2
import org.junit.jupiter.api.Test
import java.util.Random

class ChromiaClientIT : IntegrationTestSetup() {

    private val configFileName = "/net/postchain/d1/client/blockchain_config.xml"
    private val pubKey0 = PubKey(KeyPairHelper.pubKey(0))
    private val privKey0 = PrivKey(KeyPairHelper.privKey(0))
    private val sigMaker0 = cryptoSystem.buildSigMaker(KeyPair(pubKey0, privKey0))

    private val hashCalculator = GtvMerkleHashCalculatorV2(::sha256Digest)

    private fun randomStr() = "hello${Random().nextLong()}"

    private fun createTestNodes(nodesCount: Int, configFileName: String): Array<PostchainTestNode> {
        val sysSetup = SystemSetupFactory.buildSystemSetup(mapOf(1 to configFileName))
        sysSetup.needRestApi = true
        assertThat(nodesCount).isEqualTo(sysSetup.nodeMap.size)
        createNodesFromSystemSetup(sysSetup)
        return nodes.toTypedArray()
    }

    private fun createSignedNopTx(client: PostchainClient, bcRid: BlockchainRid, randomStr: String = randomStr()): TransactionBuilder.PostableTransaction {
        return TransactionBuilder(client, bcRid, listOf(pubKey0.data), hashCalculator, listOf(), cryptoSystem)
                .addOperation("gtx_test", gtv(1L), gtv(randomStr))
                .sign(sigMaker0)
    }

    private fun createClient(bcRid: BlockchainRid): ChromiaPostchainClient {
        val postchainClient = PostchainClientProviderImpl().createClient(
                PostchainClientConfig(
                        bcRid,
                        EndpointPool.singleUrl("http://127.0.0.1:${nodes[0].getRestApiHttpPort()}"),
                        listOf(KeyPair(pubKey0, privKey0)),
                        merkleHashVersion = 2
                ))
        return ChromiaPostchainClient(directoryChainClient = { name: String, args: Gtv ->
            gtv(
                    gtv("03a301697bdfcd704313ba48e51d567543f2a182031efd6915ddc07bbcc4e16070".hexStringToByteArray()),
                    gtv("031B84C5567B126440995D3ED5AABA0565D71E1834604819FF9C17F5E9D5DD078F".hexStringToByteArray()),
                    gtv("03B2EF623E7EC933C478135D1763853CBB91FC31BA909AEC1411CA253FDCC1AC94".hexStringToByteArray()),
                    gtv("0203C6150397F7E4197FF784A8D74357EF20DAF1D09D823FFF8D3FC9150CBAE85D".hexStringToByteArray()),
            )
        }, postchainClient, postchainClient, addNop = false)
    }

    @Test
    fun testTransaction() {
        createTestNodes(4, configFileName)
        val blockchainRid = systemSetup.blockchainMap[1]!!.rid
        val client = createClient(blockchainRid)
        val builder = createSignedNopTx(client, blockchainRid)
        val result = builder.postAwaitConfirmation()
        assertThat(result.status).isEqualTo(TransactionStatus.CONFIRMED)
        val info1 = client.getTransactionInfo(result.txRid)
        assertThat(info1.blockHeight).isEqualTo(1)
        assertThat(result.txRid.rid, info1.txRID.toHex())
        val proof = client.decodedConfirmationProof(result.txRid)
        assertThat(info1.txHash).isEqualTo(proof.hash.wrap())
        val info2 = client.verifiedTransactionInfo(result.txRid)
        assertThat(info2).isEqualTo(info1)

        val blockDetail1 = client.verifiedBlockAtHeight(info1.blockHeight)!!
        val blockDetail2 = client.verifiedBlockByRid(BlockRid(info1.blockRID.toHex()))!!
        assertThat(blockDetail2).isEqualTo(blockDetail1)
        assertThat(blockDetail1.transactions.size).isEqualTo(1)
        assertThat(blockDetail1.transactions.first().rid).isEqualTo(result.txRid.rid.hexStringToByteArray().wrap())
    }
}

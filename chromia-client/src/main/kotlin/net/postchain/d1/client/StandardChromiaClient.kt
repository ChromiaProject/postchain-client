package net.postchain.d1.client

import net.postchain.chain0.anchoring_chain_common.isBlockAnchored
import net.postchain.chromia.cm_api.cmGetBlockchainApiUrls
import net.postchain.chromia.cm_api.cmGetBlockchainCluster
import net.postchain.chromia.cm_api.cmGetClusterInfo
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.PostchainClient
import net.postchain.client.core.TransactionInfo
import net.postchain.client.core.TxRid
import net.postchain.client.impl.PostchainClientImpl
import net.postchain.client.impl.QueryMajorityRequestStrategyFactory
import net.postchain.client.impl.TryNextOnErrorRequestStrategyFactory
import net.postchain.client.request.EndpointPool
import net.postchain.client.request.RequestStrategyFactory
import net.postchain.common.BlockchainRid
import java.lang.Thread.sleep
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * The standard chromia client requires at least one node endpoint but will resolve and connect to other
 * system nodes detected in the network.
 */
class StandardChromiaClient(
        override val config: ChromiaClientConfig
) : ChromiaClient {
    constructor(endpoint: String) : this(EndpointPool.singleUrl(endpoint))

    constructor(endpointPool: EndpointPool) : this(ChromiaClientConfig(endpointPool))

    val managementPostchainClient: PostchainClient

    init {
        val initialConfig = PostchainClientConfig(
                BlockchainRid.ZERO_RID,
                config.endpointPool,
                config.signers,
                statusPollCount = config.statusPollCount,
                statusPollInterval = config.statusPollInterval,
                connectTimeout = config.connectTimeout,
                responseTimeout = config.responseTimeout,
                requestStrategy = TryNextOnErrorRequestStrategyFactory()
        )
        val dcBrid = PostchainClientImpl(initialConfig).getBlockchainRID(0)

        val dcBridConfig = initialConfig.copy(blockchainRid = dcBrid)
        val apiUrls = PostchainClientImpl(dcBridConfig).cmGetBlockchainApiUrls(dcBrid)

        managementPostchainClient = PostchainClientImpl(dcBridConfig.copy(endpointPool = EndpointPool.default(apiUrls.toList())))
    }

    override fun isTxAnchored(blockchainRid: BlockchainRid, txId: TxRid): Boolean {
        val transactionInfo = getTransactionInfo(blockchainRid, txId)
        return isBlockAnchored(blockchainRid, transactionInfo.blockRID.data)
    }

    override fun isBlockAnchored(blockchainRid: BlockchainRid, blockRid: ByteArray): Boolean {
        val anchoringPostchainClient = getAnchoringPostchainClient(blockchainRid)
        return anchoringPostchainClient.isBlockAnchored(blockchainRid, blockRid)
    }

    override fun awaitAnchoredTx(
            blockchainRid: BlockchainRid,
            txId: TxRid,
            retries: Int,
            pollInterval: Duration
        ) {

        val transactionInfo = getTransactionInfo(blockchainRid, txId)
        val anchoringPostchainClient = getAnchoringPostchainClient(blockchainRid)

        repeat(retries) {
            val blockAnchored = anchoringPostchainClient.isBlockAnchored(blockchainRid, transactionInfo.blockRID.data)
            if (blockAnchored) {
                return
            }
            sleep(pollInterval.toMillis())
        }
        throw TimeoutException("Timeout while waiting for transaction to be anchored")
    }

    private fun getTransactionInfo(blockchainRid: BlockchainRid, txId: TxRid): TransactionInfo {
        val sourceChainClient = getPostchainClient(blockchainRid)
        val transactionInfo = sourceChainClient.getTransactionInfo(txId)
        return transactionInfo
    }

    override fun getAnchoringPostchainClient(dappBlockchainRid: BlockchainRid): PostchainClient {
        return getAnchoringPostchainClient(managementPostchainClient.cmGetBlockchainCluster(dappBlockchainRid.data))
    }

    override fun getAnchoringPostchainClient(cluster: String): PostchainClient {
        val clusterInfo = managementPostchainClient.cmGetClusterInfo(cluster)
        return getPostchainClient(BlockchainRid(clusterInfo.anchoringChain), QueryMajorityRequestStrategyFactory())
    }

    override fun getPostchainClient(
            blockchainRid: BlockchainRid,
            requestStrategy: RequestStrategyFactory
    ) = PostchainClientImpl(managementPostchainClient.config.copy(blockchainRid = blockchainRid, requestStrategy = requestStrategy))
}
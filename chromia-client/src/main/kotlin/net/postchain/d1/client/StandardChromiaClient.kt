package net.postchain.d1.client

import net.postchain.chain0.anchoring_chain_common.isBlockAnchored
import net.postchain.chain0.cm_api.CmClusterInfo
import net.postchain.chain0.cm_api.CmPeerInfo
import net.postchain.chain0.cm_api.cmGetBlockchainApiUrls
import net.postchain.chain0.cm_api.cmGetBlockchainCluster
import net.postchain.chain0.cm_api.cmGetClusterInfo
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.PostchainClient
import net.postchain.client.core.TransactionInfo
import net.postchain.client.core.TxRid
import net.postchain.client.impl.PostchainClientImpl
import net.postchain.client.impl.QueryMajorityRequestStrategyFactory
import net.postchain.client.impl.TryNextOnErrorRequestStrategyFactory
import net.postchain.client.request.EndpointPool
import net.postchain.client.request.RandomizedEndpointPool
import net.postchain.client.request.RequestStrategyFactory
import net.postchain.common.BlockchainRid
import java.lang.Thread.sleep
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
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
    private val clusterNodes = ConcurrentHashMap<String, CmClusterInfo>()
    private val clients = ConcurrentHashMap<BlockchainRid, PostchainClient>()

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

    override fun isTxClusterAnchored(blockchainRid: BlockchainRid, txId: TxRid): Boolean {
        val transactionInfo = getTransactionInfo(blockchainRid, txId)
        return isBlockClusterAnchored(blockchainRid, transactionInfo.blockRID.data)
    }

    override fun isBlockClusterAnchored(blockchainRid: BlockchainRid, blockRid: ByteArray): Boolean {
        val anchoringPostchainClient = getClusterAnchoringPostchainClient(blockchainRid)
        return anchoringPostchainClient.isBlockAnchored(blockchainRid, blockRid)
    }

    override fun awaitClusterAnchoredTx(
            blockchainRid: BlockchainRid,
            txId: TxRid,
            retries: Int,
            pollInterval: Duration
        ) {

        val transactionInfo = getTransactionInfo(blockchainRid, txId)
        val anchoringPostchainClient = getClusterAnchoringPostchainClient(blockchainRid)

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
        val sourceChainClient = getOrCreatePostchainClient(blockchainRid)
        val transactionInfo = sourceChainClient.getTransactionInfo(txId)
        return transactionInfo
    }

    override fun getClusterAnchoringPostchainClient(dappBlockchainRid: BlockchainRid): PostchainClient {
        return getClusterAnchoringPostchainClient(managementPostchainClient.cmGetBlockchainCluster(dappBlockchainRid.data))
    }

    override fun getClusterAnchoringPostchainClient(cluster: String): PostchainClient {
        val clusterInfo = managementPostchainClient.cmGetClusterInfo(cluster)
        return getOrCreatePostchainClient(BlockchainRid(clusterInfo.anchoringChain), QueryMajorityRequestStrategyFactory())
    }

    override fun getPostchainClient(
            blockchainRid: BlockchainRid,
            requestStrategy: RequestStrategyFactory
    ): PostchainClient {
        val clusterName = managementPostchainClient.cmGetBlockchainCluster(blockchainRid.data)
        val clusterInfo = getClusterInfo(clusterName)

        return PostchainClientImpl(managementPostchainClient.config.copy(
                    blockchainRid = blockchainRid,
                    requestStrategy = requestStrategy,
                    endpointPool = RandomizedEndpointPool(clusterInfo.peers.map(CmPeerInfo::apiUrl))
            ))
    }

    private fun getOrCreatePostchainClient(
            blockchainRid: BlockchainRid,
            requestStrategy: RequestStrategyFactory = TryNextOnErrorRequestStrategyFactory()
    ): PostchainClient {
        return clients.getOrPut(blockchainRid) {
            getPostchainClient(blockchainRid, requestStrategy)
        }
    }

    private fun getClusterInfo(cluster: String): CmClusterInfo {
        return clusterNodes.getOrPut(cluster) {
            managementPostchainClient.cmGetClusterInfo(cluster)
        }
    }
}
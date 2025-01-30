package net.postchain.d1.client

import net.postchain.chain0.anchoring_chain_common.isBlockAnchored
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
import net.postchain.client.request.RequestStrategyFactory
import net.postchain.common.BlockchainRid
import java.lang.Thread.sleep
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

/**
 * The standard chromia client requires at least one node but will resolve and connect to other
 * system nodes detected in the network.
 *
 * @param config  client config, the `blockchainRid` property should be the directory chain,
 * or set to `BlockchainRid.ZERO_RID` to have it looked up automatically
 */
class StandardChromiaClient(
        override val config: PostchainClientConfig
) : ChromiaClient {
    constructor(nodes: EndpointPool) : this(PostchainClientConfig(
            blockchainRid = BlockchainRid.ZERO_RID,
            endpointPool = nodes,
            requestStrategy = TryNextOnErrorRequestStrategyFactory()))

    override val directoryChainRid: BlockchainRid
    val directoryChainConfig: PostchainClientConfig
    internal val directoryChainClient: PostchainClient

    private val clients = ConcurrentHashMap<BlockchainRid, PostchainClient>()

    init {
        val initialConfig = config.copy(requestStrategy = TryNextOnErrorRequestStrategyFactory())

        val dcBridConfig = if (initialConfig.blockchainRid != BlockchainRid.ZERO_RID)
            initialConfig
        else
            initialConfig.copy(blockchainRid = PostchainClientImpl(initialConfig).getBlockchainRID(0))
        directoryChainRid = dcBridConfig.blockchainRid

        val apiUrls = PostchainClientImpl(dcBridConfig).cmGetBlockchainApiUrls(directoryChainRid)

        directoryChainConfig = dcBridConfig.copy(endpointPool = EndpointPool.default(apiUrls))
        directoryChainClient = PostchainClientImpl(directoryChainConfig)
    }

    override fun isTxClusterAnchored(blockchainRid: BlockchainRid, txId: TxRid): Boolean {
        val transactionInfo = getTransactionInfo(blockchainRid, txId)
        return isBlockClusterAnchored(blockchainRid, transactionInfo.blockRID.data)
    }

    override fun isBlockClusterAnchored(blockchainRid: BlockchainRid, blockRid: ByteArray): Boolean {
        val anchoringPostchainClient = getClusterAnchoringClient(blockchainRid)
        return anchoringPostchainClient.isBlockAnchored(blockchainRid, blockRid)
    }

    override fun awaitClusterAnchoredTx(
            blockchainRid: BlockchainRid,
            txId: TxRid,
            retries: Int,
            pollInterval: Duration
    ) {

        val transactionInfo = getTransactionInfo(blockchainRid, txId)
        val anchoringPostchainClient = getClusterAnchoringClient(blockchainRid)

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
        return sourceChainClient.getTransactionInfo(txId)
    }

    override fun getClusterAnchoringClient(dappBlockchainRid: BlockchainRid): PostchainClient =
            getClusterAnchoringClient(directoryChainClient.cmGetBlockchainCluster(dappBlockchainRid.data))

    override fun getClusterAnchoringClient(cluster: String): PostchainClient {
        val clusterInfo = directoryChainClient.cmGetClusterInfo(cluster)
        return PostchainClientImpl(config.copy(
                blockchainRid = BlockchainRid(clusterInfo.anchoringChain),
                endpointPool = EndpointPool.default(clusterInfo.peers.map { it.apiUrl }),
                requestStrategy = QueryMajorityRequestStrategyFactory(),
        ))
    }

    override fun getDirectoryChainClient(requestStrategy: RequestStrategyFactory, addNop: Boolean): PostchainClient {
        val client = PostchainClientImpl(directoryChainConfig.copy(
                requestStrategy = requestStrategy,
        ))
        return ChromiaPostchainClient(txClient = client, queryClient = client, addNop)
    }

    override fun getDirectoryChainClientForQueryReplica(queryNodes: EndpointPool, requestStrategy: RequestStrategyFactory, addNop: Boolean): PostchainClient {
        val txClient = PostchainClientImpl(directoryChainConfig.copy(
                requestStrategy = requestStrategy,
        ))
        val queryClient = PostchainClientImpl(config.copy(
                blockchainRid = directoryChainRid,
                endpointPool = queryNodes,
                requestStrategy = requestStrategy,
        ))
        return ChromiaPostchainClient(txClient = txClient, queryClient = queryClient, addNop)
    }

    override fun getClient(blockchainRid: BlockchainRid, requestStrategy: RequestStrategyFactory, addNop: Boolean): PostchainClient {
        val signerNodes = getSignerNodes(blockchainRid)

        val client = PostchainClientImpl(config.copy(
                blockchainRid = blockchainRid,
                endpointPool = signerNodes,
                requestStrategy = requestStrategy,
        ))
        return ChromiaPostchainClient(txClient = client, queryClient = client, addNop)
    }

    override fun getClientForQueryReplica(blockchainRid: BlockchainRid, queryNodes: EndpointPool, requestStrategy: RequestStrategyFactory, addNop: Boolean): PostchainClient {
        val signerNodes = getSignerNodes(blockchainRid)

        val txClient = PostchainClientImpl(config.copy(
                blockchainRid = blockchainRid,
                endpointPool = signerNodes,
                requestStrategy = requestStrategy,
        ))
        val queryClient = PostchainClientImpl(config.copy(
                blockchainRid = blockchainRid,
                endpointPool = queryNodes,
                requestStrategy = requestStrategy,
        ))
        return ChromiaPostchainClient(txClient = txClient, queryClient = queryClient, addNop)
    }

    private fun getSignerNodes(blockchainRid: BlockchainRid): EndpointPool =
            EndpointPool.default(directoryChainClient.cmGetBlockchainApiUrls(blockchainRid))

    private fun getOrCreatePostchainClient(
            blockchainRid: BlockchainRid,
            requestStrategy: RequestStrategyFactory = TryNextOnErrorRequestStrategyFactory()
    ): PostchainClient {
        return clients.getOrPut(blockchainRid) {
            getClient(blockchainRid, requestStrategy)
        }
    }
}
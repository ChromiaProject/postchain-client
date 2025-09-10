package net.postchain.d1.client

import net.postchain.chain0.anchoring_chain_common.getAnchoringTransactionForBlockRid
import net.postchain.chain0.anchoring_chain_common.isBlockAnchored
import net.postchain.chain0.cm_api.cmGetBlockchainApiUrls
import net.postchain.chain0.cm_api.cmGetBlockchainCluster
import net.postchain.chain0.cm_api.cmGetClusterInfo
import net.postchain.chain0.cm_api.cmGetSystemAnchoringChain
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.TxRid
import net.postchain.client.exception.ClientError
import net.postchain.client.impl.PostchainClientImpl
import net.postchain.client.impl.TryNextOnErrorRequestStrategyFactory
import net.postchain.client.request.EndpointPool
import net.postchain.client.request.RequestStrategyFactory
import net.postchain.client.transaction.TransactionBuilder
import net.postchain.common.BlockchainRid
import net.postchain.common.data.Hash
import net.postchain.gtv.Gtv
import java.lang.Thread.sleep
import java.time.Duration
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
    internal val directoryChainClient: PostchainClientImpl
    internal val systemAnchoringClient: ChromiaPostchainClient by lazy {
        val client = directoryChainClient.reconfigure(directoryChainClient.config.copy(
                blockchainRid = BlockchainRid(directoryChainClient.cmGetSystemAnchoringChain()
                        ?: throw IllegalStateException("No system anchoring chain")),
        ))
        ChromiaPostchainClient(directoryChainClient = directoryChainClient, txClient = client, queryClient = client, addNop = false)
    }

    init {
        val initialClient = PostchainClientImpl(config.copy(requestStrategy = TryNextOnErrorRequestStrategyFactory()))

        val dcBridClient = if (initialClient.config.blockchainRid != BlockchainRid.ZERO_RID) {
            directoryChainRid = initialClient.config.blockchainRid
            initialClient
        } else {
            directoryChainRid = initialClient.getBlockchainRID(0)
            initialClient.reconfigure(initialClient.config.copy(blockchainRid = directoryChainRid))
        }

        val apiUrls = dcBridClient.cmGetBlockchainApiUrls(directoryChainRid)
        if (apiUrls.isEmpty()) {
            throw ClientError("chromia", null, "No signer nodes for directory chain $directoryChainRid", null)
        }
        directoryChainClient = dcBridClient.reconfigure(dcBridClient.config.copy(endpointPool = EndpointPool.default(apiUrls)))
    }

    override fun awaitClusterAnchoredTx(
            blockchainRid: BlockchainRid,
            txRid: TxRid,
            retries: Int,
            pollInterval: Duration
    ) {
        val (anchoringClient, sourceChainClient) = getClusterAnchoringAndSourceChainClients(blockchainRid)
        val blockRid = sourceChainClient.decodedConfirmationProof(txRid).blockHeaderData.blockRid()

        repeat(retries) {
            val blockAnchored = anchoringClient.isBlockAnchored(blockchainRid, blockRid)
            if (blockAnchored) {
                return
            }
            sleep(pollInterval.toMillis())
        }
        throw TimeoutException("Timeout while waiting for transaction to be cluster anchored")
    }

    override fun isTxClusterAnchored(blockchainRid: BlockchainRid, txRid: TxRid): Boolean {
        val (anchoringClient, sourceChainClient) = getClusterAnchoringAndSourceChainClients(blockchainRid)
        val blockRid = sourceChainClient.decodedConfirmationProof(txRid).blockHeaderData.blockRid()
        return anchoringClient.isBlockAnchored(blockchainRid, blockRid)
    }

    private fun getClusterAnchoringAndSourceChainClients(blockchainRid: BlockchainRid): Pair<ChromiaPostchainClient, ChromiaPostchainClient> {
        val clusterInfo = directoryChainClient.cmGetClusterInfo(directoryChainClient.cmGetBlockchainCluster(blockchainRid.data))
        val signerNodes = EndpointPool.default(clusterInfo.peers.map { it.apiUrl })
        val anchoringClient = directoryChainClient.reconfigure(config.copy(
                blockchainRid = BlockchainRid(clusterInfo.anchoringChain),
                endpointPool = signerNodes,
                requestStrategy = TryNextOnErrorRequestStrategyFactory()
        ))
        val sourceChainClient = directoryChainClient.reconfigure(config.copy(
                blockchainRid = blockchainRid,
                endpointPool = signerNodes,
                requestStrategy = TryNextOnErrorRequestStrategyFactory()
        ))
        return ChromiaPostchainClient(directoryChainClient = directoryChainClient, txClient = anchoringClient, queryClient = anchoringClient, addNop = false) to
                ChromiaPostchainClient(directoryChainClient = directoryChainClient, txClient = sourceChainClient, queryClient = sourceChainClient, addNop = false)
    }

    override fun isBlockClusterAnchored(blockchainRid: BlockchainRid, blockRid: ByteArray): Boolean =
            getClusterAnchoringClient(blockchainRid).isBlockAnchored(blockchainRid, blockRid)

    override fun getClusterAnchoringClient(dappBlockchainRid: BlockchainRid): ChromiaPostchainClient =
            getClusterAnchoringClient(directoryChainClient.cmGetBlockchainCluster(dappBlockchainRid.data))

    override fun getClusterAnchoringClient(cluster: String): ChromiaPostchainClient {
        val clusterInfo = directoryChainClient.cmGetClusterInfo(cluster)
        val client = directoryChainClient.reconfigure(config.copy(
                blockchainRid = BlockchainRid(clusterInfo.anchoringChain),
                endpointPool = EndpointPool.default(clusterInfo.peers.map { it.apiUrl }),
                requestStrategy = TryNextOnErrorRequestStrategyFactory()
        ))
        return ChromiaPostchainClient(directoryChainClient = directoryChainClient, txClient = client, queryClient = client, addNop = false)
    }

    override fun isTxSystemAnchored(blockchainRid: BlockchainRid, txRid: TxRid): Boolean {
        val (clusterAnchoringClient, sourceChainClient) = getClusterAnchoringAndSourceChainClients(blockchainRid)
        val blockRid = sourceChainClient.decodedConfirmationProof(txRid).blockHeaderData.blockRid()
        val clusterAnchoringTxRid = clusterAnchoringClient.getAnchoringTransactionForBlockRid(blockchainRid, blockRid)?.let {
            TxRid(it.txRid.toHex())
        } ?: return false
        val clusterAnchoringBlockRid = clusterAnchoringClient.decodedConfirmationProof(clusterAnchoringTxRid).blockHeaderData.blockRid()
        return systemAnchoringClient.isBlockAnchored(
                blockchainRid = clusterAnchoringClient.config.blockchainRid,
                blockRid = clusterAnchoringBlockRid
        )
    }

    override fun isBlockSystemAnchored(blockchainRid: BlockchainRid, blockRid: ByteArray): Boolean {
        val clusterAnchoringClient = getClusterAnchoringClient(blockchainRid)
        val clusterAnchoringTxRid = clusterAnchoringClient.getAnchoringTransactionForBlockRid(blockchainRid, blockRid)?.let {
            TxRid(it.txRid.toHex())
        } ?: return false
        val clusterAnchoringBlockRid = clusterAnchoringClient.decodedConfirmationProof(clusterAnchoringTxRid).blockHeaderData.blockRid()
        return systemAnchoringClient.isBlockAnchored(
                blockchainRid = clusterAnchoringClient.config.blockchainRid,
                blockRid = clusterAnchoringBlockRid
        )
    }

    override fun awaitSystemAnchoredTx(blockchainRid: BlockchainRid, txRid: TxRid, retries: Int, pollInterval: Duration) {
        val (clusterAnchoringClient, sourceChainClient) = getClusterAnchoringAndSourceChainClients(blockchainRid)
        val blockRid = sourceChainClient.decodedConfirmationProof(txRid).blockHeaderData.blockRid()

        var retriesLeft = retries

        var clusterAnchoringBlockRid: ByteArray? = null
        while (retriesLeft > 0) {
            val clusterAnchoringTxRid = clusterAnchoringClient.getAnchoringTransactionForBlockRid(blockchainRid, blockRid)?.let {
                TxRid(it.txRid.toHex())
            }
            if (clusterAnchoringTxRid != null) {
                clusterAnchoringBlockRid = clusterAnchoringClient.decodedConfirmationProof(clusterAnchoringTxRid).blockHeaderData.blockRid()
                break
            } else {
                retriesLeft--
                sleep(pollInterval.toMillis())
            }
        }

        while (retriesLeft > 0) {
            val blockAnchored = systemAnchoringClient.isBlockAnchored(
                    blockchainRid = clusterAnchoringClient.config.blockchainRid,
                    blockRid = clusterAnchoringBlockRid!!
            )
            if (blockAnchored) {
                return
            }
            retriesLeft--
            sleep(pollInterval.toMillis())
        }

        throw TimeoutException("Timeout while waiting for transaction to be system anchored")
    }

    override fun getSystemAnchoringClient(): ChromiaPostchainClient = systemAnchoringClient

    override fun getDirectoryChainClient(requestStrategy: RequestStrategyFactory, addNop: Boolean): ChromiaPostchainClient {
        val client = directoryChainClient.reconfigure(directoryChainClient.config.copy(
                requestStrategy = requestStrategy,
        ))
        return ChromiaPostchainClient(directoryChainClient = directoryChainClient, txClient = client, queryClient = client, addNop)
    }

    override fun getDirectoryChainClientForQueryReplica(queryNodes: EndpointPool, requestStrategy: RequestStrategyFactory, addNop: Boolean): ChromiaPostchainClient {
        val txClient = directoryChainClient.reconfigure(directoryChainClient.config.copy(
                requestStrategy = requestStrategy,
        ))
        val queryClient = directoryChainClient.reconfigure(config.copy(
                blockchainRid = directoryChainRid,
                endpointPool = queryNodes,
                requestStrategy = requestStrategy,
        ))
        return ChromiaPostchainClient(directoryChainClient = directoryChainClient, txClient = txClient, queryClient = queryClient, addNop)
    }

    override fun getDirectoryChainClientForForwardingReplica(nodes: EndpointPool, requestStrategy: RequestStrategyFactory, addNop: Boolean): ChromiaPostchainClient {
        val client = directoryChainClient.reconfigure(config.copy(
                blockchainRid = directoryChainRid,
                endpointPool = nodes,
                requestStrategy = requestStrategy,
        ))
        return ChromiaPostchainClient(directoryChainClient = directoryChainClient, txClient = client, queryClient = client, addNop)
    }

    override fun getSystemChainClient(blockchainRid: BlockchainRid, requestStrategy: RequestStrategyFactory, addNop: Boolean): ChromiaPostchainClient {
        val client = directoryChainClient.reconfigure(directoryChainClient.config.copy(
                blockchainRid = blockchainRid,
                requestStrategy = requestStrategy,
        ))
        return ChromiaPostchainClient(directoryChainClient = directoryChainClient, txClient = client, queryClient = client, addNop)
    }

    override fun getSystemChainClientForQueryReplica(blockchainRid: BlockchainRid, queryNodes: EndpointPool, requestStrategy: RequestStrategyFactory, addNop: Boolean): ChromiaPostchainClient {
        val txClient = directoryChainClient.reconfigure(directoryChainClient.config.copy(
                blockchainRid = blockchainRid,
                requestStrategy = requestStrategy,
        ))
        val queryClient = directoryChainClient.reconfigure(config.copy(
                blockchainRid = blockchainRid,
                endpointPool = queryNodes,
                requestStrategy = requestStrategy,
        ))
        return ChromiaPostchainClient(directoryChainClient = directoryChainClient, txClient = txClient, queryClient = queryClient, addNop)
    }

    override fun getSystemChainClientForForwardingReplica(blockchainRid: BlockchainRid, nodes: EndpointPool, requestStrategy: RequestStrategyFactory, addNop: Boolean): ChromiaPostchainClient {
        val client = directoryChainClient.reconfigure(config.copy(
                blockchainRid = blockchainRid,
                endpointPool = nodes,
                requestStrategy = requestStrategy,
        ))
        return ChromiaPostchainClient(directoryChainClient = directoryChainClient, txClient = client, queryClient = client, addNop)
    }

    override fun getClient(blockchainRid: BlockchainRid, requestStrategy: RequestStrategyFactory, addNop: Boolean): ChromiaPostchainClient {
        val signerNodes = getSignerNodes(blockchainRid)

        val client = directoryChainClient.reconfigure(config.copy(
                blockchainRid = blockchainRid,
                endpointPool = signerNodes,
                requestStrategy = requestStrategy,
        ))
        return ChromiaPostchainClient(directoryChainClient = directoryChainClient, txClient = client, queryClient = client, addNop)
    }

    override fun getClientForQueryReplica(blockchainRid: BlockchainRid, queryNodes: EndpointPool, requestStrategy: RequestStrategyFactory, addNop: Boolean): ChromiaPostchainClient {
        val signerNodes = getSignerNodes(blockchainRid)

        val txClient = directoryChainClient.reconfigure(config.copy(
                blockchainRid = blockchainRid,
                endpointPool = signerNodes,
                requestStrategy = requestStrategy,
        ))
        val queryClient = directoryChainClient.reconfigure(config.copy(
                blockchainRid = blockchainRid,
                endpointPool = queryNodes,
                requestStrategy = requestStrategy,
        ))
        return ChromiaPostchainClient(directoryChainClient = directoryChainClient, txClient = txClient, queryClient = queryClient, addNop)
    }

    override fun getClientForForwardingReplica(blockchainRid: BlockchainRid, nodes: EndpointPool, requestStrategy: RequestStrategyFactory, addNop: Boolean): ChromiaPostchainClient {
        val client = directoryChainClient.reconfigure(config.copy(
                blockchainRid = blockchainRid,
                endpointPool = nodes,
                requestStrategy = requestStrategy,
        ))
        return ChromiaPostchainClient(directoryChainClient = directoryChainClient, txClient = client, queryClient = client, addNop)
    }

    private fun getSignerNodes(blockchainRid: BlockchainRid): EndpointPool {
        val apiUrls = directoryChainClient.cmGetBlockchainApiUrls(blockchainRid)
        if (apiUrls.isEmpty()) {
            throw ClientError("chromia", null, "No signer nodes for blockchain $blockchainRid", null)
        }
        return EndpointPool.default(apiUrls)
    }

    override fun addIccfProof(
            transactionBuilder: TransactionBuilder,
            txToProveRID: TxRid,
            sourceBlockchainRid: BlockchainRid,
            forceIntraNetworkIccfOperation: Boolean,
    ): Gtv = IccfBuilder(this).addIccfProof(transactionBuilder, txToProveRID, null, sourceBlockchainRid, forceIntraNetworkIccfOperation)

    override fun addIccfProof(
            transactionBuilder: TransactionBuilder,
            txToProveRID: TxRid,
            txToProveHash: Hash,
            sourceBlockchainRid: BlockchainRid,
            forceIntraNetworkIccfOperation: Boolean,
    ): Gtv =  IccfBuilder(this).addIccfProof(transactionBuilder, txToProveRID, txToProveHash, sourceBlockchainRid, forceIntraNetworkIccfOperation)
}

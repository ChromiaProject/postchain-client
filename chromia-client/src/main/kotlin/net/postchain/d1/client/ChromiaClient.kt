package net.postchain.d1.client

import net.postchain.client.core.PostchainClient
import net.postchain.client.core.TxRid
import net.postchain.client.impl.TryNextOnErrorRequestStrategyFactory
import net.postchain.client.request.RequestStrategyFactory
import net.postchain.common.BlockchainRid
import java.net.URI
import java.time.Duration

interface ChromiaClient {

    /** Chromia configuration used to configure new subsequent created postchain clients */
    val config: ChromiaClientConfig

    fun isTxClusterAnchored(blockchainRid: BlockchainRid, txId: TxRid): Boolean

    fun isBlockClusterAnchored(blockchainRid: BlockchainRid, blockRid: ByteArray): Boolean

    /** Block until the transaction is either anchored or the timeout is reached and an exception is thrown. */
    fun awaitClusterAnchoredTx(
            blockchainRid: BlockchainRid,
            txId: TxRid,
            retries: Int = config.statusPollCount,
            pollInterval: Duration = config.statusPollInterval,
    )

    /** Create a postchain client for the chain anchoring the given dapp chain */
    fun getClusterAnchoringPostchainClient(dappBlockchainRid: BlockchainRid): PostchainClient

    /** Create a postchain client for the chain anchoring the given cluster */
    fun getClusterAnchoringPostchainClient(cluster: String): PostchainClient

    /**
     * Get a postchain client for the specified blockchain and based on the implementation configuration.
     *
     * @param blockchainRid  the RID of the blockchain
     * @param requestStrategy  request strategy to use
     */
    fun getPostchainClient(
            blockchainRid: BlockchainRid,
            requestStrategy: RequestStrategyFactory = TryNextOnErrorRequestStrategyFactory(),
    ): PostchainClient

    /**
     * Get a postchain client for the specified blockchain and based on the implementation configuration.
     *
     * Queries will be sent to the specified node(s), transactions will be sent to signer nodes.
     *
     * @param blockchainRid  the RID of the blockchain
     * @param queryNodes  node(s) to query
     * @param requestStrategy  request strategy to use
     */
    fun getPostchainClientForQueryReplica(
            blockchainRid: BlockchainRid,
            queryNodes: List<URI>,
            requestStrategy: RequestStrategyFactory = TryNextOnErrorRequestStrategyFactory(),
    ): PostchainClient

    /**
     * Get a postchain client for the specified blockchain and based on the implementation configuration.
     *
     * Both queries and transactions will be sent to the specified node(s).
     *
     * @param blockchainRid  the RID of the blockchain
     * @param nodes  node(s) to use
     * @param requestStrategy  request strategy to use
     */
    fun getPostchainClientForFullReplica(
            blockchainRid: BlockchainRid,
            nodes: List<URI>,
            requestStrategy: RequestStrategyFactory = TryNextOnErrorRequestStrategyFactory(),
    ): PostchainClient
}

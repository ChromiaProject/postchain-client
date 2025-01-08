package net.postchain.d1.client

import net.postchain.client.core.PostchainClient
import net.postchain.client.core.TxRid
import net.postchain.client.impl.TryNextOnErrorRequestStrategyFactory
import net.postchain.client.request.RequestStrategyFactory
import net.postchain.common.BlockchainRid
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

    /** Get a postchain client for the specific blockchainRid and based on the implementation configuration. */
    fun getPostchainClient(blockchainRid: BlockchainRid, requestStrategy: RequestStrategyFactory = TryNextOnErrorRequestStrategyFactory()): PostchainClient
}

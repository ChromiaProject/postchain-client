package net.postchain.d1.client

import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.TxRid
import net.postchain.client.exception.ClientError
import net.postchain.client.request.EndpointPool
import net.postchain.client.request.RequestStrategyFactory
import net.postchain.client.transaction.TransactionBuilder
import net.postchain.common.BlockchainRid
import net.postchain.common.data.Hash
import net.postchain.gtv.Gtv
import java.time.Duration

interface ChromiaClient {

    /** Configuration used to configure new subsequently created postchain clients */
    val config: PostchainClientConfig

    /** Directory chain blockchain RID, as specified or looked up. */
    val directoryChainRid: BlockchainRid

    fun isTxClusterAnchored(blockchainRid: BlockchainRid, txRid: TxRid): Boolean

    fun isBlockClusterAnchored(blockchainRid: BlockchainRid, blockRid: ByteArray): Boolean

    /** Block until the transaction is either anchored or the timeout is reached and an exception is thrown. */
    fun awaitClusterAnchoredTx(
            blockchainRid: BlockchainRid,
            txRid: TxRid,
            retries: Int = config.statusPollCount,
            pollInterval: Duration = config.statusPollInterval,
    )

    /** Create a postchain client for the chain anchoring the given dapp chain */
    fun getClusterAnchoringClient(dappBlockchainRid: BlockchainRid): ChromiaPostchainClient

    /** Create a postchain client for the chain anchoring the given cluster */
    fun getClusterAnchoringClient(cluster: String): ChromiaPostchainClient

    fun isTxSystemAnchored(blockchainRid: BlockchainRid, txRid: TxRid): Boolean

    fun isBlockSystemAnchored(blockchainRid: BlockchainRid, blockRid: ByteArray): Boolean

    /** Block until the transaction is either system anchored or the timeout is reached and an exception is thrown. */
    fun awaitSystemAnchoredTx(
            blockchainRid: BlockchainRid,
            txRid: TxRid,
            retries: Int = config.statusPollCount,
            pollInterval: Duration = config.statusPollInterval,
    )

    /** Create a postchain client for the system anchoring chain */
    fun getSystemAnchoringClient(): ChromiaPostchainClient

    /**
     * Create a postchain client for the directory chain.
     *
     * Both queries and transactions will be sent to the signer nodes in the system cluster.
     *
     * @param requestStrategy  request strategy to use
     * @param addNop add a no-op to each transaction builder
     */
    fun getDirectoryChainClient(
            requestStrategy: RequestStrategyFactory = config.requestStrategy,
            addNop: Boolean = false,
    ): ChromiaPostchainClient

    /**
     * Get a postchain client for the directory chain.
     *
     * Queries will be sent to the specified node(s),
     * transactions and transaction status requests will be sent to signer nodes in the system cluster.
     *
     * @param queryNodes  node(s) to query
     * @param requestStrategy  request strategy to use
     * @param addNop add a no-op to each transaction builder
     */
    fun getDirectoryChainClientForQueryReplica(
            queryNodes: EndpointPool = config.endpointPool,
            requestStrategy: RequestStrategyFactory = config.requestStrategy,
            addNop: Boolean = false,
    ): ChromiaPostchainClient

    /**
     * Get a postchain client for the directory chain.
     *
     * Both queries and transactions will be sent to the specified node(s).
     * Useful for replica nodes started with `forwarding_replica=true` in node configuration.
     *
     * @param nodes  node(s) to use
     * @param requestStrategy  request strategy to use
     * @param addNop add a no-op to each transaction builder
     */
    fun getDirectoryChainClientForForwardingReplica(
            nodes: EndpointPool = config.endpointPool,
            requestStrategy: RequestStrategyFactory = config.requestStrategy,
            addNop: Boolean = false,
    ): ChromiaPostchainClient

    /**
     * Get a postchain client for the specified system blockchain and based on the implementation configuration.
     *
     * Both queries and transactions will be sent to the signer nodes in the system cluster.
     *
     * @param blockchainRid  the RID of the blockchain
     * @param requestStrategy  request strategy to use
     * @param addNop add a no-op to each transaction builder
     */
    fun getSystemChainClient(
            blockchainRid: BlockchainRid,
            requestStrategy: RequestStrategyFactory = config.requestStrategy,
            addNop: Boolean = false,
    ): ChromiaPostchainClient

    /**
     * Get a postchain client for the specified system blockchain and based on the implementation configuration.
     *
     * Queries will be sent to the specified node(s),
     * transactions and transaction status requests will be sent to the signer nodes in the system cluster.
     *
     * @param blockchainRid  the RID of the blockchain
     * @param queryNodes  node(s) to query
     * @param requestStrategy  request strategy to use
     * @param addNop add a no-op to each transaction builder
     */
    fun getSystemChainClientForQueryReplica(
            blockchainRid: BlockchainRid,
            queryNodes: EndpointPool = config.endpointPool,
            requestStrategy: RequestStrategyFactory = config.requestStrategy,
            addNop: Boolean = false,
    ): ChromiaPostchainClient

    /**
     * Get a postchain client for the specified system blockchain and based on the implementation configuration.
     *
     * Both queries and transactions will be sent to the specified node(s).
     * Useful for replica nodes started with `forwarding_replica=true` in node configuration.
     *
     * @param blockchainRid  the RID of the blockchain
     * @param nodes  node(s) to use
     * @param requestStrategy  request strategy to use
     * @param addNop add a no-op to each transaction builder
     */
    fun getSystemChainClientForForwardingReplica(
            blockchainRid: BlockchainRid,
            nodes: EndpointPool = config.endpointPool,
            requestStrategy: RequestStrategyFactory = config.requestStrategy,
            addNop: Boolean = false,
    ): ChromiaPostchainClient

    /**
     * Get a postchain client for the specified blockchain and based on the implementation configuration.
     *
     * Both queries and transactions will be sent to the signer nodes.
     *
     * @param blockchainRid  the RID of the blockchain
     * @param requestStrategy  request strategy to use
     * @param addNop add a no-op to each transaction builder
     */
    fun getClient(
            blockchainRid: BlockchainRid,
            requestStrategy: RequestStrategyFactory = config.requestStrategy,
            addNop: Boolean = false,
    ): ChromiaPostchainClient

    /**
     * Get a postchain client for the specified blockchain and based on the implementation configuration.
     *
     * Queries will be sent to the specified node(s),
     * transactions and transaction status requests will be sent to signer nodes.
     *
     * @param blockchainRid  the RID of the blockchain
     * @param queryNodes  node(s) to query
     * @param requestStrategy  request strategy to use
     * @param addNop add a no-op to each transaction builder
     */
    fun getClientForQueryReplica(
            blockchainRid: BlockchainRid,
            queryNodes: EndpointPool = config.endpointPool,
            requestStrategy: RequestStrategyFactory = config.requestStrategy,
            addNop: Boolean = false,
    ): ChromiaPostchainClient

    /**
     * Get a postchain client for the specified blockchain and based on the implementation configuration.
     *
     * Both queries and transactions will be sent to the specified node(s).
     * Useful for replica nodes started with `forwarding_replica=true` in node configuration.
     *
     * @param blockchainRid  the RID of the blockchain
     * @param nodes  node(s) to use
     * @param requestStrategy  request strategy to use
     * @param addNop add a no-op to each transaction builder
     */
    fun getClientForForwardingReplica(
            blockchainRid: BlockchainRid,
            nodes: EndpointPool = config.endpointPool,
            requestStrategy: RequestStrategyFactory = config.requestStrategy,
            addNop: Boolean = false,
    ): ChromiaPostchainClient


    /**
     * Adds an ICCF proof to the given transaction builder.
     *
     * @param transactionBuilder The transaction builder to which the ICCF proof should be added.
     * @param txToProveRID The transaction RID of the transaction to be proved.
     * @param sourceBlockchainRid The RID of the source blockchain where the transaction to be proved resides.
     * @param forceIntraNetworkIccfOperation A flag to indicate if the operation should be forced as intra-network; defaults to false.
     * @return The proven transaction
     * @throws ClientError If the transaction proof cannot be verified due to mismatched hashes or other validation failures.
     */
    fun addIccfProof(
            transactionBuilder: TransactionBuilder,
            txToProveRID: TxRid,
            sourceBlockchainRid: BlockchainRid,
            forceIntraNetworkIccfOperation: Boolean = false,
    ): Gtv

    /**
     * Adds an ICCF proof to the given transaction builder.
     *
     * @param transactionBuilder The transaction builder to which the ICCF proof should be added.
     * @param txToProveRID The transaction RID of the transaction to be proved.
     * @param txToProveHash The transaction hash of the transaction to be proved.
     * @param sourceBlockchainRid The RID of the source blockchain where the transaction to be proved resides.
     * @param forceIntraNetworkIccfOperation A flag to indicate if the operation should be forced as intra-network; defaults to false.
     * @return The proven transaction
     * @throws ClientError If the transaction proof cannot be verified due to mismatched hashes or other validation failures.
     */
    fun addIccfProof(
            transactionBuilder: TransactionBuilder,
            txToProveRID: TxRid,
            txToProveHash: Hash,
            sourceBlockchainRid: BlockchainRid,
            forceIntraNetworkIccfOperation: Boolean = false,
    ): Gtv
}

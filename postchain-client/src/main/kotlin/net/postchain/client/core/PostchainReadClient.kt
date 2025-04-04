package net.postchain.client.core

import net.postchain.common.BlockchainRid
import net.postchain.common.rest.HighestBlockHeightAnchoringCheck
import net.postchain.gtv.Gtv

interface PostchainReadClient : PostchainBlockClient, PostchainQuery {
    /**
     * Query current block height.
     */
    fun currentBlockHeight(container: String? = null): Long

    /**
     * Get confirmation proof for transaction.
     */
    fun confirmationProof(txRid: TxRid): ByteArray

    /**
     * Get raw transaction data
     */
    fun getTransaction(txRid: TxRid): ByteArray

    /**
     * Get information about a transaction
     */
    fun getTransactionInfo(txRid: TxRid): TransactionInfo

    /**
     * Get information about all transactions
     *
     * @param limit optional limit
     * @param beforeTime optional before time
     * @param signer optional signer
     * @return list of transaction infos
     */
    fun getTransactionsInfo(limit: Long = -1, beforeTime: Long = -1, signer: String? = null): List<TransactionInfo>

    /**
     * Get number of transactions
     */
    fun getTransactionsCount(): Long

    /**
     * Get blockchain RID by chain IID. Please note that chain IID is the internal ID of a chain and might vary between
     * nodes and is not recommended to use in production.
     */
    fun getBlockchainRID(chainIID: Long): BlockchainRid

    /**
     * Fetch blockchain configuration.
     *
     * @param height  block height to fetch configuration for, or null for current/latest configuration
     */
    fun getConfiguration(height: Long? = null): Gtv

    /**
     * Query block by RID.
     */
    fun blockByRid(blockRid: BlockRid): BlockDetail?

    /**
     * Retrieves the features of a blockchain.
     */
    fun getFeatures(): Map<String, Gtv>

    /**
     * Fetch a list of all waiting transaction RIDs.
     */
    fun getWaitingTransactions(): List<TxRid>

    /**
     * Get node API version.
     */
    fun getVersion(): Version

    /**
     * Check if the highest block of a blockchain matches the block anchored in CAC, SAC and EVM.
     */
    fun getHighestBlockHeightAnchoringCheck(): HighestBlockHeightAnchoringCheck
}

package net.postchain.client.core

import net.postchain.client.impl.PostchainClientImpl
import net.postchain.common.BlockchainRid
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
     * Retrieves the features of a blockchain identified by the specified blockchain RID in hexadecimal format.
     *
     * @param blockchainRIDHex The blockchain RID represented as a hexadecimal string.
     * @return An object of type [PostchainClientImpl.BlockchainFeatures] that contains the features of the blockchain.
     */
    fun getFeatures(blockchainRIDHex: String): PostchainClientImpl.BlockchainFeatures
}

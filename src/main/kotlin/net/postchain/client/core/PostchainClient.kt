package net.postchain.client.core

import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.exception.ClientError
import net.postchain.client.transaction.TransactionBuilder
import net.postchain.common.BlockchainRid
import net.postchain.common.rest.HighestBlockHeightAnchoringCheck
import net.postchain.crypto.KeyPair
import net.postchain.crypto.PubKey
import net.postchain.gtv.Gtv
import net.postchain.gtx.Gtx
import java.io.Closeable
import java.time.Duration

interface PostchainClient : PostchainBlockClient, PostchainQuery, Closeable {
    val config: PostchainClientConfig

    /**
     * Creates a [TransactionBuilder] with the default signer list
     */
    fun transactionBuilder(): TransactionBuilder

    /**
     * Creates a [TransactionBuilder] with a given list of signers
     */
    fun transactionBuilder(signers: List<KeyPair>): TransactionBuilder

    fun transactionBuilder(initialSigners: List<KeyPair>, allSigners: List<PubKey>): TransactionBuilder


    /**
     * Post a [Gtx] transaction.
     */
    fun postTransaction(tx: Gtx): TransactionResult

    /**
     * Post a [Gtx] transaction and wait until it is included in a block
     */
    fun postTransactionAwaitConfirmation(tx: Gtx): TransactionResult

    /**
     * Wait until the given [TxRid] is included in a block
     */
    fun awaitConfirmation(txRid: TxRid, retries: Int, pollInterval: Duration): TransactionResult

    /**
     * Check the current status of a [TxRid]
     */
    fun checkTxStatus(txRid: TxRid): TransactionResult

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
     * Validates that the supplied blockchain configuration is compatible with the running blockchain configuration.
     * @param configuration blockchain configuration to verify
     * @throws ClientError
     */
    fun validateConfiguration(configuration: Gtv)

    /**
     * Get node API version.
     */
    fun getVersion(): Version

    /**
     * Check if the highest block of a blockchain matches the block anchored in CAC, SAC and EVM.
     */
    fun getHighestBlockHeightAnchoringCheck(): HighestBlockHeightAnchoringCheck

    /**
     * Query block by RID.
     */
    fun blockByRid(blockRid: BlockRid): BlockDetail?
}
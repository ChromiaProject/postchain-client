package net.postchain.client.core

import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.exception.ClientError
import net.postchain.client.transaction.TransactionBuilder
import net.postchain.common.rest.HighestBlockHeightAnchoringCheck
import net.postchain.crypto.KeyPair
import net.postchain.crypto.PubKey
import net.postchain.gtv.Gtv
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorBase
import net.postchain.gtx.Gtx
import java.io.Closeable
import java.time.Duration

interface PostchainClient : PostchainReadClient, PostchainBlockClient, PostchainQuery, Closeable {
    val config: PostchainClientConfig
    val merkleHashCalculator: GtvMerkleHashCalculatorBase

    /**
     * Creates a [TransactionBuilder] with the default signer list
     */
    fun transactionBuilder(): TransactionBuilder

    /**
     * Creates a [TransactionBuilder] with a given list of signers
     */
    fun transactionBuilder(signers: List<KeyPair>): TransactionBuilder

    /**
     * Creates a [TransactionBuilder] that supports multi-sign by being partially signed by
     * an initial group of signers and later be completely signed by the remaining required signers.
     *
     * @param initialSigners A list of key pairs representing the signers who will initiate the transaction.
     * @param remainingRequiredSigners A list of public keys representing the remaining signers who need to sign the transaction.
     */
    fun transactionBuilder(initialSigners: List<KeyPair>, remainingRequiredSigners: List<PubKey>): TransactionBuilder

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

}
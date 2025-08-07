package net.postchain.client.core

/**
 * Transaction event that is emitted during transaction processing.
 * These events are used to track the status and lifecycle of transactions in the blockchain.
 *
 * @property txRid The unique identifier of the transaction this event is associated with
 */
interface TxEvent {
    val txRid: TxRid
}

/**
 * Emitted before the transaction is posted.
 */
data class PostingTransaction(override val txRid: TxRid) : TxEvent

/**
 * Emitted when the transaction has been successfully posted.
 */
data class TransactionPostedSuccessfully(override val txRid: TxRid) : TxEvent

/**
 * Emitted when the transaction was rejected during posting.
 *
 * @property rejectReason The reason for rejecting the transaction
 */
data class TransactionPostedRejected(override val txRid: TxRid, val rejectReason: String) : TxEvent

/**
 * Emitted before polling for transaction status, can be emitted multiple times for one transaction.
 */
data class PollingTransactionStatus(override val txRid: TxRid) : TxEvent

/**
 * Emitted when the transaction was rejected during polling.
 *
 * @property rejectReason The reason for rejecting the transaction
 */
data class TransactionPollingRejected(override val txRid: TxRid, val rejectReason: String) : TxEvent

/**
 * Emitted when the transaction has been confirmed.
 */
data class TransactionConfirmed(override val txRid: TxRid) : TxEvent

/**
 * Emitted when polling for the transaction timed out.
 */
data class TransactionPollingTimeout(override val txRid: TxRid) : TxEvent

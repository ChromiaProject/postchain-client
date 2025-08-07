package net.postchain.client.core

/**
 * Listener interface for transaction-related events in the Postchain client.
 * Implementations can be used to monitor and react to various transaction states
 * during transaction processing and confirmation.
 *
 * Implementations of this interface should be prepared to receive unknown events, since new event types might be
 * added over time.
 */
fun interface TxEventListener {
    /**
     * Called when a transaction event occurs.
     *
     * @param event The transaction event containing information about the transaction's state
     */
    fun onTxEvent(event: TxEvent)
}

/**
 * A no-operation implementation of [TxEventListener] that ignores all transaction events.
 * This can be used as a default or placeholder listener when event handling is not required.
 */
object NullTxEventListener : TxEventListener {
    /**
     * Does nothing with the received transaction event.
     *
     * @param event The transaction event to be ignored
     */
    override fun onTxEvent(event: TxEvent) {}
}

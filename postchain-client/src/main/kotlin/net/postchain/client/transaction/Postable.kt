package net.postchain.client.transaction

import net.postchain.client.core.TransactionResult
import net.postchain.client.core.TxEventListener

interface Postable {
    fun post(): TransactionResult
    fun postAwaitConfirmation(): TransactionResult
    fun postAwaitConfirmation(listener: TxEventListener): TransactionResult
}

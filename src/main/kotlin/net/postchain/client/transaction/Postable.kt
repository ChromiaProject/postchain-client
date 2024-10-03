package net.postchain.client.transaction

import net.postchain.client.core.TransactionResult

interface Postable {
    fun post(): TransactionResult
    fun postAwaitConfirmation(): TransactionResult
    fun postPartialTransaction(signatureBuilder: TransactionBuilder.SignatureBuilder): TransactionResult
    fun postPartialTransactionAwaitConfirmation(signatureBuilder: TransactionBuilder.SignatureBuilder): TransactionResult
}

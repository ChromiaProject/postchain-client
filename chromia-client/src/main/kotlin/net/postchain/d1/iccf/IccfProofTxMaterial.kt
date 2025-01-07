package net.postchain.d1.iccf

import net.postchain.client.transaction.TransactionBuilder
import net.postchain.gtx.Gtx

/**
 * @param txBuilder A transaction builder including an ICCF proof operation for the transaction
 * @param updatedTx Will be set to the actual transaction that was included into a block if it does not match the
 * transaction that the user expected.
 */
class IccfProofTxMaterial(
        val txBuilder: TransactionBuilder,
        val updatedTx: Gtx?
)

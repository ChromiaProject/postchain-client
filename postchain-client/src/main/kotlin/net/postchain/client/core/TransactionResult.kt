// Copyright (c) 2020 ChromaWay AB. See README for license information.

package net.postchain.client.core

import net.postchain.common.tx.TransactionStatus

/**
 * Acknowledge from the server. Holds the status of the TX.
 *
 * @property txRid The unique identifier of the transaction
 * @property status The final status of the transaction
 * @property httpStatusCode the HTTP status code returned from the last request made, or `null` of another error occurred
 * @property rejectReason The reason for rejecting the transaction
 */
data class TransactionResult(
        val txRid: TxRid,
        val status: TransactionStatus,
        val httpStatusCode: Int?,
        val rejectReason: String?
)

package net.postchain.client.core

import net.postchain.common.hexStringToWrappedByteArray
import net.postchain.common.types.WrappedByteArray

/**
 * @param timestamp  milliseconds since epoch
 */
data class TransactionInfo(
        val blockRID: WrappedByteArray,
        val blockHeight: Long,
        val blockHeader: WrappedByteArray,
        val witness: WrappedByteArray,
        val timestamp: Long,
        val txRID: WrappedByteArray,
        val txHash: WrappedByteArray,
        val txData: WrappedByteArray
) {

    companion object {
        fun fromJson(json: Json): TransactionInfo {
            return TransactionInfo(
                    json.blockRID.hexStringToWrappedByteArray(),
                    json.blockHeight,
                    json.blockHeader.hexStringToWrappedByteArray(),
                    json.witness.hexStringToWrappedByteArray(),
                    json.timestamp,
                    json.txRID.hexStringToWrappedByteArray(),
                    json.txHash.hexStringToWrappedByteArray(),
                    json.txData.hexStringToWrappedByteArray()
            )
        }
    }

    data class Json(val blockRID: String, val blockHeight: Long, val blockHeader: String, val witness: String,
                    val timestamp: Long, val txRID: String, val txHash: String, val txData: String)
}

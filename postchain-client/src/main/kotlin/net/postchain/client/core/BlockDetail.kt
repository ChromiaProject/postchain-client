package net.postchain.client.core

import net.postchain.common.types.WrappedByteArray
import net.postchain.gtv.mapper.Name

/**
 * @param timestamp  milliseconds since epoch
 */
data class BlockDetail(
        @param:Name("rid") val rid: WrappedByteArray,
        @param:Name("prevBlockRID") val prevBlockRID: WrappedByteArray,
        @param:Name("header") val header: WrappedByteArray,
        @param:Name("height") val height: Long,
        @param:Name("transactions") val transactions: List<TxDetail>,
        @param:Name("witness") val witness: WrappedByteArray,
        @param:Name("timestamp") val timestamp: Long
)

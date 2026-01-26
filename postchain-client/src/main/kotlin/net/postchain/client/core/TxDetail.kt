package net.postchain.client.core

import net.postchain.common.types.WrappedByteArray
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.Nullable

data class TxDetail(
        @param:Name("rid") val rid: WrappedByteArray,
        @param:Name("hash") val hash: WrappedByteArray,
        @param:Name("data") @param:Nullable val data: WrappedByteArray?
)

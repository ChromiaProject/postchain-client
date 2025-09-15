package net.postchain.client.core

import net.postchain.crypto.Signature
import net.postchain.gtv.Gtv

data class QueryResponse(
        val height: Long,
        val response: Gtv,
        val signature: Signature,
)

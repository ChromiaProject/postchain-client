package net.postchain.client.core

import net.postchain.gtv.Gtv

data class QueryResponseSignatureData(
        val name: String,
        val args: Gtv,
        val height: Long,
        val response: Gtv,
)

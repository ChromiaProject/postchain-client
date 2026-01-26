package net.postchain.client.core

import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.Name

enum class AsyncQueryResponseStatus {
    NOT_FOUND,
    PENDING,
    COMPLETED,
    FAILED,
}

data class AsyncQueryResponse(
        @param:Name("status") val status: AsyncQueryResponseStatus,
        @param:Name("response") val queryResponse: Gtv,
        @param:Name("error") val errorMessage: String?,
)

package com.chromia.ft4

import com.chromia.lib.ft4.external.assets.getAssetBalance
import net.postchain.client.core.PostchainQuery
import java.math.BigInteger

/**
 * Retrieves the balance of a specific asset for a given account.
 *
 * @param client the PostchainQuery instance used to perform the query
 * @param accountId the unique identifier of the account to query the balance for
 * @param assetId the unique identifier of the asset for which the balance is requested
 *
 * @return the balance of the asset as a BigInteger in the smallest denomination of the asset
 */
fun getAssetBalance(client: PostchainQuery, accountId: ByteArray, assetId: ByteArray): BigInteger {
    val response = client.getAssetBalance(accountId, assetId)
    return response?.amount ?: BigInteger.ZERO
}

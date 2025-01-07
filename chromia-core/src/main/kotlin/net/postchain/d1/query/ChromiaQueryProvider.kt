package net.postchain.d1.query

import net.postchain.client.core.PostchainQuery
import net.postchain.client.core.PostchainBlockClient
import net.postchain.common.BlockchainRid

interface ChromiaQueryProvider {
    fun getChain0Query(): PostchainQuery
    fun getSystemAnchoringQuery(): PostchainBlockClient?
    fun getClusterAnchoringQuery(): PostchainBlockClient?
    fun getQuery(targetBlockchainRid: BlockchainRid): PostchainBlockClient?
}

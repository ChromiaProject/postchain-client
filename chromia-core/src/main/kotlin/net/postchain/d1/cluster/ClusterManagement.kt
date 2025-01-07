package net.postchain.d1.cluster

import net.postchain.common.BlockchainRid
import net.postchain.crypto.PubKey

interface ClusterManagement {
    fun getClusterNames(): Collection<String>
    fun getClusterInfo(clusterName: String): D1ClusterInfo
    fun getBlockchainApiUrls(blockchainRid: BlockchainRid): Collection<String>
    fun getBlockchainPeers(blockchainRid: BlockchainRid, height: Long): Collection<PubKey>
    fun getActiveBlockchains(clusterName: String): Collection<BlockchainRid>
    fun getClusterOfBlockchain(blockchainRid: BlockchainRid): String
    fun getClusterAnchoringChains(): Collection<BlockchainRid>
    fun getSystemAnchoringChain(): BlockchainRid?
    fun getRemovedClusterBlockchains(clusterName: String, removedAfter: Long): Collection<BlockchainRid>
    fun getRemovedClusterAnhcoringChains(removedAfter: Long): Collection<BlockchainRid>
}

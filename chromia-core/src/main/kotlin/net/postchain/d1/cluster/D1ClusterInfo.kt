package net.postchain.d1.cluster

import net.postchain.common.BlockchainRid

data class D1ClusterInfo(
    val name: String,
    val anchoringChain: BlockchainRid,
    val peers: Collection<D1PeerInfo>
)

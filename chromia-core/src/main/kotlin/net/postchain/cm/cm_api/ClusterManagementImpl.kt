package net.postchain.cm.cm_api

import net.postchain.chromia.cm_api.cmApiVersion
import net.postchain.chromia.cm_api.cmGetBlockchainApiUrls
import net.postchain.chromia.cm_api.cmGetBlockchainCluster
import net.postchain.chromia.cm_api.cmGetClusterAnchoringChains
import net.postchain.chromia.cm_api.cmGetClusterBlockchains
import net.postchain.chromia.cm_api.cmGetClusterInfo
import net.postchain.chromia.cm_api.cmGetClusterNames
import net.postchain.chromia.cm_api.cmGetPeerInfo
import net.postchain.chromia.cm_api.cmGetRemovedClusterAnchoringChains
import net.postchain.chromia.cm_api.cmGetRemovedClusterBlockchains
import net.postchain.chromia.cm_api.cmGetSystemAnchoringChain
import net.postchain.chromia.version.apiVersion
import net.postchain.client.core.PostchainQuery
import net.postchain.common.BlockchainRid
import net.postchain.crypto.PubKey
import net.postchain.d1.cluster.ClusterManagement
import net.postchain.d1.cluster.D1ClusterInfo
import net.postchain.d1.cluster.D1PeerInfo

class ClusterManagementImpl(private val query: PostchainQuery) : ClusterManagement {

    private val cmApiVersion by lazy {
        // CM API version was introduced in version 58 of directory chain
        if (query.apiVersion() >= 58) {
            query.cmApiVersion()
        } else 1L
    }

    override fun getClusterNames(): Collection<String> = query.cmGetClusterNames()

    override fun getClusterInfo(clusterName: String): D1ClusterInfo = query.cmGetClusterInfo(clusterName)
            .let {
                D1ClusterInfo(
                        it.name,
                        BlockchainRid(it.anchoringChain),
                        it.peers.map { peer -> D1PeerInfo(peer.apiUrl, PubKey(peer.pubkey)) })
            }

    override fun getBlockchainApiUrls(blockchainRid: BlockchainRid): Collection<String> =
            query.cmGetBlockchainApiUrls(blockchainRid)

    override fun getBlockchainPeers(blockchainRid: BlockchainRid, height: Long): Collection<PubKey> =
            query.cmGetPeerInfo(blockchainRid.data, height).map { PubKey(it) }

    override fun getActiveBlockchains(clusterName: String): Collection<BlockchainRid> =
            query.cmGetClusterBlockchains(clusterName).map { BlockchainRid(it) }

    override fun getClusterOfBlockchain(blockchainRid: BlockchainRid): String =
            query.cmGetBlockchainCluster(blockchainRid.data)

    override fun getClusterAnchoringChains(): Collection<BlockchainRid> =
            query.cmGetClusterAnchoringChains().map { BlockchainRid(it) }

    override fun getSystemAnchoringChain(): BlockchainRid? =
            query.cmGetSystemAnchoringChain()?.let { BlockchainRid(it) }

    override fun getRemovedClusterBlockchains(clusterName: String, removedAfter: Long): Collection<BlockchainRid> {
        return if (cmApiVersion >= 2) {
            query.cmGetRemovedClusterBlockchains(clusterName, removedAfter).map { BlockchainRid(it) }
        } else listOf()
    }

    override fun getRemovedClusterAnhcoringChains(removedAfter: Long): Collection<BlockchainRid> {
        return if (cmApiVersion >= 2) {
            query.cmGetRemovedClusterAnchoringChains(removedAfter).map { BlockchainRid(it) }
        } else listOf()
    }
}

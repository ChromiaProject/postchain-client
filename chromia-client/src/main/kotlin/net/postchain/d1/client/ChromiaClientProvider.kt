package net.postchain.d1.client

import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.PostchainClient
import net.postchain.client.impl.PostchainClientImpl
import net.postchain.client.request.EndpointPool
import net.postchain.cm.cm_api.ClusterManagementImpl
import net.postchain.common.BlockchainRid
import net.postchain.d1.cluster.ClusterManagement

/**
 * Provides postchain clients that can be used to communicate with dapps within the chromia network
 *
 * @param clusterManagement Cluster management api
 * @param configTemplate Template for create client configurations
 */
open class ChromiaClientProvider(
        val clusterManagement: ClusterManagement,
        private val configTemplate: PostchainClientConfig = PostchainClientConfig(BlockchainRid.ZERO_RID, EndpointPool.singleUrl("")),
) {
    val cryptoSystem get() = configTemplate.cryptoSystem

    /**
     * Gets the names of all clusters in the network
     */
    open fun clusters(): Collection<String> = clusterManagement.getClusterNames()

    /**
     * Creates a [ClusterPostchainClient] that points to a cluster
     *
     * @param clusterName name of the cluster
     */
    open fun cluster(clusterName: String): ClusterPostchainClient {
        val apiUrls = clusterManagement.getClusterInfo(clusterName).peers.map { it.restApiUrl }
        return ClusterPostchainClient(EndpointPool.default(apiUrls))
    }

    /**
     * Creates a [PostchainClient] that communicates with a certain blockchain
     *
     * @param blockchainRid blockchain rid for the blockchain
     */
    open fun blockchain(blockchainRid: BlockchainRid): PostchainClient {
        val apiUrls = clusterManagement.getBlockchainApiUrls(blockchainRid)
        return client(blockchainRid, EndpointPool.default(apiUrls.toList()))
    }

    /**
     * A client that can spawn [PostchainClient] that are on the same cluster
     */
    open inner class ClusterPostchainClient(private val endpointPool: EndpointPool) {
        /**
         * Creates a [PostchainClient] for communicating with blockchain.
         */
        open fun blockchain(blockchainRid: BlockchainRid): PostchainClient = client(blockchainRid, endpointPool)
    }

    protected fun client(blockchainRid: BlockchainRid, endpointPool: EndpointPool) = PostchainClientImpl(
            configTemplate.copy(blockchainRid, endpointPool)
    )

    companion object {
        /**
         * Builds an instance of [ChromiaClientProvider] that uses a http client to query chain0
         */
        fun fromClientConfig(config: PostchainClientConfig): ChromiaClientProvider {
            val chain0Client: PostchainClient = PostchainClientImpl(config)
            val clusterManagement = ClusterManagementImpl(chain0Client)
            return ChromiaClientProvider(clusterManagement, config)
        }
    }
}

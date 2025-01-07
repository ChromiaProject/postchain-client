package net.postchain.d1.nm_api

import net.postchain.chromia.model.BlockchainState
import net.postchain.chromia.nm_api.BlockchainConfigurationInfo
import net.postchain.chromia.nm_api.NmGetPendingBlockchainConfigurationByHashResult
import net.postchain.chromia.nm_api.nmApiVersion
import net.postchain.chromia.nm_api.nmGetBlockchainConfigurationInfo
import net.postchain.chromia.nm_api.nmGetBlockchainState
import net.postchain.chromia.nm_api.nmGetManagementChain
import net.postchain.chromia.nm_api.nmGetPendingBlockchainConfigurationByHash
import net.postchain.client.core.PostchainQuery
import net.postchain.common.BlockchainRid
import net.postchain.d1.nm_api.ApiCompatV18.nmGetBlockchainConfigurationV18

class NodeManagementImpl(private val query: PostchainQuery) : NodeManagement {

    override fun nmApiVersion() = query.nmApiVersion()

    override fun getManagementChain() = BlockchainRid(query.nmGetManagementChain())

    override fun getPendingBlockchainConfigByHash(blockchainRid: BlockchainRid, configHash: ByteArray): NmGetPendingBlockchainConfigurationByHashResult? =
            query.nmGetPendingBlockchainConfigurationByHash(blockchainRid, configHash)

    override fun getBlockchainConfiguration(blockchainRid: BlockchainRid, height: Long): BlockchainConfigurationInfo? {
        return if (nmApiVersion() <= 17) {
            query.nmGetBlockchainConfigurationV18(blockchainRid, height)?.let {
                BlockchainConfigurationInfo(it.baseConfig, it.signers, it.configHash)
            }
        } else {
            query.nmGetBlockchainConfigurationInfo(blockchainRid, height)
        }
    }

    override fun getBlockchainState(blockchainRid: BlockchainRid): BlockchainState {
        return BlockchainState.valueOf(query.nmGetBlockchainState(blockchainRid))
    }
}
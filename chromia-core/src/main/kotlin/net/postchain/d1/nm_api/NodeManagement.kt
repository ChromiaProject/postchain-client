package net.postchain.d1.nm_api

import net.postchain.chromia.model.BlockchainState
import net.postchain.chromia.nm_api.BlockchainConfigurationInfo
import net.postchain.chromia.nm_api.NmGetPendingBlockchainConfigurationByHashResult
import net.postchain.common.BlockchainRid

interface NodeManagement {
    fun nmApiVersion(): Long
    fun getManagementChain(): BlockchainRid
    fun getPendingBlockchainConfigByHash(blockchainRid: BlockchainRid, configHash: ByteArray): NmGetPendingBlockchainConfigurationByHashResult?
    fun getBlockchainConfiguration(blockchainRid: BlockchainRid, height: Long): BlockchainConfigurationInfo?
    fun getBlockchainState(blockchainRid: BlockchainRid): BlockchainState
}
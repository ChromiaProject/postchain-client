package net.postchain.d1.nm_api

import net.postchain.client.core.PostchainQuery
import net.postchain.common.BlockchainRid
import net.postchain.common.types.WrappedByteArray
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.toObject
import javax.annotation.processing.Generated

object ApiCompatV18 {

    @Generated("net.postchain.rell.codegen.CodeGenerator", comments = "")
    data class NmGetBlockchainConfigurationV18Result(
            @Name("base_config") val baseConfig: WrappedByteArray,
            @Name("signers") val signers: List<WrappedByteArray>,
            @Name("config_hash") val configHash: WrappedByteArray
    )

    /**
     * Query nm_api:nm_get_blockchain_configuration_v5
     */
    @Generated("net.postchain.rell.codegen.CodeGenerator", comments = "nm_api:nm_get_blockchain_configuration_v5")
    fun PostchainQuery.nmGetBlockchainConfigurationV18(
            blockchainRid: BlockchainRid,
            height: Long
    ) = query("nm_get_blockchain_configuration_v5", gtv(mapOf("blockchain_rid" to gtv(blockchainRid), "height" to gtv(height)))).let { v0 -> if (v0 is GtvNull) null else v0.toObject<NmGetBlockchainConfigurationV18Result>() }

}
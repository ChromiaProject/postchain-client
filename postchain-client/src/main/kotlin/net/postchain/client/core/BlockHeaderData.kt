package net.postchain.client.core

import net.postchain.common.data.Hash
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvDecoder
import net.postchain.gtv.GtvDictionary
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvInteger
import net.postchain.gtv.merkle.makeMerkleHashCalculator
import net.postchain.gtv.merkleHash

data class BlockHeaderData(
        val gtvBlockchainRid: GtvByteArray,
        val gtvPreviousBlockRid: GtvByteArray,
        val gtvMerkleRootHash: GtvByteArray,
        val gtvTimestamp: GtvInteger,
        val gtvHeight: GtvInteger,
        val gtvDependencies: Gtv, // Can be either GtvNull or GtvArray
        val gtvExtra: GtvDictionary) {

    constructor(
            blockchainRid: ByteArray,
            previousBlockRid: ByteArray,
            merkleRootHash: ByteArray,
            timestamp: Long,
            height: Long,
            dependencies: Gtv, // Can be either GtvNull or GtvArray
            extra: Map<String, Gtv>
    ) : this(
            gtv(blockchainRid),
            gtv(previousBlockRid),
            gtv(merkleRootHash),
            gtv(timestamp),
            gtv(height),
            dependencies,
            GtvDictionary.build(extra)
    )

    val merkleHashVersion: Long = gtvExtra["merkle_hash_version"]?.asInteger() ?: 1

    val merkleHashCalculator = makeMerkleHashCalculator(merkleHashVersion)

    fun blockRid(): Hash = toGtv().merkleHash(merkleHashCalculator)

    fun toBinary(): ByteArray = GtvEncoder.encodeGtv(toGtv())

    fun toGtv(): GtvArray = gtv(gtvBlockchainRid, gtvPreviousBlockRid, gtvMerkleRootHash, gtvTimestamp, gtvHeight, gtvDependencies, gtvExtra)

    fun computeMerkleRootHash(transactionHashes: List<ByteArray>): Hash {
        val digestsGtv = gtv(transactionHashes.map { gtv(it) })
        return digestsGtv.merkleHash(merkleHashCalculator)
    }

    companion object {
        fun fromBinary(rawData: ByteArray) = fromGtv(GtvDecoder.decodeGtv(rawData))

        fun fromGtv(gtv: Gtv) = BlockHeaderData(
                gtv[0] as GtvByteArray,
                gtv[1] as GtvByteArray,
                gtv[2] as GtvByteArray,
                gtv[3] as GtvInteger,
                gtv[4] as GtvInteger,
                gtv[5],
                gtv[6] as GtvDictionary)
    }
}
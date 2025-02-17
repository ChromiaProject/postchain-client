package net.postchain.client.impl

import net.postchain.base.merkleProofTree
import net.postchain.client.core.BlockHeaderData
import net.postchain.common.hexStringToByteArray
import net.postchain.common.wrap
import net.postchain.crypto.sha256Digest
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvDictionary
import net.postchain.gtv.GtvEncoder.encodeGtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvInteger
import net.postchain.gtv.GtvNull
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV1
import net.postchain.gtv.merkle.makeMerkleHashCalculator
import net.postchain.gtv.merkleHash

const val BLOCKCHAIN_RID = "EC03EDC6959E358B80D226D16A5BB6BC8EDE80EC17BD8BD0F21846C244AE7E8F"

val txHash1 = "F537E4B8224F4BC84DB37AD3E4F898A3A9127D2E86C25213508F7236016E58B9".hexStringToByteArray()
val txHash2 = "E537E4B8224F4BC84DB37AD3E4F898A3A9127D2E86C25213508F7236016E58B9".hexStringToByteArray()

val blockHeader = BlockHeaderData(
        gtvBlockchainRid = GtvByteArray(BLOCKCHAIN_RID.hexStringToByteArray()),
        gtvPreviousBlockRid = GtvByteArray(ByteArray(32) { 1 }),
        gtvMerkleRootHash = gtv(gtv(gtv(txHash1), gtv(txHash2)).merkleHash(GtvMerkleHashCalculatorV1(::sha256Digest))),
        gtvTimestamp = GtvInteger(17),
        gtvHeight = GtvInteger(1),
        gtvDependencies = GtvNull,
        gtvExtra = GtvDictionary.build(mapOf()))

val blockRid = blockHeader.blockRid()

fun validBlockDetail(i: Byte) = gtv(mapOf(
        "rid" to gtv(blockRid),
        "prevBlockRID" to GtvByteArray(ByteArray(32) { 1 }),
        "header" to gtv(encodeGtv(blockHeader.toGtv())),
        "height" to gtv(1),
        "transactions" to gtv(
                gtv(mapOf(
                        "rid" to gtv("62F71D71BA63D03FA0C6741DE22B116A3A8022893E7977DDC2A9CD981BBADE29".hexStringToByteArray()),
                        "hash" to gtv(txHash1),
                        "data" to GtvNull
                )),
                gtv(mapOf(
                        "rid" to gtv("72F71D71BA63D03FA0C6741DE22B116A3A8022893E7977DDC2A9CD981BBADE29".hexStringToByteArray()),
                        "hash" to gtv(txHash2),
                        "data" to GtvNull
                ))),
        "witness" to gtv(ByteArray(16) { i }),
        "timestamp" to gtv(17)
))

val invalidBlockHeader = BlockHeaderData(
        gtvBlockchainRid = GtvByteArray(BLOCKCHAIN_RID.hexStringToByteArray()),
        gtvPreviousBlockRid = GtvByteArray(ByteArray(32) { 2 }),
        gtvMerkleRootHash = gtv(gtv(gtv(txHash1), gtv(txHash2)).merkleHash(GtvMerkleHashCalculatorV1(::sha256Digest))),
        gtvTimestamp = GtvInteger(17),
        gtvHeight = GtvInteger(1),
        gtvDependencies = GtvNull,
        gtvExtra = GtvDictionary.build(mapOf()))

val invalidBlockDetail = gtv(mapOf(
        "rid" to gtv(blockRid),
        "prevBlockRID" to GtvByteArray(ByteArray(32) { 1 }),
        "header" to gtv(encodeGtv(invalidBlockHeader.toGtv())),
        "height" to gtv(1),
        "transactions" to gtv(
                gtv(mapOf(
                        "rid" to gtv("62F71D71BA63D03FA0C6741DE22B116A3A8022893E7977DDC2A9CD981BBADE29".hexStringToByteArray()),
                        "hash" to gtv(txHash1),
                        "data" to GtvNull
                )),
                gtv(mapOf(
                        "rid" to gtv("72F71D71BA63D03FA0C6741DE22B116A3A8022893E7977DDC2A9CD981BBADE29".hexStringToByteArray()),
                        "hash" to gtv(txHash2),
                        "data" to GtvNull
                ))),
        "witness" to gtv("03D8844CFC0CE7BECD33CDF49A9881364695C944E266E06356CDA11C2305EAB83A".hexStringToByteArray()),
        "timestamp" to gtv(17)
))

val confirmationProofData = merkleProofTree(txHash1.wrap(), arrayOf(txHash1.wrap(), txHash2.wrap()), makeMerkleHashCalculator(1)).let { result ->
    ConfirmationProofData(
            hash = txHash1,
            blockHeader = encodeGtv(blockHeader.toGtv()),
            witness = ByteArray(16) { 1 },
            merkleProofTree = result.second,
            txIndex = result.first
    )
}

val encodedConfirmationProof = encodeGtv(GtvObjectMapper.toGtvDictionary(confirmationProofData))

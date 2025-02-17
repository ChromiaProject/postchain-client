package net.postchain.d1.client

import net.postchain.client.core.BlockHeaderData
import net.postchain.gtv.merkle.proof.GtvMerkleProofTree

/**
 * Encapsulating a proof of a transaction hash in a block header
 *
 * @param hash The transaction hash the proof applies to
 * @param blockHeader The raw block header the [hash] is supposedly in
 * @param witness The block witness
 * @param merkleProofTree a proof including [hash] (in its raw form)
 * @param txIndex is the index of the proven transaction in the block (i.e. our "path").
 * @param blockHeaderData Decoded block header the [hash] is supposedly in
 */
class ConfirmationProof(
        val hash: ByteArray,
        val blockHeader: ByteArray,
        val witness: ByteArray,
        val merkleProofTree: GtvMerkleProofTree,
        val txIndex: Long,
        val blockHeaderData: BlockHeaderData,
)
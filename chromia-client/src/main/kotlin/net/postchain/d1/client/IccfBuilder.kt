package net.postchain.d1.client

import net.postchain.chain0.anchoring_chain_common.getAnchoringTransactionForBlockRid
import net.postchain.chain0.cm_api.cmGetBlockchainCluster
import net.postchain.client.core.BlockHeaderData
import net.postchain.client.core.TxRid
import net.postchain.client.exception.ClientError
import net.postchain.client.impl.ConfirmationProofData
import net.postchain.client.transaction.TransactionBuilder
import net.postchain.common.BlockchainRid
import net.postchain.common.data.Hash
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.crypto.Signature
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDecoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.toObject
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorBase
import net.postchain.gtv.merkleHash
import net.postchain.gtx.Gtx

class IccfBuilder(private val chromiaClient: ChromiaClient) {
    companion object {
        const val ICCF_OP_NAME = "iccf_proof"
    }

    fun addIccfProof(
            transactionBuilder: TransactionBuilder,
            txToProveRID: TxRid,
            txToProveHash: Hash?,
            sourceBlockchainRid: BlockchainRid,
            forceIntraNetworkIccfOperation: Boolean,
    ): Gtv {
        val sourceClient = chromiaClient.getClient(sourceBlockchainRid)
        val txProof = sourceClient.confirmationProof(txToProveRID)
        val decodedProof = GtvDecoder.decodeGtv(txProof).toObject<ConfirmationProofData>()
        val blockHeaderData = BlockHeaderData.fromBinary(decodedProof.blockHeader)
        val merkleHashCalculator = blockHeaderData.merkleHashCalculator

        val rawTx = sourceClient.getTransaction(txToProveRID)
        val txGtv = GtvDecoder.decodeGtv(rawTx)
        val fetchedTxHash = txGtv.merkleHash(merkleHashCalculator)
        if (!fetchedTxHash.contentEquals(decodedProof.hash)) {
            // We received another hash for tx RID than what was included in proof
            // Possibly rouge or faulty node(s). Anyway, we need to give up.
            throw ClientError("iccf", null, "Unable to verify source transaction proof, " +
                    "transaction hash in proof ${decodedProof.hash.toHex()} does not match hash from fetched transaction ${fetchedTxHash.toHex()}", null
            )
        }
        val sourceTxHash = if (txToProveHash == null) {
            fetchedTxHash
        } else if (!txToProveHash.contentEquals(decodedProof.hash)) {
            // investigate why we have a mismatch
            val tx = Gtx.fromGtv(txGtv)
            val txRid = tx.calculateTxRid(merkleHashCalculator)
            if (!txRid.contentEquals(txToProveRID.rid.hexStringToByteArray())) {
                // We received a tx with a different RID than we asked for. Rouge or faulty node(s).
                throw ClientError("iccf", null, "Unable to verify source transaction proof, got a different transaction from query than we asked for", null)
            }
            // Signatures may have been reformatted
            tx.gtxBody.signers.forEachIndexed { index, signer ->
                val signature = tx.signatures[index]
                if (!sourceClient.config.cryptoSystem.verifyDigest(txRid, Signature(signer, signature))) {
                    throw ClientError("iccf", null, "Incorrect signature $signature for signer $signer in fetched source transaction", null)
                }
            }
            fetchedTxHash
        } else {
            txToProveHash
        }

        val directoryChainClient = chromiaClient.getDirectoryChainClient()
        val sourceCluster = directoryChainClient.cmGetBlockchainCluster(sourceBlockchainRid.data)
        val targetCluster = directoryChainClient.cmGetBlockchainCluster(transactionBuilder.blockchainRid.data)
        if (!forceIntraNetworkIccfOperation && sourceCluster == targetCluster) { // intra-cluster
            addTransactionProofOperation(transactionBuilder, sourceBlockchainRid, sourceTxHash, txProof)
        } else { // intra-network
            addAnchoredProofOperation(txProof, decodedProof.blockHeader, sourceCluster, sourceBlockchainRid,
                    transactionBuilder, sourceTxHash, merkleHashCalculator)
        }

        return txGtv
    }

    private fun addTransactionProofOperation(txBuilder: TransactionBuilder, sourceBlockchainRid: BlockchainRid, txHash: Hash, txProof: ByteArray) {
        txBuilder.addOperation(
                ICCF_OP_NAME,
                gtv(sourceBlockchainRid.data),
                gtv(txHash),
                gtv(txProof)
        )
    }

    private fun addAnchoredProofOperation(txProof: ByteArray, sourceBlockHeader: ByteArray, sourceCluster: String, sourceBlockchainRid: BlockchainRid, txBuilder: TransactionBuilder, txHash: Hash, hashCalculator: GtvMerkleHashCalculatorBase) {
        val sourceBlockRid = GtvDecoder.decodeGtv(sourceBlockHeader).merkleHash(hashCalculator)
        val anchoringChainClient = chromiaClient.getClusterAnchoringClient(sourceCluster)
        val anchoringTx = anchoringChainClient.getAnchoringTransactionForBlockRid(sourceBlockchainRid, sourceBlockRid)
                ?: throw ClientError("iccf", null, "Block is not present in cluster anchoring chain", null)
        val anchoringProof = anchoringChainClient.confirmationProof(TxRid(anchoringTx.txRid.toHex()))

        txBuilder.addOperation(
                ICCF_OP_NAME,
                gtv(sourceBlockchainRid.data),
                gtv(txHash),
                gtv(txProof),
                gtv(anchoringTx.txData),
                gtv(anchoringTx.txOpIndex),
                gtv(anchoringProof)
        )
    }
}

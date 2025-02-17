package net.postchain.d1.client

import net.postchain.chain0.cm_api.cmGetPeerInfo
import net.postchain.client.bftMajority
import net.postchain.client.core.BlockHeaderData
import net.postchain.client.core.PostchainClient
import net.postchain.client.core.PostchainQuery
import net.postchain.client.core.TransactionInfo
import net.postchain.client.core.TransactionResult
import net.postchain.client.core.TxRid
import net.postchain.client.exception.ClientError
import net.postchain.client.impl.ConfirmationProofData
import net.postchain.client.transaction.TransactionBuilder
import net.postchain.common.exception.UserMistake
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.common.wrap
import net.postchain.crypto.KeyPair
import net.postchain.crypto.PubKey
import net.postchain.crypto.Signature
import net.postchain.gtv.GtvDecoder
import net.postchain.gtv.mapper.toObject
import net.postchain.gtv.merkle.proof.merkleHash
import net.postchain.gtv.merkle.proof.toGtvVirtual
import net.postchain.gtv.merkleHash
import net.postchain.gtx.Gtx
import java.io.IOException
import java.nio.BufferUnderflowException
import java.time.Duration

class ChromiaPostchainClient(val directoryChainClient: PostchainQuery, val txClient: PostchainClient, val queryClient: PostchainClient, val addNop: Boolean)
    : PostchainClient by queryClient {
    override fun transactionBuilder(): TransactionBuilder = txClient.transactionBuilder().apply { if (addNop) addNop() }

    override fun transactionBuilder(signers: List<KeyPair>): TransactionBuilder =
            txClient.transactionBuilder(signers).apply { if (addNop) addNop() }

    override fun transactionBuilder(initialSigners: List<KeyPair>, remainingRequiredSigners: List<PubKey>): TransactionBuilder =
            txClient.transactionBuilder(initialSigners, remainingRequiredSigners).apply { if (addNop) addNop() }

    override fun postTransaction(tx: Gtx): TransactionResult =
            txClient.postTransaction(tx)

    override fun postTransactionAwaitConfirmation(tx: Gtx): TransactionResult =
            txClient.postTransactionAwaitConfirmation(tx)

    override fun awaitConfirmation(txRid: TxRid, retries: Int, pollInterval: Duration): TransactionResult =
            txClient.awaitConfirmation(txRid, retries, pollInterval)

    override fun checkTxStatus(txRid: TxRid): TransactionResult =
            txClient.checkTxStatus(txRid)

    override fun close() {
        txClient.close()
        if (queryClient !== txClient) {
            queryClient.close()
        }
    }

    @Throws(IOException::class)
    fun decodedConfirmationProof(txRid: TxRid): ConfirmationProof {
        val proofData = confirmationProof(txRid)
        val confirmationProofData = GtvDecoder.decodeGtv(proofData).toObject<ConfirmationProofData>()
        val blockHeaderData = BlockHeaderData.fromBinary(confirmationProofData.blockHeader)

        val proofRootHash = confirmationProofData.merkleProofTree.merkleHash(blockHeaderData.merkleHashCalculator)
        if (!blockHeaderData.gtvMerkleRootHash.bytearray.contentEquals(proofRootHash)) {
            throw ClientError("decodedConfirmationProof", null,
                    "Proof tree root hash mismatch, expected ${blockHeaderData.gtvMerkleRootHash.bytearray.toHex()} but was ${proofRootHash.toHex()}", null)
        }

        val proofTxHash = try {
            confirmationProofData.merkleProofTree.toGtvVirtual()[confirmationProofData.txIndex.toInt()].asByteArray()
        } catch (e: UserMistake) {
            throw ClientError("decodedConfirmationProof", null, "Invalid conformation proof: ${e.message}", null)
        }
        if (!confirmationProofData.hash.contentEquals(proofTxHash)) {
            throw ClientError("decodedConfirmationProof", null,
                    "Proof transaction hash mismatch, expected ${confirmationProofData.hash.toHex()} but was ${proofTxHash.toHex()}", null)
        }

        verifyWitness(blockHeaderData, confirmationProofData)

        return ConfirmationProof(
                hash = confirmationProofData.hash,
                blockHeader = confirmationProofData.blockHeader,
                witness = confirmationProofData.witness,
                merkleProofTree = confirmationProofData.merkleProofTree,
                txIndex = confirmationProofData.txIndex,
                blockHeaderData = blockHeaderData
        )
    }

    private fun verifyWitness(blockHeaderData: BlockHeaderData, confirmationProofData: ConfirmationProofData) {
        val peers = directoryChainClient.cmGetPeerInfo(config.blockchainRid.data, blockHeaderData.gtvHeight.integer)
        val signatures = try {
            decodeWitness(confirmationProofData.witness)
        } catch (_: BufferUnderflowException) {
            throw ClientError("decodedConfirmationProof", null, "Invalid witness: ${confirmationProofData.witness.toHex()}", null)
        }

        val threshold = bftMajority(peers.size)

        if (signatures.size < threshold) {
            throw ClientError("decodedConfirmationProof", null, "Insufficient number of witness (needs at least $threshold but got only ${signatures.size})", null)
        }

        val validSignatures = mutableListOf<Signature>()
        for (signature in signatures) {
            if (!peers.any { signature.subjectID.contentEquals(it) }) {
                throw ClientError("decodedConfirmationProof", null, "Unexpected subject ${signature.subjectID.toHex()} of signature", null)
            }

            // Do not add two signatures from the same subject!
            if (validSignatures.any { it.subjectID.contentEquals(signature.subjectID) }) {
                break
            }
            if (!config.cryptoSystem.verifyDigest(blockHeaderData.blockRid(), signature)) {
                throw ClientError("decodedConfirmationProof", null, "Invalid signature from subject ${signature.subjectID.toHex()}", null)
            }
            validSignatures.add(signature)
        }

        if (validSignatures.size < threshold) {
            throw ClientError("decodedConfirmationProof", null, "Insufficient number of valid witness signatures", null)
        }
    }

    @Throws(IOException::class)
    fun verifiedTransactionInfo(txRid: TxRid): TransactionInfo {
        val confirmationProof = decodedConfirmationProof(txRid)
        val txData = getTransaction(txRid)
        val gtv = GtvDecoder.decodeGtv(txData)
        val derivedTxHash = gtv.merkleHash(confirmationProof.blockHeaderData.merkleHashCalculator)
        if (!derivedTxHash.contentEquals(confirmationProof.hash)) {
            throw ClientError("verifiedTransactionInfo", null,
                    "Transaction hash mismatch, expected ${confirmationProof.hash.toHex()} but was ${derivedTxHash.toHex()}", null)
        }
        val gtx = Gtx.fromGtv(gtv)
        val derivedTxRid = gtx.calculateTxRid(confirmationProof.blockHeaderData.merkleHashCalculator)
        if (!derivedTxRid.contentEquals(txRid.rid.hexStringToByteArray())) {
            throw ClientError("verifiedTransactionInfo", null,
                    "Transaction RID mismatch, expected ${txRid.rid} but was ${derivedTxRid.toHex()}", null)
        }
        return TransactionInfo(
                blockRID = confirmationProof.blockHeaderData.blockRid().wrap(),
                blockHeight = confirmationProof.blockHeaderData.gtvHeight.integer,
                blockHeader = confirmationProof.blockHeader.wrap(),
                witness = confirmationProof.witness.wrap(),
                timestamp = confirmationProof.blockHeaderData.gtvTimestamp.integer,
                txRID = derivedTxRid.wrap(),
                txHash = derivedTxHash.wrap(),
                txData = txData.wrap()
        )
    }
}

package net.postchain.client.transaction

import net.postchain.client.core.PostchainClient
import net.postchain.common.BlockchainRid
import net.postchain.crypto.CryptoSystem
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.crypto.SigMaker
import net.postchain.crypto.Signature
import net.postchain.gtv.Gtv
import net.postchain.gtx.Gtx
import net.postchain.gtx.GtxBuilder

/**
 * @param maxTxSize maximal allowed transaction size, or -1 for no limit
 */
class TransactionBuilder(
        private val client: PostchainClient,
        blockchainRid: BlockchainRid,
        private val signers: List<ByteArray>,
        private val defaultSigners: List<SigMaker> = listOf(),
        private val cryptoSystem: CryptoSystem = Secp256K1CryptoSystem(),
        private val maxTxSize: Int = -1,
        private val defaultSignersPubkey: List<ByteArray> = listOf(),
) : Postable {
    private val EMPTY_SIGNATURE: ByteArray = ByteArray(64)

    private val gtxBuilder = GtxBuilder(blockchainRid, signers, cryptoSystem, maxTxSize)

    /**
     * Adds an operation to this transaction
     *
     * @throws IllegalStateException if the operation does not fit
     */
    fun addOperation(name: String, vararg args: Gtv) = apply {
        gtxBuilder.addOperation(name, *args)
    }

    /**
     * Adds a nop operation to make the transaction unique
     */
    fun addNop() = apply { gtxBuilder.addNop() }

    /**
     * Sign this transaction with default signers and [PostchainClient.postTransaction]
     */
    override fun post() = sign().post()

    /**
     * Sign this transaction with default signers and [PostchainClient.postTransactionAwaitConfirmation]
     */
    override fun postAwaitConfirmation() = sign().postAwaitConfirmation()

    /**
     * Sign this transaction with the [defaultSigners] and prepare it to be posted
     */
    fun sign() = sign(*defaultSigners.toTypedArray())

    /**
     * Builds a multi-sig transaction, sign it with initial signers and prepare the transaction to be signed by the remaining required signers.
     */
    fun build(): ByteArray {
        return uncheckedSignBuilder().apply {
            defaultSigners.forEach { sign(it) }
            subtractFrom(signers, defaultSignersPubkey).forEach { sign(Signature(it, EMPTY_SIGNATURE)) }
        }.buildGtx().encode()
    }

    /**
     * Rebuilds a transaction from gtx and sign it with provided signers.
     */
    fun signTransaction(gtxByteArray: ByteArray): ByteArray {
        val gtx = Gtx.decode(gtxByteArray)
        val gtxBuilder = GtxBuilder(gtx.gtxBody.blockchainRid, gtx.gtxBody.signers, cryptoSystem, maxTxSize)
        gtx.gtxBody.operations.forEach { gtxBuilder.addOperation(it.opName, *it.args) }

        val signBuilder = gtxBuilder.uncheckedSignBuilder()
        val signerToSignature = gtx.gtxBody.signers.zip(gtx.signatures).toMap()

        subtractFrom(gtx.gtxBody.signers, defaultSignersPubkey).forEach { signer -> signBuilder.sign(Signature(signer, signerToSignature[signer]!!)) }
        defaultSigners.forEach { signer -> signBuilder.sign(signer) }
        return signBuilder.buildGtx().encode()
    }

    /**
     * Rebuilds a transaction from gtx, verify the signatures and post the transaction.
     */
    fun sendTransaction(gtxByteArray: ByteArray) {
        val gtx = Gtx.decode(gtxByteArray)
        val gtxBuilder = GtxBuilder(gtx.gtxBody.blockchainRid, gtx.gtxBody.signers, cryptoSystem, maxTxSize)
        gtx.gtxBody.operations.forEach { gtxBuilder.addOperation(it.opName, *it.args) }

        val signBuilder = gtxBuilder.finish()
        val signerToSignature = gtx.gtxBody.signers.zip(gtx.signatures).toMap()

        gtx.gtxBody.signers.forEach { signer -> signBuilder.sign(Signature(signer, signerToSignature[signer]!!)) }
        PostableTransaction(signBuilder.buildGtx()).post()
    }

    private fun subtractFrom(list: List<ByteArray>, elementsToRemove: List<ByteArray>) =
            ArrayList(list.filter { element -> elementsToRemove.none { elementToRemove -> element.contentEquals(elementToRemove) } })

    private fun uncheckedSignBuilder(): SignatureBuilder {
        return SignatureBuilder(gtxBuilder.uncheckedSignBuilder())
    }
    /**
     * Sign this transaction and prepare it to be posted
     */
    fun sign(vararg sigMaker: SigMaker): PostableTransaction {
        return finish().apply {
            sigMaker.forEach { sign(it) }
        }.build()
    }

    /**
     * Marks this transaction as finished and ready to be signed
     */
    fun finish(): SignatureBuilder {
        return SignatureBuilder(gtxBuilder.finish())
    }

    inner class SignatureBuilder(private val signBuilder: GtxBuilder.GtxSignBuilder) {

        /**
         * Sign this transaction
         */
        fun sign(sigMaker: SigMaker) = apply {
            signBuilder.sign(sigMaker)
        }

        fun sign(signature: Signature) = apply {
            signBuilder.sign(signature)
        }

        /**
         * Build a transaction that can be posted
         */
        fun build(): PostableTransaction {
            return PostableTransaction(buildGtx())
        }

        /**
         * Build Gtx
         */
        fun buildGtx() = signBuilder.buildGtx()
    }

    inner class PostableTransaction(private val tx: Gtx) : Postable {

        /**
         * [PostchainClient.postTransaction]
         */
        override fun post() = client.postTransaction(tx)

        /**
         * [PostchainClient.postTransactionAwaitConfirmation]
         */
        override fun postAwaitConfirmation() = client.postTransactionAwaitConfirmation(tx)

    }
}

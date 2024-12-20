package net.postchain.client.transaction

import net.postchain.crypto.CryptoSystem
import net.postchain.crypto.KeyPair
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.gtx.Gtx
import net.postchain.gtx.GtxBuilder

/**
 * Rebuilds a transaction from gtx and sign it with provided signers.
 */
fun signTransaction(
        gtxByteArray: ByteArray,
        signers: List<KeyPair>,
        cryptoSystem: CryptoSystem = Secp256K1CryptoSystem(),
        maxTxSize: Int = -1,
): ByteArray {
    val gtx = Gtx.decode(gtxByteArray)
    val gtxBuilder = GtxBuilder(gtx.gtxBody.blockchainRid, gtx.gtxBody.signers, cryptoSystem, maxTxSize, gtx.gtxBody.operations)

    val signBuilder = gtxBuilder.uncheckedSignBuilder()
    signBuilder.addSignatures(gtx.signatures)
    signers.forEach { signBuilder.signOverEmptySignature(cryptoSystem.buildSigMaker(it)) }
    return signBuilder.buildGtx().encode()
}
package net.postchain.d1.client

import net.postchain.crypto.Signature
import java.nio.ByteBuffer

fun decodeWitness(rawWitness: ByteArray): Array<Signature> {
    val buffer = ByteBuffer.wrap(rawWitness)
    val sigCount = buffer.int
    val signatures = Array(sigCount) {
        val subjectIdSize = buffer.int
        val subjectId = ByteArray(subjectIdSize)
        buffer.get(subjectId)
        val signatureSize = buffer.int
        val signature = ByteArray(signatureSize)
        buffer.get(signature)
        Signature(subjectId, signature)
    }
    return signatures
}

fun encodeWitness(signatures: Array<Signature>): ByteArray {
    var size = 4 // space for sig count
    signatures.forEach { size += 8 + it.subjectID.size + it.data.size }
    val bytes = ByteBuffer.allocate(size)
    bytes.putInt(signatures.size)
    for (signature in signatures) {
        bytes.putInt(signature.subjectID.size)
        bytes.put(signature.subjectID)
        bytes.putInt(signature.data.size)
        bytes.put(signature.data)
    }
    return bytes.array()
}

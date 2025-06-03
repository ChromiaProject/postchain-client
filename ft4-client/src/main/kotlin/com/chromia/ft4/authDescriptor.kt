package com.chromia.ft4

import com.chromia.ft4.lib.ft4.core.accounts.AuthType
import com.chromia.ft4.lib.ft4.external.accounts.Ft4GetAccountAuthDescriptorsBySignerResult

fun Ft4GetAccountAuthDescriptorsBySignerResult.flags() = this.args.asArray().first().asArray().map { it.asString() }

fun Ft4GetAccountAuthDescriptorsBySignerResult.numberOfSigners(): Long = if (this.authType == AuthType.S) {
    1
} else {
    this.args.asArray()[1].asInteger()
}

fun Ft4GetAccountAuthDescriptorsBySignerResult.getSingleSigner(): ByteArray {
    // The second argument in gtv of AuthType.S has the public key
    return if (this.authType == AuthType.S) {
        this.args.asArray()[1].asByteArray()
    } else {
        throw IllegalArgumentException("can only be done on auth descriptors of type S")
    }
}

fun Ft4GetAccountAuthDescriptorsBySignerResult.getMultiSigners(): List<ByteArray> {
    // The third argument in gtv of AuthType.M has the public key
    return if (this.authType == AuthType.M) {
        this.args.asArray()[2].asArray().map { it.asByteArray() }
    } else {
        throw IllegalArgumentException("can only be done on auth descriptors of type M")
    }
}

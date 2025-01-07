package net.postchain.d1.cluster

import net.postchain.crypto.PubKey

data class D1PeerInfo(
    val restApiUrl: String,
    val pubkey: PubKey
)

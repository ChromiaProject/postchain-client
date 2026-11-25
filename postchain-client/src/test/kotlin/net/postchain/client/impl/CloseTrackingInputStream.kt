package net.postchain.client.impl

import java.io.ByteArrayInputStream

internal class CloseTrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    var closed = false
        private set

    override fun close() {
        closed = true
        super.close()
    }
}

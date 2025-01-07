package net.postchain.d1.client

import net.postchain.client.config.CONNECT_TIMEOUT
import net.postchain.client.config.FailOverConfig
import net.postchain.client.config.RESPONSE_TIMEOUT
import net.postchain.client.config.STATUS_POLL_COUNT
import net.postchain.client.config.STATUS_POLL_INTERVAL
import net.postchain.client.request.EndpointPool
import net.postchain.common.config.Config
import net.postchain.crypto.KeyPair
import java.time.Duration

data class ChromiaClientConfig(
        val endpointPool: EndpointPool,
        val signers: List<KeyPair> = listOf(),
        val connectTimeout: Duration = CONNECT_TIMEOUT,
        val statusPollCount: Int = STATUS_POLL_COUNT,
        val statusPollInterval: Duration = STATUS_POLL_INTERVAL,
        val failOverConfig: FailOverConfig = FailOverConfig(),
        val responseTimeout: Duration = RESPONSE_TIMEOUT,
) : Config
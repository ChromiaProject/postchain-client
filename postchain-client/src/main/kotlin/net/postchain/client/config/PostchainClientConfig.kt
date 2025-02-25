package net.postchain.client.config

import net.postchain.client.impl.AbortOnErrorRequestStrategyFactory
import net.postchain.client.request.EndpointPool
import net.postchain.client.request.RequestStrategyFactory
import net.postchain.common.BlockchainRid
import net.postchain.common.PropertiesFileLoader
import net.postchain.common.config.Config
import net.postchain.common.config.getEnvOrBooleanProperty
import net.postchain.common.config.getEnvOrIntProperty
import net.postchain.common.config.getEnvOrListProperty
import net.postchain.common.config.getEnvOrLongProperty
import net.postchain.common.config.getEnvOrStringProperty
import net.postchain.common.reflection.newInstanceOf
import net.postchain.crypto.CryptoSystem
import net.postchain.crypto.KeyPair
import net.postchain.crypto.Secp256K1CryptoSystem
import org.apache.commons.configuration2.BaseConfiguration
import org.apache.commons.configuration2.Configuration
import java.time.Duration

const val STATUS_POLL_COUNT = 20
val STATUS_POLL_INTERVAL: Duration = Duration.ofMillis(500)
const val MAX_RESPONSE_SIZE = 64 * 1024 * 1024 // 64 MiB
val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(60)
val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(60)
const val MAX_TX_SIZE = -1 // no limit
const val MERKLE_HASH_VERSION = 1

data class PostchainClientConfig @JvmOverloads constructor(
        val blockchainRid: BlockchainRid,
        val endpointPool: EndpointPool,
        val signers: List<KeyPair> = listOf(),
        val statusPollCount: Int = STATUS_POLL_COUNT,
        val statusPollInterval: Duration = STATUS_POLL_INTERVAL,
        val failOverConfig: FailOverConfig = FailOverConfig(),
        val cryptoSystem: CryptoSystem = Secp256K1CryptoSystem(),
        val queryByChainId: Long? = null,
        val maxResponseSize: Int = MAX_RESPONSE_SIZE,
        /** Will not be used if `httpClient` parameter is specified when creating `ConcretePostchainClient`. */
        val connectTimeout: Duration = CONNECT_TIMEOUT,
        /** Will not be used if `httpClient` parameter is specified when creating `ConcretePostchainClient`. */
        val responseTimeout: Duration = RESPONSE_TIMEOUT,
        val requestStrategy: RequestStrategyFactory = AbortOnErrorRequestStrategyFactory(),
        val maxTxSize: Int = MAX_TX_SIZE,
        /** Will only be applied to synchronous client if enabled */
        val compressRequestBodies: Boolean = false,
        val merkleHashVersion: Int = MERKLE_HASH_VERSION,
) : Config {
    companion object {
        val defaultConfig by lazy { PostchainClientConfig(BlockchainRid.ZERO_RID, EndpointPool.singleUrl("")) }

        @JvmStatic
        @JvmOverloads
        fun fromProperties(propertiesFileName: String?, defaults: PostchainClientConfig = defaultConfig): PostchainClientConfig =
                fromConfiguration(propertiesFileName?.let { PropertiesFileLoader.load(it) } ?: BaseConfiguration(), defaults)

        @JvmOverloads
        fun fromConfiguration(config: Configuration, defaults: PostchainClientConfig = defaultConfig): PostchainClientConfig {
            val pubkeys = config.getEnvOrListProperty("POSTCHAIN_CLIENT_PUBKEY", "pubkey", listOf())
            val privkeys = config.getEnvOrListProperty("POSTCHAIN_CLIENT_PRIVKEY", "privkey", listOf())
            require(pubkeys.size == privkeys.size) { "Equally many pubkeys as privkeys must be provided, but ${pubkeys.size} and ${privkeys.size} was found" }
            val signers = if (privkeys.isEmpty() || privkeys.first() == "") listOf() else pubkeys.zip(privkeys).map { KeyPair.of(it.first, it.second) }
            val apiUrls = config.getEnvOrListProperty("POSTCHAIN_CLIENT_API_URL", "api.url", listOf())
            require(apiUrls.isNotEmpty()) { "Missing 'api.url'" }
            return PostchainClientConfig(
                    blockchainRid = BlockchainRid.buildFromHex(
                            requireNotNull(config.getEnvOrStringProperty("POSTCHAIN_CLIENT_BLOCKCHAIN_RID", "brid")) { "Missing 'brid' (blockchain-rid)" }),
                    endpointPool = EndpointPool.default(apiUrls),
                    signers = signers,

                    statusPollCount = config.getEnvOrIntProperty("POSTCHAIN_CLIENT_STATUS_POLL_COUNT", "status.poll-count",
                            defaults.statusPollCount),
                    statusPollInterval = config.getEnvOrLongProperty("POSTCHAIN_CLIENT_STATUS_POLL_INTERVAL", "status.poll-interval",
                            defaults.statusPollInterval.toMillis()).let { Duration.ofMillis(it) },
                    failOverConfig = FailOverConfig.fromConfiguration(config, defaults.failOverConfig),
                    cryptoSystem = config.getEnvOrStringProperty("POSTCHAIN_CRYPTO_SYSTEM", "cryptosystem")?.let {
                        newInstanceOf<CryptoSystem>(it)
                    } ?: defaults.cryptoSystem,
                    maxResponseSize = config.getEnvOrIntProperty("POSTCHAIN_CLIENT_MAX_RESPONSE_SIZE", "max.response.size",
                            defaults.maxResponseSize),
                    connectTimeout = config.getEnvOrLongProperty("POSTCHAIN_CLIENT_CONNECT_TIMEOUT", "connect.timeout",
                            defaults.connectTimeout.toMillis()).let { Duration.ofMillis(it) },
                    responseTimeout = config.getEnvOrLongProperty("POSTCHAIN_CLIENT_RESPONSE_TIMEOUT", "response.timeout",
                            defaults.responseTimeout.toMillis()).let { Duration.ofMillis(it) },
                    requestStrategy = config.getEnvOrStringProperty("POSTCHAIN_CLIENT_REQUEST_STRATEGY", "request.strategy")?.let {
                        RequestStrategies.valueOf(it).factory
                    } ?: defaults.requestStrategy,
                    maxTxSize = config.getEnvOrIntProperty("POSTCHAIN_CLIENT_MAX_TX_SIZE", "max.tx.size", defaults.maxTxSize),
                    compressRequestBodies = config.getEnvOrBooleanProperty("POSTCHAIN_CLIENT_COMPRESS_REQUEST_BODIES", "compress.requests", defaults.compressRequestBodies),
                    merkleHashVersion = config.getEnvOrIntProperty("POSTCHAIN_CLIENT_MERKLE_HASH_VERSION", "merkle-hash-version", defaults.merkleHashVersion),
            )
        }
    }
}

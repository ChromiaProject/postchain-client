package net.postchain.client.config

import net.postchain.common.config.Config
import net.postchain.common.config.getEnvOrIntProperty
import net.postchain.common.config.getEnvOrLongProperty
import org.apache.commons.configuration2.Configuration
import java.time.Duration

const val ATTEMPTS_PER_ENDPOINT = 5
val FAIL_OVER_INTERVAL: Duration = Duration.ofMillis(500)

data class FailOverConfig(
        val attemptsPerEndpoint: Int = ATTEMPTS_PER_ENDPOINT,
        val attemptInterval: Duration = FAIL_OVER_INTERVAL,
) : Config {
    companion object {
        @JvmOverloads
        fun fromConfiguration(config: Configuration, defaults: FailOverConfig = FailOverConfig()): FailOverConfig {
            return FailOverConfig(
                    attemptsPerEndpoint = config.getEnvOrIntProperty("POSTCHAIN_CLIENT_FAIL_OVER_ATTEMPTS", "failover.attempts",
                            defaults.attemptsPerEndpoint),
                    attemptInterval = config.getEnvOrLongProperty("POSTCHAIN_CLIENT_FAIL_OVER_INTERVAL", "failover.interval",
                            defaults.attemptInterval.toMillis()).let { Duration.ofMillis(it) }
            )
        }
    }
}

package net.postchain.client.config

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.message
import net.postchain.client.impl.TryNextOnErrorRequestStrategyFactory
import net.postchain.common.BlockchainRid
import net.postchain.crypto.KeyPair
import net.postchain.crypto.pqc.dilithium.DilithiumCryptoSystem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration

class PostchainClientConfigTest {

    @Test
    fun `fromProperties with standard defaults`(@TempDir tempDir: File) {
        `fromProperties with defaults`(tempDir, PostchainClientConfig.defaultConfig)
    }

    @Test
    fun `fromProperties with overridden defaults`(@TempDir tempDir: File) {
        val defaultOverrides = PostchainClientConfig.defaultConfig.copy(
                statusPollCount = 10,
                failOverConfig = FailOverConfig(3, Duration.ofMillis(50)),
                cryptoSystem = DilithiumCryptoSystem(),
                connectTimeout = Duration.ofSeconds(10),
                requestStrategy = TryNextOnErrorRequestStrategyFactory(),
                compressRequestBodies = true,
        )

        `fromProperties with defaults`(tempDir, defaultOverrides)
    }

    private fun `fromProperties with defaults`(tempDir: File, usedDefaults: PostchainClientConfig) {
        val configFile = tempDir.resolve("client.properties")
        configFile.writeText("""
                    api.url=https://node0.devnet2.chromia.dev:7740,https://node1.devnet2.chromia.dev:7740,https://node2.devnet2.chromia.dev:7740,https://node3.devnet2.chromia.dev:7740
                    privkey=D27E183BE752B5BD3BF5CFAE131CD848D72DED09743291813EF9B26FCC1C0523,A2D750E826DF8D923570DBB7F6722061FE21DFCFC206CB5E45FA4A5812E9BFDA
                    pubkey=026D5F18ED32B87364DCCE38510035FFE590D6F957F0C8F3FF725E7BB363C49BAC,0383AD77667C7265C99C52021B49B025F629B98A1C38E76D2050530A1CFC8ECB78
                    brid = 91357DDAB60605FEBF751F8DAF59C42A593A4B154F3041B893D2C5BDF27ABB9F        
                """.trimIndent())

        val result = PostchainClientConfig.fromProperties(configFile.absolutePath, usedDefaults)

        assertThat(result.endpointPool.size).isEqualTo(4)
        assertThat(result.blockchainRid).isEqualTo(BlockchainRid.buildFromHex("91357DDAB60605FEBF751F8DAF59C42A593A4B154F3041B893D2C5BDF27ABB9F"))
        assertThat(result.signers).isEqualTo(listOf(
                KeyPair.of("026D5F18ED32B87364DCCE38510035FFE590D6F957F0C8F3FF725E7BB363C49BAC", "D27E183BE752B5BD3BF5CFAE131CD848D72DED09743291813EF9B26FCC1C0523"),
                KeyPair.of("0383AD77667C7265C99C52021B49B025F629B98A1C38E76D2050530A1CFC8ECB78", "A2D750E826DF8D923570DBB7F6722061FE21DFCFC206CB5E45FA4A5812E9BFDA"),
        ))

        assertThat(result.statusPollCount).isEqualTo(usedDefaults.statusPollCount)
        assertThat(result.failOverConfig.attemptsPerEndpoint).isEqualTo(usedDefaults.failOverConfig.attemptsPerEndpoint)
        assertThat(result.failOverConfig.attemptInterval).isEqualTo(usedDefaults.failOverConfig.attemptInterval)
        assertThat(result.cryptoSystem.javaClass.name).isEqualTo(usedDefaults.cryptoSystem.javaClass.name)
        assertThat(result.connectTimeout).isEqualTo(usedDefaults.connectTimeout)
        assertThat(result.requestStrategy.javaClass.name).isEqualTo(usedDefaults.requestStrategy.javaClass.name)
        assertThat(result.compressRequestBodies).isEqualTo(usedDefaults.compressRequestBodies)
    }

    @Test
    fun `fromProperties missing API URL`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("client.properties")
        configFile.writeText("""
            privkey=D27E183BE752B5BD3BF5CFAE131CD848D72DED09743291813EF9B26FCC1C0523,A2D750E826DF8D923570DBB7F6722061FE21DFCFC206CB5E45FA4A5812E9BFDA
            pubkey=026D5F18ED32B87364DCCE38510035FFE590D6F957F0C8F3FF725E7BB363C49BAC,0383AD77667C7265C99C52021B49B025F629B98A1C38E76D2050530A1CFC8ECB78
            brid = 91357DDAB60605FEBF751F8DAF59C42A593A4B154F3041B893D2C5BDF27ABB9F        
        """.trimIndent())

        assertFailure {
            PostchainClientConfig.fromProperties(configFile.absolutePath)
        }.isInstanceOf(IllegalArgumentException::class)
                .message().isEqualTo("Missing 'api.url'")
    }

    @Test
    fun `fromProperties signers mismatch`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("client.properties")
        configFile.writeText("""
            api.url=https://node0.devnet2.chromia.dev:7740
            privkey=D27E183BE752B5BD3BF5CFAE131CD848D72DED09743291813EF9B26FCC1C0523
            pubkey=026D5F18ED32B87364DCCE38510035FFE590D6F957F0C8F3FF725E7BB363C49BAC,0383AD77667C7265C99C52021B49B025F629B98A1C38E76D2050530A1CFC8ECB78
            brid = 91357DDAB60605FEBF751F8DAF59C42A593A4B154F3041B893D2C5BDF27ABB9F        
        """.trimIndent())

        assertFailure {
            PostchainClientConfig.fromProperties(configFile.absolutePath)
        }.isInstanceOf(IllegalArgumentException::class)
                .message().isEqualTo("Equally many pubkeys as privkeys must be provided, but 2 and 1 was found")
    }

    @Test
    fun `fromProperties missing brid`(@TempDir tempDir: File) {
        val configFile = tempDir.resolve("client.properties")
        configFile.writeText("""
            api.url=https://node0.devnet2.chromia.dev:7740
            privkey=D27E183BE752B5BD3BF5CFAE131CD848D72DED09743291813EF9B26FCC1C0523,A2D750E826DF8D923570DBB7F6722061FE21DFCFC206CB5E45FA4A5812E9BFDA
            pubkey=026D5F18ED32B87364DCCE38510035FFE590D6F957F0C8F3FF725E7BB363C49BAC,0383AD77667C7265C99C52021B49B025F629B98A1C38E76D2050530A1CFC8ECB78
        """.trimIndent())

        assertFailure {
            PostchainClientConfig.fromProperties(configFile.absolutePath)
        }.isInstanceOf(IllegalArgumentException::class)
                .message().isEqualTo("Missing 'brid' (blockchain-rid)")
    }
}

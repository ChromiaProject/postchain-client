package net.postchain.client.request

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

private const val BASE_URL = "http://localhost:7740"

class EndpointSanitationTest {

    @ParameterizedTest
    @MethodSource("endpoints")
    fun `Endpoints gets sanitized`(endpoint: String) {
        assertThat(EndpointPool.singleUrl(endpoint).single().url).isEqualTo(BASE_URL)
        assertThat(EndpointPool.default(listOf(endpoint)).single().url).isEqualTo(BASE_URL)
    }

    companion object {
        @JvmStatic
        fun endpoints() = arrayListOf(
                arrayOf("$BASE_URL  "),
                arrayOf("$BASE_URL/ "),
                arrayOf("$BASE_URL/\t"),
                arrayOf("$BASE_URL/\n"),
                arrayOf("$BASE_URL/    "),
                arrayOf("$BASE_URL/\r"),
                arrayOf("$BASE_URL/\n\n"),
                arrayOf("  $BASE_URL"),
                arrayOf("\n$BASE_URL"),
        )
    }
}
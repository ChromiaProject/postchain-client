package net.postchain.d1.client

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.configureFor
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.ok
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import net.postchain.chain0.anchoring_chain_common.AnchoringTxWithOpIndex
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.BlockHeaderData
import net.postchain.client.core.TxRid
import net.postchain.client.exception.ClientError
import net.postchain.client.impl.ConfirmationProofData
import net.postchain.client.impl.PostchainClientImpl
import net.postchain.client.request.EndpointPool
import net.postchain.client.transaction.TransactionBuilder
import net.postchain.common.BlockchainRid
import net.postchain.common.data.Hash
import net.postchain.common.toHex
import net.postchain.common.wrap
import net.postchain.crypto.CURVE_PARAMS
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.crypto.Signature
import net.postchain.crypto.encodeSignature
import net.postchain.crypto.secp256k1_decodeSignature
import net.postchain.d1.client.IccfBuilder.Companion.ICCF_OP_NAME
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.generateProof
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV2
import net.postchain.gtv.merkleHash
import net.postchain.gtx.Gtx
import net.postchain.gtx.GtxBody
import net.postchain.gtx.GtxBuilder
import net.postchain.gtx.GtxOp
import org.http4k.core.ContentType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IccfTest {
    companion object {
        private const val EXTRA_HEADER_MERKLE_HASH_VERSION_NAME = "merkle_hash_version"
    }

    private val server = WireMockServer(wireMockConfig().port(0))
    private lateinit var mockServerUrl: String

    private val cryptoSystem = Secp256K1CryptoSystem()
    private val hashCalculator = GtvMerkleHashCalculatorV2(cryptoSystem)

    private val sourceBlockchainRID = BlockchainRid.buildRepeat(5)
    private val clusterATargetBlockchainRID = BlockchainRid.buildRepeat(1)
    private val clusterBTargetBlockchainRID = BlockchainRid.buildRepeat(2)
    private val sourceClusterAnchoringChain = BlockchainRid.buildRepeat(3)
    private val directoryChain = BlockchainRid.buildRepeat(4)
    private val clusterA = "clusterA"
    private val clusterB = "clusterB"

    lateinit var chromiaClient: ChromiaClient

    private val clientTxSigners = listOf(
            cryptoSystem.generateKeyPair(),
            cryptoSystem.generateKeyPair()
    )
    private val clientTx = GtxBuilder(sourceBlockchainRID, clientTxSigners.map { it.pubKey.data }, cryptoSystem, hashCalculator)
            .addOperation("dummy")
            .finish()
            .sign(cryptoSystem.buildSigMaker(clientTxSigners[0]))
            .sign(cryptoSystem.buildSigMaker(clientTxSigners[1]))
            .buildGtx()
    private val clientTxHash = clientTx.toGtv().merkleHash(hashCalculator)
    private val dummyBlockHeader = BlockHeaderData(
            blockchainRid = BlockchainRid.ZERO_RID.data,
            previousBlockRid = ByteArray(0),
            merkleRootHash = ByteArray(0),
            timestamp = 0,
            height = 0,
            dependencies = GtvNull,
            extra = mapOf(EXTRA_HEADER_MERKLE_HASH_VERSION_NAME to gtv(2))
    ).toGtv()

    @BeforeEach
    fun setup() {
        server.start()
        mockServerUrl = "http://localhost:${server.port()}"
        configureFor("localhost", server.port())
        val directoryChainConfig = PostchainClientConfig(blockchainRid = directoryChain, endpointPool = EndpointPool.singleUrl(mockServerUrl))
        val directoryChainClient = PostchainClientImpl(directoryChainConfig)
        stubQuery(baseUrl = "", directoryChain, "cm_get_blockchain_cluster",
                mapOf("brid" to gtv(sourceBlockchainRID)),
                gtv(clusterA))
        stubQuery(baseUrl = "", directoryChain, "cm_get_blockchain_cluster",
                mapOf("brid" to gtv(clusterATargetBlockchainRID)),
                gtv(clusterA))
        stubQuery(baseUrl = "", directoryChain, "cm_get_blockchain_cluster",
                mapOf("brid" to gtv(clusterBTargetBlockchainRID)),
                gtv(clusterB))

        chromiaClient = mock {
            on { config } doReturn directoryChainConfig
            on { getClient(any(), any(), any()) } doAnswer {
                val blockchainRid = it.arguments[0] as BlockchainRid
                val client = PostchainClientImpl(PostchainClientConfig(blockchainRid = blockchainRid, endpointPool = EndpointPool.singleUrl(mockServerUrl)))
                ChromiaPostchainClient(directoryChainClient, client, client, addNop = false)
            }
            on { getDirectoryChainClient(any(), any()) } doAnswer {
                ChromiaPostchainClient(directoryChainClient, directoryChainClient, directoryChainClient, addNop = false)
            }
            on { getClusterAnchoringClient(anyString()) } doAnswer {
                // val cluster = it.arguments[0] as String
                val client = PostchainClientImpl(PostchainClientConfig(blockchainRid = sourceClusterAnchoringChain, endpointPool = EndpointPool.singleUrl(mockServerUrl)))
                ChromiaPostchainClient(directoryChainClient, client, client, addNop = false)
            }
        }
    }

    @Test
    fun intraClusterIccf() {
        val txProof = generateAndStubConfirmationProof()

        verifyIntraClusterIccf(txProof)
    }

    @Test
    fun intraNetworkIccf() {
        val txProof = generateAndStubConfirmationProof()

        val dummyBlockRid = dummyBlockHeader.merkleHash(hashCalculator)
        val anchoringTx = Gtx(GtxBody(sourceClusterAnchoringChain, listOf(GtxOp("mock_anchoring_op")), listOf()), listOf())
        val anchoringTxRid = anchoringTx.calculateTxRid(hashCalculator)
        val anchoringChainResponse = AnchoringTxWithOpIndex(anchoringTxRid.wrap(), anchoringTx.encode().wrap(), 0)

        stubQuery(baseUrl = "", sourceClusterAnchoringChain, "get_anchoring_transaction_for_block_rid",
                mapOf("blockchain_rid" to gtv(sourceBlockchainRID), "block_rid" to gtv(dummyBlockRid)),
                GtvObjectMapper.toGtvDictionary(anchoringChainResponse))

        // Does not really matter what the content of the proof is
        val anchoringConfirmationProof = GtvEncoder.encodeGtv(gtv(
                "hash" to gtv(anchoringTx.toGtv().merkleHash(hashCalculator))
        ))
        stubFor(get("/tx/${sourceClusterAnchoringChain.toHex()}/${anchoringTxRid.toHex()}/confirmationProof").willReturn(okJson(
                """{"proof":"${anchoringConfirmationProof.toHex()}"}"""
        )))
        stubFor(get("/tx/${sourceClusterAnchoringChain.toHex()}/${anchoringTxRid.toHex()}").willReturn(okJson(
                """{"tx":"${clientTx.encodeHex()}"}"""
        )))

        val txBuilder: TransactionBuilder = mock {
            on { blockchainRid } doReturn clusterBTargetBlockchainRID
        }
        val provenTx = IccfBuilder(chromiaClient).addIccfProof(
                txBuilder,
                TxRid(clientTx.calculateTxRid(hashCalculator).toHex()),
                clientTxHash,
                sourceBlockchainRID,
                forceIntraNetworkIccfOperation = false,
        )
        verify(txBuilder).addOperation(
                ICCF_OP_NAME,
                gtv(sourceBlockchainRID),
                gtv(clientTxHash),
                gtv(txProof),
                gtv(anchoringTx.encode()),
                gtv(0),
                gtv(anchoringConfirmationProof)
        )
        assertThat(provenTx).isEqualTo(clientTx.toGtv())
    }


    @Test
    fun forceIntraNetworkIccf() {
        val txProof = generateAndStubConfirmationProof()
        val dummyBlockRid = dummyBlockHeader.merkleHash(hashCalculator)
        val anchoringTx = Gtx(GtxBody(sourceClusterAnchoringChain, listOf(GtxOp("mock_anchoring_op")), listOf()), listOf())
        val anchoringTxRid = anchoringTx.calculateTxRid(hashCalculator)
        val anchoringChainResponse = AnchoringTxWithOpIndex(anchoringTxRid.wrap(), anchoringTx.encode().wrap(), 0)

        stubQuery(baseUrl = "", sourceClusterAnchoringChain, "get_anchoring_transaction_for_block_rid",
                mapOf("blockchain_rid" to gtv(sourceBlockchainRID), "block_rid" to gtv(dummyBlockRid)),
                GtvObjectMapper.toGtvDictionary(anchoringChainResponse))

        // Does not really matter what the content of the proof is
        val anchoringConfirmationProof = GtvEncoder.encodeGtv(GtvObjectMapper.toGtvDictionary(ConfirmationProofData(
                anchoringTx.toGtv().merkleHash(hashCalculator),
                GtvEncoder.encodeGtv(dummyBlockHeader),
                ByteArray(0),
                gtv(listOf(gtv(0))).generateProof(listOf(0), hashCalculator),
                0
        )))

        stubFor(get("/tx/${sourceClusterAnchoringChain.toHex()}/${anchoringTxRid.toHex()}/confirmationProof").willReturn(okJson(
                """{"proof":"${anchoringConfirmationProof.toHex()}"}"""
        )))
        stubFor(get("/tx/${sourceClusterAnchoringChain.toHex()}/${anchoringTxRid.toHex()}").willReturn(okJson(
                """{"tx":"${clientTx.encodeHex()}"}"""
        )))

        val txBuilder: TransactionBuilder = mock {
            on { blockchainRid } doReturn clusterATargetBlockchainRID
        }
        val provenTx = IccfBuilder(chromiaClient).addIccfProof(
                txBuilder,
                TxRid(clientTx.calculateTxRid(hashCalculator).toHex()),
                clientTxHash,
                sourceBlockchainRID,
                forceIntraNetworkIccfOperation = true
        )
        verify(txBuilder).addOperation(
                ICCF_OP_NAME,
                gtv(sourceBlockchainRID),
                gtv(clientTxHash),
                gtv(txProof),
                gtv(anchoringTx.encode()),
                gtv(0),
                gtv(anchoringConfirmationProof)
        )
        assertThat(provenTx).isEqualTo(clientTx.toGtv())
    }

    @Test
    fun hashMismatchWithReformattedSignature() {
        // For every ECDSA signature (r,s), the signature (r, -s (mod N)) is a valid signature of the same message
        // Source: https://en.bitcoin.it/wiki/Transaction_malleability#Signature_Malleability
        val (r, s) = secp256k1_decodeSignature(clientTx.signatures[0])
        val newSignatureSValue = s.negate().mod(CURVE_PARAMS.n)
        val newSignature = encodeSignature(r, newSignatureSValue)

        val actualTx = GtxBuilder(sourceBlockchainRID, clientTxSigners.map { it.pubKey.data }, cryptoSystem, hashCalculator)
                .addOperation("dummy")
                .finish()
                .sign(Signature(clientTxSigners[0].pubKey.data, newSignature))
                .sign(cryptoSystem.buildSigMaker(clientTxSigners[1]))
                .buildGtx()
        val actualTxHash = actualTx.toGtv().merkleHash(hashCalculator)
        assertThat(clientTxHash.contentEquals(actualTxHash)).isFalse()

        val txProof = generateAndStubConfirmationProof(actualTxHash)

        stubFor(get("/tx/${sourceBlockchainRID.toHex()}/${clientTx.calculateTxRid(hashCalculator).toHex()}").willReturn(okJson(
                """{"tx":"${actualTx.encodeHex()}"}"""
        )))

        verifyIntraClusterIccf(txProof, actualTxHash, verifyProvenTx = false)
    }

    @Test
    fun incorrectSignature() {
        val incorrectSignature = cryptoSystem.buildSigMaker(clientTxSigners[0]).signDigest(gtv("incorrect").merkleHash(hashCalculator))

        val actualTx = Gtx(
                GtxBody(
                        sourceBlockchainRID,
                        listOf(GtxOp("dummy")),
                        clientTxSigners.map { it.pubKey.data }
                ),
                listOf(incorrectSignature.data, clientTx.signatures[1])
        )
        val actualTxHash = actualTx.toGtv().merkleHash(hashCalculator)

        generateAndStubConfirmationProof(actualTxHash)

        stubFor(get("/tx/${sourceBlockchainRID.toHex()}/${clientTx.calculateTxRid(hashCalculator).toHex()}").willReturn(okJson(
                """{"tx":"${actualTx.encodeHex()}"}"""
        )))

        val txBuilder: TransactionBuilder = mock {
            on { blockchainRid } doReturn clusterATargetBlockchainRID
        }
        assertThrows<ClientError> {
            IccfBuilder(chromiaClient).addIccfProof(
                    txBuilder,
                    TxRid(clientTx.calculateTxRid(hashCalculator).toHex()),
                    clientTxHash,
                    sourceBlockchainRID,
                    forceIntraNetworkIccfOperation = false,
            )
        }
        verify(txBuilder, never()).addOperation(any(), any())
    }

    private fun stubQuery(baseUrl: String, brid: BlockchainRid, name: String, args: Map<String, Gtv>, response: Gtv) {
        stubFor(
                stubQueryBuilder(baseUrl, brid, name, args, response)
        )
    }

    private fun stubQueryBuilder(baseUrl: String, brid: BlockchainRid, name: String, args: Map<String, Gtv>, response: Gtv): MappingBuilder =
            get("$baseUrl/query_gtv/${brid}?type=${name}&%7Eargs=${GtvEncoder.encodeGtv(gtv(args)).toHex()}")
                    .willReturn(ok(ContentType.OCTET_STREAM.value).withBody(GtvEncoder.encodeGtv(response)))

    private fun generateAndStubConfirmationProof(proofHashOverride: Hash? = null): ByteArray {
        // We are only concerned about the hash and block header fields
        val confirmationProof = GtvEncoder.encodeGtv(GtvObjectMapper.toGtvDictionary(ConfirmationProofData(
                proofHashOverride ?: clientTxHash,
                GtvEncoder.encodeGtv(dummyBlockHeader),
                ByteArray(0),
                gtv(listOf(gtv(0))).generateProof(listOf(0), hashCalculator),
                0
        )))

        stubFor(get("/tx/${sourceBlockchainRID.toHex()}/${clientTx.calculateTxRid(hashCalculator).toHex()}/confirmationProof").willReturn(okJson(
                """{"proof":"${confirmationProof.toHex()}"}"""
        )))
        stubFor(get("/tx/${sourceBlockchainRID.toHex()}/${clientTx.calculateTxRid(hashCalculator).toHex()}").willReturn(okJson(
                """{"tx":"${clientTx.encodeHex()}"}"""
        )))

        return confirmationProof
    }

    private fun verifyIntraClusterIccf(txProof: ByteArray, proofHashOverride: Hash? = null, verifyProvenTx: Boolean = false) {
        val txBuilder: TransactionBuilder = mock {
            on { blockchainRid } doReturn clusterATargetBlockchainRID
        }
        val provenTx = IccfBuilder(chromiaClient).addIccfProof(
                txBuilder,
                TxRid(clientTx.calculateTxRid(hashCalculator).toHex()),
                clientTxHash,
                sourceBlockchainRID,
                forceIntraNetworkIccfOperation = false,
        )
        verify(txBuilder).addOperation(
                ICCF_OP_NAME,
                gtv(sourceBlockchainRID),
                gtv(proofHashOverride ?: clientTxHash),
                gtv(txProof)
        )
        if (verifyProvenTx) {
            assertThat(provenTx).isEqualTo(clientTx.toGtv())
        }
    }
}

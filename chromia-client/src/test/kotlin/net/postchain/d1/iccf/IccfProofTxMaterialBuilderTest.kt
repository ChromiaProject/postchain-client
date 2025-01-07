package net.postchain.d1.iccf

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.configureFor
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.ok
import com.github.tomakehurst.wiremock.client.WireMock.okForContentType
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import net.postchain.chain0.anchoring_chain_common.AnchoringTxWithOpIndex
import net.postchain.client.core.TxRid
import net.postchain.common.BlockchainRid
import net.postchain.common.data.Hash
import net.postchain.common.exception.UserMistake
import net.postchain.common.toHex
import net.postchain.common.wrap
import net.postchain.crypto.CURVE_PARAMS
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.crypto.Signature
import net.postchain.crypto.encodeSignature
import net.postchain.crypto.secp256k1_decodeSignature
import net.postchain.d1.client.ChromiaClientProvider
import net.postchain.d1.client.ConfirmationProofData
import net.postchain.d1.cluster.ClusterManagement
import net.postchain.d1.cluster.D1ClusterInfo
import net.postchain.d1.iccf.IccfProofTxMaterialBuilder.Companion.ICCF_OP_NAME
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
import net.postchain.gtx.GtxQuery
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

private const val JsonContentType = "application/json"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IccfProofTxMaterialBuilderTest {
    private val server = WireMockServer(wireMockConfig().port(7740))
    private val mockServerUrl = "http://localhost:7740"

    private val cryptoSystem = Secp256K1CryptoSystem()
    private val hashCalculator = GtvMerkleHashCalculatorV2(cryptoSystem)

    private val sourceBlockchainRID = BlockchainRid.buildRepeat(0)
    private val clusterATargetBlockchainRID = BlockchainRid.buildRepeat(1)
    private val clusterBTargetBlockchainRID = BlockchainRid.buildRepeat(2)
    private val sourceClusterAnchoringChain = BlockchainRid.buildRepeat(3)
    private val clusterA = "clusterA"
    private val clusterB = "clusterB"

    private val clusterManagement: ClusterManagement = mock {
        on { getBlockchainApiUrls(any()) } doReturn listOf(mockServerUrl)
        on { getClusterOfBlockchain(sourceBlockchainRID) } doReturn clusterA
        on { getClusterOfBlockchain(clusterATargetBlockchainRID) } doReturn clusterA
        on { getClusterOfBlockchain(clusterBTargetBlockchainRID) } doReturn clusterB
        on { getClusterInfo(clusterA) } doReturn D1ClusterInfo(clusterA, sourceClusterAnchoringChain, listOf())
    }
    val chromiaClientProvider = ChromiaClientProvider(clusterManagement)

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
    private val dummyBlockHeader = gtv(listOf(GtvNull, GtvNull, GtvNull, GtvNull, GtvNull, GtvNull, gtv(mapOf(
            EXTRA_HEADER_MERKLE_HASH_VERSION_NAME to gtv(2)
    ))))

    @BeforeEach
    fun setup() {
        server.start()
        configureFor("localhost", server.port())
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

        stubFor(post("/query_gtv/${sourceClusterAnchoringChain.toHex()}")
                .withRequestBody(binaryEqualTo(
                        GtxQuery("get_anchoring_transaction_for_block_rid", gtv(
                                "blockchain_rid" to gtv(sourceBlockchainRID),
                                "block_rid" to gtv(dummyBlockRid)
                        )).encode()
                ))
                .willReturn(ok().withBody(GtvEncoder.encodeGtv(GtvObjectMapper.toGtvDictionary(anchoringChainResponse))))
        )

        // Does not really matter what the content of the proof is
        val anchoringConfirmationProof = GtvEncoder.encodeGtv(gtv(
                "hash" to gtv(anchoringTx.toGtv().merkleHash(hashCalculator))
        ))
        stubFor(get("/tx/${sourceClusterAnchoringChain.toHex()}/${anchoringTxRid.toHex()}/confirmationProof").willReturn(okForContentType(
                JsonContentType, """{"proof":"${anchoringConfirmationProof.toHex()}"}"""
        )))

        val iccfTxMaterial = IccfProofTxMaterialBuilder(chromiaClientProvider).build(
                TxRid(clientTx.gtxBody.calculateTxRid(hashCalculator).toHex()),
                clientTxHash,
                clientTxSigners.map { it.pubKey },
                sourceBlockchainRID,
                clusterBTargetBlockchainRID
        )

        val iccfTx = iccfTxMaterial.txBuilder.finish().buildGtx()
        assertThat(iccfTx.gtxBody.operations).containsExactly(GtxOp(
                ICCF_OP_NAME,
                gtv(sourceBlockchainRID),
                gtv(clientTxHash),
                gtv(txProof),
                gtv(anchoringTx.encode()),
                gtv(0),
                gtv(anchoringConfirmationProof)
        ))
    }


    @Test
    fun forceIntraNetworkIccf() {
        val txProof = generateAndStubConfirmationProof()
        val dummyBlockRid = dummyBlockHeader.merkleHash(hashCalculator)
        val anchoringTx = Gtx(GtxBody(sourceClusterAnchoringChain, listOf(GtxOp("mock_anchoring_op")), listOf()), listOf())
        val anchoringTxRid = anchoringTx.calculateTxRid(hashCalculator)
        val anchoringChainResponse = AnchoringTxWithOpIndex(anchoringTxRid.wrap(), anchoringTx.encode().wrap(), 0)

        stubFor(post("/query_gtv/${sourceClusterAnchoringChain.toHex()}")
                .withRequestBody(binaryEqualTo(
                        GtxQuery("get_anchoring_transaction_for_block_rid", gtv(
                                "blockchain_rid" to gtv(sourceBlockchainRID),
                                "block_rid" to gtv(dummyBlockRid)
                        )).encode()
                ))
                .willReturn(ok().withBody(GtvEncoder.encodeGtv(GtvObjectMapper.toGtvDictionary(anchoringChainResponse))))
        )

        // Does not really matter what the content of the proof is
        val anchoringConfirmationProof = GtvEncoder.encodeGtv(GtvObjectMapper.toGtvDictionary(ConfirmationProofData(
                anchoringTx.toGtv().merkleHash(hashCalculator),
                GtvEncoder.encodeGtv(dummyBlockHeader),
                ByteArray(0),
                gtv(listOf(gtv(0))).generateProof(listOf(0), hashCalculator),
                0
        )))

        stubFor(get("/tx/${sourceClusterAnchoringChain.toHex()}/${anchoringTxRid.toHex()}/confirmationProof").willReturn(okForContentType(
                JsonContentType, """{"proof":"${anchoringConfirmationProof.toHex()}"}"""
        )))

        val iccfTxMaterial = IccfProofTxMaterialBuilder(chromiaClientProvider).build(
                TxRid(clientTx.gtxBody.calculateTxRid(hashCalculator).toHex()),
                clientTxHash,
                clientTxSigners.map { it.pubKey },
                sourceBlockchainRID,
                clusterATargetBlockchainRID,
                forceIntraNetworkIccfOperation = true
        )

        val iccfTx = iccfTxMaterial.txBuilder.finish().buildGtx()
        assertThat(iccfTx.gtxBody.operations).containsExactly(GtxOp(
                ICCF_OP_NAME,
                gtv(sourceBlockchainRID),
                gtv(clientTxHash),
                gtv(txProof),
                gtv(anchoringTx.encode()),
                gtv(0),
                gtv(anchoringConfirmationProof)
        ))
    }

    @Test
    fun hashAndSignatureMismatch() {
        val actualTx = GtxBuilder(sourceBlockchainRID, listOf(clientTxSigners[0].pubKey.data), cryptoSystem, hashCalculator)
                .addOperation("dummy")
                .finish()
                .sign(cryptoSystem.buildSigMaker(clientTxSigners[0]))
                .buildGtx()
        val actualTxHash = actualTx.toGtv().merkleHash(hashCalculator)

        generateAndStubConfirmationProof(actualTxHash)

        stubFor(get("/tx/${sourceBlockchainRID.toHex()}/${clientTx.gtxBody.calculateTxRid(hashCalculator).toHex()}").willReturn(okForContentType(
                JsonContentType, """{"tx":"${actualTx.encodeHex()}"}"""
        )))

        assertThrows<UserMistake> {
            IccfProofTxMaterialBuilder(chromiaClientProvider).build(
                    TxRid(clientTx.gtxBody.calculateTxRid(hashCalculator).toHex()),
                    clientTxHash,
                    clientTxSigners.map { it.pubKey },
                    sourceBlockchainRID,
                    clusterATargetBlockchainRID
            )
        }
    }

    @Test
    fun hashMismatchWithReorderedSignatures() {
        val actualTx = GtxBuilder(sourceBlockchainRID, clientTxSigners.map { it.pubKey.data }, cryptoSystem, hashCalculator)
                .addOperation("dummy")
                .finish()
                .sign(cryptoSystem.buildSigMaker(clientTxSigners[1]))
                .sign(cryptoSystem.buildSigMaker(clientTxSigners[0]))
                .buildGtx()
        val actualTxHash = actualTx.toGtv().merkleHash(hashCalculator)
        assertThat(clientTxHash.contentEquals(actualTxHash)).isFalse()

        val txProof = generateAndStubConfirmationProof(actualTxHash)

        stubFor(get("/tx/${sourceBlockchainRID.toHex()}/${clientTx.gtxBody.calculateTxRid(hashCalculator).toHex()}").willReturn(okForContentType(
                JsonContentType,
                """{"tx":"${actualTx.encodeHex()}"}"""
        )))

        verifyIntraClusterIccf(txProof, actualTxHash)
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

        stubFor(get("/tx/${sourceBlockchainRID.toHex()}/${clientTx.gtxBody.calculateTxRid(hashCalculator).toHex()}").willReturn(okForContentType(
                JsonContentType, """{"tx":"${actualTx.encodeHex()}"}"""
        )))

        verifyIntraClusterIccf(txProof, actualTxHash)
    }

    private fun generateAndStubConfirmationProof(proofHashOverride: Hash? = null): ByteArray {
        // We are only concerned about the hash and block header fields
        val confirmationProof = GtvEncoder.encodeGtv(GtvObjectMapper.toGtvDictionary(ConfirmationProofData(
                proofHashOverride ?: clientTxHash,
                GtvEncoder.encodeGtv(dummyBlockHeader),
                ByteArray(0),
                gtv(listOf(gtv(0))).generateProof(listOf(0), hashCalculator),
                0
        )))

        stubFor(get("/tx/${sourceBlockchainRID.toHex()}/${clientTx.gtxBody.calculateTxRid(hashCalculator).toHex()}/confirmationProof").willReturn(okForContentType(
                JsonContentType, """{"proof":"${confirmationProof.toHex()}"}"""
        )))

        return confirmationProof
    }

    private fun verifyIntraClusterIccf(txProof: ByteArray, proofHashOverride: Hash? = null) {
        val iccfTxMaterial = IccfProofTxMaterialBuilder(chromiaClientProvider).build(
                TxRid(clientTx.gtxBody.calculateTxRid(hashCalculator).toHex()),
                clientTxHash,
                clientTxSigners.map { it.pubKey },
                sourceBlockchainRID,
                clusterATargetBlockchainRID
        )

        val iccfTx = iccfTxMaterial.txBuilder.finish().buildGtx()
        assertThat(iccfTx.gtxBody.operations).containsExactly(GtxOp(
                ICCF_OP_NAME,
                gtv(sourceBlockchainRID),
                gtv(proofHashOverride ?: clientTxHash),
                gtv(txProof)
        ))
        if (proofHashOverride == null) {
            assertThat(iccfTxMaterial.updatedTx).isNull()
        } else {
            assertThat(iccfTxMaterial.updatedTx).isNotNull()
        }
    }
}

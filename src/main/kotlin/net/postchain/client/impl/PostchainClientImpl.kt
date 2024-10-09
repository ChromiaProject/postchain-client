// Copyright (c) 2020 ChromaWay AB. See README for license information.

package net.postchain.client.impl

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import mu.KLogging
import net.postchain.client.config.PostchainClientConfig
import net.postchain.client.core.BlockDetail
import net.postchain.client.core.BlockRid
import net.postchain.client.core.PostchainClient
import net.postchain.client.core.TransactionInfo
import net.postchain.client.core.TransactionResult
import net.postchain.client.core.TxRid
import net.postchain.client.core.Version
import net.postchain.client.defaultHttpHandler
import net.postchain.client.exception.ClientError
import net.postchain.client.exception.NotFoundError
import net.postchain.client.request.Endpoint
import net.postchain.client.transaction.TransactionBuilder
import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.common.rest.HighestBlockHeightAnchoringCheck
import net.postchain.common.toHex
import net.postchain.common.tx.TransactionStatus
import net.postchain.common.tx.TransactionStatus.CONFIRMED
import net.postchain.common.tx.TransactionStatus.REJECTED
import net.postchain.common.tx.TransactionStatus.UNKNOWN
import net.postchain.common.tx.TransactionStatus.WAITING
import net.postchain.crypto.KeyPair
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDecoder
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtv.merkleHash
import net.postchain.gtx.Gtx
import net.postchain.gtx.GtxQuery
import org.apache.commons.io.input.BoundedInputStream
import org.apache.commons.lang3.exception.ExceptionUtils
import org.http4k.core.*
import java.io.EOFException
import java.io.IOException
import java.lang.Thread.sleep
import java.lang.reflect.Type
import java.time.Duration
import java.util.zip.GZIPInputStream

object Header {
    const val ContentType = "Content-Type"
    const val Accept = "Accept"
    const val XPostchainSignature = "X-Postchain-Signature"
}

class PostchainClientImpl(
        override val config: PostchainClientConfig,
        httpClient: HttpHandler = defaultHttpHandler(config),
) : PostchainClient {

    companion object : KLogging()

    private val blockchainRIDHex = config.blockchainRid.toHex()
    private val blockchainRIDOrID = config.queryByChainId?.let { "iid_$it" } ?: blockchainRIDHex
    private val cryptoSystem = config.cryptoSystem
    private val calculator = GtvMerkleHashCalculator(cryptoSystem)
    private val gson = Gson()
    private val requestStrategy = config.requestStrategy.create(config, httpClient)

    override fun transactionBuilder() = transactionBuilder(config.signers)

    override fun transactionBuilder(signers: List<KeyPair>) = TransactionBuilder(
            this,
            config.blockchainRid,
            signers.map { it.pubKey.data },
            signers.map { it.sigMaker(cryptoSystem) },
            cryptoSystem,
            config.maxTxSize
    )

    @Throws(IOException::class)
    override fun query(name: String, args: Gtv): Gtv = requestStrategy.request({ endpoint ->
        val gtxQuery = GtxQuery(name, args)
        Request(Method.POST, "${endpoint.url}/query_gtv/$blockchainRIDOrID")
                .header(Header.ContentType, ContentType.OCTET_STREAM.value)
                .header(Header.Accept, ContentType.OCTET_STREAM.value)
                .body(MemoryBody(gtxQuery.encode()))
    }, { response, endpoint ->
        decodeGtv("query", response, endpoint)
    }, { response, endpoint ->
        buildExceptionFromErrorResponse("query", response, endpoint)
    },
            true)

    @Throws(IOException::class)
    override fun currentBlockHeight(container: String?): Long = requestStrategy.request({ endpoint ->
        Request(Method.GET, "${endpoint.url}/blockchain/$blockchainRIDOrID/height")
                .header(Header.Accept, ContentType.APPLICATION_JSON.value)
                .let {
                    if (container != null) it.query("container", container) else it
                }
    }, { response, endpoint ->
        parseJson("currentBlockHeight", response, endpoint, CurrentBlockHeight::class.java).blockHeight
    }, { response, endpoint ->
        buildExceptionFromErrorResponse("currentBlockHeight", response, endpoint)
    },
            false)

    @Throws(IOException::class)
    override fun blockAtHeight(height: Long): BlockDetail? = requestStrategy.request({ endpoint ->
        Request(Method.GET, "${endpoint.url}/blocks/$blockchainRIDOrID/height/$height")
                .header(Header.Accept, ContentType.OCTET_STREAM.value)
    }, { response, endpoint ->
        val gtv = decodeGtv("blockAtHeight", response, endpoint)
        if (gtv.isNull()) null else GtvObjectMapper.fromGtv(gtv, BlockDetail::class)
    }, { response, endpoint ->
        buildExceptionFromErrorResponse("blockAtHeight", response, endpoint)
    },
            true)

    @Throws(IOException::class)
    override fun postTransaction(tx: Gtx): TransactionResult {
        val txRid = TxRid(tx.calculateTxRid(calculator).toHex())
        return requestStrategy.request({ endpoint ->
            Request(Method.POST, "${endpoint.url}/tx/$blockchainRIDHex")
                    .header(Header.ContentType, ContentType.OCTET_STREAM.value)
                    .header(Header.Accept, ContentType.OCTET_STREAM.value)
                    .body(MemoryBody(tx.encode()))
        }, { response, _ ->
            TransactionResult(txRid, WAITING, response.status.code, response.status.description)
        }, { response, _ ->
            val rejectReason = parseErrorResponse(response)
            TransactionResult(txRid, REJECTED, response.status.code, rejectReason)
        }, false)
    }

    @Throws(IOException::class)
    override fun postTransactionAwaitConfirmation(tx: Gtx): TransactionResult {
        val result = postTransaction(tx)
        if (result.status == REJECTED) {
            return result
        }
        return awaitConfirmation(result.txRid, config.statusPollCount, config.statusPollInterval)
    }

    @Throws(IOException::class)
    override fun awaitConfirmation(txRid: TxRid, retries: Int, pollInterval: Duration): TransactionResult {
        var lastKnownTxResult = TransactionResult(txRid, UNKNOWN, null, null)
        // keep polling till getting Confirmed or Rejected
        run poll@{
            repeat(retries) {
                try {
                    lastKnownTxResult = checkTxStatus(txRid)
                    if (lastKnownTxResult.status == CONFIRMED || lastKnownTxResult.status == REJECTED) return@poll
                } catch (e: ClientError) {
                    logger.warn { "Unable to poll for new block: ${e.errorMessage}" }
                    lastKnownTxResult = TransactionResult(txRid, UNKNOWN, null, null)
                } catch (e: Exception) {
                    logger.warn(e) { "Unable to poll for new block: $e" }
                    lastKnownTxResult = TransactionResult(txRid, UNKNOWN, null, null)
                }
                sleep(pollInterval.toMillis())
            }
        }
        return lastKnownTxResult
    }

    @Throws(IOException::class)
    override fun checkTxStatus(txRid: TxRid): TransactionResult = requestStrategy.request({ endpoint ->
        Request(Method.GET, "${endpoint.url}/tx/$blockchainRIDOrID/${txRid.rid}/status")
                .header(Header.Accept, ContentType.APPLICATION_JSON.value)
    }, { response, endpoint ->
        val txStatus = parseJson("checkTxStatus", response, endpoint, TxStatus::class.java)
        TransactionResult(
                txRid,
                TransactionStatus.valueOf(txStatus.status?.uppercase() ?: "UNKNOWN"),
                response.status.code,
                txStatus.rejectReason
        )
    }, { response, endpoint ->
        buildExceptionFromErrorResponse("checkTxStatus", response, endpoint)
    },
            true)

    @Throws(IOException::class)
    override fun confirmationProof(txRid: TxRid): ByteArray = requestStrategy.request({ endpoint ->
        Request(Method.GET, "${endpoint.url}/tx/$blockchainRIDOrID/${txRid.rid}/confirmationProof")
                .header(Header.Accept, ContentType.APPLICATION_JSON.value)
    }, { response, endpoint ->
        val confirmationProof = parseJson("confirmationProof", response, endpoint, ConfirmationProof::class.java)
        confirmationProof.proof.hexStringToByteArray()
    }, { response, endpoint ->
        buildExceptionFromErrorResponse("confirmationProof", response, endpoint)
    },
            true)

    @Throws(IOException::class)
    override fun getTransaction(txRid: TxRid): ByteArray = requestStrategy.request({ endpoint ->
        Request(Method.GET, "${endpoint.url}/tx/$blockchainRIDOrID/${txRid.rid}")
                .header(Header.Accept, ContentType.OCTET_STREAM.value)
    }, { response, endpoint ->
        val contentType = response.header(Header.ContentType) ?: ""
        when {
            contentType == ContentType.OCTET_STREAM.value -> responseStream(response).use { it.readAllBytes() }

            contentType.startsWith(ContentType.APPLICATION_JSON.value) -> {
                val txResponse = parseJson("getTransaction", response, endpoint, Transaction::class.java)
                txResponse.tx.hexStringToByteArray()
            }

            else -> throw ClientError("getTransaction", response.status,
                    "Unexpected response type: $contentType", endpoint)
        }
    }, { response, endpoint ->
        buildExceptionFromErrorResponse("getTransaction", response, endpoint)
    },
            true)

    @Throws(IOException::class)
    override fun getTransactionInfo(txRid: TxRid): TransactionInfo = requestStrategy.request({ endpoint ->
        Request(Method.GET, "${endpoint.url}/transactions/$blockchainRIDOrID/${txRid.rid}")
                .header(Header.Accept, ContentType.APPLICATION_JSON.value)
    }, { response, endpoint ->
        TransactionInfo.fromJson(
                parseJson("getTransactionInfo", response, endpoint, TransactionInfo.Json::class.java)
        )
    }, { response, endpoint ->
        buildExceptionFromErrorResponse("getTransactionInfo", response, endpoint)
    },
            true)

    @Throws(IOException::class)
    override fun getTransactionsInfo(limit: Long, beforeTime: Long, signer: String?): List<TransactionInfo> =
            requestStrategy.request({ endpoint ->
                var request = Request(Method.GET, "${endpoint.url}/transactions/$blockchainRIDOrID")
                        .header(Header.Accept, ContentType.APPLICATION_JSON.value)
                request = if (limit > -1) request.query("limit", limit.toString()) else request
                request = if (beforeTime > -1) request.query("before-time", beforeTime.toString()) else request
                request = if (signer != null) request.query("signer", signer) else request
                request
            }, { response, endpoint ->
                val listType: Type = object : TypeToken<ArrayList<TransactionInfo.Json>>() {}.type
                val infos: List<TransactionInfo.Json> = parseJsonArray("getTransactionsInfo", response, endpoint, listType)
                infos.map { TransactionInfo.fromJson(it) }
            }, { response, endpoint ->
                buildExceptionFromErrorResponse("getTransactionsInfo", response, endpoint)
            },
                    true)

    @Throws(IOException::class)
    override fun getTransactionsCount(): Long = requestStrategy.request({ endpoint ->
        Request(Method.GET, "${endpoint.url}/transactions/$blockchainRIDOrID/count")
                .header(Header.Accept, ContentType.APPLICATION_JSON.value)
    }, { response, endpoint ->
        parseJson("getTransactionsCount", response, endpoint, TransactionsCount::class.java).transactionsCount
    }, { response, endpoint ->
        buildExceptionFromErrorResponse("getTransactionInfo", response, endpoint)
    },
            true)

    @Throws(IOException::class)
    override fun getBlockchainRID(chainIID: Long): BlockchainRid = requestStrategy.request({ endpoint ->
        Request(Method.GET, "${endpoint.url}/brid/iid_$chainIID")
                .header(Header.Accept, ContentType.TEXT_PLAIN.value)
    }, { response, endpoint ->
        parsePlainValue("getBrid", response, endpoint) { BlockchainRid(it.hexStringToByteArray()) }
    }, { response, endpoint ->
        buildExceptionFromErrorResponse("getBrid", response, endpoint)
    },
            true)

    @Throws(IOException::class)
    override fun getConfiguration(height: Long?): Gtv = requestStrategy.request({ endpoint ->
        Request(Method.GET, "${endpoint.url}/config/$blockchainRIDHex").let {
            if (height != null) it.query("height", height.toString()) else it
        }
                .header(Header.Accept, ContentType.OCTET_STREAM.value)
    }, { response, endpoint ->
        decodeGtv("getConfiguration", response, endpoint)
    }, { response, endpoint ->
        buildExceptionFromErrorResponse("getConfiguration", response, endpoint)
    },
            true)

    @Throws(IOException::class)
    override fun validateConfiguration(configuration: Gtv) {

        val configHash = configuration.merkleHash(GtvMerkleHashCalculator(cryptoSystem))
        val signatures = config.signers
                .map { it.sigMaker(cryptoSystem) }
                .map { it.signDigest(configHash) }
                .joinToString(",") { "${it.subjectID.toHex()}:${it.data.toHex()}" }

        return requestStrategy.request({ endpoint ->
            Request(Method.POST, "${endpoint.url}/config/$blockchainRIDHex")
                    .header(Header.ContentType, ContentType.OCTET_STREAM.value)
                    .header(Header.Accept, ContentType.OCTET_STREAM.value)
                    .header(Header.XPostchainSignature, signatures)
                    .body(MemoryBody(GtvEncoder.encodeGtv(configuration)))
        }, { _, _ -> }, { response, endpoint -> buildExceptionFromErrorResponse("validateConfig", response, endpoint) }, false)
    }

    @Throws(IOException::class)
    override fun getVersion(): Version = requestStrategy.request({ endpoint ->
        Request(Method.GET, "${endpoint.url}/version")
                .header(Header.Accept, ContentType.APPLICATION_JSON.value)
    }, { response, endpoint ->
        parseJson("getVersion", response, endpoint, Version::class.java)
    }, { response, endpoint ->
        buildExceptionFromErrorResponse("getVersion", response, endpoint)
    },
            true)

    @Throws(IOException::class)
    override fun getHighestBlockHeightAnchoringCheck(): HighestBlockHeightAnchoringCheck = requestStrategy.request({ endpoint ->
        Request(Method.GET, "${endpoint.url}/highest_block_height_anchoring_check/$blockchainRIDOrID")
                .header(Header.Accept, ContentType.APPLICATION_JSON.value)
    }, { response, endpoint ->
        parseJson("getHighestBlockHeightAnchoringCheck", response, endpoint, HighestBlockHeightAnchoringCheck::class.java)
    }, { response, endpoint ->
        buildExceptionFromErrorResponse("getHighestBlockHeightAnchoringCheck", response, endpoint)
    },
            true)

    @Throws(IOException::class)
    override fun blockByRid(blockRid: BlockRid): BlockDetail? = requestStrategy.request({ endpoint ->
        Request(Method.GET, "${endpoint.url}/blocks/$blockchainRIDOrID/${blockRid.rid}")
                .header(Header.Accept, ContentType.OCTET_STREAM.value)
    }, { response, endpoint ->
        val gtv = decodeGtv("blockByRid", response, endpoint)
        if (gtv.isNull()) null else GtvObjectMapper.fromGtv(gtv, BlockDetail::class)
    }, { response, endpoint ->
        buildExceptionFromErrorResponse("blockByRid", response, endpoint)
    },
            true)


    private fun <T> parsePlainValue(context: String, response: Response, endpoint: Endpoint, converter: (String) -> T): T {
        try {
            return converter(responseStream(response).bufferedReader().readText())
        } catch (e: Exception) {
            throw ClientError(context, response.status, "Parsing response failed", endpoint)
        }
    }

    private fun <T> parseJsonArray(context: String, response: Response, endpoint: Endpoint, listType: Type): List<T> = try {
        gson.fromJson(responseStream(response).bufferedReader(), listType)
    } catch (e: JsonParseException) {
        val rootCause = ExceptionUtils.getRootCause(e)
        if (rootCause is IOException) throw rootCause
        else throw ClientError(context, response.status, "JSON parsing failed", endpoint)
    }

    private fun <T> parseJson(context: String, response: Response, endpoint: Endpoint, cls: Class<T>): T =
            parseJson(responseStream(response), cls)
                    ?: throw ClientError(context, response.status, "JSON parsing failed", endpoint)

    private fun decodeGtv(context: String, response: Response, endpoint: Endpoint) =
            decodeGtv(responseStream(response))
                    ?: throw ClientError(context, response.status, "GTV decoding failed", endpoint)

    private fun buildExceptionFromErrorResponse(context: String, response: Response, endpoint: Endpoint): Nothing {
        val errorMessage = parseErrorResponse(response)
        throw when (response.status) {
            Status.NOT_FOUND -> NotFoundError(context, errorMessage, endpoint)
            else -> ClientError(context, response.status, errorMessage, endpoint)
        }
    }

    private fun parseErrorResponse(response: Response): String {
        val responseStream = responseStream(response)
        val contentType = response.header(Header.ContentType) ?: ""
        return when {
            contentType.startsWith(ContentType.APPLICATION_JSON.value) -> parseJson(responseStream, ErrorResponse::class.java)?.error
                    ?: response.status.description

            contentType == ContentType.OCTET_STREAM.value ->
                decodeGtv(responseStream)?.asString() ?: response.status.description

            else -> {
                val responseBody = responseStream(response).use { it.readAllBytes() }
                if (responseBody.isNotEmpty()) String(responseBody) else response.status.description
            }
        }
    }

    private fun <T> parseJson(responseStream: BoundedInputStream, cls: Class<T>): T? = try {
        val body = responseStream.bufferedReader()
        gson.fromJson(body, cls)
    } catch (e: JsonParseException) {
        val rootCause = ExceptionUtils.getRootCause(e)
        if (rootCause is IOException) throw rootCause
        else null
    }

    private fun decodeGtv(responseStream: BoundedInputStream): Gtv? = try {
        GtvDecoder.decodeGtv(responseStream)
    } catch (e: EOFException) {
        throw e
    } catch (e: IOException) {
        val rootCause = ExceptionUtils.getRootCause(e)
        if (rootCause is IOException) throw rootCause
        else null
    }

    private fun responseStream(response: Response): BoundedInputStream {
        val originalStream = if (response.header("content-encoding") == "gzip") {
            GZIPInputStream(response.body.stream)
        } else {
            response.body.stream
        }
        return BoundedInputStream.builder()
                .setInputStream(originalStream)
                .setMaxCount(config.maxResponseSize.toLong())
                .setPropagateClose(true)
                .get()
    }

    @Throws(IOException::class)
    override fun close() {
        requestStrategy.close()
    }

    /* JSON structures */
    data class TxStatus(val status: String?, val rejectReason: String?)
    data class CurrentBlockHeight(val blockHeight: Long)
    data class ErrorResponse(val error: String)
}

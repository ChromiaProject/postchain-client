package com.chromia.ft4

import com.chromia.ft4.lib.ft4.core.accounts.AuthType
import com.chromia.ft4.lib.ft4.core.auth.Signature
import com.chromia.ft4.lib.ft4.external.accounts.Ft4GetAccountAuthDescriptorsBySignerResult
import com.chromia.ft4.lib.ft4.external.accounts.getAccountAuthDescriptorsBySigner
import com.chromia.ft4.lib.ft4.external.accounts.getAuthDescriptorCounter
import com.chromia.ft4.lib.ft4.external.auth.evmAuthOperation
import com.chromia.ft4.lib.ft4.external.auth.evmSignaturesOperation
import com.chromia.ft4.lib.ft4.external.auth.ftAuthOperation
import com.chromia.ft4.lib.ft4.external.auth.getAuthFlags
import com.chromia.ft4.lib.ft4.external.auth.getAuthMessageTemplate
import net.postchain.client.core.PostchainClient
import net.postchain.client.core.PostchainQuery
import net.postchain.client.transaction.TransactionBuilder
import net.postchain.common.exception.UserMistake
import net.postchain.common.toHex
import net.postchain.common.wrap
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.merkleHash

/**
 * Adds a FT4 authentication operation with Chromia authentication `ft4.ft_auth` to a transaction builder.
 * This operation authorizes the next operation in the transaction to be executed on behalf
 * of the specified account and requires the provided auth descriptor.
 *
 * The whole transaction must be signed by all the signers that are required by the auth
 * descriptor.
 *
 * @param client the PostchainClient
 * @param transactionBuilder the TransactionBuilder
 * @param opName the name of the operation that is being authorized
 * @param chromiaPublicKey the public key of the signer for the operation
 * @param accountId the FT4 account ID
 * @param optionalAuthDescriptorId an optional argument specifying the auth descriptor ID to use for authorization;
 *                                 if not provided, a valid auth descriptor will be determined automatically if possible
 */
fun addFtAuthenticationOp(
        client: PostchainQuery,
        transactionBuilder: TransactionBuilder,
        opName: String,
        chromiaPublicKey: ByteArray,
        accountId: ByteArray,
        optionalAuthDescriptorId: ByteArray? = null
) {
    val authDescriptorId = findValidAuthDescriptorIdForOperation(
            client, opName,
            accountId, chromiaPublicKey, optionalAuthDescriptorId
    )
    transactionBuilder.ftAuthOperation(accountId, authDescriptorId)
}

/**
 * Performs EVM message signing.
 */
fun interface EvmSigner {
    /**
     * Generates an EIP-191 signature using the EVM address and the authentication message.
     *
     * @param address the EVM address
     * @param message the authentication message
     * @return the signature
     */
    fun sign(address: ByteArray, message: String): Signature
}

/**
 * Adds an FT4 authentication operation with EVM authentication `ft4.evm_auth` to a transaction builder.
 * This operation authorizes the next operation in the transaction to be executed on behalf
 * of the specified account and requires the provided auth descriptor.
 *
 * @param client the PostchainClient
 * @param transactionBuilder the TransactionBuilder
 * @param opName The name of the operation requiring authentication.
 * @param opArgs The arguments for the operation.
 * @param evmAddress The Ethereum address that will authenticate the operation.
 * @param accountId the FT4 account ID
 * @param optionalAuthDescriptorId an optional argument specifying the auth descriptor ID to use for authorization;
 *                                 if not provided, a valid auth descriptor will be determined automatically if possible
 * @param signer Used to perform the actual signing and generate the signature that will be included in the operation.
 */
fun addEvmAuthenticationOp(
        client: PostchainClient,
        transactionBuilder: TransactionBuilder,
        opName: String,
        opArgs: List<Gtv>,
        evmAddress: ByteArray,
        accountId: ByteArray,
        optionalAuthDescriptorId: ByteArray? = null,
        signer: EvmSigner,
) {
    val authDescriptorId = findValidAuthDescriptorIdForOperation(
            client, opName,
            accountId, evmAddress, optionalAuthDescriptorId
    )
    val authMessage = fetchEvmAuthMessage(
            client,
            accountId = accountId,
            authDescriptorId = authDescriptorId,
            opName,
            opArgs,
            false
    )
    val signature = signer.sign(evmAddress, authMessage)
    transactionBuilder.evmAuthOperation(accountId, authDescriptorId, listOf(signature))
}

/**
 * Adds an FT4 signatures operation with EVM authentication `ft4.evm_signatures` to a transaction builder.
 * When it is required that an EVM signer signs an operation, but the signer is not part of
 * an account auth descriptor yet, this operation must be used.
 *
 * @param client the PostchainClient
 * @param transactionBuilder the TransactionBuilder
 * @param opName The name of the operation requiring authentication.
 * @param opArgs The arguments for the operation.
 * @param evmAddress The Ethereum address that will authenticate the operation.
 * @param accountId the FT4 account ID
 * @param optionalAuthDescriptorId an optional argument specifying the auth descriptor ID to use for authorization;
 *                                 if not provided, a valid auth descriptor will be determined automatically if possible
 * @param signer Used to perform the actual signing and generate the signature that will be included in the operation.
 */
fun addEvmSignaturesOp(
        client: PostchainClient,
        transactionBuilder: TransactionBuilder,
        opName: String,
        opArgs: List<Gtv>,
        evmAddress: ByteArray,
        accountId: ByteArray,
        optionalAuthDescriptorId: ByteArray? = null,
        signer: EvmSigner,
) {
    val authDescriptorId = findValidAuthDescriptorIdForOperation(
            client, opName,
            accountId, evmAddress, optionalAuthDescriptorId
    )
    val authMessage = fetchEvmAuthMessage(
            client,
            accountId = accountId,
            authDescriptorId = authDescriptorId,
            opName,
            opArgs,
            true
    )
    val signature = signer.sign(evmAddress, authMessage)
    transactionBuilder.evmSignaturesOperation(listOf(evmAddress), listOf(signature))
}

private fun findValidAuthDescriptorIdForOperation(
        client: PostchainQuery,
        opName: String,
        accountId: ByteArray,
        signer: ByteArray,
        optionalAuthDescriptorId: ByteArray?
): ByteArray {
    val flags = client.getAuthFlags(opName)
    val authDescriptors = client.getAccountAuthDescriptorsBySigner(accountId, signer = signer)

    val authDescriptorsCandidates = findAuthDescriptors(authDescriptors, optionalAuthDescriptorId, signer)
    val authDescriptor = if (authDescriptorsCandidates.isEmpty()) {
        throw UserMistake("No valid account descriptor found. User not authorized for operation $opName")
    } else if (authDescriptorsCandidates.size == 1) {
        authDescriptorsCandidates.first()
    } else {
        throw UserMistake("Multiple account descriptors (${authDescriptorsCandidates.size}) found. Need to specify one.")
    }

    if (!isValid(flags, authDescriptor)) {
        throw UserMistake(
                """No valid account descriptor found. 
                    |Operation $opName requires the flag(s): $flags, 
                    |while the flag(s) of the auth descriptor is: ${authDescriptor.flags()}""".trimMargin()
        )
    }
    return authDescriptor.id.data
}

private fun findAuthDescriptors(
        descriptors: List<Ft4GetAccountAuthDescriptorsBySignerResult>,
        optionalAuthDescriptorId: ByteArray?,
        signer: ByteArray
): List<Ft4GetAccountAuthDescriptorsBySignerResult> {
    return descriptors.filter { descriptor ->
        when {
            optionalAuthDescriptorId != null && optionalAuthDescriptorId.isNotEmpty() -> {
                descriptor.id == optionalAuthDescriptorId.wrap()
            }

            descriptor.authType == AuthType.S -> {
                descriptor.getSingleSigner().wrap() == signer.wrap()
            }

            descriptor.authType == AuthType.M -> {
                descriptor.getMultiSigners().any { oneSigner ->
                    oneSigner.wrap() == signer.wrap()
                }
            }

            else -> {
                throw UserMistake("AuthType: ${descriptor.authType} is not supported")
            }
        }
    }
}

private fun isValid(requiredFlags: List<String>, authDescriptor: Ft4GetAccountAuthDescriptorsBySignerResult): Boolean {
    val flags = authDescriptor.flags()
    return flags.containsAll(requiredFlags)
}

fun fetchEvmAuthMessage(
        client: PostchainClient,
        accountId: ByteArray,
        authDescriptorId: ByteArray,
        opName: String,
        opArgs: List<Gtv>,
        forEvmSignatures: Boolean
): String {
    val authMessageTemplate = client.getAuthMessageTemplate(opName, gtv(opArgs))
    val counter = if (forEvmSignatures) {
        0
    } else {
        client.getAuthDescriptorCounter(accountId, authDescriptorId) ?: throw UserMistake(
                "Invalid auth descriptor counter. Was the auth descriptor too close to expiration?"
        )
    }
    val nonce = gtv(listOf(
            gtv(client.config.blockchainRid),
            gtv(opName),
            gtv(opArgs),
            gtv(counter),
    )).merkleHash(client.merkleHashCalculator)
    return authMessageTemplate
            .replace("{blockchain_rid}", client.config.blockchainRid.toHex().uppercase())
            .replace("{nonce}", nonce.toHex().uppercase())
            .replace("{account_id}", accountId.toHex().uppercase())
            .replace("{auth_descriptor_id}", authDescriptorId.toHex().uppercase())
}

package net.postchain.d1.client

import net.postchain.client.core.PostchainClient
import net.postchain.client.core.TransactionResult
import net.postchain.client.core.TxRid
import net.postchain.client.transaction.TransactionBuilder
import net.postchain.crypto.KeyPair
import net.postchain.crypto.PubKey
import net.postchain.gtx.Gtx
import java.time.Duration

internal class ChromiaPostchainClient(val txClient: PostchainClient, val queryClient: PostchainClient) : PostchainClient by queryClient {
    override fun transactionBuilder(): TransactionBuilder = txClient.transactionBuilder()

    override fun transactionBuilder(signers: List<KeyPair>): TransactionBuilder =
            txClient.transactionBuilder(signers)

    override fun transactionBuilder(initialSigners: List<KeyPair>, remainingRequiredSigners: List<PubKey>): TransactionBuilder =
            txClient.transactionBuilder(initialSigners, remainingRequiredSigners)

    override fun postTransaction(tx: Gtx): TransactionResult =
            txClient.postTransaction(tx)

    override fun postTransactionAwaitConfirmation(tx: Gtx): TransactionResult =
            txClient.postTransactionAwaitConfirmation(tx)

    override fun awaitConfirmation(txRid: TxRid, retries: Int, pollInterval: Duration): TransactionResult =
            txClient.awaitConfirmation(txRid, retries, pollInterval)

    override fun checkTxStatus(txRid: TxRid): TransactionResult =
            txClient.checkTxStatus(txRid)

    override fun close() {
        txClient.close()
        if (queryClient !== txClient) {
            queryClient.close()
        }
    }
}

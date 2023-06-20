# Postchain-client

Postchain-client is a kotlin client for making queries and transactions from an application

## Setup

To depend on this jar file you need to add this to your Maven "pom.xml" (currently it's not on Maven central, so this
requires Gitlab access):

```xml
<dependency>
    <groupId>net.postchain</groupId>
    <artifactId>postchain-client</artifactId>
</dependency>
```
```xml
<repository>
    <id>Postchain Client</id>
    <name>Postchain Client GitLab Registry</name>
    <url>https://gitlab.com/api/v4/projects/46288950/packages/maven</url>
</repository>
```

## Usage

### Initializing the Client

```kotlin
import net.postchain.client.config.PostchainClientConfig
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.client.core.PostchainClient
import net.postchain.client.impl.PostchainClientProviderImpl
import net.postchain.client.request.EndpointPool
import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.common.tx.TransactionStatus
import net.postchain.crypto.KeyPair

fun main() {
    val cryptoSystem = Secp256K1CryptoSystem()
    val bcRid: BlockchainRid = BlockchainRid.buildFromHex("...") // The blockchain RID of the chain (can be found in the logs when P.C. server starts)
    val pubKey0 = "...".hexStringToByteArray() // This must be a real public key
    val privKey0 = "...".hexStringToByteArray() // This must be a real private key
    val keyPair0 = KeyPair(pubKey0, privKey0)
    val sigMaker0 = cryptoSystem.buildSigMaker(keyPair0)
    val endpointPool = EndpointPool.singleUrl("http://127.0.0.1:7740") // Running P.C. server on localhost
    val psClient: PostchainClient = PostchainClientProviderImpl().createClient(
            PostchainClientConfig(bcRid, endpointPool, listOf(keyPair0))
    )
}
```

### Queries
```kotlin
psClient.query("hello_world", GtvFactory.gtv(mapOf()))
```

### Transactions
Create a basic transaction, sign it, and send it.
```kotlin
val txBuilder = psClient.transactionBuilder()
txBuilder.addOperation("nop") // Operation "nop" without arguments
txBuilder.sign(sigMaker0) // Sign it

val result = txBuilder.post()
when (result.status) {
    TransactionStatus.WAITING -> println("TX has been put into the TX queue of the Postchain server")
    TransactionStatus.REJECTED -> println("TX most likely wrong number of args")
    TransactionStatus.CONFIRMED -> println("TX has been included in a block")
    TransactionStatus.UNKNOWN -> println("Investigate")
}  
```
Build a transaction containing operation with arguments, sign it, and send it.
```kotlin
val txBuilder = psClient.transactionBuilder()
txBuilder.addOperation("set_name", GtvFactory.gtv(name))
//addNop makes tx unique so one can send same operation more than one time
txBuilder.addNop()
txBuilder.sign(sigMaker0) // Sign it
val result = txBuilder.post()
```



... see PostchainClientTest in the devtools Maven module for more examples.

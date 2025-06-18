# Postchain-client

Postchain-client is a Kotlin client for making queries and transactions from an application.

## Setup

To use the library, you'll need to add these package registries since it's not available on Maven Central. 
The specific steps for adding the URL may differ based on your chosen build tool.

For Maven add this to your `pom.xml`:

```xml
<project>
    <dependencies>
        <dependency>
            <groupId>net.postchain.client</groupId>
            <artifactId>postchain-client</artifactId>
        </dependency>
    </dependencies>

    <repositories>
        <repository>
            <id>chromia-parent</id>
            <name>Chromia parent GitLab Registry</name>
            <url>https://gitlab.com/api/v4/projects/50818999/packages/maven</url>
        </repository>
        <repository>
            <id>postchain</id>
            <name>Postchain GitLab Registry</name>
            <url>https://gitlab.com/api/v4/projects/32294340/packages/maven</url>
        </repository>
        <repository>
            <id>postchain-client</id>
            <name>Postchain Client GitLab Registry</name>
            <url>https://gitlab.com/api/v4/projects/46288950/packages/maven</url>
        </repository>
    </repositories>
</project>
```

For Gradle, add this to your `build.gradle.kts`:

```kotlin
repositories {
    maven("https://gitlab.com/api/v4/projects/50818999/packages/maven")
    maven("https://gitlab.com/api/v4/projects/32294340/packages/maven")
    maven("https://gitlab.com/api/v4/projects/46288950/packages/maven")
}

dependencies {
    implementation("net.postchain.client:postchain-client")
}
```

## Usage

### Initializing the Client

```kotlin
fun main() {
    val cryptoSystem = Secp256K1CryptoSystem()
    val bcRid: BlockchainRid = BlockchainRid.buildFromHex("33398FDBB5BB91A2ECD7FC95D95948267D4CC7E277D3A18A46745C6E03583F3F") // The blockchain RID of the chain (can be found in the logs when P.C. server starts)
    val pubKey0 = "020CA58A45A1F97C8EE7A95B49738517FD11A072F1F9AC2450E13875EBD817BA2E".hexStringToByteArray() // This must be a real public key
    val privKey0 = "4CB84F555AD0F93C938EB8EF0E10F1CE129D143D74A6516F7D8E89ED21954593".hexStringToByteArray() // This must be a real private key
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

# Chromia client

The Chromia client is an alternative to the Postchain client and can be used to more easily communicate with Chromia networks. 
When initialized, it will look up and connect to system nodes of the network.

## Setup

For Maven add this to your `pom.xml`:

```xml
<project>
    <dependencies>
        <dependency>
            <groupId>net.postchain.client</groupId>
            <artifactId>chromia-client</artifactId>
        </dependency>
    </dependencies>

    <repositories>
        <repository>
            <id>chromia-parent</id>
            <name>Chromia parent GitLab Registry</name>
            <url>https://gitlab.com/api/v4/projects/50818999/packages/maven</url>
        </repository>
        <repository>
            <id>postchain</id>
            <name>Postchain GitLab Registry</name>
            <url>https://gitlab.com/api/v4/projects/32294340/packages/maven</url>
        </repository>
        <repository>
            <id>postchain-client</id>
            <name>Postchain Client GitLab Registry</name>
            <url>https://gitlab.com/api/v4/projects/46288950/packages/maven</url>
        </repository>
    </repositories>
</project>
```

For Gradle, add this to your `build.gradle.kts`:

```kotlin
repositories {
    maven("https://gitlab.com/api/v4/projects/50818999/packages/maven")
    maven("https://gitlab.com/api/v4/projects/32294340/packages/maven")
    maven("https://gitlab.com/api/v4/projects/46288950/packages/maven")
}

dependencies {
    implementation("net.postchain.client:postchain-client")
}
```

## Example usage

To create a client and wait for a transaction to be anchored:

```kotlin
val chromiaClient = StandardChromiaClient("http://127.0.0.1:7740")
chromiaClient.awaitAnchoredTx(
        BlockchainRid.buildFromHex("335C75E08AFAC7D6678263F1A13D5AFED9CD009344B6349107D7CEEA3A40EA08"),
        TxRid("3DE7FC7BCF6DAF2FFD8564D46D73F42C069818DDC249835535AD50D8D9270FF3"))
```

# FT4 client

The FT4 client provides functionality for interacting with dapps using the [FT4](https://docs.chromia.com/ft4/intro) 
library, focusing on authentication, accounts, and asset management.

## Setup

For Maven add this to your `pom.xml`:

```xml
<project>
    <dependencies>
        <dependency>
            <groupId>net.postchain.client</groupId>
            <artifactId>ft4-client</artifactId>
        </dependency>
    </dependencies>

    <repositories>
        <repository>
            <id>chromia-parent</id>
            <name>Chromia parent GitLab Registry</name>
            <url>https://gitlab.com/api/v4/projects/50818999/packages/maven</url>
        </repository>
        <repository>
            <id>postchain</id>
            <name>Postchain GitLab Registry</name>
            <url>https://gitlab.com/api/v4/projects/32294340/packages/maven</url>
        </repository>
        <repository>
            <id>postchain-client</id>
            <name>Postchain Client GitLab Registry</name>
            <url>https://gitlab.com/api/v4/projects/46288950/packages/maven</url>
        </repository>
    </repositories>
</project>
```

For Gradle, add this to your `build.gradle.kts`:

```kotlin
repositories {
    maven("https://gitlab.com/api/v4/projects/50818999/packages/maven")
    maven("https://gitlab.com/api/v4/projects/32294340/packages/maven")
    maven("https://gitlab.com/api/v4/projects/46288950/packages/maven")
}

dependencies {
    implementation("net.postchain.client:ft4-client")
}
```

## Features

### Authentication

The FT4 client supports both Chromia and Ethereum-style authentication for operations:

#### Chromia Authentication

```kotlin
// Assuming you have initialized a PostchainClient and TransactionBuilder
val operationName = "your_operation_name"

val cryptoSystem = Secp256K1CryptoSystem()
val bcRid: BlockchainRid = BlockchainRid.buildFromHex("33398FDBB5BB91A2ECD7FC95D95948267D4CC7E277D3A18A46745C6E03583F3F") // The blockchain RID of the chain (can be found in the logs when P.C. server starts)
val pubKey0 = "020CA58A45A1F97C8EE7A95B49738517FD11A072F1F9AC2450E13875EBD817BA2E".hexStringToByteArray() // This must be a real public key
val privKey0 = "4CB84F555AD0F93C938EB8EF0E10F1CE129D143D74A6516F7D8E89ED21954593".hexStringToByteArray() // This must be a real private key
val keyPair0 = KeyPair(pubKey0, privKey0)
val sigMaker0 = cryptoSystem.buildSigMaker(keyPair0)
val endpointPool = EndpointPool.singleUrl("http://127.0.0.1:7740") // Running P.C. server on localhost
val psClient: PostchainClient = PostchainClientProviderImpl().createClient(PostchainClientConfig(bcRid, endpointPool, listOf(keyPair0)))
val txBuilder = psClient.transactionBuilder()

val accountId: ByteArray = "8ECB5E014C0FED6FCC50765802D0D15E210C64079D88A416D7852CC75F9407B4".hexStringToByteArray() // The FT4 account ID

// Add an authentication operation to the transaction
addFtAuthenticationOp(
    psClient,
    txBuilder,
    operationName,
    pubKey0,
    accountId
)

// Add the operation that requires authentication
txBuilder.addOperation(operationName, gtv("arg1"))

// Sign and post the transaction
txBuilder.sign(sigMaker)
val result = txBuilder.post()
```

#### Ethereum Authentication

```kotlin
// Implement the EvmSigner interface
val evmSigner = object : EvmSigner {
    override fun sign(address: ByteArray, message: String): Signature {
        // Implement EVM signing logic here
        return Signature(byteArrayOf(), byteArrayOf(), 0)
    }
}

// Assuming you have initialized a PostchainClient and TransactionBuilder
val evmAddress: ByteArray = byteArrayOf() // The Ethereum address
val accountId: ByteArray = byteArrayOf() // The FT4 account ID
val operationName = "your_operation_name"
val operationArgs: List<Gtv> = listOf(gtv("arg1")) // Arguments for the operation

// Add the authentication operation to the transaction
addEvmAuthenticationOp(
    psClient,
    txBuilder,
    operationName,
    operationArgs,
    evmAddress,
    accountId,
    null, // Optional auth descriptor ID
    evmSigner
)

// Add the operation that requires authentication
txBuilder.addOperation(operationName, gtv("arg1"))

// Post the transaction
val result = txBuilder.post()
```

For operations where an EVM signer is not part of an account auth descriptor yet, use `addEvmSignaturesOp` instead:

```kotlin
// Add the signatures operation to the transaction
addEvmSignaturesOp(
    psClient,
    txBuilder,
    operationName,
    operationArgs,
    evmAddress,
    accountId,
    null, // Optional auth descriptor ID
    evmSigner
)
```

### Authentication Descriptors

The FT4 client provides helper functions for working with authentication descriptors:

```kotlin
// Assuming you have initialized a PostchainClient
val accountId: ByteArray = byteArrayOf() // The account ID
val signer: ByteArray = byteArrayOf() // The signer's public key or address

// Get auth descriptors for the account and signer
val authDescriptors = psClient.getAccountAuthDescriptorsBySigner(accountId, signer)

// Work with the auth descriptors
for (descriptor in authDescriptors) {
    // Get the flags for the auth descriptor
    val flags = descriptor.flags()

    // Get the number of signers
    val signerCount = descriptor.numberOfSigners()

    // For single-signer auth descriptors
    if (descriptor.authType == AuthType.S) {
        val signerPublicKey = descriptor.getSingleSigner()
        // Process the signer public key
    }

    // For multi-signer auth descriptors
    if (descriptor.authType == AuthType.M) {
        val signerPublicKeys = descriptor.getMultiSigners()
        // Process the list of signer public keys
    }
}
```

### Asset Management

The FT4 client provides functionality for querying asset balances:

```kotlin
// Assuming you have initialized a PostchainClient
val accountId: ByteArray = "8ECB5E014C0FED6FCC50765802D0D15E210C64079D88A416D7852CC75F9407B4".hexStringToByteArray() // The FT4 account ID
val assetId: ByteArray = "718B90D1E7C7B77D5329EDA83B31B43424BC8B2A155B05DBF8FDB985098DC447".hexStringToByteArray() // The asset for which the balance is requested
val balance: BigInteger = getAssetBalance(psClient, accountId, assetId)
```

## License

Copyright 2019-2025 ChromaWay AB.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

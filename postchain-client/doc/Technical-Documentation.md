# Postchain Client Technical Documentation

The Postchain client is a software library that provides a set of features
for querying and verifying data on a blockchain network. This
documentation outlines the various features of the Postchain client.

## Failover strategies

The Postchain client includes four strategies for failover:

### Abort On Error

The Abort On Error Request Strategy will abort on client error and retry
on server error. This means that if a client error occurs, such as an
invalid query parameter, the Request Strategy will not retry the query.
However, if a server error occurs, such as a timeout or internal server
error, the Request Strategy will retry the query on another node.

### Try Next On Error

The Try Next On Error Request Strategy is similar to Abort On Error, but
will also retry on client error. This means that if a client error
occurs, the Request Strategy will retry the query on another node, as
well as retrying on server error.

### Single Endpoint

The Single Endpoint Request Strategy will not retry on another node.
This can be useful in cases where there is a specific node that should
be queried, or when a specific node is known to be more reliable than
others.

### Query Majority

The Query Majority Request Strategy will query all nodes in parallel and
wait until an EBFT majority of the nodes return the same response. This
can help to ensure the integrity of the system by requiring a consensus
among nodes before accepting a result.

(This was implemented by request from the EU project, but is available for everyone to use.)

## Error Codes

The Postchain client uses HTTP status codes to indicate client errors
and server errors:

### Client Errors

- 400 Bad Request: The request was invalid or could not be understood
  by the server.

- 404 Not Found: The requested blockchain could not be found.

- 409 Conflict: The request could not be completed because the
  transaction is already in the queue.

- 413 Request Entity Too Large.

### Server Errors

- Inability to resolve hostname in DNS.

- Connection refused.

- 500 Internal Server Error: The server encountered an unexpected
  condition that prevented it from fulfilling the request.

- 503 Service Unavailable: The server is currently unable to handle
  the request due to a temporary overload or maintenance.

### Node Discovery

When configured towards a Chromia network, the Postchain client will
discover which cluster your dapp is running on and reconfigure itself to
point to endpoints of that cluster. With the kotlin client, this is done
using a wrapper object called `ChromiaClientProvider` by which you
configure it to point to the directory1 brid on any sets of nodes in the
network, and it will spawn a new client for a dapp using its brid.

## API

### Base functionalities

- `query(name: String, args: Gtv): Gtv`: Performs a query on the
  blockchain by providing the name of the query and its arguments.
  Returns the result of the query.

- `transactionBuilder(): TransactionBuilder`: Creates a transaction
  builder with the default list of signers.

- `transactionBuilder(signers: List<KeyPair>): TransactionBuilder`:
  Creates a transaction builder with the given list of signers.

- `postTransaction(tx: Gtx): TransactionResult`: Posts a transaction
  to the blockchain and returns the result.

- `postTransactionAwaitConfirmation(tx: Gtx): TransactionResult`:
  Posts a transaction to the blockchain and waits for it to be
  included in a block before returning the result.

- `awaitConfirmation(txRid: TxRid, retries: Int, pollInterval:
  Duration): TransactionResult`: Waits for a transaction to be
  confirmed by checking its status at intervals until it is confirmed,
  or the maximum number of retries is reached.

- `checkTxStatus(txRid: TxRid): TransactionResult`: Checks the current
  status of a transaction.

### ICCF

- `confirmationProof(txRid: TxRid): ByteArray`: Returns the
  confirmation proof for a given transaction, or throws `ClientError`
  if it cannot be found.

- `getTransaction(txRid: TxRid): ByteArray`: Returns the raw
  transaction data for a given transaction, or or throws `ClientError`
  if it cannot be found.

### Additional helpers (not used by dapps)

- `currentBlockHeight(): Long`: Queries the current height of the most
  recently added block.

- `blockAtHeight(height: Long): BlockDetail?`: Queries a specific
  block at a given height on the blockchain. Returns the block
  details, or null if the block cannot be found.

## Configuration Properties

When creating a `PostchainClientConfig` instance:

| Property Name         | Property Data Type     | Property Default Value | Description                                                                                                                     |
|-----------------------|------------------------|------------------------|---------------------------------------------------------------------------------------------------------------------------------|
| blockchainRid         | BlockchainRid          | n/a                    | Identifier for the blockchain that the client is connecting to.                                                                 |
| endpointPool          | EndpointPool           | n/a                    | A list of endpoints that the client will use for connecting to the blockchain nodes.                                            |
| signers               | List\<KeyPair\>        | empty list             | A list of public and private key pairs used by default for signing transactions.                                                |
| statusPollCount       | Int                    | 20                     | The number of times the client will poll the status of a transaction.                                                           |
| statusPollInterval    | Duration               | 500 milliseconds       | The interval between polling attempts.                                                                                          |
| attemptsPerEndpoint   | Int                    | 5                      | The number of times the client will attempt to query each endpoint before moving to the next one when using a Failover strategy |
| attemptInterval       | Int                    | 500 milliseconds       | The interval between each retry attempt.                                                                                        |
| cryptoSystem          | CryptoSystem           | Secp256K1              | The cryptographic functions used by the client when signing transactions                                                        |
| maxResponseSize       | Int                    | 64 \* 1024 \* 1024     | The maximum response size allowed in bytes.                                                                                     |
| connectTimeout        | Duration               | 60 seconds             | The timeout for connecting to a blockchain node.                                                                                |
| responseTimeout       | Duration               | 60 seconds             | The timeout for receiving a response from a blockchain node.                                                                    |
| requestStrategy       | RequestStrategyFactory | Abort On Error         | The fail over strategy used by the client.                                                                                      |
| maxTxSize             | Int                    | \-1 (no limit)         | Max transaction size.                                                                                                           |
| compressRequestBodies | Boolean                | false                  | Use GZIP compression on request bodies                                                                                          |

When applying configuration from file/environment variables (descriptions mostly refer to above table for simplicity):

| Key                  | Environment Variable                     | Default                                      | Type                   | Description                                                                                                                    |
|----------------------|------------------------------------------|----------------------------------------------|------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| brid                 | POSTCHAIN_CLIENT_BLOCKCHAIN_RID          | n/a                                          | String                 | See `blockchainRid`                                                                                                            |
| api.url              | POSTCHAIN_CLIENT_API_URL                 | n/a                                          | Comma separated string | See `endpointPool`                                                                                                             |
| pubkey               | POSTCHAIN_CLIENT_PUBKEY                  | ""                                           | Comma separated string | Corresponding value at same index in `privkey` config will be used to form a keypair as element in `signers` (see above table) |
| privkey              | POSTCHAIN_CLIENT_PRIVKEY                 | ""                                           | Comma separated string | Corresponding value at same index in `pubkey` config will be used to form a keypair as element in `signers` (see above table)  |
| status.poll-count    | POSTCHAIN_CLIENT_STATUS_POLL_COUNT       | 20                                           | Int                    | See `statusPollCount`                                                                                                          |
| status.poll-interval | POSTCHAIN_CLIENT_STATUS_POLL_INTERVAL    | 500                                          | Int                    | See `statusPollInterval`. Time unit is milliseconds.                                                                           |
| failover.attempts    | POSTCHAIN_CLIENT_FAIL_OVER_ATTEMPTS      | 5                                            | Int                    | See `attemptsPerEndpoint`                                                                                                      |
| failover.interval    | POSTCHAIN_CLIENT_FAIL_OVER_INTERVAL      | 500                                          | Int                    | See `attemptInterval`. Time unit is milliseconds.                                                                              |
| cryptosystem         | POSTCHAIN_CRYPTO_SYSTEM                  | "net.postchain.crypto.Secp256K1CryptoSystem" | String                 | See `cryptoSystem`                                                                                                             |
| max.response.size    | POSTCHAIN_CLIENT_MAX_RESPONSE_SIZE       | 64 \* 1024 \* 1024                           | Int                    | See `maxResponseSize`                                                                                                          |
| connect.timeout      | POSTCHAIN_CLIENT_CONNECT_TIMEOUT         | 60 000                                       | Int                    | See `connectTimeout`. Time unit is milliseconds.                                                                               |
| response.timeout     | POSTCHAIN_CLIENT_RESPONSE_TIMEOUT        | 60 000                                       | Int                    | See `responseTimeout`. Time unit is milliseconds.                                                                              |
| request.strategy     | POSTCHAIN_CLIENT_REQUEST_STRATEGY        | ABORT_ON_ERROR                               | String                 | See `requestStrategy`. Should be the `name` of a `RequestStrategies` enum                                                      |
| max.tx.size          | POSTCHAIN_CLIENT_MAX_TX_SIZE             | -1                                           | Int                    | See `maxTxSize`                                                                                                                |
| compress.requests    | POSTCHAIN_CLIENT_COMPRESS_REQUEST_BODIES | false                                        | Boolean                | See `compressRequestBodies`                                                                                                    |

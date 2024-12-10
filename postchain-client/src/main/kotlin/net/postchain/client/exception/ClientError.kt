package net.postchain.client.exception

import net.postchain.client.request.Endpoint
import net.postchain.common.exception.UserMistake
import org.http4k.core.Status

open class ClientError(val context: String, val status: Status?, val errorMessage: String, val endpoint: Endpoint?)
    : UserMistake("$context: ${if (status != null) "$status " else ""} ${if (errorMessage.isBlank()) "" else "$errorMessage "}${if (endpoint != null) "from ${endpoint.url}" else ""}")

class NodesDisagree(message: String)
    : ClientError(context = "Nodes disagree", status = null, errorMessage = message, endpoint = null)

class NotFoundError(context: String, errorMessage: String, endpoint: Endpoint?)
    : ClientError(context = context, status = Status.NOT_FOUND, errorMessage = errorMessage, endpoint = endpoint)

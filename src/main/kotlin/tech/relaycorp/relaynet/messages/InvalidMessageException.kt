package tech.relaycorp.relaynet.messages

import tech.relaycorp.relaynet.RelaynetException

class InvalidMessageException(message: String, cause: Throwable? = null) :
    RelaynetException(message, cause)

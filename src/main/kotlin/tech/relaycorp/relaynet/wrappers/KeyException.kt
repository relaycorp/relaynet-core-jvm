package tech.relaycorp.relaynet.wrappers

import tech.relaycorp.relaynet.RelaynetException

/**
 * Exception while generating a cryptographic key.
 */
class KeyException(message: String) : RelaynetException(message)

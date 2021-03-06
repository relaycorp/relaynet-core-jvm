package tech.relaycorp.relaynet.ramf

import tech.relaycorp.relaynet.messages.payloads.Payload

class StubPayload(val payload: String) : Payload {
    override fun serializePlaintext() = payload.toByteArray()
}

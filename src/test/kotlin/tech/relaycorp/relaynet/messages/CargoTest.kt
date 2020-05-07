package tech.relaycorp.relaynet.messages

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.TestFactory
import tech.relaycorp.relaynet.issueStubCertificate
import tech.relaycorp.relaynet.ramf.generateConstructorTests
import tech.relaycorp.relaynet.wrappers.generateRSAKeyPair
import kotlin.test.Test
import kotlin.test.assertEquals

class CargoTest {
    @TestFactory
    fun makeConstructorTests() = generateConstructorTests(::Cargo, 0x43, 0x00)

    @Nested
    inner class Companion {
        @Nested
        inner class Deserialize {
            private val recipientAddress = "0deadbeef"
            private val payload = "Payload".toByteArray()
            private val keyPair = generateRSAKeyPair()
            private val senderCertificate = issueStubCertificate(keyPair.public, keyPair.private)

            @Test
            fun `Valid CCAs should be deserialized`() {
                val cargo = Cargo(recipientAddress, payload, senderCertificate)
                val cargoSerialized = cargo.serialize(keyPair.private)

                val cargoDeserialized = Cargo.deserialize(cargoSerialized)

                assertEquals(cargo.messageId, cargoDeserialized.messageId)
            }
        }
    }
}

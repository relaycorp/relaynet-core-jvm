package tech.relaycorp.relaynet.messages

import tech.relaycorp.relaynet.CDACertPath
import tech.relaycorp.relaynet.KeyPairSet
import tech.relaycorp.relaynet.messages.payloads.CargoMessageSet
import tech.relaycorp.relaynet.ramf.RAMFSpecializationTestCase
import tech.relaycorp.relaynet.wrappers.x509.Certificate
import kotlin.test.Test
import kotlin.test.assertEquals

internal class CargoTest : RAMFSpecializationTestCase<Cargo>(
    ::Cargo,
    { r: String, p: ByteArray, s: Certificate -> Cargo(r, p, s) },
    0x43,
    0x00,
    Cargo.Companion
) {
    @Test
    fun `Payload deserialization should be delegated to CargoMessageSet`() {
        val cargoMessageSet = CargoMessageSet(arrayOf("msg1".toByteArray(), "msg2".toByteArray()))
        val cargo = Cargo(
            "https://gb.relaycorp.tech",
            cargoMessageSet.encrypt(CDACertPath.PUBLIC_GW),
            CDACertPath.PRIVATE_GW
        )

        val payloadDeserialized = cargo.unwrapPayload(KeyPairSet.PUBLIC_GW.private)

        assertEquals(
            cargoMessageSet.messages.map { it.asList() },
            payloadDeserialized.messages.map { it.asList() }
        )
    }
}

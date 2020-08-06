package tech.relaycorp.relaynet.messages.control

import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import tech.relaycorp.relaynet.DummyCertPath
import tech.relaycorp.relaynet.messages.InvalidMessageException
import tech.relaycorp.relaynet.wrappers.asn1.ASN1Exception
import tech.relaycorp.relaynet.wrappers.asn1.ASN1Utils
import tech.relaycorp.relaynet.wrappers.x509.CertificateException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientRegistrationTest {
    @Nested
    inner class Serialize {
        @Test
        fun `Client certificate should be serialized`() {
            val registration =
                ClientRegistration(DummyCertPath.endpointCert, DummyCertPath.privateGatewayCert)

            val serialization = registration.serialize()

            val sequence = ASN1Utils.deserializeSequence(serialization)
            val clientCertificateASN1 =
                DEROctetString.getInstance(sequence.first() as ASN1TaggedObject, false)
            assertEquals(
                DummyCertPath.endpointCert.serialize().asList(),
                clientCertificateASN1.octets.asList()
            )
        }

        @Test
        fun `Server certificate should be serialized`() {
            val registration =
                ClientRegistration(DummyCertPath.endpointCert, DummyCertPath.privateGatewayCert)

            val serialization = registration.serialize()

            val sequence = ASN1Utils.deserializeSequence(serialization)
            val serverCertificateASN1 =
                DEROctetString.getInstance(sequence[1] as ASN1TaggedObject, false)
            assertEquals(
                DummyCertPath.privateGatewayCert.serialize().asList(),
                serverCertificateASN1.octets.asList()
            )
        }
    }

    @Nested
    inner class Deserialize {
        @Test
        fun `Serialization should be DER sequence`() {
            val invalidSerialization = "foo".toByteArray()

            val exception = assertThrows<InvalidMessageException> {
                ClientRegistration.deserialize(invalidSerialization)
            }

            assertEquals("Client registration is not a DER sequence", exception.message)
            assertTrue(exception.cause is ASN1Exception)
        }

        @Test
        fun `Sequence should have at least two items`() {
            val invalidSerialization =
                ASN1Utils.serializeSequence(arrayOf(DERNull.INSTANCE), false)

            val exception = assertThrows<InvalidMessageException> {
                ClientRegistration.deserialize(invalidSerialization)
            }

            assertEquals(
                "Client registration sequence should have at least two items (got 1)",
                exception.message
            )
        }

        @Test
        fun `Invalid client certificates should be refused`() {
            val invalidSerialization =
                ASN1Utils.serializeSequence(arrayOf(DERNull.INSTANCE, DERNull.INSTANCE), false)

            val exception = assertThrows<InvalidMessageException> {
                ClientRegistration.deserialize(invalidSerialization)
            }

            assertEquals(
                "Client registration contains invalid client certificate",
                exception.message
            )
            assertTrue(exception.cause is CertificateException)
        }

        @Test
        fun `Invalid server certificates should be refused`() {
            val invalidSerialization = ASN1Utils.serializeSequence(
                arrayOf(
                    DEROctetString(DummyCertPath.endpointCert.serialize()),
                    DERNull.INSTANCE
                ), false
            )

            val exception = assertThrows<InvalidMessageException> {
                ClientRegistration.deserialize(invalidSerialization)
            }

            assertEquals(
                "Client registration contains invalid server certificate",
                exception.message
            )
            assertTrue(exception.cause is CertificateException)
        }

        @Test
        fun `Valid registration should be accepted`() {
            val registration =
                ClientRegistration(DummyCertPath.endpointCert, DummyCertPath.privateGatewayCert)
            val serialization = registration.serialize()

            val registrationDeserialized = ClientRegistration.deserialize(serialization)

            assertEquals(DummyCertPath.endpointCert, registrationDeserialized.clientCertificate)
            assertEquals(
                DummyCertPath.privateGatewayCert,
                registrationDeserialized.serverCertificate
            )
        }
    }
}
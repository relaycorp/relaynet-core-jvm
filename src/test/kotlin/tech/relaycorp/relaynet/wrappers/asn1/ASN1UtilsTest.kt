package tech.relaycorp.relaynet.wrappers.asn1

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1StreamParser
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DEROctetStringParser
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.DERVisibleString
import org.bouncycastle.asn1.DLSequenceParser
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ASN1UtilsTest {
    val value1 = DERVisibleString("foo")
    val value2 = DEROctetString("bar".toByteArray())

    @Nested
    inner class SerializeSequence {
        @Test
        fun `Values should be explicitly tagged by default`() {
            val serialization = ASN1Utils.serializeSequence(arrayOf(value1, value2))

            val parser = ASN1StreamParser(serialization)
            val sequence = parser.readObject() as DLSequenceParser

            val item1 = sequence.readObject()
            assertTrue(item1 is DERVisibleString)
            assertEquals(value1.string, item1.string)

            val item2 = sequence.readObject()
            assertTrue(item2 is DEROctetStringParser)
            assertEquals(
                value2.octets.asList(),
                (item2.loadedObject as DEROctetString).octets.asList()
            )
        }

        @Test
        fun `Implicitly-tagged values should be supported`() {
            val serialization = ASN1Utils.serializeSequence(arrayOf(value1, value2), false)

            val parser = ASN1StreamParser(serialization)
            val sequence =
                ASN1Sequence.getInstance(parser.readObject() as DLSequenceParser).toArray()

            val item1 = ASN1Utils.getVisibleString(sequence[0] as ASN1TaggedObject)
            assertEquals(value1.string, item1.string)

            val item2 = ASN1Utils.getOctetString(sequence[1] as ASN1TaggedObject)
            assertEquals(
                value2.octets.asList(),
                (item2.loadedObject as DEROctetString).octets.asList()
            )
        }
    }

    @Nested
    inner class DeserializeSequence {
        @Test
        fun `Value should be refused if it's empty`() {
            val exception = assertThrows<ASN1Exception> {
                ASN1Utils.deserializeHeterogeneousSequence(byteArrayOf())
            }

            assertEquals("Value is empty", exception.message)
        }

        @Test
        fun `Value should be refused if it's not DER-encoded`() {
            val exception = assertThrows<ASN1Exception> {
                ASN1Utils.deserializeHeterogeneousSequence("a".toByteArray())
            }

            assertEquals("Value is not DER-encoded", exception.message)
        }

        @Test
        fun `Value should be refused if it's not a sequence`() {
            val serialization = DERVisibleString("hey").encoded

            val exception = assertThrows<ASN1Exception> {
                ASN1Utils.deserializeHeterogeneousSequence(serialization)
            }

            assertEquals("Value is not an ASN.1 sequence", exception.message)
        }

        @Test
        fun `Explicitly tagged items should be deserialized with their corresponding types`() {
            val serialization = ASN1Utils.serializeSequence(arrayOf(value1, value1))

            val sequence = ASN1Utils.deserializeHomogeneousSequence<DERVisibleString>(serialization)

            assertEquals(2, sequence.size)
            val value1Deserialized = sequence.first()
            assertEquals(value1, value1Deserialized)
            val value2Deserialized = sequence.last()
            assertEquals(value1, value2Deserialized)
        }

        @Test
        fun `Explicitly tagged items with unexpected types should be refused`() {
            val serialization = ASN1Utils.serializeSequence(arrayOf(value1, value2))

            val exception = assertThrows<ASN1Exception> {
                ASN1Utils.deserializeHomogeneousSequence<DERVisibleString>(serialization)
            }

            assertEquals(
                "Sequence contains an item of an unexpected type " +
                    "(${value2::class.java.simpleName})",
                exception.message
            )
        }

        @Test
        fun `Implicitly tagged items should be deserialized with their corresponding types`() {
            val serialization = ASN1Utils.serializeSequence(arrayOf(value1, value2), false)

            val sequence = ASN1Utils.deserializeHeterogeneousSequence(serialization)

            assertEquals(2, sequence.size)
            assertEquals(
                value1.octets.asList(),
                ASN1Utils.getVisibleString(sequence[0]).octets.asList()
            )
            assertEquals(
                value2.octets.asList(),
                ASN1Utils.getOctetString(sequence[1]).octets.asList()
            )
        }
    }

    @Nested
    inner class GetOID {
        private val oid = ASN1ObjectIdentifier("1.2.3.4.5")

        @Test
        fun `Invalid OID should be refused`() {
            val invalidImplicitlyTaggedOID = DERTaggedObject(false, 0, DERNull.INSTANCE)

            val exception = assertThrows<ASN1Exception> {
                ASN1Utils.getOID(invalidImplicitlyTaggedOID)
            }

            assertEquals("Value is not an OID", exception.message)
            assertTrue(exception.cause is IllegalArgumentException)
        }

        @Test
        fun `Implicitly tagged OID should be accepted`() {
            val implicitlyTaggedOID = DERTaggedObject(false, 0, oid)

            val oidDeserialized = ASN1Utils.getOID(implicitlyTaggedOID)

            assertEquals(oid, oidDeserialized)
        }
    }
}

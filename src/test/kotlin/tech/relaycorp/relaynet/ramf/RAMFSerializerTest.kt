package tech.relaycorp.relaynet.ramf

import com.beanit.jasn1.ber.BerLength
import com.beanit.jasn1.ber.BerTag
import com.beanit.jasn1.ber.ReverseByteArrayOutputStream
import com.beanit.jasn1.ber.types.BerDateTime
import com.beanit.jasn1.ber.types.BerGeneralizedTime
import com.beanit.jasn1.ber.types.BerInteger
import com.beanit.jasn1.ber.types.BerOctetString
import com.beanit.jasn1.ber.types.BerType
import com.beanit.jasn1.ber.types.string.BerVisibleString
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERGeneralizedTime
import org.bouncycastle.asn1.DERVisibleString
import org.bouncycastle.asn1.DLTaggedObject
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private val BER_DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

class RAMFSerializerTest {
    val stubConcreteMessageType: Byte = 32
    val stubConcreteMessageVersion: Byte = 0

    private val stubFieldSet = RAMFFieldSet(
        "04334",
        "message-id",
        ZonedDateTime.now(ZoneId.of("UTC")),
        12345,
        "payload".toByteArray()
    )

    private val stubSerializer = RAMFSerializer(
        stubConcreteMessageType,
        stubConcreteMessageVersion,
        ::StubRAMFMessage
    )

    @Nested
    inner class Serialize {
        private val stubSerialization = stubSerializer.serialize(stubFieldSet)

        @Test
        fun `Magic constant should be ASCII string "Relaynet"`() {
            val magicSignature = stubSerialization.copyOfRange(0, 8)
            assertEquals("Relaynet", magicSignature.toString(Charset.forName("ASCII")))
        }

        @Test
        fun `Concrete message type should be set`() {
            assertEquals(stubConcreteMessageType, stubSerialization[8])
        }

        @Test
        fun `Concrete message version should be set`() {
            assertEquals(stubConcreteMessageVersion, stubSerialization[9])
        }

        @Nested
        inner class Fields {
            @Test
            fun `Message fields should be wrapped in an ASN1 Sequence`() {
                val sequence = getAsn1Sequence(stubSerialization)
                assertEquals(5, sequence.size())
            }

            @Test
            fun `Recipient should be stored as an ASN1 VisibleString`() {
                val sequence = getAsn1Sequence(stubSerialization)
                val recipientRaw = sequence.getObjectAt(0) as DLTaggedObject
                val recipientDer = DERVisibleString.getInstance(recipientRaw, false)
                assertEquals(stubFieldSet.recipientAddress, recipientDer.string)
            }

            @Test
            fun `Message id should be stored as an ASN1 VisibleString`() {
                val sequence = getAsn1Sequence(stubSerialization)
                val messageIdRaw = sequence.getObjectAt(1) as DLTaggedObject
                val messageIdDer = DERVisibleString.getInstance(messageIdRaw, false)
                assertEquals(stubFieldSet.messageId, messageIdDer.string)
            }

            @Nested
            inner class CreationTime {
                @Test
                fun `Creation time should be stored as an ASN1 DateTime`() {
                    val sequence = getAsn1Sequence(stubSerialization)
                    val creationTimeRaw = sequence.getObjectAt(2) as DLTaggedObject
                    // We should technically be using a DateTime type instead of GeneralizedTime, but BC
                    // doesn't support it.
                    val creationTimeDer = DERGeneralizedTime.getInstance(creationTimeRaw, false)
                    assertEquals(
                        stubFieldSet.creationTime.format(BER_DATETIME_FORMATTER),
                        creationTimeDer.timeString
                    )
                }

                @Test
                fun `Creation time should be converted to UTC when provided in different timezone`() {
                    val nowTimezoneUnaware = LocalDateTime.now()
                    val zoneId = ZoneId.of("Etc/GMT-5")
                    val fieldSet = stubFieldSet.copy(creationTime = ZonedDateTime.of(nowTimezoneUnaware, zoneId))

                    val sequence = getAsn1Sequence(stubSerializer.serialize(fieldSet))

                    val creationTimeRaw = sequence.getObjectAt(2) as DLTaggedObject
                    // We should technically be using a DateTime type instead of GeneralizedTime, but BC
                    // doesn't support it.
                    val creationTimeDer = DERGeneralizedTime.getInstance(creationTimeRaw, false)
                    assertEquals(
                        fieldSet.creationTime.withZoneSameInstant(ZoneId.of("UTC")).format(BER_DATETIME_FORMATTER),
                        creationTimeDer.timeString
                    )
                }
            }

            @Test
            fun `TTL should be stored as an ASN1 Integer`() {
                val sequence = getAsn1Sequence(stubSerialization)
                val ttlRaw = sequence.getObjectAt(3) as DLTaggedObject
                val ttlDer = ASN1Integer.getInstance(ttlRaw, false)
                assertEquals(stubFieldSet.ttl, ttlDer.intPositiveValueExact())
            }

            @Test
            fun `Payload should be stored as an ASN1 Octet String`() {
                val sequence = getAsn1Sequence(stubSerialization)
                val payloadRaw = sequence.getObjectAt(4) as DLTaggedObject
                val payloadDer = ASN1OctetString.getInstance(payloadRaw, false)
                assertEquals(stubFieldSet.payload.asList(), payloadDer.octets.asList())
            }

            private fun getAsn1Sequence(serialization: ByteArray): ASN1Sequence {
                val asn1Serialization = skipFormatSignature(serialization)
                val asn1Stream = ASN1InputStream(asn1Serialization)
                return ASN1Sequence.getInstance(asn1Stream.readObject())
            }
        }
    }

    @Nested
    inner class Deserialize {
        @Nested
        inner class FormatSignature {
            @Test
            fun `Format signature must be present`() {
                val formatSignatureLength = 10
                val invalidSerialization = "a".repeat(formatSignatureLength - 1).toByteArray()

                val exception = assertThrows<RAMFException> { stubSerializer.deserialize(invalidSerialization) }

                assertEquals("Serialization is too short to contain format signature", exception.message)
            }

            @Test
            fun `Magic constant should be ASCII string "Relaynet"`() {
                val incompleteSerialization = "Relaynope01234".toByteArray()

                val exception = assertThrows<RAMFException> { stubSerializer.deserialize(incompleteSerialization) }

                assertEquals("Format signature should start with magic constant 'Relaynet'", exception.message)
            }

            @Test
            fun `Concrete message type should match expected one`() {
                val invalidMessageType = stubSerializer.concreteMessageType.inc()
                val invalidSerialization = ByteArrayOutputStream(10)
                invalidSerialization.write("Relaynet".toByteArray())
                invalidSerialization.write(invalidMessageType.toInt())
                invalidSerialization.write(stubSerializer.concreteMessageVersion.toInt())

                val exception = assertThrows<RAMFException> {
                    stubSerializer.deserialize(invalidSerialization.toByteArray())
                }

                assertEquals(
                    "Message type should be ${stubSerializer.concreteMessageType} (got $invalidMessageType)",
                    exception.message
                )
            }

            @Test
            fun `Concrete message version should match expected one`() {
                val invalidMessageVersion = stubSerializer.concreteMessageVersion.inc()
                val invalidSerialization = ByteArrayOutputStream(10)
                invalidSerialization.write("Relaynet".toByteArray())
                invalidSerialization.write(stubSerializer.concreteMessageType.toInt())
                invalidSerialization.write(invalidMessageVersion.toInt())

                val exception = assertThrows<RAMFException> {
                    stubSerializer.deserialize(invalidSerialization.toByteArray())
                }

                assertEquals(
                    "Message version should be ${stubSerializer.concreteMessageVersion} (got $invalidMessageVersion)",
                    exception.message
                )
            }
        }

        @Nested
        inner class FieldSet {
            private val formatSignature: ByteArray = "Relaynet".toByteArray() + byteArrayOf(
                stubSerializer.concreteMessageType,
                stubSerializer.concreteMessageVersion
            )

            @Test
            fun `Fields should be DER-serialized`() {
                val invalidSerialization = ByteArrayOutputStream(11)
                invalidSerialization.write(formatSignature)
                invalidSerialization.write(0xff)

                val exception = assertThrows<RAMFException> {
                    stubSerializer.deserialize(invalidSerialization.toByteArray())
                }

                assertEquals("Message fields are not a DER-encoded", exception.message)
            }

            @Test
            fun `Fields should be stored as a universal, constructed sequence`() {
                val invalidSerialization = ByteArrayOutputStream(11)
                invalidSerialization.write(formatSignature)

                val fieldSetSerialization = ReverseByteArrayOutputStream(100)
                BerOctetString("Not a sequence".toByteArray()).encode(fieldSetSerialization)
                invalidSerialization.write(fieldSetSerialization.array)

                val exception = assertThrows<RAMFException> {
                    stubSerializer.deserialize(invalidSerialization.toByteArray())
                }

                assertEquals("Message fields are not a ASN.1 sequence", exception.message)
            }

            @Test
            fun `Fields should be a sequence of no more than 5 items`() {
                val invalidSerialization = ByteArrayOutputStream()
                invalidSerialization.write(formatSignature)

                val fieldSetSerialization = serializeSequence(
                    BerVisibleString("1"),
                    BerVisibleString("2"),
                    BerVisibleString("3"),
                    BerVisibleString("4"),
                    BerVisibleString("5"),
                    BerVisibleString("6")
                )
                invalidSerialization.write(fieldSetSerialization)

                val exception = assertThrows<RAMFException> {
                    stubSerializer.deserialize(invalidSerialization.toByteArray())
                }

                assertEquals(
                    "Field sequence should contain 5 items (got 6)",
                    exception.message
                )
            }

            @Test
            fun `Message fields should be output when the serialization is valid`() {
                val serialization = stubSerializer.serialize(stubFieldSet)

                val parsedMessage = stubSerializer.deserialize(serialization)

                assertEquals(stubFieldSet.recipientAddress, parsedMessage.recipientAddress)

                assertEquals(stubFieldSet.messageId, parsedMessage.messageId)

                assertEquals(
                    stubFieldSet.creationTime.withNano(0),
                    parsedMessage.creationTime
                )

                assertEquals(stubFieldSet.ttl, parsedMessage.ttl)

                assertEquals(stubFieldSet.payload.asList(), parsedMessage.payload.asList())
            }

            @Test
            fun `Creation time in a format other than ASN1 DATE-TIME should be refused`() {
                // For example, a GeneralizedTime value (which includes timezone) should be refused
                val invalidSerialization = ByteArrayOutputStream()
                invalidSerialization.write(formatSignature)

                val fieldSetSerialization = serializeFieldSet(creationTime = BerGeneralizedTime("20200307173323-03"))
                invalidSerialization.write(fieldSetSerialization)

                val exception = assertThrows<RAMFException> {
                    stubSerializer.deserialize(invalidSerialization.toByteArray())
                }

                assertEquals(
                    "Creation time should be an ASN.1 DATE-TIME value",
                    exception.message
                )
            }

            @Test
            fun `Creation time should be parsed as UTC`() {
                // Pick timezone that's never equivalent to UTC (like "Europe/London")
                val nonUTCZoneId = ZoneId.of("America/Caracas")
                val serialization = stubSerializer.serialize(
                    stubFieldSet.copy(
                        creationTime = stubFieldSet.creationTime.withZoneSameInstant(nonUTCZoneId)
                    )
                )

                val parsedMessage = stubSerializer.deserialize(serialization)

                assertEquals(parsedMessage.creationTime.zone, ZoneId.of("UTC"))
            }

            private fun serializeFieldSet(
                recipientAddress: BerType = BerVisibleString(stubFieldSet.recipientAddress),
                messageId: BerType = BerVisibleString(stubFieldSet.messageId),
                creationTime: BerType = BerDateTime(stubFieldSet.creationTime.format(BER_DATETIME_FORMATTER)),
                ttl: BerType = BerInteger(stubFieldSet.ttl.toBigInteger()),
                payload: BerType = BerOctetString(stubFieldSet.payload)
            ): ByteArray {
                return serializeSequence(
                    recipientAddress,
                    messageId,
                    creationTime,
                    ttl,
                    payload
                )
            }

            private fun serializeSequence(
                vararg items: BerType
            ): ByteArray {
                val reverseOS = ReverseByteArrayOutputStream(256, true)
                val lastIndex = 0x80 + items.size - 1
                val serializationLength =
                    items.reversed().mapIndexed { i, v -> serializeItem(v, reverseOS, lastIndex - i) }.sum()

                BerLength.encodeLength(reverseOS, serializationLength)
                BerTag(BerTag.UNIVERSAL_CLASS, BerTag.CONSTRUCTED, 16).encode(reverseOS)
                return reverseOS.array
            }

            private fun serializeItem(
                item: BerType,
                reverseOS: ReverseByteArrayOutputStream,
                index: Int
            ): Int {
                val length = when (item) {
                    is BerVisibleString -> item.encode(reverseOS, false)
                    is BerInteger -> item.encode(reverseOS, false)
                    is BerOctetString -> item.encode(reverseOS, false)
                    else -> throw Exception("Unsupported BER type")
                }
                reverseOS.write(index)
                return length + 1
            }
        }
    }
}

fun skipFormatSignature(ramfMessage: ByteArray): ByteArray {
    return ramfMessage.copyOfRange(10, ramfMessage.lastIndex + 1)
}

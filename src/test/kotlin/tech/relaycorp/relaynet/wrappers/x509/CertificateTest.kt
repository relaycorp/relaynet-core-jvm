package tech.relaycorp.relaynet.wrappers.x509

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERBMPString
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import tech.relaycorp.relaynet.BC_PROVIDER
import tech.relaycorp.relaynet.issueStubCertificate
import tech.relaycorp.relaynet.sha256
import tech.relaycorp.relaynet.sha256Hex
import tech.relaycorp.relaynet.wrappers.cms.stubKeyPair
import tech.relaycorp.relaynet.wrappers.generateRSAKeyPair
import tech.relaycorp.relaynet.wrappers.generateRandomBigInteger
import java.math.BigInteger
import java.security.InvalidAlgorithmParameterException
import java.security.cert.CertPathBuilderException
import java.sql.Date
import java.time.LocalDateTime
import java.time.ZoneOffset.UTC
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CertificateTest {
    private val stubSubjectCommonName = "The CommonName"
    private val stubSubjectKeyPair = generateRSAKeyPair()
    private val stubValidityEndDate = ZonedDateTime.now().plusMonths(2)

    @Nested
    inner class Issue {
        @Test
        fun `Certificate version should be 3`() {
            val certificate = Certificate.issue(
                stubSubjectCommonName,
                stubSubjectKeyPair.public,
                stubSubjectKeyPair.private,
                stubValidityEndDate
            )

            assertEquals(3, certificate.certificateHolder.versionNumber)
        }

        @Test
        fun `Subject public key should be the specified one`() {
            val certificate = Certificate.issue(
                stubSubjectCommonName,
                stubSubjectKeyPair.public,
                stubSubjectKeyPair.private,
                stubValidityEndDate
            )

            assertEquals(
                stubSubjectKeyPair.public.encoded.asList(),
                certificate.certificateHolder.subjectPublicKeyInfo.encoded.asList()
            )
        }

        @Test
        fun `Certificate should be signed with issuer private key`() {
            val certificate = Certificate.issue(
                stubSubjectCommonName,
                stubSubjectKeyPair.public,
                stubSubjectKeyPair.private,
                stubValidityEndDate
            )

            val verifierProvider = JcaContentVerifierProviderBuilder()
                .setProvider(BC_PROVIDER)
                .build(stubSubjectKeyPair.public)
            assert(certificate.certificateHolder.isSignatureValid(verifierProvider))
        }

        @Test
        fun `Certificate should be signed with RSA-PSS`() {
            val certificate = Certificate.issue(
                stubSubjectCommonName,
                stubSubjectKeyPair.public,
                stubSubjectKeyPair.private,
                stubValidityEndDate
            )

            assertEquals(
                PKCSObjectIdentifiers.id_RSASSA_PSS,
                certificate.certificateHolder.signatureAlgorithm.algorithm
            )
        }

        @Test
        fun `Serial number should be autogenerated`() {
            val certificate = Certificate.issue(
                stubSubjectCommonName,
                stubSubjectKeyPair.public,
                stubSubjectKeyPair.private,
                stubValidityEndDate
            )

            assert(certificate.certificateHolder.serialNumber > BigInteger.ZERO)
        }

        @Test
        fun `Validity start date should be set to current UTC time by default`() {
            val nowDate = ZonedDateTime.now(UTC)

            val certificate = Certificate.issue(
                stubSubjectCommonName,
                stubSubjectKeyPair.public,
                stubSubjectKeyPair.private,
                stubValidityEndDate
            )

            val startDateTimestamp = certificate.certificateHolder.notBefore.toInstant().epochSecond
            assertTrue(nowDate.toEpochSecond() <= startDateTimestamp)
            assertTrue(startDateTimestamp <= (nowDate.toEpochSecond() + 2))
        }

        @Test
        fun `Validity end date should be honored`() {
            val endZonedDate = ZonedDateTime.now().plusDays(1)
            val certificate = Certificate.issue(
                stubSubjectCommonName,
                stubSubjectKeyPair.public,
                stubSubjectKeyPair.private,
                endZonedDate
            )

            assertEquals(
                endZonedDate.toEpochSecond(),
                certificate.certificateHolder.notAfter.toInstant().epochSecond
            )
        }

        @Test
        fun `The end date should be later than the start date`() {
            val validityStartDate = ZonedDateTime.now().plusMonths(1)

            val exception = assertThrows<CertificateException> {
                Certificate.issue(
                    stubSubjectCommonName,
                    stubSubjectKeyPair.public,
                    stubSubjectKeyPair.private,
                    validityStartDate,
                    validityStartDate = validityStartDate // Same as start date
                )
            }
            assertEquals(
                "The end date must be later than the start date",
                exception.message
            )
        }

        @Test
        fun `Subject DN should be set to specified CN`() {
            val commonName = "The CN"
            val certificate = Certificate.issue(
                commonName,
                stubSubjectKeyPair.public,
                stubSubjectKeyPair.private,
                stubValidityEndDate
            )

            val distinguishedNames = certificate.certificateHolder.subject.rdNs
            assertEquals(1, distinguishedNames.size)
            assertEquals(false, distinguishedNames[0].isMultiValued)
            assertEquals(BCStyle.CN, distinguishedNames[0].first.type)
            assertTrue(distinguishedNames[0].first.value is DERBMPString)
            assertEquals(commonName, distinguishedNames[0].first.value.toString())
        }

        @Test
        fun `Issuer DN should be same as subject when certificate is self-issued`() {
            val commonName = "The CN"
            val certificate = Certificate.issue(
                commonName,
                stubSubjectKeyPair.public,
                stubSubjectKeyPair.private,
                stubValidityEndDate
            )

            val distinguishedNames = certificate.certificateHolder.issuer.rdNs
            assertEquals(1, distinguishedNames.size)
            assertEquals(false, distinguishedNames[0].isMultiValued)
            assertEquals(BCStyle.CN, distinguishedNames[0].first.type)
            assertEquals(commonName, distinguishedNames[0].first.value.toString())
        }

        @Nested
        inner class IssuerCertificate {
            private val issuerKeyPair = generateRSAKeyPair()

            @Test
            fun `Issuer DN should be set to subject of issuer certificate`() {
                // Use an intermediate CA as the issuer because its subject and issuer would be
                // different. If we use a root/self-signed CA, its subject and issuer would be
                // the same, which would make it hard to see why the test passed.
                val rootCAKeyPair = generateRSAKeyPair()
                val rootCACert = Certificate.issue(
                    "root",
                    rootCAKeyPair.public,
                    rootCAKeyPair.private,
                    stubValidityEndDate,
                    isCA = true,
                    pathLenConstraint = 1
                )
                val issuerCommonName = "The issuer"
                val issuerCertificate = Certificate.issue(
                    issuerCommonName,
                    issuerKeyPair.public,
                    issuerKeyPair.private,
                    stubValidityEndDate,
                    rootCACert,
                    isCA = true
                )
                val subjectCertificate = Certificate.issue(
                    stubSubjectCommonName,
                    stubSubjectKeyPair.public,
                    issuerKeyPair.private,
                    stubValidityEndDate,
                    issuerCertificate = issuerCertificate
                )

                assertEquals(1, subjectCertificate.certificateHolder.issuer.rdNs.size)
                assertEquals(
                    false,
                    subjectCertificate.certificateHolder.issuer.rdNs[0].isMultiValued
                )
                assertEquals(
                    issuerCommonName,
                    subjectCertificate.certificateHolder.issuer.rdNs[0].first.value.toString()
                )
            }

            @Test
            fun `Issuer certificate should have basicConstraints extension`() {
                val issuerCommonName = "The issuer"
                val issuerDistinguishedNameBuilder = X500NameBuilder(BCStyle.INSTANCE)
                issuerDistinguishedNameBuilder.addRDN(BCStyle.CN, issuerCommonName)

                val builder = X509v3CertificateBuilder(
                    issuerDistinguishedNameBuilder.build(),
                    42.toBigInteger(),
                    Date.valueOf(LocalDateTime.now().toLocalDate()),
                    Date.valueOf(LocalDateTime.now().toLocalDate().plusMonths(1)),
                    issuerDistinguishedNameBuilder.build(),
                    SubjectPublicKeyInfo.getInstance(issuerKeyPair.public.encoded)
                )
                val signatureAlgorithm =
                    DefaultSignatureAlgorithmIdentifierFinder().find("SHA256WithRSAEncryption")
                val digestAlgorithm =
                    DefaultDigestAlgorithmIdentifierFinder().find(signatureAlgorithm)
                val privateKeyParam: AsymmetricKeyParameter =
                    PrivateKeyFactory.createKey(issuerKeyPair.private.encoded)
                val contentSignerBuilder =
                    BcRSAContentSignerBuilder(signatureAlgorithm, digestAlgorithm)
                val signerBuilder = contentSignerBuilder.build(privateKeyParam)
                val issuerCertificate = Certificate(builder.build(signerBuilder))

                val exception = assertThrows<CertificateException> {
                    Certificate.issue(
                        stubSubjectCommonName,
                        stubSubjectKeyPair.public,
                        issuerKeyPair.private,
                        stubValidityEndDate,
                        issuerCertificate = issuerCertificate
                    )
                }

                assertEquals(
                    "Issuer certificate should have basic constraints extension",
                    exception.message
                )
            }

            @Test
            fun `Issuer certificate should be marked as CA`() {
                val issuerCommonName = "The issuer"
                val issuerCertificate = Certificate.issue(
                    issuerCommonName,
                    issuerKeyPair.public,
                    issuerKeyPair.private,
                    stubValidityEndDate,
                    isCA = false
                )
                val exception = assertThrows<CertificateException> {
                    Certificate.issue(
                        stubSubjectCommonName,
                        stubSubjectKeyPair.public,
                        issuerKeyPair.private,
                        stubValidityEndDate,
                        issuerCertificate = issuerCertificate
                    )
                }

                assertEquals("Issuer certificate should be marked as CA", exception.message)
            }
        }

        @Nested
        inner class BasicConstraintsExtension {
            private val extensionOid = "2.5.29.19"

            @Test
            fun `Extension should be included and marked as critical`() {
                val certificate = Certificate.issue(
                    stubSubjectCommonName,
                    stubSubjectKeyPair.public,
                    stubSubjectKeyPair.private,
                    stubValidityEndDate
                )

                assert(certificate.certificateHolder.hasExtensions())
                val extension =
                    certificate.certificateHolder.getExtension(ASN1ObjectIdentifier(extensionOid))
                assert(extension is Extension)
                assert(extension.isCritical)
            }

            @Test
            fun `CA flag should be false by default`() {
                val certificate = Certificate.issue(
                    stubSubjectCommonName,
                    stubSubjectKeyPair.public,
                    stubSubjectKeyPair.private,
                    stubValidityEndDate
                )

                val basicConstraints =
                    BasicConstraints.fromExtensions(certificate.certificateHolder.extensions)
                assertFalse(basicConstraints.isCA)
            }

            @Test
            fun `CA flag should be enabled if requested`() {
                val certificate = Certificate.issue(
                    stubSubjectCommonName,
                    stubSubjectKeyPair.public,
                    stubSubjectKeyPair.private,
                    stubValidityEndDate,
                    isCA = true
                )

                assert(
                    BasicConstraints.fromExtensions(certificate.certificateHolder.extensions).isCA
                )
            }

            @Test
            fun `CA flag should be enabled if pathLenConstraint is greater than 0`() {
                val exception = assertThrows<CertificateException> {
                    Certificate.issue(
                        stubSubjectCommonName,
                        stubSubjectKeyPair.public,
                        stubSubjectKeyPair.private,
                        stubValidityEndDate,
                        isCA = false,
                        pathLenConstraint = 1
                    )
                }

                assertEquals(
                    "Subject should be a CA if pathLenConstraint=1",
                    exception.message
                )
            }

            @Test
            fun `pathLenConstraint should be 0 by default`() {
                val certificate = Certificate.issue(
                    stubSubjectCommonName,
                    stubSubjectKeyPair.public,
                    stubSubjectKeyPair.private,
                    stubValidityEndDate
                )

                val basicConstraints = BasicConstraints.fromExtensions(
                    certificate.certificateHolder.extensions
                )
                assertEquals(
                    0,
                    basicConstraints.pathLenConstraint.toInt()
                )
            }

            @Test
            fun `pathLenConstraint can be set to a custom value of up to 2`() {
                val certificate = Certificate.issue(
                    stubSubjectCommonName,
                    stubSubjectKeyPair.public,
                    stubSubjectKeyPair.private,
                    stubValidityEndDate,
                    isCA = true,
                    pathLenConstraint = 2
                )

                val basicConstraints = BasicConstraints.fromExtensions(
                    certificate.certificateHolder.extensions
                )
                assertEquals(
                    2,
                    basicConstraints.pathLenConstraint.toInt()
                )
            }

            @Test
            fun `pathLenConstraint should not be greater than 2`() {
                val exception = assertThrows<CertificateException> {
                    Certificate.issue(
                        stubSubjectCommonName,
                        stubSubjectKeyPair.public,
                        stubSubjectKeyPair.private,
                        stubValidityEndDate,
                        pathLenConstraint = 3
                    )
                }

                assertEquals(
                    "pathLenConstraint should be between 0 and 2 (got 3)",
                    exception.message
                )
            }

            @Test
            fun `pathLenConstraint should not be negative`() {
                val exception = assertThrows<CertificateException> {
                    Certificate.issue(
                        stubSubjectCommonName,
                        stubSubjectKeyPair.public,
                        stubSubjectKeyPair.private,
                        stubValidityEndDate,
                        pathLenConstraint = -1
                    )
                }

                assertEquals(
                    "pathLenConstraint should be between 0 and 2 (got -1)",
                    exception.message
                )
            }
        }

        @Nested
        inner class AuthorityKeyIdentifierTest {
            private val issuerKeyPair = generateRSAKeyPair()
            private val issuerCertificate = Certificate.issue(
                stubSubjectCommonName,
                issuerKeyPair.public,
                issuerKeyPair.private,
                stubValidityEndDate,
                isCA = true
            )

            @Test
            fun `Value should correspond to subject when self-issued`() {
                val certificate = Certificate.issue(
                    stubSubjectCommonName,
                    stubSubjectKeyPair.public,
                    stubSubjectKeyPair.private,
                    stubValidityEndDate
                )

                val aki = AuthorityKeyIdentifier.fromExtensions(
                    certificate.certificateHolder.extensions
                )
                val subjectPublicKeyInfo = certificate.certificateHolder.subjectPublicKeyInfo
                assertEquals(
                    sha256(subjectPublicKeyInfo.encoded).asList(),
                    aki.keyIdentifier.asList()
                )
            }

            @Test
            fun `Issuer should be refused if it does not have an SKI`() {
                val issuerDistinguishedName = X500Name("CN=issuer")
                val subjectPublicKeyInfo =
                    SubjectPublicKeyInfo.getInstance(issuerKeyPair.public.encoded)
                val builder = X509v3CertificateBuilder(
                    issuerDistinguishedName,
                    generateRandomBigInteger(),
                    Date.from(ZonedDateTime.now().toInstant()),
                    Date.from(stubValidityEndDate.toInstant()),
                    issuerDistinguishedName,
                    subjectPublicKeyInfo
                )
                val basicConstraints = BasicConstraintsExtension(true, 0)
                builder.addExtension(Extension.basicConstraints, true, basicConstraints)
                val signer = JcaContentSignerBuilder("SHA256WITHRSAANDMGF1")
                    .setProvider(BC_PROVIDER)
                    .build(issuerKeyPair.private)
                val issuerWithoutSKI = Certificate(builder.build(signer))

                val exception = assertThrows<CertificateException> {
                    Certificate.issue(
                        stubSubjectCommonName,
                        stubSubjectKeyPair.public,
                        issuerKeyPair.private,
                        stubValidityEndDate,
                        issuerCertificate = issuerWithoutSKI
                    )
                }

                assertEquals(
                    "Issuer must have the SubjectKeyIdentifier extension",
                    exception.message
                )
            }

            @Test
            fun `Value should correspond to issuer when issued by a CA`() {
                val subjectCertificate = Certificate.issue(
                    stubSubjectCommonName,
                    stubSubjectKeyPair.public,
                    stubSubjectKeyPair.private,
                    stubValidityEndDate,
                    issuerCertificate = issuerCertificate
                )

                val issuerPublicKeyInfo = issuerCertificate.certificateHolder.subjectPublicKeyInfo
                val aki = AuthorityKeyIdentifier.fromExtensions(
                    subjectCertificate.certificateHolder.extensions
                )
                assertEquals(
                    sha256(issuerPublicKeyInfo.encoded).asList(),
                    aki.keyIdentifier.asList()
                )
            }
        }

        @Test
        fun `Subject Key Identifier extension should be SHA-256 digest of subject key`() {
            val certificate = Certificate.issue(
                stubSubjectCommonName,
                stubSubjectKeyPair.public,
                stubSubjectKeyPair.private,
                stubValidityEndDate
            )

            val ski = SubjectKeyIdentifier.fromExtensions(
                certificate.certificateHolder.extensions
            )
            val subjectPublicKeyInfo = certificate.certificateHolder.subjectPublicKeyInfo
            assertEquals(
                sha256(subjectPublicKeyInfo.encoded).asList(),
                ski.keyIdentifier.asList()
            )
        }
    }

    @Nested
    inner class Deserialize {
        @Test
        fun `Valid certificates should be parsed`() {
            val certificate = Certificate.issue(
                stubSubjectCommonName,
                stubSubjectKeyPair.public,
                stubSubjectKeyPair.private,
                stubValidityEndDate
            )
            val certificateSerialized = certificate.serialize()

            val certificateDeserialized = Certificate.deserialize(certificateSerialized)

            assertEquals(certificate, certificateDeserialized)
        }

        @Test
        fun `Invalid certificates should result in errors`() {
            val exception = assertThrows<CertificateException> {
                Certificate.deserialize("Not a certificate".toByteArray())
            }

            assertEquals("Value should be a DER-encoded, X.509 v3 certificate", exception.message)
        }
    }

    @Nested
    inner class Properties {
        @Test
        fun commonName() {
            val certificate = Certificate.issue(
                stubSubjectCommonName,
                stubSubjectKeyPair.public,
                stubSubjectKeyPair.private,
                stubValidityEndDate
            )

            assertEquals(stubSubjectCommonName, certificate.commonName)
        }

        @Test
        fun subjectPrivateAddress() {
            val certificate = Certificate.issue(
                stubSubjectCommonName,
                stubSubjectKeyPair.public,
                stubSubjectKeyPair.private,
                stubValidityEndDate
            )

            val expectedAddress = "0${sha256Hex(stubSubjectKeyPair.public.encoded)}"
            assertEquals(expectedAddress, certificate.subjectPrivateAddress)
        }
    }

    @Nested
    inner class Serialize {
        @Test
        fun `Output should be DER-encoded`() {
            val certificate = Certificate.issue(
                stubSubjectCommonName,
                stubSubjectKeyPair.public,
                stubSubjectKeyPair.private,
                stubValidityEndDate
            )

            val certificateSerialized = certificate.serialize()

            val certificateHolderDeserialized = X509CertificateHolder(certificateSerialized)
            assertEquals(certificate.certificateHolder, certificateHolderDeserialized)
        }
    }

    @Nested
    inner class Equals {
        private val stubCertificate = Certificate.issue(
            stubSubjectCommonName,
            stubSubjectKeyPair.public,
            stubSubjectKeyPair.private,
            stubValidityEndDate
        )

        @Suppress("ReplaceCallWithBinaryOperator")
        @Test
        fun `A non-Certificate object should not equal`() {
            assertFalse(stubCertificate.equals("Hey"))
        }

        @Test
        fun `A different certificate should not equal`() {
            val anotherKeyPair = generateRSAKeyPair()
            val anotherCertificate = Certificate.issue(
                stubSubjectCommonName,
                anotherKeyPair.public,
                anotherKeyPair.private,
                stubValidityEndDate
            )
            assertNotEquals(anotherCertificate, stubCertificate)
        }

        @Test
        fun `An equivalent certificate should equal`() {
            val sameCertificate = Certificate(stubCertificate.certificateHolder)
            assertEquals(stubCertificate, sameCertificate)
        }
    }

    @Nested
    inner class HashCode {
        @Test
        fun `Hashcode should be that of certificate holder`() {
            val stubCertificate = Certificate.issue(
                stubSubjectCommonName,
                stubSubjectKeyPair.public,
                stubSubjectKeyPair.private,
                stubValidityEndDate
            )

            assertEquals(stubCertificate.certificateHolder.hashCode(), stubCertificate.hashCode())
        }
    }

    @Nested
    inner class Validate {
        @Nested
        inner class ValidityPeriod {
            @Test
            fun `Start date in the future should be refused`() {
                val startDate = ZonedDateTime.now().plusSeconds(2)
                val certificate = Certificate.issue(
                    stubSubjectCommonName,
                    stubSubjectKeyPair.public,
                    stubSubjectKeyPair.private,
                    stubValidityEndDate,
                    validityStartDate = startDate
                )

                val exception = assertThrows<CertificateException> { certificate.validate() }

                assertEquals("Certificate is not yet valid", exception.message)
            }

            @Test
            fun `Expiry date in the past should be refused`() {
                val startDate = ZonedDateTime.now().minusSeconds(2)
                val endDate = startDate.plusSeconds(1)
                val certificate = Certificate.issue(
                    stubSubjectCommonName,
                    stubSubjectKeyPair.public,
                    stubSubjectKeyPair.private,
                    endDate,
                    validityStartDate = startDate
                )

                val exception = assertThrows<CertificateException> { certificate.validate() }

                assertEquals("Certificate already expired", exception.message)
            }

            @Test
            fun `Start date in the past and end date in the future should be accepted`() {
                val certificate = Certificate.issue(
                    stubSubjectCommonName,
                    stubSubjectKeyPair.public,
                    stubSubjectKeyPair.private,
                    stubValidityEndDate
                )

                certificate.validate()
            }
        }

        @Nested
        inner class CommonName {
            @Test
            fun `Validation should fail if Common Name is missing`() {
                val issuerDistinguishedNameBuilder = X500NameBuilder(BCStyle.INSTANCE)
                issuerDistinguishedNameBuilder.addRDN(BCStyle.C, "GB")
                val builder = X509v3CertificateBuilder(
                    issuerDistinguishedNameBuilder.build(),
                    42.toBigInteger(),
                    Date.valueOf(LocalDateTime.now().toLocalDate()),
                    Date.valueOf(stubValidityEndDate.toLocalDate().plusMonths(1)),
                    issuerDistinguishedNameBuilder.build(),
                    SubjectPublicKeyInfo.getInstance(stubSubjectKeyPair.public.encoded)
                )
                val signatureAlgorithm =
                    DefaultSignatureAlgorithmIdentifierFinder().find("SHA256WithRSAEncryption")
                val digestAlgorithm =
                    DefaultDigestAlgorithmIdentifierFinder().find(signatureAlgorithm)
                val privateKeyParam: AsymmetricKeyParameter =
                    PrivateKeyFactory.createKey(stubSubjectKeyPair.private.encoded)
                val contentSignerBuilder =
                    BcRSAContentSignerBuilder(signatureAlgorithm, digestAlgorithm)
                val signerBuilder = contentSignerBuilder.build(privateKeyParam)
                val invalidCertificate = Certificate(builder.build(signerBuilder))

                val exception = assertThrows<CertificateException> { invalidCertificate.validate() }

                assertEquals("Subject should have a Common Name", exception.message)
            }

            @Test
            fun `Validation should pass if Common Name is present`() {
                val certificate = Certificate.issue(
                    stubSubjectCommonName,
                    stubSubjectKeyPair.public,
                    stubKeyPair.private,
                    stubValidityEndDate
                )

                certificate.validate()
            }
        }
    }

    @Nested
    inner class GetCertificationPath {
        private val rootCACert = Certificate.issue(
            stubSubjectCommonName,
            stubSubjectKeyPair.public,
            stubSubjectKeyPair.private,
            stubValidityEndDate,
            isCA = true,
            pathLenConstraint = 2
        )

        @Test
        fun `Certificate self-issued by trusted CA should be trusted`() {
            val certPath = rootCACert.getCertificationPath(emptySet(), setOf(rootCACert))

            assertEquals(listOf(rootCACert), certPath.asList())
        }

        @Test
        fun `Cert issued by trusted CA should be trusted`() {
            val endEntityKeyPair = generateRSAKeyPair()
            val endEntityCert = issueStubCertificate(
                endEntityKeyPair.public,
                stubSubjectKeyPair.private,
                rootCACert
            )

            val certPath = endEntityCert.getCertificationPath(emptySet(), setOf(rootCACert))

            assertEquals(listOf(endEntityCert, rootCACert), certPath.asList())
        }

        @Test
        fun `Cert not issued by trusted cert should not be trusted`() {
            val endEntityKeyPair = generateRSAKeyPair()
            val endEntityCert =
                issueStubCertificate(endEntityKeyPair.public, endEntityKeyPair.private)

            val exception = assertThrows<CertificateException> {
                endEntityCert.getCertificationPath(emptySet(), setOf(rootCACert))
            }

            assertEquals("No certification path could be found", exception.message)
            assertTrue(exception.cause is CertPathBuilderException)
        }

        @Test
        fun `Cert issued by untrusted intermediate CA should be trusted if root is trusted`() {
            val intermediateCAKeyPair = generateRSAKeyPair()
            val intermediateCACert = issueStubCertificate(
                intermediateCAKeyPair.public,
                stubSubjectKeyPair.private,
                rootCACert,
                isCA = true
            )
            val endEntityKeyPair = generateRSAKeyPair()
            val endEntityCert = issueStubCertificate(
                endEntityKeyPair.public,
                intermediateCAKeyPair.private,
                intermediateCACert
            )

            val certPath =
                endEntityCert.getCertificationPath(setOf(intermediateCACert), setOf(rootCACert))

            assertEquals(listOf(endEntityCert, intermediateCACert, rootCACert), certPath.asList())
        }

        @Test
        fun `Valid paths may have multiple untrusted intermediate CA`() {
            val intermediateCA1KeyPair = generateRSAKeyPair()
            val intermediateCA1Cert = Certificate.issue(
                "intermediate1",
                intermediateCA1KeyPair.public,
                stubSubjectKeyPair.private,
                stubValidityEndDate,
                rootCACert,
                true,
                1
            )
            val intermediateCA2KeyPair = generateRSAKeyPair()
            val intermediateCA2Cert = Certificate.issue(
                "intermediate2",
                intermediateCA2KeyPair.public,
                intermediateCA1KeyPair.private,
                stubValidityEndDate,
                intermediateCA1Cert,
                true
            )
            val endEntityKeyPair = generateRSAKeyPair()
            val endEntityCert = Certificate.issue(
                "end",
                endEntityKeyPair.public,
                intermediateCA2KeyPair.private,
                stubValidityEndDate,
                intermediateCA2Cert
            )

            val certPath = endEntityCert.getCertificationPath(
                setOf(intermediateCA1Cert, intermediateCA2Cert),
                setOf(rootCACert)
            )

            assertEquals(
                listOf(endEntityCert, intermediateCA2Cert, intermediateCA1Cert, rootCACert),
                certPath.asList()
            )
        }

        @Test
        fun `Cert issued by trusted intermediate CA should be trusted`() {
            val intermediateCAKeyPair = generateRSAKeyPair()
            val intermediateCACert = issueStubCertificate(
                intermediateCAKeyPair.public,
                stubSubjectKeyPair.private,
                rootCACert,
                isCA = true
            )
            val endEntityKeyPair = generateRSAKeyPair()
            val endEntityCert = issueStubCertificate(
                endEntityKeyPair.public,
                intermediateCAKeyPair.private,
                intermediateCACert
            )

            val certPath =
                endEntityCert.getCertificationPath(emptySet(), setOf(intermediateCACert))

            assertEquals(listOf(endEntityCert, intermediateCACert), certPath.asList())
        }

        @Test
        fun `Cert issued by untrusted intermediate CA should not be trusted`() {
            val intermediateCAKeyPair = generateRSAKeyPair()
            val intermediateCACert = issueStubCertificate(
                intermediateCAKeyPair.public,
                intermediateCAKeyPair.private,
                isCA = true
            )
            val endEntityKeyPair = generateRSAKeyPair()
            val endEntityCert = issueStubCertificate(
                endEntityKeyPair.public,
                endEntityKeyPair.private,
                intermediateCACert
            )

            val exception = assertThrows<CertificateException> {
                endEntityCert.getCertificationPath(setOf(intermediateCACert), setOf(rootCACert))
            }

            assertEquals("No certification path could be found", exception.message)
        }

        @Test
        fun `Including trusted intermediate CA should not make certificate trusted`() {
            val intermediateCAKeyPair = generateRSAKeyPair()
            val intermediateCACert = issueStubCertificate(
                intermediateCAKeyPair.public,
                intermediateCAKeyPair.private,
                isCA = true
            )
            val endEntityKeyPair = generateRSAKeyPair()
            val endEntityCert = issueStubCertificate(
                endEntityKeyPair.public,
                endEntityKeyPair.private,
                intermediateCACert
            )

            val exception = assertThrows<CertificateException> {
                endEntityCert.getCertificationPath(
                    setOf(rootCACert, intermediateCACert),
                    setOf(rootCACert)
                )
            }

            assertEquals("No certification path could be found", exception.message)
        }

        @Test
        fun `Root CA in path should be identified when there are multiple trusted CAs`() {
            // Java doesn't include the trusted CA in the path, so we have to include it
            // ourselves. Let's make sure we do it properly.
            val trustedCA2KeyPair = generateRSAKeyPair()
            val trustedCA2Cert = issueStubCertificate(
                trustedCA2KeyPair.public,
                trustedCA2KeyPair.private,
                isCA = true
            )

            val intermediateCAKeyPair = generateRSAKeyPair()
            val intermediateCACert = issueStubCertificate(
                intermediateCAKeyPair.public,
                stubSubjectKeyPair.private,
                rootCACert,
                isCA = true
            )
            val endEntityKeyPair = generateRSAKeyPair()
            val endEntityCert = issueStubCertificate(
                endEntityKeyPair.public,
                intermediateCAKeyPair.private,
                intermediateCACert
            )

            val certPath = endEntityCert.getCertificationPath(
                setOf(intermediateCACert),
                setOf(trustedCA2Cert, rootCACert)
            )

            assertEquals(listOf(endEntityCert, intermediateCACert, rootCACert), certPath.asList())
        }

        @Test
        fun `The exact same instance of the certificates should be returned`() {
            val intermediateCAKeyPair = generateRSAKeyPair()
            val intermediateCACert = issueStubCertificate(
                intermediateCAKeyPair.public,
                stubSubjectKeyPair.private,
                rootCACert,
                isCA = true
            )
            val endEntityKeyPair = generateRSAKeyPair()
            val endEntityCert = issueStubCertificate(
                endEntityKeyPair.public,
                intermediateCAKeyPair.private,
                intermediateCACert
            )

            val certPath =
                endEntityCert.getCertificationPath(setOf(intermediateCACert), setOf(rootCACert))

            assertEquals(3, certPath.size)
            assertSame(endEntityCert, certPath.first())
            assertSame(intermediateCACert, certPath[1])
            assertSame(rootCACert, certPath.last())
        }

        @Test
        fun `An empty set of trusted CAs will fail validation`() {
            val endEntityKeyPair = generateRSAKeyPair()
            val endEntityCert = issueStubCertificate(
                endEntityKeyPair.public,
                stubSubjectKeyPair.private,
                rootCACert
            )

            val exception = assertThrows<CertificateException> {
                endEntityCert.getCertificationPath(emptySet(), emptySet())
            }

            assertEquals(
                "Failed to initialize path builder; set of trusted CAs might be empty",
                exception.message
            )
            assertTrue(exception.cause is InvalidAlgorithmParameterException)
        }
    }
}

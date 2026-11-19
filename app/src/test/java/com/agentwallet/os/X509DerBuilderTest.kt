package com.agentwallet.os

import com.agentwallet.os.crypto.X509DerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class X509DerBuilderTest {

  private fun decodeBase64(b64: String): ByteArray {
    return java.util.Base64.getDecoder().decode(b64)
  }

  @Test
  fun selfSignedCertificate_isValidX509_andVerifiesWithPublicKey() {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp = kpg.generateKeyPair()

    val pem = X509DerBuilder.generateSelfSignedCertificatePem(
      keyPair = kp,
      commonName = "agent.wallet.internal",
      organization = "AI Corp Enterprises",
      organizationalUnit = "SecOps",
      country = "US",
      state = "CA",
      locality = "San Francisco",
      email = "ops@agentwallet.os",
      validityDays = 365
    )

    assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----"))
    assertTrue(pem.endsWith("-----END CERTIFICATE-----"))

    val cf = CertificateFactory.getInstance("X.509")
    val cert = cf.generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.UTF_8))) as X509Certificate

    cert.checkValidity()
    cert.verify(kp.public)
    assertEquals(3, cert.version)
    val principalName = cert.subjectX500Principal.name
    assertTrue(principalName.contains("CN=agent.wallet.internal"))
    assertTrue(principalName.contains("O=AI Corp Enterprises"))
    assertTrue(principalName.contains("C=US"))
  }

  @Test
  fun pkcs10Csr_hasValidDerStructure_andValidSha256RsaSignature() {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp = kpg.generateKeyPair()

    val pem = X509DerBuilder.generateCsrPem(
      keyPair = kp,
      commonName = "gateway.agentwallet.os",
      organization = "Agent Wallet Corp",
      organizationalUnit = "Treasury",
      country = "PL",
      state = "Mazovia",
      locality = "Warsaw",
      email = "pki@agentwallet.os"
    )

    assertTrue(pem.startsWith("-----BEGIN CERTIFICATE REQUEST-----"))
    assertTrue(pem.endsWith("-----END CERTIFICATE REQUEST-----"))

    val body = pem
      .removePrefix("-----BEGIN CERTIFICATE REQUEST-----")
      .removeSuffix("-----END CERTIFICATE REQUEST-----")
      .replace("\n", "")
      .trim()
    val der = decodeBase64(body)

    // Parse outer SEQUENCE { certificationRequestInfo, signatureAlgorithm, signature BIT STRING }
    val (outerTag, outerRange) = readTlv(der, 0)
    assertEquals(0x30, outerTag)
    var pos = outerRange.first

    val (infoTag, infoContent) = readTlv(der, pos)
    assertEquals(0x30, infoTag)
    val infoEnd = infoContent.last + 1
    val infoBytes = der.copyOfRange(pos, infoEnd)
    pos = infoEnd

    val (algTag, algContent) = readTlv(der, pos)
    assertEquals(0x30, algTag)
    pos = algContent.last + 1

    val (sigTag, sigContent) = readTlv(der, pos)
    assertEquals(0x03, sigTag)
    assertEquals(0, der[sigContent.first].toInt() and 0xFF) // 0 unused bits
    val signatureBytes = der.copyOfRange(sigContent.first + 1, sigContent.last + 1)

    val verifier = Signature.getInstance("SHA256withRSA")
    verifier.initVerify(kp.public)
    verifier.update(infoBytes)
    assertTrue("PKCS#10 CSR signature must verify against public key", verifier.verify(signatureBytes))
  }

  private fun readTlv(data: ByteArray, offset: Int): Pair<Int, IntRange> {
    val tag = data[offset].toInt() and 0xFF
    val lenByte = data[offset + 1].toInt() and 0xFF
    val (len, contentStart) = if (lenByte < 0x80) {
      lenByte to (offset + 2)
    } else {
      val numBytes = lenByte and 0x7F
      var acc = 0
      for (i in 0 until numBytes) {
        acc = (acc shl 8) or (data[offset + 2 + i].toInt() and 0xFF)
      }
      acc to (offset + 2 + numBytes)
    }
    return tag to (contentStart until (contentStart + len))
  }
}

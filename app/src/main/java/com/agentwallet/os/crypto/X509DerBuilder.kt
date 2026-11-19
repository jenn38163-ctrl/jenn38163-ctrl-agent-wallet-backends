package com.agentwallet.os.crypto

import java.math.BigInteger
import java.security.KeyPair
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure-Kotlin RFC 2986 (PKCS#10 CSR) and RFC 5280 (X.509 v3 Self-Signed Certificate)
 * ASN.1 DER encoder and SHA256withRSA signer.
 *
 * Works with AndroidKeyStore RSA keys at runtime and standard JCA RSA KeyPairs in JVM unit tests
 * (zero external ASN.1 dependencies and minSdk 24+ compatible).
 */
object X509DerBuilder {
  private const val OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
  private const val OID_COUNTRY = "2.5.4.6"
  private const val OID_STATE = "2.5.4.8"
  private const val OID_LOCALITY = "2.5.4.7"
  private const val OID_ORG = "2.5.4.10"
  private const val OID_OU = "2.5.4.11"
  private const val OID_COMMON_NAME = "2.5.4.3"
  private const val OID_EMAIL = "1.2.840.113549.1.9.1"
  private const val OID_KEY_USAGE = "2.5.29.15"
  private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"

  private val random = SecureRandom()

  fun generateCsrPem(
    keyPair: KeyPair,
    commonName: String,
    organization: String,
    organizationalUnit: String,
    country: String,
    state: String,
    locality: String,
    email: String
  ): String {
    val der = generateCsrDer(
      keyPair = keyPair,
      commonName = commonName,
      organization = organization,
      organizationalUnit = organizationalUnit,
      country = country,
      state = state,
      locality = locality,
      email = email
    )
    return wrapPem("CERTIFICATE REQUEST", der)
  }

  fun generateCsrDer(
    keyPair: KeyPair,
    commonName: String,
    organization: String,
    organizationalUnit: String,
    country: String,
    state: String,
    locality: String,
    email: String
  ): ByteArray {
    val subjectName = buildDistinguishedName(
      commonName = commonName,
      organization = organization,
      organizationalUnit = organizationalUnit,
      country = country,
      state = state,
      locality = locality,
      email = email
    )
    val subjectPublicKeyInfo = keyPair.public.encoded
      ?: throw IllegalArgumentException("Public key does not provide X.509 SPKI encoding")

    // CertificationRequestInfo ::= SEQUENCE { version INTEGER(0), subject Name, subjectPKInfo, attributes [0] IMPLICIT }
    val certificationRequestInfo = derSequence(
      derInteger(BigInteger.ZERO),
      subjectName,
      subjectPublicKeyInfo,
      derTag(0xA0, ByteArray(0))
    )

    val signatureBytes = signSha256WithRsa(keyPair.private, certificationRequestInfo)
    val sigAlgId = derSequence(derOid(OID_SHA256_WITH_RSA), derNull())

    return derSequence(
      certificationRequestInfo,
      sigAlgId,
      derBitString(signatureBytes, 0)
    )
  }

  fun generateSelfSignedCertificatePem(
    keyPair: KeyPair,
    commonName: String,
    organization: String,
    organizationalUnit: String,
    country: String,
    state: String,
    locality: String,
    email: String,
    validityDays: Int,
    nowMillis: Long = System.currentTimeMillis()
  ): String {
    val der = generateSelfSignedCertificateDer(
      keyPair = keyPair,
      commonName = commonName,
      organization = organization,
      organizationalUnit = organizationalUnit,
      country = country,
      state = state,
      locality = locality,
      email = email,
      validityDays = validityDays,
      nowMillis = nowMillis
    )
    return wrapPem("CERTIFICATE", der)
  }

  fun generateSelfSignedCertificateDer(
    keyPair: KeyPair,
    commonName: String,
    organization: String,
    organizationalUnit: String,
    country: String,
    state: String,
    locality: String,
    email: String,
    validityDays: Int,
    nowMillis: Long = System.currentTimeMillis()
  ): ByteArray {
    val safeDays = validityDays.coerceAtLeast(1)
    val subjectName = buildDistinguishedName(
      commonName = commonName,
      organization = organization,
      organizationalUnit = organizationalUnit,
      country = country,
      state = state,
      locality = locality,
      email = email
    )
    val subjectPublicKeyInfo = keyPair.public.encoded
      ?: throw IllegalArgumentException("Public key does not provide X.509 SPKI encoding")

    val serialBytes = ByteArray(16)
    random.nextBytes(serialBytes)
    val serial = BigInteger(1, serialBytes).max(BigInteger.ONE)

    val sigAlgId = derSequence(derOid(OID_SHA256_WITH_RSA), derNull())

    val notBefore = nowMillis - 60_000L
    val notAfter = nowMillis + safeDays.toLong() * 86_400_000L
    val validity = derSequence(derTime(notBefore), derTime(notAfter))

    // X.509 v3 BasicConstraints (cA = FALSE, critical = TRUE)
    val basicConstraintsExt = derSequence(
      derOid(OID_BASIC_CONSTRAINTS),
      derBoolean(true),
      derOctetString(derSequence())
    )
    // KeyUsage (digitalSignature bit 0 + keyEncipherment bit 2 -> 0xA0, 5 unused bits, critical = TRUE)
    val keyUsageExt = derSequence(
      derOid(OID_KEY_USAGE),
      derBoolean(true),
      derOctetString(derBitString(byteArrayOf(0xA0.toByte()), 5))
    )
    val extensions = derTag(0xA3, derSequence(basicConstraintsExt, keyUsageExt))

    val tbsCertificate = derSequence(
      derTag(0xA0, derInteger(BigInteger.valueOf(2))), // version = v3(2)
      derInteger(serial),
      sigAlgId,
      subjectName, // issuer == subject for self-signed
      validity,
      subjectName,
      subjectPublicKeyInfo,
      extensions
    )

    val signatureBytes = signSha256WithRsa(keyPair.private, tbsCertificate)
    return derSequence(
      tbsCertificate,
      sigAlgId,
      derBitString(signatureBytes, 0)
    )
  }

  private fun signSha256WithRsa(privateKey: PrivateKey, payload: ByteArray): ByteArray {
    val signer = Signature.getInstance("SHA256withRSA")
    signer.initSign(privateKey)
    signer.update(payload)
    return signer.sign()
  }

  private fun buildDistinguishedName(
    commonName: String,
    organization: String,
    organizationalUnit: String,
    country: String,
    state: String,
    locality: String,
    email: String
  ): ByteArray {
    val rdns = mutableListOf<ByteArray>()

    val cleanCountry = country.trim().uppercase(Locale.US)
    if (cleanCountry.isNotEmpty()) {
      val countryValue = if (cleanCountry.all { it in 'A'..'Z' || it in '0'..'9' }) {
        derPrintableString(cleanCountry.take(2))
      } else {
        derUtf8String(cleanCountry.take(2))
      }
      rdns += derRdn(OID_COUNTRY, countryValue)
    }
    if (state.isNotBlank()) rdns += derRdn(OID_STATE, derUtf8String(state.trim()))
    if (locality.isNotBlank()) rdns += derRdn(OID_LOCALITY, derUtf8String(locality.trim()))
    if (organization.isNotBlank()) rdns += derRdn(OID_ORG, derUtf8String(organization.trim()))
    if (organizationalUnit.isNotBlank()) rdns += derRdn(OID_OU, derUtf8String(organizationalUnit.trim()))

    val resolvedCn = commonName.trim().ifEmpty {
      if (rdns.isEmpty()) "Agent Wallet OS" else ""
    }
    if (resolvedCn.isNotEmpty()) {
      rdns += derRdn(OID_COMMON_NAME, derUtf8String(resolvedCn))
    }
    if (email.isNotBlank()) {
      val trimmedEmail = email.trim()
      val emailVal = if (trimmedEmail.all { it.code in 0x20..0x7E }) {
        derIa5String(trimmedEmail)
      } else {
        derUtf8String(trimmedEmail)
      }
      rdns += derRdn(OID_EMAIL, emailVal)
    }

    return derSequence(*rdns.toTypedArray())
  }

  private fun derRdn(oid: String, valueTlv: ByteArray): ByteArray {
    return derSet(derSequence(derOid(oid), valueTlv))
  }

  private fun derSequence(vararg items: ByteArray): ByteArray = derTag(0x30, concat(*items))

  private fun derSet(vararg items: ByteArray): ByteArray = derTag(0x31, concat(*items))

  private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

  private fun derBoolean(value: Boolean): ByteArray =
    derTag(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0x00))

  private fun derInteger(value: BigInteger): ByteArray = derTag(0x02, value.toByteArray())

  private fun derBitString(bytes: ByteArray, unusedBits: Int): ByteArray =
    derTag(0x03, byteArrayOf(unusedBits.toByte()) + bytes)

  private fun derOctetString(bytes: ByteArray): ByteArray = derTag(0x04, bytes)

  private fun derUtf8String(value: String): ByteArray =
    derTag(0x0C, value.toByteArray(Charsets.UTF_8))

  private fun derPrintableString(value: String): ByteArray =
    derTag(0x13, value.toByteArray(Charsets.US_ASCII))

  private fun derIa5String(value: String): ByteArray =
    derTag(0x16, value.toByteArray(Charsets.US_ASCII))

  private fun derTime(epochMillis: Long): ByteArray {
    val utcZone = TimeZone.getTimeZone("UTC")
    val yearFmt = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = utcZone }
    val year = yearFmt.format(Date(epochMillis)).toInt()
    return if (year in 1950..2049) {
      val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = utcZone }
      derTag(0x17, fmt.format(Date(epochMillis)).toByteArray(Charsets.US_ASCII))
    } else {
      val fmt = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US).apply { timeZone = utcZone }
      derTag(0x18, fmt.format(Date(epochMillis)).toByteArray(Charsets.US_ASCII))
    }
  }

  private fun derOid(oid: String): ByteArray {
    val arcs = oid.split('.').map { it.toLong() }
    require(arcs.size >= 2) { "Invalid OID: $oid" }
    val out = mutableListOf<Byte>()
    out.add((arcs[0] * 40 + arcs[1]).toByte())
    for (i in 2 until arcs.size) {
      var v = arcs[i]
      if (v == 0L) {
        out.add(0)
        continue
      }
      val stack = mutableListOf<Byte>()
      while (v > 0L) {
        stack.add((v and 0x7F).toByte())
        v = v ushr 7
      }
      for (j in stack.size - 1 downTo 0) {
        val b = stack[j].toInt() and 0x7F
        out.add((if (j > 0) (b or 0x80) else b).toByte())
      }
    }
    return derTag(0x06, out.toByteArray())
  }

  private fun derTag(tag: Int, content: ByteArray): ByteArray {
    val len = content.size
    val lenBytes = when {
      len < 0x80 -> byteArrayOf(len.toByte())
      len <= 0xFF -> byteArrayOf(0x81.toByte(), len.toByte())
      len <= 0xFFFF -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
      len <= 0xFFFFFF -> byteArrayOf(
        0x83.toByte(),
        (len shr 16).toByte(),
        (len shr 8).toByte(),
        len.toByte()
      )
      else -> byteArrayOf(
        0x84.toByte(),
        (len shr 24).toByte(),
        (len shr 16).toByte(),
        (len shr 8).toByte(),
        len.toByte()
      )
    }
    val res = ByteArray(1 + lenBytes.size + content.size)
    res[0] = tag.toByte()
    System.arraycopy(lenBytes, 0, res, 1, lenBytes.size)
    System.arraycopy(content, 0, res, 1 + lenBytes.size, content.size)
    return res
  }

  private fun concat(vararg arrays: ByteArray): ByteArray {
    val total = arrays.sumOf { it.size }
    val out = ByteArray(total)
    var offset = 0
    for (arr in arrays) {
      System.arraycopy(arr, 0, out, offset, arr.size)
      offset += arr.size
    }
    return out
  }

  fun encodeBase64(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
    var i = 0
    while (i < bytes.size) {
      val b0 = bytes[i++].toInt() and 0xFF
      val hasB1 = i < bytes.size
      val b1 = if (hasB1) (bytes[i++].toInt() and 0xFF) else 0
      val hasB2 = i < bytes.size
      val b2 = if (hasB2) (bytes[i++].toInt() and 0xFF) else 0

      sb.append(alphabet[b0 ushr 2])
      sb.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
      sb.append(if (hasB1) alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
      sb.append(if (hasB2) alphabet[b2 and 0x3F] else '=')
    }
    return sb.toString()
  }

  private fun wrapPem(type: String, derBytes: ByteArray): String {
    val b64 = encodeBase64(derBytes).chunked(64).joinToString("\n")
    return "-----BEGIN $type-----\n$b64\n-----END $type-----"
  }
}

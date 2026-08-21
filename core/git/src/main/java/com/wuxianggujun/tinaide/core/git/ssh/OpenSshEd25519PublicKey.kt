package com.wuxianggujun.tinaide.core.git.ssh

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.PublicKey
import java.util.Base64

private const val OPENSSH_ED25519_KEY_TYPE = "ssh-ed25519"
private const val ED25519_RAW_PUBLIC_KEY_BYTES = 32
private val ED25519_SUBJECT_PUBLIC_KEY_INFO_PREFIX = byteArrayOf(
    0x30,
    0x2a,
    0x30,
    0x05,
    0x06,
    0x03,
    0x2b,
    0x65,
    0x70,
    0x03,
    0x21,
    0x00,
)

internal fun formatOpenSshEd25519PublicKey(publicKey: PublicKey, comment: String? = null): String {
    require(publicKey.algorithm.equals("Ed25519", ignoreCase = true) ||
        publicKey.algorithm.equals("EdDSA", ignoreCase = true)) {
        "Expected an Ed25519 public key"
    }
    val rawPublicKey = extractRawEd25519PublicKey(publicKey.encoded)
    val payload = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeSshString(OPENSSH_ED25519_KEY_TYPE.toByteArray(Charsets.US_ASCII))
            output.writeSshString(rawPublicKey)
        }
        bytes.toByteArray()
    }
    val suffix = normalizeSshPublicKeyComment(comment)?.let { " $it" }.orEmpty()
    return "$OPENSSH_ED25519_KEY_TYPE ${Base64.getEncoder().encodeToString(payload)}$suffix"
}

internal fun normalizeSshPublicKeyComment(comment: String?): String? = comment
    ?.filter { it >= ' ' && it != '\u007f' }
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

private fun extractRawEd25519PublicKey(encoded: ByteArray): ByteArray {
    require(encoded.size == ED25519_SUBJECT_PUBLIC_KEY_INFO_PREFIX.size + ED25519_RAW_PUBLIC_KEY_BYTES &&
        encoded.copyOfRange(0, ED25519_SUBJECT_PUBLIC_KEY_INFO_PREFIX.size)
            .contentEquals(ED25519_SUBJECT_PUBLIC_KEY_INFO_PREFIX)) {
        "Unsupported Ed25519 public key encoding"
    }
    return encoded.copyOfRange(ED25519_SUBJECT_PUBLIC_KEY_INFO_PREFIX.size, encoded.size)
}

private fun DataOutputStream.writeSshString(value: ByteArray) {
    writeInt(value.size)
    write(value)
}

package com.noahprail.kairos.protocol

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Challenge/response from docs/BLE_PROTOCOL.md §5. */
object AuthProof {
    private val random = SecureRandom()

    private fun mac(secret: ByteArray, label: String, nonce: ByteArray, clientNonce: ByteArray): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(secret, "HmacSHA256"))
        hmac.update(label.toByteArray(Charsets.UTF_8))
        hmac.update(nonce)
        hmac.update(clientNonce)
        return hmac.doFinal()
    }

    /** Proof the client sends in `auth`. */
    fun client(secret: ByteArray, nonce: ByteArray, clientNonce: ByteArray): ByteArray =
        mac(secret, "kairos-auth-v1", nonce, clientNonce)

    /** Proof the device returns, so the client knows it's the real device. */
    fun server(secret: ByteArray, nonce: ByteArray, clientNonce: ByteArray): ByteArray =
        mac(secret, "kairos-auth-v1-srv", nonce, clientNonce)

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }
}

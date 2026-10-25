package com.noahprail.camtrigger.ble

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.noahprail.camtrigger.protocol.DeviceError
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pairing keys, one per device, encrypted with a key held in the Android Keystore.
 * The ciphertext lives in app-private preferences that are excluded from backup
 * and device transfer, so a key never leaves this phone.
 */
class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun load(deviceId: String): ByteArray? {
        val stored = prefs.getString(deviceId.uppercase(), null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE)
        } catch (_: Exception) {
            null
        }
    }

    fun save(secret: ByteArray, deviceId: String) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val sealed = cipher.iv + cipher.doFinal(secret)
            val ok = prefs.edit().putString(deviceId.uppercase(), Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
            check(ok) { "preferences write failed" }
        } catch (e: Exception) {
            throw DeviceError("keystore", "Could not save the pairing key (${e.message ?: "Keystore error"}).")
        }
    }

    fun delete(deviceId: String) {
        prefs.edit().remove(deviceId.uppercase()).apply()
    }

    private companion object {
        const val ALIAS = "camtrigger_pairing_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

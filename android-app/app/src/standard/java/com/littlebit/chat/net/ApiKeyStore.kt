package com.littlebit.chat.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys are encrypted with a non-exportable AndroidKeyStore AES key
 * (`AES/GCM/NoPadding`, randomized 12-byte IV, stored as `[IV || ciphertext]`
 * Base64). The file lives in app-private storage and is excluded from backups.
 *
 * A key that cannot be decrypted (factory reset restore, keystore invalidation,
 * corrupted file) is deleted and reported as "no key" instead of crashing. Key
 * material is never logged.
 */
class ApiKeyStore(context: Context) {

    private val file = File(File(context.filesDir, "api_keys").apply { if (!isDirectory) mkdirs() }, "cloud.key.enc")

    fun hasKey(): Boolean = file.isFile && file.length() > IV_BYTES

    fun save(key: String) {
        val plaintext = key.trim()
        require(plaintext.isNotEmpty()) { "Enter an API key first." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Unexpected GCM IV length" }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(ciphertext, 0, payload, iv.size, ciphertext.size)
        val encoded = Base64.getEncoder().encodeToString(payload)
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(encoded)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }

    fun read(): String? {
        if (!hasKey()) return null
        return try {
            val payload = Base64.getDecoder().decode(file.readText())
            if (payload.size <= IV_BYTES) throw GeneralSecurityException("Truncated key payload")
            val iv = payload.copyOfRange(0, IV_BYTES)
            val ciphertext = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            clear()
            null
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear()
            null
        } catch (e: ProviderException) {
            clear()
            null
        } catch (e: GeneralSecurityException) {
            clear()
            null
        } catch (e: IllegalArgumentException) {
            clear()
            null
        } catch (e: IllegalStateException) {
            clear()
            null
        }
    }

    fun clear() {
        file.delete()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun secretKey(): SecretKey {
        val store = keyStore()
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "littlebit_cloud_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

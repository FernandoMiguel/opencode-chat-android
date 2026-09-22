package net.lichias.opencodechat.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the API key encrypted with a hardware-backed AES-GCM key that never
 * leaves the secure enclave (StrongBox when the device has one). Only ciphertext
 * is written to disk; plaintext exists in memory only while the app runs.
 */
object KeyVault {

    private const val ALIAS = "opencodechat_api_key"
    private const val PREFS = "opencode_secure"
    private const val KEY_BLOB = "cipher_blob"
    private const val IV_SIZE = 12

    /** Per-provider / token slot ids. Legacy single blob maps to Zen. */
    fun keyIdForProvider(providerId: String): String = "key_$providerId"

    fun saveFor(context: Context, keyId: String, plain: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (plain.isBlank()) {
            prefs.edit().remove(blobKey(keyId)).apply()
            if (keyId == keyIdForProvider("ZEN")) {
                // Keep legacy slot in sync when clearing the Zen key.
                prefs.edit().remove(KEY_BLOB).apply()
            }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val editor = prefs.edit()
            .putString(blobKey(keyId), Base64.encodeToString(encrypted, Base64.NO_WRAP))
        if (keyId == keyIdForProvider("ZEN")) {
            editor.putString(KEY_BLOB, Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
        editor.apply()
    }

    fun loadFor(context: Context, keyId: String): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var blob = prefs.getString(blobKey(keyId), null)
        if (blob == null && keyId == keyIdForProvider("ZEN")) {
            // Migrate legacy single-key installs.
            blob = prefs.getString(KEY_BLOB, null) ?: return ""
        }
        if (blob == null) return ""
        return runCatching {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, bytes.copyOfRange(0, IV_SIZE)),
            )
            String(cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun blobKey(keyId: String): String = "blob_$keyId"

    fun save(context: Context, plain: String) = saveFor(context, keyIdForProvider("ZEN"), plain)

    fun load(context: Context): String = loadFor(context, keyIdForProvider("ZEN"))

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        }
    }

    fun encryptBytes(context: Context, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decryptBytes(context: Context, blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, blob.copyOfRange(0, IV_SIZE)),
        )
        return cipher.doFinal(blob.copyOfRange(IV_SIZE, blob.size))
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        try {
            generator.init(spec.setIsStrongBoxBacked(true).build())
            return generator.generateKey()
        } catch (_: Exception) {
            // Device has no StrongBox; fall back to the TEE-backed keystore.
        }
        generator.init(spec.build())
        return generator.generateKey()
    }

    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}

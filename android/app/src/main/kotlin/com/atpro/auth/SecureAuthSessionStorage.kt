package com.atpro.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface AuthSessionStorage {
    fun load(): SupabaseAuthSession?
    fun save(session: SupabaseAuthSession)
    fun clear()
}

/**
 * Stores the Supabase session encrypted with an AES-GCM key held by Android Keystore.
 * Only ciphertext and the random GCM IV are written to app-private SharedPreferences.
 */
internal class SecureAuthSessionStorage(context: Context) : AuthSessionStorage {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): SupabaseAuthSession? {
        val encoded = prefs.getString(KEY_SESSION, null) ?: return null
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= GCM_IV_LENGTH) return null

            val iv = packed.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = packed.copyOfRange(GCM_IV_LENGTH, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            Json.decodeFromString<SupabaseAuthSession>(cipher.doFinal(encrypted).toString(Charsets.UTF_8))
        } catch (_: Exception) {
            // A corrupted or invalidated local key must not crash app startup.
            clear()
            null
        }
    }

    override fun save(session: SupabaseAuthSession) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(Json.encodeToString(session).toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        val saved = prefs.edit()
            .putString(KEY_SESSION, Base64.encodeToString(packed, Base64.NO_WRAP))
            .commit()
        check(saved) { "Không thể lưu phiên đăng nhập an toàn." }
    }

    override fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "atpro_secure_auth"
        const val KEY_SESSION = "encrypted_session"
        const val KEY_ALIAS = "atpro_supabase_auth_session"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}

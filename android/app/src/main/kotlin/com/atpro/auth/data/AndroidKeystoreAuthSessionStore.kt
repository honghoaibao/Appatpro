package com.atpro.auth.data

import android.content.Context
import android.util.Base64
import com.atpro.auth.domain.AuthSession
import com.atpro.auth.domain.AuthUser
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * Stores only the Supabase session, encrypted with an AES-GCM key held by Android Keystore.
 *
 * This intentionally does not use StringEncryptor: its XOR transformation is obfuscation,
 * not cryptographic protection. Call these methods from an IO coroutine.
 */
class AndroidKeystoreAuthSessionStore(context: Context) : AuthSessionStore {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    override fun read(): AuthSession? = synchronized(LOCK) {
        val encoded = preferences.getString(KEY_SESSION, null) ?: return@synchronized null

        try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) {
                throw IOException("Invalid encrypted session payload")
            }

            val iv = packed.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = packed.copyOfRange(IV_LENGTH_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

            decodeSession(cipher.doFinal(ciphertext))
        } catch (_: Exception) {
            // A corrupted or invalidated local session must not block app startup.
            preferences.edit().remove(KEY_SESSION).commit()
            null
        }
    }

    override fun write(session: AuthSession) = synchronized(LOCK) {
        require(session.accessToken.isNotBlank()) { "Access token must not be blank" }
        require(session.refreshToken.isNotBlank()) { "Refresh token must not be blank" }
        require(session.user.id.isNotBlank()) { "User id must not be blank" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(encodeSession(session))
        val iv = cipher.iv ?: throw IOException("Android Keystore did not return an AES-GCM IV")
        val packed = Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)

        if (!preferences.edit().putString(KEY_SESSION, packed).commit()) {
            throw IOException("Unable to persist the encrypted auth session")
        }
    }

    override fun clear() = synchronized(LOCK) {
        if (!preferences.edit().remove(KEY_SESSION).commit()) {
            throw IOException("Unable to clear the local auth session")
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encodeSession(session: AuthSession): ByteArray {
        val json = JSONObject()
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("token_type", session.tokenType)
            .put("expires_at", session.expiresAtEpochSeconds)
            .put("user_id", session.user.id)
            .put("email", session.user.email ?: JSONObject.NULL)

        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decodeSession(payload: ByteArray): AuthSession? {
        val json = JSONObject(String(payload, Charsets.UTF_8))
        val accessToken = json.optString("access_token")
        val refreshToken = json.optString("refresh_token")
        val userId = json.optString("user_id")

        if (accessToken.isBlank() || refreshToken.isBlank() || userId.isBlank()) return null

        val email = if (json.isNull("email")) null else {
            json.optString("email").takeIf { it.isNotBlank() }
        }

        return AuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = json.optString("token_type", "bearer").ifBlank { "bearer" },
            expiresAtEpochSeconds = json.optLong("expires_at", 0L),
            user = AuthUser(id = userId, email = email),
        )
    }

    private companion object {
        const val PREFERENCES_FILE = "atpro_auth_session_v1"
        const val KEY_SESSION = "encrypted_session"
        const val KEY_ALIAS = "com.atpro.auth.session.v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
        val LOCK = Any()
    }
}

package com.atpro.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Kết quả lưu cookie. */
sealed interface CookieSaveResult {
    data class Saved(val accountId: String, val replaced: Boolean) : CookieSaveResult
    data class Incomplete(val missing: List<String>, val hasAccountId: Boolean) : CookieSaveResult
    data object Invalid : CookieSaveResult
    data object Full : CookieSaveResult
    /** Keystore/ghi đĩa lỗi — cookie CHƯA được lưu. */
    data object StorageError : CookieSaveResult
}

/**
 * CookieVault (v1.12.7) — kho cookie đăng nhập Facebook/Instagram, mã hoá AES-256-GCM bằng khoá trong Android Keystore.
 *
 * Ràng buộc (xem `.claude/context/constraints.md`):
 *  • KHÔNG dùng `StringEncryptor` (XOR chỉ là che mắt, không phải mã hoá);
 *  • KHÔNG nằm trong `BackupConfigPolicy` / snapshot — cookie không bao giờ rời máy; `allowBackup=false` trong manifest;
 *  • không log nội dung; giao diện chỉ nhận [CookieSummary] (không có giá trị cookie);
 *  • kho hỏng/khoá Keystore mất hiệu lực → xoá sạch kho và trả rỗng (người dùng dán lại cookie), không chặn mở màn hình.
 *
 * Khung cho tính năng "nuôi bằng cookie" — chưa có bộ chạy thật; [get] dành cho bộ chạy đó.
 * Gọi từ coroutine IO (đọc/ghi SharedPreferences + Keystore).
 *
 * Không dùng chung với `AndroidKeystoreAuthSessionStore` (đã audit) — alias khoá và tệp riêng; có thể gộp phần mã hoá
 * dùng chung ở một thay đổi riêng sau khi cả hai được kiểm chứng trên thiết bị.
 */
class CookieVault private constructor(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    fun list(platform: CookiePlatform): List<CookieSummary> = synchronized(LOCK) {
        CookieVaultCodec.summaries(readAll(), platform)
    }

    /** Cookie đầy đủ — CHỈ cho bộ chạy nuôi acc; không đưa lên giao diện, không log. */
    fun get(platform: CookiePlatform, accountId: String): String? = synchronized(LOCK) {
        readAll().firstOrNull { it.platform == platform.name && it.accountId == accountId }?.cookie
    }

    fun save(platform: CookiePlatform, raw: String, nowMs: Long = System.currentTimeMillis()): CookieSaveResult =
        synchronized(LOCK) {
            when (val prepared = CookieVaultCodec.prepare(raw, platform, nowMs)) {
                CookiePrepareResult.Invalid -> CookieSaveResult.Invalid
                is CookiePrepareResult.Incomplete -> CookieSaveResult.Incomplete(prepared.missing, prepared.hasAccountId)
                is CookiePrepareResult.Ok -> {
                    val current = readAll()
                    val updated = CookieVaultCodec.upsert(current, prepared.entry) ?: return@synchronized CookieSaveResult.Full
                    val replaced = current.any { it.platform == prepared.entry.platform && it.accountId == prepared.entry.accountId }
                    if (writeAll(updated)) CookieSaveResult.Saved(prepared.entry.accountId, replaced) else CookieSaveResult.StorageError
                }
            }
        }

    /** @return true nếu đã ghi xong (kể cả khi không có mục nào để xoá). */
    fun remove(platform: CookiePlatform, accountId: String): Boolean = synchronized(LOCK) {
        val current = readAll()
        val updated = CookieVaultCodec.remove(current, platform, accountId)
        updated.size == current.size || writeAll(updated)
    }

    // ── Mã hoá ───────────────────────────────────────────────────────────────

    private fun readAll(): List<StoredCookie> {
        val encoded = preferences.getString(KEY_BLOB, null) ?: return emptyList()
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.size <= IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES) throw IOException("payload")
            val iv = packed.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = packed.copyOfRange(IV_LENGTH_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            CookieVaultCodec.decode(cipher.doFinal(ciphertext))
        } catch (_: KeyPermanentlyInvalidatedException) {
            discardCorruptedVault(alsoDeleteKey = true); emptyList()
        } catch (_: Exception) {
            // Không đưa message của ngoại lệ vào log: có thể chứa một phần dữ liệu.
            discardCorruptedVault(alsoDeleteKey = false); emptyList()
        }
    }

    private fun writeAll(items: List<StoredCookie>): Boolean = try {
        val cipher = try {
            newEncryptionCipher()
        } catch (_: KeyPermanentlyInvalidatedException) {
            deleteKey(); newEncryptionCipher()
        }
        val ciphertext = cipher.doFinal(CookieVaultCodec.encode(items))
        val iv = cipher.iv ?: throw IOException("iv")
        preferences.edit().putString(KEY_BLOB, Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)).commit()
    } catch (_: Exception) {
        false
    }

    private fun discardCorruptedVault(alsoDeleteKey: Boolean) {
        preferences.edit().remove(KEY_BLOB).commit()
        if (alsoDeleteKey) deleteKey()
    }

    private fun newEncryptionCipher(): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }

    private fun deleteKey() {
        runCatching { KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFERENCES_FILE = "atpro_cookie_vault_v1"
        private const val KEY_BLOB = "encrypted_cookies"
        private const val KEY_ALIAS = "com.atpro.cookies.v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
        private val LOCK = Any()

        @Volatile private var instance: CookieVault? = null
        fun getInstance(context: Context): CookieVault =
            instance ?: synchronized(LOCK) { instance ?: CookieVault(context).also { instance = it } }
    }
}

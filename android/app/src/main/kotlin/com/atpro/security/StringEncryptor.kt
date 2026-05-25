package com.atpro.security

object StringEncryptor {
    private val KEY = byteArrayOf(0x4B, 0x72, 0x39, 0x8A.toByte(), 0xF1.toByte(),
        0x2C, 0x77, 0xE4.toByte(), 0x5D, 0x91.toByte(), 0xBC.toByte(), 0x43)

    fun decrypt(encoded: IntArray): String {
        val bytes = ByteArray(encoded.size) { i ->
            (encoded[i] xor KEY[i % KEY.size].toInt()).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    // "com.ss.android.ugc.trill" — verified correct
    val TIKTOK_PKG: String by lazy {
        decrypt(intArrayOf(0x28,0x1d,0x54,0xa4,0x82,0x5f,0x59,0x85,0x33,0xf5,0xce,
            0x2c,0x22,0x16,0x17,0xff,0x96,0x4f,0x59,0x90,0x2f,0xf8,0xd0,0x2f))
    }

    // "com.zhiliaoapp.musically" — verified correct
    val TIKTOK_PKG_ALT: String by lazy {
        decrypt(intArrayOf(0x28,0x1d,0x54,0xa4,0x8b,0x44,0x1e,0x88,0x34,0xf0,0xd3,
            0x22,0x3b,0x02,0x17,0xe7,0x84,0x5f,0x1e,0x87,0x3c,0xfd,0xd0,0x3a))
    }

    fun encrypt(plain: String): IntArray {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        return IntArray(bytes.size) { i -> (bytes[i].toInt() and 0xFF) xor (KEY[i % KEY.size].toInt() and 0xFF) }
    }
}

object AppConstants {
    val tiktokPackage: String get() = StringEncryptor.TIKTOK_PKG
    val tiktokPackageAlt: String get() = StringEncryptor.TIKTOK_PKG_ALT
    val tiktokPackages: List<String> get() = listOf(
        tiktokPackage, tiktokPackageAlt, "com.ss.android.ugc.aweme"
    )
    const val WS_PORT = 8765
    const val DB_VERSION = 1
}

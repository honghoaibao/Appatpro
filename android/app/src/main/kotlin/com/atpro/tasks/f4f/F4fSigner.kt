package com.atpro.tasks.f4f

import com.atpro.security.StringEncryptor
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Ký request Follow4Follow — header `x-ts` + `x-sign`, gắn vào MỌI request (xem
 * `.claude/context/follow4follow/API_reference_v2.txt` mục 2).
 *
 *   x-ts   = Unix timestamp (giây)
 *   x-sign = hex(HMAC-SHA256(key = "follow4follow_key", msg = "{ts}:{METHOD}:{path không query}"))
 *
 * Chữ ký KHÔNG gồm body và KHÔNG gồm query. Server từ chối nếu x-ts lệch giờ thật quá ~60 giây.
 * Khoá ký là hằng số công khai trong bundle JS của website (không phải bí mật của người dùng).
 */
object F4fSigner {

    data class Signed(val ts: Long, val sign: String)

    private val key: ByteArray by lazy { StringEncryptor.F4F_SIGN_KEY.toByteArray(Charsets.UTF_8) }

    fun sign(method: String, path: String, ts: Long): Signed {
        val msg = "$ts:${method.uppercase()}:${path.substringBefore('?')}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val digest = mac.doFinal(msg.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) hex.append("%02x".format(b.toInt() and 0xFF))
        return Signed(ts, hex.toString())
    }
}

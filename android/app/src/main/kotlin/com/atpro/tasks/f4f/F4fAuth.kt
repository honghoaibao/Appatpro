package com.atpro.tasks.f4f

import android.content.Context
import com.atpro.security.StringEncryptor
import com.atpro.tasks.TaskResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Trạng thái đăng nhập Follow4Follow cho UI. */
sealed interface F4fAuthState {
    object LoggedOut : F4fAuthState

    data class LoggedIn(
        val username: String,
        /** Số dư đã định dạng, vd "1.250 coin"; null nếu chưa biết. */
        val balance: String? = null,
        val balancePending: String? = null,
    ) : F4fAuthState
}

/** Nơi lưu phiên đăng nhập. Chỉ lưu TOKEN (+ tên hiển thị, số dư) — KHÔNG bao giờ lưu mật khẩu. */
interface F4fSessionStorage {
    var token: String?
    var username: String?
    var balance: String?
    var balancePending: String?
    fun clear()
}

/** SharedPreferences riêng của app (MODE_PRIVATE) — cùng mức bảo vệ với database Room của app. */
class SharedPrefsSessionStorage(context: Context) : F4fSessionStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Token đi qua StringEncryptor (ràng buộc dự án: dữ liệu nhạy cảm không lưu dạng chữ thường).
    override var token: String?
        get() = F4fTokenCodec.decode(prefs.getString(K_TOKEN, null))
        set(v) = prefs.edit().putString(K_TOKEN, v?.let(F4fTokenCodec::encode)).apply()
    override var username: String?
        get() = prefs.getString(K_USER, null)
        set(v) = prefs.edit().putString(K_USER, v).apply()
    override var balance: String?
        get() = prefs.getString(K_BAL, null)
        set(v) = prefs.edit().putString(K_BAL, v).apply()
    override var balancePending: String?
        get() = prefs.getString(K_PEND, null)
        set(v) = prefs.edit().putString(K_PEND, v).apply()

    override fun clear() { prefs.edit().clear().apply() }

    private companion object {
        const val PREFS  = "f4f_session"
        const val K_TOKEN = "access_token"
        const val K_USER  = "username"
        const val K_BAL   = "balance"
        const val K_PEND  = "balance_pending"
    }
}

/**
 * Mã hoá/giải mã token khi lưu xuống đĩa bằng [StringEncryptor] (che chuỗi, KHÔNG phải mã hoá mạnh —
 * mục đích là không để token ở dạng chữ thường trong file prefs, theo `constraints.md`).
 * Định dạng lưu: các số nguyên cách nhau dấu phẩy.
 */
object F4fTokenCodec {
    fun encode(token: String): String = StringEncryptor.encrypt(token).joinToString(",")

    /** Dữ liệu hỏng/không đúng định dạng → null (coi như chưa đăng nhập) thay vì ném exception. */
    fun decode(stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        return try {
            StringEncryptor.decrypt(stored.split(",").map { it.trim().toInt() }.toIntArray())
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Đăng nhập / đăng xuất / làm mới hồ sơ Follow4Follow và phát [state] cho UI.
 *
 * Login: `POST /user/auth/login {username, password}` (KHÔNG có recaptcha) → `data.access_token`.
 * Token hết hạn: provider gọi [sessionExpired] khi server trả 401 → về [F4fAuthState.LoggedOut].
 */
class F4fAuthManager(
    private val storage: F4fSessionStorage,
    private val api: F4fApiClient,
) {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<F4fAuthState> = _state.asStateFlow()

    val isLoggedIn: Boolean get() = _state.value is F4fAuthState.LoggedIn

    suspend fun login(username: String, password: String): TaskResult<Unit> {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            return TaskResult.Error("Nhập tên đăng nhập và mật khẩu")
        }
        // Gửi KHÔNG kèm Bearer cũ (token hết hạn có thể làm server từ chối cả request login).
        val r = api.post(
            PATH_LOGIN,
            buildJsonObject { put("username", user); put("password", password) },
            authenticated = false,
        )
        if (r.isError) return TaskResult.Error(r.describe())

        val data = r.dataObj
        val profile = data?.get("user").asObj()
        val token = data?.get("access_token").asStr() ?: profile?.get("access_token").asStr()
        if (token.isNullOrBlank()) {
            return TaskResult.Error(r.message.ifBlank { "Đăng nhập không trả về access_token" })
        }
        storage.token = token
        applyProfile(profile, fallbackName = user)
        return TaskResult.Success(Unit)
    }

    /** Kiểm tra token còn hiệu lực + cập nhật số dư (`GET /user/auth/me`). 401 → tự đăng xuất cục bộ. */
    suspend fun refresh(): TaskResult<Unit> {
        if (!api.hasToken) return TaskResult.Error("Chưa đăng nhập Follow4Follow", isAuthError = true)
        val r = api.get(PATH_ME)
        if (r.isError) {
            if (r.isUnauthorized) {
                sessionExpired()
                return TaskResult.Error("Phiên đăng nhập đã hết hạn — đăng nhập lại", isAuthError = true)
            }
            return TaskResult.Error(r.describe())
        }
        applyProfile(r.dataObj, fallbackName = storage.username.orEmpty())
        return TaskResult.Success(Unit)
    }

    /** Đăng xuất: báo server (best-effort, bỏ qua lỗi) rồi xoá token cục bộ. */
    suspend fun logout() {
        if (api.hasToken) api.post(PATH_LOGOUT)
        sessionExpired()
    }

    /** Xoá phiên cục bộ (đăng xuất hoặc token bị server từ chối). */
    fun sessionExpired() {
        storage.clear()
        _state.value = F4fAuthState.LoggedOut
    }

    /** Provider báo số dư mới sau mỗi job hoàn thành (chuỗi thô từ server). */
    fun updateBalance(balance: String?, pending: String?) {
        val current = _state.value as? F4fAuthState.LoggedIn ?: return
        val b = formatCoin(balance) ?: current.balance
        val p = formatCoin(pending) ?: current.balancePending
        storage.balance = b
        storage.balancePending = p
        _state.value = current.copy(balance = b, balancePending = p)
    }

    private fun applyProfile(profile: JsonObject?, fallbackName: String) {
        val name = profile?.get("username").asStr()?.takeIf { it.isNotBlank() } ?: fallbackName
        val bal  = formatCoin(profile?.get("balance").asStr())
        val pend = formatCoin(profile?.get("balance_pending").asStr())
        storage.username = name
        storage.balance = bal
        storage.balancePending = pend
        _state.value = F4fAuthState.LoggedIn(name, bal, pend)
    }

    private fun initialState(): F4fAuthState =
        if (storage.token.isNullOrBlank()) F4fAuthState.LoggedOut
        else F4fAuthState.LoggedIn(storage.username.orEmpty(), storage.balance, storage.balancePending)

    internal companion object {
        const val PATH_LOGIN  = "/user/auth/login"
        const val PATH_LOGOUT = "/user/auth/logout"
        const val PATH_ME     = "/user/auth/me"

        /** "1250" → "1250 coin"; "0 coin" giữ nguyên; rỗng/null → null. */
        fun formatCoin(raw: String?): String? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null
            return if (s.toDoubleOrNull() != null) "$s coin" else s
        }
    }
}

/** Điểm truy cập singleton: UI và `TikTokAccessibilityService` dùng chung 1 phiên đăng nhập. */
object F4fAuth {
    @Volatile private var manager: F4fAuthManager? = null
    @Volatile private var provider: Follow4FollowProvider? = null
    @Volatile private var service: F4fService? = null

    fun manager(context: Context): F4fAuthManager =
        manager ?: synchronized(this) { manager ?: build(context.applicationContext) }

    fun provider(context: Context): Follow4FollowProvider {
        manager(context)
        return provider!!
    }

    /** v1.7.0: các tính năng ví (tổng quan, xếp hạng, rút tiền, lịch sử, giới thiệu, thông báo). */
    fun service(context: Context): F4fService {
        manager(context)
        return service!!
    }

    private fun build(appContext: Context): F4fAuthManager {
        val storage = SharedPrefsSessionStorage(appContext)
        val api = F4fApiClient(UrlConnectionTransport(), tokenProvider = { storage.token })
        val mgr = F4fAuthManager(storage, api)
        // Gán provider/service TRƯỚC manager: manager != null ⇒ cả hai != null (xem provider()/service()).
        provider = Follow4FollowProvider(
            api = api,
            onBalance = mgr::updateBalance,
            onAuthExpired = mgr::sessionExpired,
        )
        service = F4fService(api, onAuthExpired = mgr::sessionExpired)
        manager = mgr
        return mgr
    }
}

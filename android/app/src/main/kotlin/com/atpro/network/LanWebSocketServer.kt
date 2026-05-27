package com.atpro.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * LanWebSocketServer — WebSocket server nội bộ cho multi-device control.
 *
 * Lifecycle:
 *   FarmForegroundService.onCreate()  → start(context)
 *   FarmForegroundService.onDestroy() → stop()
 *
 * Network reconnect (TASK-005):
 *   ConnectivityManager.NetworkCallback lắng nghe thay đổi Wi-Fi/Ethernet.
 *   Khi network thay đổi (IP mới), server tự restart trong vòng ~3 giây.
 *   Tránh tình trạng server bind sai IP cũ sau khi đổi mạng.
 *
 * Port: [PORT] = 8765 (cố định, sync với Flutter ws_monitor_screen.dart)
 * Endpoint: ws://<device-ip>:8765/ws
 * Health check: http://<device-ip>:8765/health
 */
object LanWebSocketServer {

    const val TAG  = "LanWsServer"
    const val PORT = 8765

    private var server:  AtProWsdServer? = null
    private val clients = ConcurrentHashMap<String, NanoWSD.WebSocket>()

    // Scope cho coroutine reconnect — tách khỏi FarmForegroundService scope
    // để reconnect logic không bị cancel khi farm tạm dừng.
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null

    // Network callback — giữ reference để unregister đúng lúc.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null

    // ── Public API ────────────────────────────────────────────

    /**
     * Khởi động server và đăng ký network callback.
     * Phải gọi với [context] để có thể dùng ConnectivityManager.
     * Idempotent: gọi nhiều lần chỉ start một lần.
     */
    fun start(context: Context) {
        startServer()
        registerNetworkCallback(context.applicationContext)
    }

    /**
     * Dừng server, unregister network callback, cancel tất cả jobs.
     * Gọi trong FarmForegroundService.onDestroy().
     */
    fun stop() {
        unregisterNetworkCallback()
        reconnectJob?.cancel()
        stopServer()
    }

    fun broadcast(type: String, data: Map<String, Any?>) {
        if (clients.isEmpty()) return
        val json = buildJsonObject {
            put("type", type)
            data.forEach { (k, v) -> putAny(k, v) }
        }.toString()
        clients.values.forEach { runCatching { it.send(json) } }
    }

    fun getLocalIp(): String = try {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.filter { it.isUp && !it.isLoopback && !it.isVirtual &&
                (it.name.startsWith("wlan") || it.name.startsWith("eth") || it.name.startsWith("en")) }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.filterNot { it.isLoopbackAddress }
            ?.firstOrNull()?.hostAddress ?: "127.0.0.1"
    } catch (_: Exception) { "127.0.0.1" }

    fun getConnectedCount(): Int = clients.size

    // ── Network reconnect (TASK-005) ──────────────────────────

    /**
     * Đăng ký lắng nghe thay đổi mạng Wi-Fi/Ethernet.
     *
     * Khi network thay đổi (onAvailable / onLost), schedule restart server
     * với delay 2 giây để IP mới ổn định trước khi bind port.
     *
     * Dùng debounce (reconnectJob cancel + relaunch) để tránh restart liên tục
     * khi network toggle nhanh (thường xảy ra khi roaming giữa 2 AP).
     */
    private fun registerNetworkCallback(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {

            /**
             * Gọi khi network mới available (bao gồm khi Wi-Fi reconnect).
             * Schedule restart để bind đúng IP mới.
             */
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available — scheduling server restart")
                scheduleRestart(delayMs = 2_000)
            }

            /**
             * Gọi khi network bị mất (Wi-Fi disconnect, airplane mode...).
             * Stop server ngay lập tức, clients sẽ tự timeout.
             */
            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost — stopping server")
                reconnectJob?.cancel()
                stopServer()
            }
        }

        networkCallback = callback
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { Log.e(TAG, "registerNetworkCallback failed: ${it.message}") }
    }

    private fun unregisterNetworkCallback() {
        val cm       = connectivityManager ?: return
        val callback = networkCallback    ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
        networkCallback      = null
        connectivityManager  = null
    }

    /**
     * Debounced restart: cancel job cũ → launch job mới với [delayMs].
     * Đảm bảo chỉ có một lần restart ngay cả khi nhận nhiều network events.
     */
    private fun scheduleRestart(delayMs: Long) {
        reconnectJob?.cancel()
        reconnectJob = serverScope.launch {
            delay(delayMs)
            Log.i(TAG, "Restarting WS server after network change")
            stopServer()
            delay(500) // Cho NanoHTTPD thực sự release port
            startServer()
        }
    }

    // ── Server lifecycle ──────────────────────────────────────

    private fun startServer() {
        if (server?.isAlive == true) return
        try {
            server = AtProWsdServer(PORT)
            server!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            val ip = getLocalIp()
            Log.i(TAG, "WS started: ws://$ip:$PORT/ws")
            broadcast("wsServer", mapOf(
                "status" to "started",
                "ip"     to ip,
                "port"   to PORT,
                "url"    to "ws://$ip:$PORT/ws",
            ))
        } catch (e: Exception) {
            Log.e(TAG, "WS start failed: ${e.message}")
            broadcast("wsServer", mapOf(
                "status" to "error",
                "reason" to (e.message ?: "unknown"),
            ))
        }
    }

    private fun stopServer() {
        clients.values.forEach {
            runCatching {
                it.close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "shutdown", false)
            }
        }
        clients.clear()
        server?.stop()
        server = null
        broadcast("wsServer", mapOf("status" to "stopped"))
    }

    // ── Client callbacks (called by AtProWebSocket) ───────────

    internal fun onClientOpen(id: String, ws: NanoWSD.WebSocket) {
        clients[id] = ws
        val welcome = buildJsonObject {
            put("type", "welcome")
            put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            put("ip", getLocalIp())
        }.toString()
        runCatching { ws.send(welcome) }
    }

    internal fun onClientClose(id: String) { clients.remove(id) }

    internal fun onClientMessage(id: String, text: String) {
        try {
            val json    = Json.parseToJsonElement(text).jsonObject
            val msgType = json["type"]?.jsonPrimitive?.content ?: return
            when (msgType) {
                "ping" ->
                    clients[id]?.let { runCatching { it.send("""{"type":"pong"}""") } }
                "startFarm", "stopFarm", "pauseFarm" ->
                    broadcast("remoteCommand", mapOf("command" to msgType))
            }
        } catch (_: Exception) {}
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun JsonObjectBuilder.putAny(key: String, value: Any?) = when (value) {
        is String  -> put(key, value)
        is Int     -> put(key, value)
        is Long    -> put(key, value)
        is Boolean -> put(key, value)
        is Float   -> put(key, value.toDouble())
        is Double  -> put(key, value)
        null       -> put(key, JsonNull)
        else       -> put(key, value.toString())
    }
}

// ── NanoWSD server ────────────────────────────────────────────

private class AtProWsdServer(port: Int) : NanoWSD(port) {
    override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket =
        AtProWebSocket(handshake, "${handshake.remoteIpAddress}:${System.currentTimeMillis()}")

    /** /health endpoint — dùng để kiểm tra server còn sống từ Flutter/debug. */
    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response =
        if (session.uri == "/health")
            newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "application/json",
                """{"status":"ok","clients":${LanWebSocketServer.getConnectedCount()}}"""
            )
        else
            super.serve(session)
}

private class AtProWebSocket(
    handshake: NanoHTTPD.IHTTPSession,
    private val id: String,
) : NanoWSD.WebSocket(handshake) {
    override fun onOpen() = LanWebSocketServer.onClientOpen(id, this)
    override fun onClose(
        code:              NanoWSD.WebSocketFrame.CloseCode?,
        reason:            String?,
        initiatedByRemote: Boolean,
    ) = LanWebSocketServer.onClientClose(id)
    override fun onMessage(message: NanoWSD.WebSocketFrame) =
        message.textPayload?.let { LanWebSocketServer.onClientMessage(id, it) } ?: Unit
    override fun onPong(pong: NanoWSD.WebSocketFrame) = Unit
    override fun onException(exception: IOException) = LanWebSocketServer.onClientClose(id)
}

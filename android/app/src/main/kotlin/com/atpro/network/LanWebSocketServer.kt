package com.atpro.network

import android.util.Log
import com.atpro.bridge.FlutterBridge
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

object LanWebSocketServer {

    const val TAG  = "LanWsServer"
    const val PORT = 8765

    private var server: AtProWsdServer? = null
    private val clients = ConcurrentHashMap<String, NanoWSD.WebSocket>()

    fun start() {
        if (server?.isAlive == true) return
        try {
            server = AtProWsdServer(PORT)
            server!!.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            val ip = getLocalIp()
            Log.i(TAG, "WS started: ws://$ip:$PORT/ws")
            FlutterBridge.sendEvent("wsServer", mapOf(
                "status" to "started", "ip" to ip,
                "port" to PORT, "url" to "ws://$ip:$PORT/ws",
            ))
        } catch (e: Exception) {
            Log.e(TAG, "WS start failed: ${e.message}")
        }
    }

    fun stop() {
        clients.values.forEach {
            runCatching {
                it.close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "shutdown", false)
            }
        }
        clients.clear()
        server?.stop()
        server = null
        FlutterBridge.sendEvent("wsServer", mapOf("status" to "stopped"))
    }

    fun broadcast(type: String, data: Map<String, Any?>) {
        if (clients.isEmpty()) return
        val json = buildJsonObject {
            put("type", type)
            data.forEach { (k, v) -> putAny(k, v) }
        }.toString()
        clients.values.forEach { runCatching { it.send(json) } }
    }

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
                "ping" -> clients[id]?.let { runCatching { it.send("""{"type":"pong"}""") } }
                "startFarm", "stopFarm", "pauseFarm" ->
                    FlutterBridge.sendEvent("remoteCommand", mapOf("command" to msgType))
            }
        } catch (_: Exception) {}
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

// NanoWSD uses NanoHTTPD.IHTTPSession — fully qualified to avoid ambiguity
private class AtProWsdServer(port: Int) : NanoWSD(port) {
    override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket =
        AtProWebSocket(handshake, "${handshake.remoteIpAddress}:${System.currentTimeMillis()}")

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response =
        if (session.uri == "/health")
            newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json",
                """{"status":"ok","clients":${LanWebSocketServer.getConnectedCount()}}""")
        else
            super.serve(session)
}

private class AtProWebSocket(
    handshake: NanoHTTPD.IHTTPSession,
    private val id: String,
) : NanoWSD.WebSocket(handshake) {
    override fun onOpen() = LanWebSocketServer.onClientOpen(id, this)
    override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) =
        LanWebSocketServer.onClientClose(id)
    override fun onMessage(message: NanoWSD.WebSocketFrame) =
        message.textPayload?.let { LanWebSocketServer.onClientMessage(id, it) } ?: Unit
    override fun onPong(pong: NanoWSD.WebSocketFrame) = Unit
    override fun onException(exception: IOException) = LanWebSocketServer.onClientClose(id)
}

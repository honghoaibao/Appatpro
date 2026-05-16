package com.atpro.bridge

import android.util.Log
import com.atpro.data.LocalRepository
import androidx.work.WorkManager
import com.atpro.scheduler.ScheduledFarmManager
import com.atpro.scheduler.ScheduledFarmManager.FarmSchedule
import com.atpro.data.AccessibilitySettingsHelper
import com.atpro.db.AtProDatabase
import com.atpro.network.LanWebSocketServer
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*

object FlutterBridge {
    const val TAG            = "FlutterBridge"
    private lateinit var appContext: android.content.Context
    const val METHOD_CHANNEL = "com.atpro/control"
    const val EVENT_CHANNEL  = "com.atpro/events"

    private var eventSink: EventChannel.EventSink? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun setup(flutterEngine: FlutterEngine) {
        val ctx = com.atpro.AtProApplication.ctx.also { appContext = it }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                val repo = runCatching { LocalRepository.getInstance(ctx) }.getOrNull()
                val db   = runCatching { AtProDatabase.getInstance(ctx) }.getOrNull()

                when (call.method) {
                    // Farm control
                    "startFarm" -> {
                        val accounts = call.argument<List<String>>("accounts") ?: emptyList()
                        val svc = com.atpro.accessibility.TikTokAccessibilityService.instance
                        if (svc == null) { result.error("NO_SERVICE", "Accessibility Service chưa bật", null); return@setMethodCallHandler }
                        svc.engine.startFarm(accounts)
                        result.success(mapOf("status" to "started"))
                    }
                    "stopFarm"         -> { com.atpro.accessibility.TikTokAccessibilityService.instance?.engine?.stop();   main { result.success(null) } }
                    "pauseFarm"        -> { com.atpro.accessibility.TikTokAccessibilityService.instance?.engine?.pause();  main { result.success(null) } }
                    "resumeFarm"       -> { com.atpro.accessibility.TikTokAccessibilityService.instance?.engine?.resume(); main { result.success(null) } }
                    "getServiceStatus" -> result.success(mapOf(
                        "connected" to com.atpro.accessibility.TikTokAccessibilityService.isRunning,
                        "farming"   to (com.atpro.accessibility.TikTokAccessibilityService.instance?.engine?.isFarming ?: false),
                    ))
                    // Account list from node tree (for switch popup)
                    "getAccountList" -> {
                        val svc = com.atpro.accessibility.TikTokAccessibilityService.instance
                        val root = svc?.getRootNode()
                        result.success(com.atpro.accessibility.NodeTraverser.parseAccountList(root))
                    }
                    // DB: accounts
                    "getAccountsFromDb" -> scope.launch {
                        val list = repo?.getAccounts()?.map { a -> mapOf(
                            "username" to a.username, "status" to a.status,
                            "checkpoint" to a.checkpoint, "sessionsCount" to a.sessionsCount,
                            "totalLikes" to a.totalLikes, "totalFollows" to a.totalFollows, "totalVideos" to a.totalVideos,
                        )} ?: emptyList()
                        main { result.success(list) }
                    }
                    "addAccount" -> scope.launch {
                        repo?.addAccount(call.argument<String>("username") ?: "")
                        main { result.success(null) }
                    }
                    "deleteAccount" -> scope.launch {
                        repo?.deleteAccount(call.argument<String>("username") ?: "")
                        main { result.success(null) }
                    }
                    "setCheckpoint" -> scope.launch {
                        repo?.setCheckpoint(
                            call.argument<String>("username") ?: "",
                            call.argument<Boolean>("checkpoint") ?: false,
                        )
                        main { result.success(null) }
                    }
                    // DB: sessions
                    "getRecentSessions" -> scope.launch {
                        val limit = call.argument<Int>("limit") ?: 50
                        val list = db?.sessionDao()?.getRecent(limit)?.map { s -> mapOf(
                            "id" to s.id, "account_id" to s.accountId, "started_at" to s.startedAt,
                            "likes" to s.likes, "follows" to s.follows,
                            "videos_watched" to s.videosWatched, "duration_secs" to s.durationSecs,
                        )} ?: emptyList()
                        main { result.success(list) }
                    }
                    "getDailyStats" -> scope.launch {
                        val days = call.argument<Int>("days") ?: 30
                        val list = repo?.getDailyStats(days)?.map { r -> mapOf(
                            "date" to r.date, "account_id" to r.accountId,
                            "likes" to r.likes, "follows" to r.follows,
                            "videos_watched" to r.videosWatched, "session_count" to r.sessionCount,
                        )} ?: emptyList()
                        main { result.success(list) }
                    }
                    "getTotals" -> scope.launch {
                        val days = call.argument<Int>("days") ?: 30
                        val t = repo?.getTotals(days)
                        main { result.success(mapOf(
                            "likes" to (t?.likes ?: 0), "follows" to (t?.follows ?: 0),
                            "videos" to (t?.videosWatched ?: 0), "sessions" to (t?.sessionCount ?: 0),
                        ))}
                    }
                    // Config
                    "saveConfig" -> scope.launch {
                        repo?.setConfig(call.argument<String>("key") ?: "", call.argument<String>("value") ?: "")
                        main { result.success(null) }
                    }
                    "loadConfig" -> scope.launch {
                        val v = repo?.getConfig(call.argument<String>("key") ?: "")
                        main { result.success(v) }
                    }
                    // Export
                    "exportSessionsCsv" -> scope.launch {
                        val csv = repo?.exportSessionsCsv().orEmpty()
                        main { result.success(csv) }
                    }
                    "exportAccountsCsv" -> scope.launch {
                        val csv = repo?.exportAccountsCsv().orEmpty()
                        main { result.success(csv) }
                    }
                    // WS
                    "getWsServerInfo" -> result.success(mapOf(
                        "ip" to LanWebSocketServer.getLocalIp(),
                        "port" to LanWebSocketServer.PORT,
                        "clients" to LanWebSocketServer.getConnectedCount(),
                        "url" to "ws://${LanWebSocketServer.getLocalIp()}:${LanWebSocketServer.PORT}/ws",
                    ))
                    // Permissions
                    "getPermissionStatus" -> {
                        val status = AccessibilitySettingsHelper.getPermissionStatus(ctx)
                        result.success(status)
                    }
                    "openAccessibilitySettings" -> {
                        AccessibilitySettingsHelper.openAccessibilitySettings(ctx)
                        result.success(null)
                    }
                    "openOverlaySettings" -> {
                        AccessibilitySettingsHelper.openOverlaySettings(ctx)
                        result.success(null)
                    }
                    "openSettings" -> {
                        AccessibilitySettingsHelper.openAppSettings(ctx)
                        result.success(null)
                    }
                                        // Schedule
                    "setSchedule" -> {
                        val id      = call.argument<String>("id")     ?: ""
                        val label   = call.argument<String>("label")  ?: "Farm"
                        val hour    = call.argument<Int>("hour")      ?: 8
                        val minute  = call.argument<Int>("minute")    ?: 0
                        val days    = call.argument<List<Int>>("days") ?: listOf(1,2,3,4,5,6,7)
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        val accounts = call.argument<List<String>>("accounts") ?: emptyList()
                        val schedule = FarmSchedule(id=id, label=label, hourOfDay=hour,
                            minute=minute, daysOfWeek=days, enabled=enabled, accounts=accounts)
                        ScheduledFarmManager.setSchedule(ctx, schedule)
                        result.success(null)
                    }
                    "cancelSchedule" -> {
                        val id = call.argument<String>("id") ?: ""
                        ScheduledFarmManager.cancelSchedule(ctx, id)
                        result.success(null)
                    }
                    // Fix 2: Load saved schedules từ Room DB
                    "getSchedules" -> scope.launch {
                        val list = ScheduledFarmManager.getSchedules()
                            .map { s -> mapOf(
                                "id"        to s.id,
                                "label"     to s.label,
                                "hour"      to s.hourOfDay,
                                "minute"    to s.minute,
                                "days"      to s.daysOfWeek,
                                "enabled"   to s.enabled,
                                "accounts"  to s.accounts,
                            )}
                        main { result.success(list) }
                    }
                                        else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(a: Any?, e: EventChannel.EventSink?) { eventSink = e }
                override fun onCancel(a: Any?) { eventSink = null }
            })
    }

    fun sendEvent(type: String, data: Map<String, Any?>) {
        val payload = data.toMutableMap().also { it["type"] = type }
        main { eventSink?.success(payload) }
        LanWebSocketServer.broadcast(type, payload)
    }

    private fun main(block: () -> Unit) =
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
}

# ═══════════════════════════════════════════════════════════════
#  AT PRO — ProGuard / R8 Rules
# ═══════════════════════════════════════════════════════════════

# ── Kotlin ────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }

# ── Room Database ─────────────────────────────────────────────
# Keep entity fields (Room uses reflection for column mapping)
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}

# ── Kotlinx Serialization ─────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.atpro.**$$serializer { *; }
-keepclassmembers class com.atpro.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ── OkHttp ────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── NanoHTTPD WebSocket ───────────────────────────────────────
-keep class fi.iki.elonen.** { *; }
-keep class org.nanohttpd.** { *; }

# ── WorkManager ───────────────────────────────────────────────
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}

# ── DataStore ─────────────────────────────────────────────────
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# ── AccessibilityService ──────────────────────────────────────
-keep class com.atpro.accessibility.** { *; }

# ── BroadcastReceiver / Service (referenced in Manifest) ──────
-keep class com.atpro.data.BootReceiver { *; }
-keep class com.atpro.data.FarmForegroundService { *; }
-keep class com.atpro.scheduler.FarmAlarmReceiver { *; }
-keep class com.atpro.scheduler.ScheduledFarmManager { *; }

# ── Coroutines ────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ── Remove logging in release ─────────────────────────────────
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ── General Android ───────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

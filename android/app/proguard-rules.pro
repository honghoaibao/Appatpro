# AT PRO ProGuard/R8 — Phase 4

# Flutter
-keep class io.flutter.** { *; }
-keep class io.flutter.embedding.** { *; }
-keep class io.flutter.plugin.** { *; }

# Android framework
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.accessibilityservice.AccessibilityService
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity

# Room DB
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }
-keep class androidx.room.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Kotlinx Serialization
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }

# Kotlin Coroutines
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-keep class kotlin.coroutines.** { *; }

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ── KEEP: entry points referenced by AndroidManifest ─────────
-keep class com.atpro.MainActivity { *; }
-keep class com.atpro.AtProApplication { *; }
-keep class com.atpro.accessibility.TikTokAccessibilityService
-keep class com.atpro.data.FarmForegroundService
-keep class com.atpro.data.BootReceiver
-keep class com.atpro.scheduler.FarmAlarmReceiver
-keep class com.atpro.bridge.FlutterBridge { *; }
-keep class com.atpro.security.StringEncryptor { *; }
-keep class com.atpro.security.AppConstants { *; }

# ── OBFUSCATE: rename automation classes (TikTok cannot detect) ─
# AutomationEngine, NodeTraverser, PopupHandler, etc → NOT kept
# R8 renames them to a/b/c/... automatically

# Strip debug logs in release
-assumenosideeffects class android.util.Log {
    public static int v(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String);
}

# Optimization
-optimizationpasses 5
-dontusemixedcaseclassnames
-repackageclasses 'x'
-allowaccessmodification

# Suppress warnings
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn javax.annotation.**

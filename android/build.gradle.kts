// ═══════════════════════════════════════════════════════════════
//  AT PRO Android — Project-level build.gradle.kts
//  Kotlin DSL (thay .groovy cũ) + AGP 8.x
// ═══════════════════════════════════════════════════════════════

plugins {
    id("com.android.application")        version "8.3.2" apply false
    id("com.android.library")            version "8.3.2" apply false
    id("org.jetbrains.kotlin.android")   version "1.9.23" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.23" apply false
}

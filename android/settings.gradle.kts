pluginManagement {
    val flutterSdkPath: String = run {
        val properties = java.util.Properties()
        file("local.properties").inputStream().use { properties.load(it) }
        properties.getProperty("flutter.sdk")
            ?: error("flutter.sdk not set in local.properties")
    }

    // THIS LINE IS CRITICAL — flutter-plugin-loader reads this to find Flutter SDK
    settings.extra.set("flutterSdkPath", flutterSdkPath)

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application")           version "8.3.2"  apply false
    id("org.jetbrains.kotlin.android")      version "1.9.23" apply false
    id("org.jetbrains.kotlin.kapt")         version "1.9.23" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.23" apply false
}

include(":app")

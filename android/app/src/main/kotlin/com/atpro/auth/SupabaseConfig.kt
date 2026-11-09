package com.atpro.auth

/** Public key only. Never embed a service_role/secret key in the APK. */
object SupabaseConfig {
    const val PROJECT_URL = ""
    const val PUBLISHABLE_KEY = ""
    val isConfigured: Boolean
        get() = PROJECT_URL.startsWith("https://") &&
            PUBLISHABLE_KEY.isNotBlank() &&
            !PUBLISHABLE_KEY.contains("service_role", ignoreCase = true)
}

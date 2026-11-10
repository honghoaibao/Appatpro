package com.atpro.network.supabase

/**
 * Public client configuration for the AT App Supabase project.
 *
 * The publishable key is designed for use in client applications. It is not a
 * server secret and must only be used together with correctly configured RLS.
 * Never place a service_role key or a Supabase secret key in this Android app.
 */
data class SupabaseClientConfig(
    val projectUrl: String,
    val publishableKey: String,
    val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {
    init {
        require(projectUrl.startsWith("https://")) {
            "Supabase project URL must use HTTPS"
        }
        require(publishableKey.isNotBlank()) {
            "Supabase publishable key must not be blank"
        }
        require(connectTimeoutMs > 0 && readTimeoutMs > 0) {
            "Network timeouts must be positive"
        }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 25_000
    }
}

/** Centralized configuration so the project URL/key can be rotated in one place. */
object AtProSupabaseConfig {
    val current = SupabaseClientConfig(
        projectUrl = "https://sctzyxhwcatngwtjhwlf.supabase.co",
        publishableKey = "sb_publishable_VTSKHqH2OKwXrAK6zPphIA_nhwh6XDe",
    )
}

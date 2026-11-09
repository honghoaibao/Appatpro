package com.atpro.auth

/**
 * Public client configuration for the AT App Supabase project.
 *
 * The publishable key is intended for Android clients. Database access remains
 * protected by Auth JWTs, least-privilege grants and RLS policies.
 * Never add a service_role or secret key here.
 */
internal object SupabaseConfig {
    const val BASE_URL = "https://sctzyxhwcatngwtjhwlf.supabase.co"
    const val PUBLISHABLE_KEY = "sb_publishable_VTSKHqH2OKwXrAK6zPphIA_nhwh6XDe"
}

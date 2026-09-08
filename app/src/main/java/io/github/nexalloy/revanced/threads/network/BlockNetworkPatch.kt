package io.github.nexalloy.revanced.threads.network

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch
import io.github.nexalloy.findFieldByExactType
import java.net.URI

val BlockNetwork = patch(
    name = "Block ads and analytics",
    description = "Blocks ads and analytics network requests."
) {
    ::networkInterceptorFingerprint.hookMethod {
        before { param ->
            val requestObj = param.args[0] ?: return@before

            val uriField = try {
                requestObj.javaClass.getDeclaredField("A08").also { it.isAccessible = true }
            } catch (e: NoSuchFieldException) {
                requestObj.javaClass.findFieldByExactType<URI>() ?: return@before
            }

            val uri = uriField.get(requestObj) as? URI ?: return@before
            val path = uri.path ?: return@before
            val host = uri.host ?: ""

            if (shouldBlock(host, path)) {
                Logger.printDebug { "[BlockNetwork] Blocked: $host$path" }
                uriField.set(requestObj, URI("https", "0.0.0.0", "/0", null))
            }
        }
    }
}

private fun shouldBlock(host: String, path: String): Boolean {
    // ── Sponsored content endpoints ──────────────────────────────────────────
    if (path.contains("/profile_ads/get_profile_ads/")) return true
    if (path.contains("/async_ads/")) return true
    if (path.contains("/feed/injected_reels_media/")) return true
    if (path == "/api/v1/ads/graphql/") return true
    if (path.contains("/api/v1/async_ads/")) return true
    if (path.contains("/api/v1/ads/")) return true
    if (path.contains("/sponsored/")) return true

    // ── Ads event / reporting ────────────────────────────────────────────────
    if (path.contains("/async_ads_event")) return true
    if (path.contains("/activity_feed_sponsored_content_api")) return true
    if (path.contains("/ads_event/")) return true

    // ── Graph API hosts (ad data, targeting) ─────────────────────────────────
    if (host.contains("graph.instagram.com")) return true
    if (host.contains("graph.facebook.com")) return true

    // ── Audience Network / CDN ad assets ─────────────────────────────────────
    if (host.contains("an.facebook.com")) return true
    if (host.contains("fbcdn.net") && path.contains("/ads/")) return true

    // ── Analytics / tracking ─────────────────────────────────────────────────
    if (path.contains("/logging_client_events")) return true

    return false
}

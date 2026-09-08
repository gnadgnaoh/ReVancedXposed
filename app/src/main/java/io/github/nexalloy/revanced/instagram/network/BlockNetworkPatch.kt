package io.github.nexalloy.revanced.instagram.network

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch
import io.github.nexalloy.findFieldByExactType
import java.net.URI

val BlockNetwork = patch(
    name = "Block ads and analytics",
    description = "Blocks ads and analytics network requests for Feed, Reels, Stories, and Explore.",
) {
    ::networkInterceptorFingerprint.hookMethod {
        before { param ->
            val obj = param.args[0] ?: return@before

            val uriField = obj.javaClass.findFieldByExactType<URI>() ?: return@before
            val uri = uriField.get(obj) as? URI ?: return@before

            val path = uri.path ?: return@before
            val host = uri.host ?: ""

            if (shouldBlock(host, path)) {
                Logger.printDebug { "[IG-BlockNetwork] Blocked: $host$path" }
                uriField.set(obj, URI("https", "0.0.0.0", "/0", null))
            }
        }
    }
}

private fun shouldBlock(host: String, path: String): Boolean {
    // ── Feed ads endpoints ──
    if (path.startsWith("/api/v1/ads/")) return true
    if (path.contains("/async_ads/")) return true
    if (path.contains("/api/v1/async_ads/")) return true
    if (path.contains("/feed/injected_reels_media/")) return true
    if (path.contains("/profile_ads/get_profile_ads/")) return true

    // ── Reels/Clips ads  ──
    if (path.contains("/clips_viewer_feed_sa_multi_ads_watch_and_browse")) return true

    // ── Ads event reporting ──
    if (path.contains("/async_ads_event")) return true

    // ── Graph API hosts (ad targeting, data) ──
    if (host.contains("graph.instagram.com")) return true
    if (host.contains("graph.facebook.com")) return true

    // ── Analytics / tracking ──
    if (path.contains("/logging_client_events")) return true

    return false
}

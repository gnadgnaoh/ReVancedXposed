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
            val obj = param.args[0] ?: return@before
            
            val uriField = obj.javaClass.findFieldByExactType<URI>() ?: return@before
            val uri = uriField.get(obj) as? URI ?: return@before
            
            val path = uri.path ?: return@before
            val host = uri.host ?: ""

            val block = path.contains("profile_ads/get_profile_ads/") ||
                path.contains("/async_ads/") ||
                path.contains("/feed/injected_reels_media/") ||
                path == "/api/v1/ads/graphql/" ||
                host.contains("graph.instagram.com") ||
                host.contains("graph.facebook.com") ||
                path.contains("/logging_client_events")

            if (block) {
                Logger.printDebug { "Blocked: $host$path" }
                uriField.set(obj, URI("https", "0.0.0.0", "/0", null))
            }
        }
    }
}

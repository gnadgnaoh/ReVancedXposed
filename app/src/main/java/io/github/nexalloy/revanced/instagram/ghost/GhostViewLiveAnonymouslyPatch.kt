package io.github.nexalloy.revanced.instagram.ghost

import app.morphe.extension.shared.Logger
import io.github.nexalloy.findFieldByExactType
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.instagram.network.networkInterceptorFingerprint
import java.net.URI

val GhostViewLiveAnonymously = patch(
    name = "View live anonymously",
    description = "Prevents Instagram from knowing you viewed a live stream " +
            "by blocking the heartbeat/viewer-count endpoint.",
) {
    ::networkInterceptorFingerprint.hookMethod {
        before { param ->
            val obj = param.args[0] ?: return@before

            val uriField = obj.javaClass.findFieldByExactType<URI>() ?: return@before
            val uri = uriField.get(obj) as? URI ?: return@before
            val path = uri.path ?: return@before

            if (path.contains("/heartbeat_and_get_viewer_count/")) {
                Logger.printDebug { "[Ghost] Blocked live heartbeat: $path" }
                uriField.set(obj, URI("https", "0.0.0.0", "/0", null))
            }
        }
    }
}

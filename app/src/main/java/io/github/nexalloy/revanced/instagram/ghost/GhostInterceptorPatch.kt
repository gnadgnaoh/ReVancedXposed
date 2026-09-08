package io.github.nexalloy.revanced.instagram.ghost

import app.morphe.extension.shared.Logger
import io.github.nexalloy.revanced.instagram.network.networkInterceptorFingerprint
import io.github.nexalloy.patch
import io.github.nexalloy.findFieldByExactType
import java.net.URI

val GhostInterceptor = patch(
    name = "Ghost interceptor",
    description = "Blocks ghost mode network requests via TigonServiceLayer (screenshot, view once, story seen).",
) {
    ::networkInterceptorFingerprint.hookMethod {
        before { param ->
            val obj = param.args[0] ?: return@before
            
            val uriField = obj.javaClass.findFieldByExactType<URI>() ?: return@before
            val uri = uriField.get(obj) as? URI ?: return@before
            val path = uri.path ?: return@before

            val block =
                // Screenshot
                path.endsWith("/screenshot/") ||
                path.endsWith("/ephemeral_screenshot/") ||
                // View once
                path.endsWith("/item_replayed/") ||
                (path.contains("/direct") && path.endsWith("/item_seen/")) ||
                // Story seen
                path.contains("/api/v2/media/seen/")

            if (block) {
                Logger.printDebug { "Ghost interceptor blocked: $path" }
                // Đè URL thành 0.0.0.0
                uriField.set(obj, URI("https", "0.0.0.0", "/0", null))
            }
        }
    }
}

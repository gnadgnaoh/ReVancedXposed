package io.github.nexalloy.revanced.instagram.tracking

import android.content.ClipData
import android.content.ClipboardManager
import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch
import io.github.nexalloy.hookMethod

val SanitizeTrackingLinks = patch(
    name = "Sanitize tracking links",
    description = "Removes tracking parameters from Instagram links when copying."
) {
    ClipboardManager::class.java.getDeclaredMethod(
        "setPrimaryClip",
        ClipData::class.java
    ).hookMethod {
        before { param ->
            val clipData = param.args[0] as? ClipData ?: return@before
            if (clipData.itemCount == 0) return@before
            
            val item = clipData.getItemAt(0) ?: return@before
            var text = item.text?.toString() ?: return@before
            
            if (!text.contains("https://www.instagram.com/")) return@before
            
            Logger.printDebug { "Sanitize tracking link: $text" }
            
            text = text.replace(Regex("[?&](igsh|ig_rid|utm_source|story_media_id)=.*"), "")

            param.args[0] = ClipData.newPlainText("URL", text)
        }
    }
}

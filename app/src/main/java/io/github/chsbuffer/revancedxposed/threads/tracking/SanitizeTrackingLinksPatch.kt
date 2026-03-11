package io.github.chsbuffer.revancedxposed.threads.tracking

import app.revanced.extension.shared.Logger
import de.robv.android.xposed.XposedHelpers
import io.github.chsbuffer.revancedxposed.hookMethod
import io.github.chsbuffer.revancedxposed.patch

val SanitizeTrackingLinks = patch(
    name = "Sanitize tracking links",
    description = "Removes tracking parameters from Threads links when copying.",
) {
    XposedHelpers.findMethodExact(
        "android.content.ClipboardManager",
        classLoader,
        "setPrimaryClip",
        android.content.ClipData::class.java
    ).hookMethod {
        before {
            val clipData = it.args[0] as? android.content.ClipData ?: return@before
            if (clipData.itemCount == 0) return@before
            val item = clipData.getItemAt(0) ?: return@before
            var string = item.text?.toString() ?: return@before
            if (!string.contains("https://www.threads.com/")) return@before
            Logger.printDebug { "Sanitize tracking link: $string" }
            string = string.replace(Regex("\\?xmt=.*"), "")
            it.args[0] = android.content.ClipData.newPlainText("URL", string)
        }
    }
}

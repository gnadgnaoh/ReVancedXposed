package io.github.nexalloy.revanced.instagram.ghost

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch
import java.lang.reflect.Field

/**
 * Makes view-once and replayable (view-twice) media behave like permanent media.
 *
 * Instagram stores the server's JSON "view_mode" field as a plain String on the media model.
 * Possible values:
 *   "once"       — view once (disappears after one open)
 *   "replayable" — view twice (one extra replay allowed)
 *   "permanent"  — normal media, always accessible
 *
 * We hook the parser after it runs and replace any non-permanent view_mode with "permanent"
 * so Instagram renders the media normally.
 *
 * Fingerprint: method using both "archived_media_timestamp" AND "view_mode", paramCount = 1,
 * non-void return (the parser returns the model object; the serializer returns void).
 */
val GhostPermanentView = patch(
    name = "Ghost permanent view",
    description = "Makes view-once and replayable media permanently accessible.",
) {
    ::permanentViewModeFingerprint.hookMethod {
        after { param ->
            val result = param.result ?: return@after

            // Collect seen_count (int) in one pass to decide if content is already consumed
            var seenCount = 0
            var cls: Class<*>? = result.javaClass
            while (cls != null && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    if (f.type == Int::class.javaPrimitiveType) {
                        f.isAccessible = true
                        try { seenCount = f.getInt(result) } catch (_: Throwable) {}
                    }
                }
                cls = cls.superclass
            }

            // Replace view_mode string fields
            cls = result.javaClass
            while (cls != null && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    if (f.type != String::class.java) continue
                    f.isAccessible = true
                    try {
                        val value = f.get(result) as? String ?: continue
                        when (value) {
                            "once" -> {
                                // seen_count >= 1 means already viewed — CDN URL is gone, skip
                                if (seenCount >= 1) return@after
                                f.set(result, "permanent")
                                Logger.printDebug { "Ghost: view_mode 'once' → 'permanent'" }
                            }
                            "replayable", "allow_replay" -> {
                                // replayable allows 2 views; >= 2 means fully consumed
                                if (seenCount >= 2) return@after
                                f.set(result, "permanent")
                                Logger.printDebug { "Ghost: view_mode '$value' → 'permanent'" }
                            }
                        }
                    } catch (_: Throwable) {}
                }
                cls = cls.superclass
            }
        }
    }
}

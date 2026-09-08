package io.github.nexalloy.revanced.instagram.ghost

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

val GhostViewOnce = patch(
    name = "Ghost view once",
    description = "Prevents view-once seen notifications from being sent.",
) {
    ::viewOnceFingerprint.hookMethod {
        before { param ->
            val obj = param.args[2] ?: return@before
            
            for (method in obj.javaClass.declaredMethods) {
                if (method.parameterTypes.isNotEmpty() || method.returnType != String::class.java) continue
                try {
                    method.isAccessible = true
                    val value = method.invoke(obj) as? String ?: continue
                    
                    if (value.contains("visual_item_seen") || value.contains("send_visual_item_seen_marker")) {
                        Logger.printDebug { "Ghost: view-once seen marker suppressed" }
                        param.result = null
                        break // Đã chặn thì break vòng lặp luôn cho tối ưu
                    }
                } catch (_: Throwable) {}
            }
        }
    }
}

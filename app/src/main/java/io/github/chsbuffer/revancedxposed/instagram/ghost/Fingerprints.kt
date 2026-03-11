package io.github.chsbuffer.revancedxposed.instagram.ghost

import io.github.chsbuffer.revancedxposed.AccessFlags
import io.github.chsbuffer.revancedxposed.accessFlags
import io.github.chsbuffer.revancedxposed.findMethodDirect
import io.github.chsbuffer.revancedxposed.strings

// ScreenshotDetection.java:
// find class with "ScreenshotNotificationManager", then void method(long) in that class
val screenshotFingerprint = findMethodDirect {
    val classNames = findClass {
        matcher {
            usingStrings("ScreenshotNotificationManager")
        }
    }.toList().map { it.name }

    classNames.firstNotNullOf { className ->
        findMethod {
            matcher {
                declaredClass(className)
                returnType = "void"
                paramTypes("long")
            }
        }.toList().firstOrNull()
    }
}

// ViewOnce.java: "visual_item_seen", void, 3 params
val viewOnceFingerprint = findMethodDirect {
    findMethod {
        matcher {
            strings("visual_item_seen")
            returnType = "void"
            paramCount = 3
        }
    }.first()
}

// StorySeen.java: "media/seen/", final void, 0 params
val storySeenFingerprint = findMethodDirect {
    findMethod {
        matcher {
            strings("media/seen/")
            returnType = "void"
            paramCount = 0
            accessFlags(AccessFlags.FINAL)
        }
    }.single()
}

// SeenState.java: "mark_thread_seen-", static final void, >= 3 params
val seenStateFingerprint = findMethodDirect {
    findMethod {
        matcher {
            strings("mark_thread_seen-")
            returnType = "void"
            accessFlags(AccessFlags.STATIC, AccessFlags.FINAL)
        }
    }.first { m -> m.paramTypeNames.size >= 3 }
}

// TypingStatus.java: "is_typing_indicator_enabled", static final void(?, boolean)
val typingStatusFingerprint = findMethodDirect {
    findMethod {
        matcher {
            strings("is_typing_indicator_enabled")
            returnType = "void"
            paramCount = 2
            accessFlags(AccessFlags.STATIC, AccessFlags.FINAL)
        }
    }.single()
}

package io.github.nexalloy.morphe.reddit.misc.tracking.url

import io.github.nexalloy.morphe.findMethodDirect
import org.luckypray.dexkit.wrap.DexMethod

val shareLinkFormatterFingerprint = findMethodDirect {
    findMethod {
        matcher {
            usingStrings("share_id", "https://www.reddit.com")
            paramCount(4)
        }
    }.single()
}

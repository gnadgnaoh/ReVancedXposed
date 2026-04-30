package io.github.nexalloy.revanced.threads.ads

import io.github.nexalloy.morphe.findMethodDirect

val adInjectorFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingStrings("SponsoredContentController.processValidatedContent")
        }
    }.single()
}

val adSponsoredContentFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "boolean"
            usingStrings("SponsoredContentController.insertItem")
        }
    }.single()
}

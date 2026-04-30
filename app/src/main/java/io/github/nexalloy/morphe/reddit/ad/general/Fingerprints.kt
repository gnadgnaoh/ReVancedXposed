package io.github.nexalloy.morphe.reddit.ad.general

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.fingerprint
import org.luckypray.dexkit.query.enums.StringMatchType

val adPostFingerprint = findMethodDirect {
    findClass {
        matcher {
            className("com.reddit.domain.model.listing.Listing")
        }
    }.single().methods
        .filter { it.isConstructor } 
        .maxByOrNull { it.paramCount }!!
}

val AdPostSectionInitFingerprint = findMethodDirect {
    findClass {
        matcher {
            usingStrings("AdPostSection(linkId=")
        }
    }.single().methods.single { it.isConstructor }
}

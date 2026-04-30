package io.github.nexalloy.revanced.facebook.ads

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.findClassDirect

// Đã XÓA bước 1 (Logger) vì hoàn toàn vô nghĩa và dễ gây crash Litho Component.

/**
 * 2. ProfileReelsAsyncAdsQuery
 * Tìm đúng class và CHỈ hook hàm có đúng 4 params (hàm fetch data thực sự).
 */
val profileReelsAdsQueryTriggerFingerprints = findMethodListDirect {
    val results = LinkedHashMap<String, org.luckypray.dexkit.result.MethodData>()
    findClass {
        matcher { usingEqStrings("ProfileReelsAsyncAdsQuery") }
    }.forEach { cls ->
        cls.findMethod {
            matcher { 
                returnType = "void"
                paramCount = 4 // BẮT BUỘC PHẢI LÀ 4. Không được bỏ trống.
            }
        }.filter { it.name !in setOf("<init>", "<clinit>") }
            .forEach { m -> results.putIfAbsent("${cls.name}.${m.name}", m) }
    }
    results.values.toList()
}

/**
 * 3. VideoHomeDataControllerImpl RTI render
 * Tìm CHÍNH XÁC hàm void chứa string, không hook cả class.
 */
val vhdcRtiAdsRenderMethodFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            usingStrings("VideoHomeDataControllerImpl.renderFbShortsRealtimeIntentAds")
        }
    }.first()
}

/**
 * 4. VideoHomeDataControllerImpl RTI trigger
 */
val vhdcRtiTriggerFingerprints = findMethodListDirect {
    val results = LinkedHashMap<String, org.luckypray.dexkit.result.MethodData>()
    listOf(
        "VideoHomeDataControllerImpl.triggerFbShortsRealtimeIntentRequest",
        "VideoHomeDataControllerImpl.triggerGapXRtiRequest"
    ).forEach { logString ->
        findMethod {
            matcher {
                returnType = "void"
                usingStrings(logString)
            }
        }.filter { it.name !in setOf("<init>", "<clinit>") }
         .forEach { m -> results.putIfAbsent("${m.className}.${m.name}", m) }
    }
    results.values.toList()
}

/**
 * 5. VideoHomeDataControllerImpl Similar Ads
 */
val vhdcSimilarAdsTriggerFingerprints = findMethodListDirect {
    val results = LinkedHashMap<String, org.luckypray.dexkit.result.MethodData>()
    findMethod {
        matcher {
            returnType = "void"
            usingStrings("VideoHomeDataControllerImpl.triggerSimilarAdsRequest")
        }
    }.filter { it.name !in setOf("<init>", "<clinit>") }
     .forEach { m -> results.putIfAbsent("${m.className}.${m.name}", m) }
    results.values.toList()
}

/**
 * 6. maybeInsertAds + triggerMultiAdsInsertionForDemoAds
 */
val vhdcMaybeInsertAdsFingerprints = findMethodListDirect {
    val results = LinkedHashMap<String, org.luckypray.dexkit.result.MethodData>()
    listOf(
        "VideoHomeDataControllerImpl.maybeInsertAds",
        "VideoHomeDataControllerImpl.triggerMultiAdsInsertionForDemoAds"
    ).forEach { logString ->
        findMethod {
            matcher {
                returnType = "void"
                usingStrings(logString)
            }
        }.filter { it.name !in setOf("<init>", "<clinit>") }
         .forEach { m -> results.putIfAbsent("${m.className}.${m.name}", m) }
    }
    results.values.toList()
}

/**
 * 7. VideoHomeDataControllerAdsUtil insert
 */
val vhdcAdsUtilInsertFingerprints = findMethodListDirect {
    val results = LinkedHashMap<String, org.luckypray.dexkit.result.MethodData>()
    findMethod {
        matcher {
            returnType = "void"
            usingStrings("VideoHomeDataControllerAdsUtil.maybeInsertFbShortsRealtimeIntentItem")
        }
    }.filter { it.name !in setOf("<init>", "<clinit>") }
     .forEach { m -> results.putIfAbsent("${m.className}.${m.name}", m) }
    results.values.toList()
}

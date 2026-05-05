package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import java.lang.reflect.Modifier

// ─── Ad-kind enum ─────────────────────────────────────────────────────────────

val adKindEnumFingerprint = findClassDirect {
    findClass {
        matcher { usingEqStrings("AD", "UGC", "PARADE", "MIDCARD") }
    }.first()
}

// ─── Reels list-builder ───────────────────────────────────────────────────────
// Primary: class that logs "Non ads story fall into ads rendering logic"
// Fallback: structural signature (static 6-param void + static 5-param ArrayList)

val listBuilderClassFingerprint = findClassDirect {
    // Primary: string-based
    val byString = findClass {
        matcher { usingStrings("Non ads story fall into ads rendering logic, StoryType=%s, StoryId=%s") }
    }.firstOrNull()
    if (byString != null) return@findClassDirect byString

    // Fallback: structural
    findMethod {
        matcher {
            modifiers = Modifier.STATIC
            returnType = "void"
            paramTypes(null, null, null, null, null, "java.util.List")
        }
    }.first { md ->
        md.declaredClass?.methods?.any { m ->
            m.isMethod && (m.modifiers and Modifier.STATIC) != 0 &&
            m.paramCount == 5 && m.paramTypeNames.getOrNull(4) == "boolean"
        } == true
    }.declaredClass!!
}

val listBuilderAppendFingerprint = findMethodDirect {
    listBuilderClassFingerprint().findMethod {
        matcher {
            modifiers = Modifier.STATIC
            returnType = "void"
            paramTypes(null, null, null, null, null, "java.util.List")
        }
    }.single()
}

val listBuilderFactoryFingerprint = findMethodDirect {
    listBuilderClassFingerprint().findMethod {
        matcher { modifiers = Modifier.STATIC; paramCount = 5 }
    }.first { it.paramTypeNames.getOrNull(4) == "boolean" }
}

// ─── Plugin packs ─────────────────────────────────────────────────────────────
// Upstream now blocks BOTH FbShortsViewerPluginPack AND MarketplaceAdsPluginPack.

val pluginPackMethodsFingerprint = findMethodListDirect {
    listOf("FbShortsViewerPluginPack", "MarketplaceAdsPluginPack").flatMap { tag ->
        findClass {
            matcher {
                methods {
                    add { returnType = "java.lang.String"; paramCount = 0; usingStrings(tag) }
                    add { returnType = "java.util.List"; paramCount = 0 }
                }
            }
        }.flatMap { cls ->
            cls.findMethod { matcher { returnType = "java.util.List"; paramCount = 0 } }
        }
    }.distinctBy { it.descriptor }.filter { !it.isConstructor }
}

// ─── Instream banner eligibility ─────────────────────────────────────────────

val instreamBannerEligibilityFingerprint = findMethodDirect {
    findMethod {
        matcher { returnType = "boolean"; paramCount = 0; usingStrings("InstreamAdIdleWithBannerState") }
    }.first { !it.isConstructor }
}

// ─── Indicator pill eligibility ──────────────────────────────────────────────

val indicatorPillAdEligibilityFingerprint = findMethodDirect {
    findMethod {
        matcher { modifiers = Modifier.STATIC; returnType = "boolean"; paramCount = 3; usingStrings("ReelsAdsFloatingCtaPlugin") }
    }.first()
}

// ─── Reels banner render methods ─────────────────────────────────────────────

val reelsBannerRenderMethodsFingerprint = findMethodListDirect {
    listOf("ReelsBannerAdsComponent", "ReelsBannerAdsNativeComponent").flatMap { tag ->
        findMethod {
            matcher { paramCount = 1; usingStrings(tag) }
        }.filter { m -> !m.isConstructor }
    }.distinctBy { it.descriptor }
}

// ─── Feed CSR cache filter ────────────────────────────────────────────────────

val feedCsrFilterMethodsFingerprint = findMethodListDirect {
    listOf("FeedCSRCacheFilter", "FeedCSRCacheFilter2025H1", "FeedCSRCacheFilter2026H1").flatMap { tag ->
        findClass {
            matcher { usingStrings(tag) }
        }.flatMap { cls ->
            cls.findMethod {
                matcher {
                    returnType = "com.google.common.collect.ImmutableList"
                    paramTypes(
                        "com.facebook.auth.usersession.FbUserSession",
                        "com.google.common.collect.ImmutableList",
                        "int"
                    )
                }
            }
        }
    }.distinctBy { it.descriptor }.filter { !it.isConstructor }
}

// ─── Late feed list sanitisers ────────────────────────────────────────────────

val lateFeedListMethodsFingerprint = findMethodListDirect {
    val results = ArrayList<org.luckypray.dexkit.result.MethodData>()

    findClass { matcher { usingStrings("handleStorageStories", "Empty Storage List") } }.forEach { cls ->
        cls.findMethod {
            matcher { returnType = "void"; paramTypes(null, "com.google.common.collect.ImmutableList", "int") }
        }.forEach { results.add(it) }
    }

    findClass { matcher { usingStrings("cancelVendingTimerAndAddToPool_") } }.forEach { cls ->
        cls.findMethod {
            matcher { returnType = "void"; paramTypes("com.google.common.collect.ImmutableList", "java.lang.String") }
        }.forEach { results.add(it) }
    }

    listOf("CSRNoOpStorageLifecycleImpl", "FeedCSRStorageLifecycle", "FriendlyFeedCSRStorageLifecycle", "FbShortsCSRStorageLifecycle").forEach { tag ->
        findClass { matcher { usingStrings(tag) } }.forEach { cls ->
            cls.findMethod {
                matcher {
                    returnType = "void"
                    paramTypes("com.facebook.auth.usersession.FbUserSession", null, "com.google.common.collect.ImmutableList")
                }
            }.forEach { results.add(it) }
        }
    }

    results.distinctBy { it.descriptor }.filter { !it.isConstructor }
}

// ─── Story pool add ───────────────────────────────────────────────────────────

val storyPoolAddMethodsFingerprint = findMethodListDirect {
    listOf("CSRStoryPoolCoordinator", "FeedStoryPoolCoordinator").flatMap { tag ->
        findClass { matcher { usingStrings(tag) } }.flatMap { cls ->
            cls.findMethod { matcher { returnType = "boolean"; paramCount = 1 } }
        }
    }.distinctBy { it.descriptor }.filter { !it.isConstructor }
}

// ─── Sponsored pool ───────────────────────────────────────────────────────────

val sponsoredPoolClassFingerprint = findClassDirect {
    findMethod {
        matcher {
            returnType = "boolean"
            paramTypes("com.facebook.graphql.model.GraphQLFeedUnitEdge")
            usingStrings("SponsoredPoolContainerAdapter")
        }
    }.first().declaredClass!!
}

val sponsoredPoolAddMethodFingerprint = findMethodDirect {
    sponsoredPoolClassFingerprint().findMethod {
        matcher { returnType = "boolean"; paramTypes("com.facebook.graphql.model.GraphQLFeedUnitEdge") }
    }.single()
}

// ─── Sponsored story manager ──────────────────────────────────────────────────

val sponsoredStoryNextMethodFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
            paramCount = 0
            usingStrings("FeedSponsoredStoryHolder.onPositionReset")
        }
    }.first()
}

// ─── Story ads in-disc source ─────────────────────────────────────────────────
// Upstream changed search string to "ads_deletion" (from commit fixing profile timeline ads)

val storyAdsInDiscClassFingerprint = findClassDirect {
    findMethod {
        matcher { usingStrings("ads_deletion") }
    }.first { md ->
        val cls = md.declaredClass ?: return@first false
        cls.findMethod {
            matcher {
                returnType = "com.google.common.collect.ImmutableList"
                paramTypes("com.facebook.auth.usersession.FbUserSession", null, "com.google.common.collect.ImmutableList")
            }
        }.isNotEmpty() && cls.findMethod {
            matcher { returnType = "void"; paramTypes(null, "com.google.common.collect.ImmutableList") }
        }.isNotEmpty()
    }.declaredClass!!
}

// ─── Game ad request methods ──────────────────────────────────────────────────

val gameAdRequestMethodsFingerprint = findMethodListDirect {
    listOf(
        "Invalid JSON content received by onGetInterstitialAdAsync: ",
        "Invalid JSON content received by onGetRewardedInterstitialAsync: ",
        "Invalid JSON content received by onRewardedVideoAsync: ",
        "Invalid JSON content received by onLoadAdAsync: ",
        "Invalid JSON content received by onShowAdAsync: "
    ).flatMap { tag ->
        findMethod {
            matcher { returnType = "void"; paramTypes("org.json.JSONObject"); usingStrings(tag) }
        }
    }.distinctBy { it.descriptor }.filter { !it.isConstructor }
}

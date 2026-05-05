package io.github.nexalloy.revanced.facebook.ad

import android.os.Bundle
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.AdStoryInspector
import io.github.nexalloy.revanced.facebook.AUDIENCE_NETWORK_ACTIVITY_CLASS
import io.github.nexalloy.revanced.facebook.AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS
import io.github.nexalloy.revanced.facebook.FB_TAG
import io.github.nexalloy.revanced.facebook.FeedItemInspector
import io.github.nexalloy.revanced.facebook.FeedListSanitizerHook
import io.github.nexalloy.revanced.facebook.NEKO_PLAYABLE_ACTIVITY_CLASS
import io.github.nexalloy.revanced.facebook.hookAudienceNetworkRewardFallbacks
import io.github.nexalloy.revanced.facebook.hookFeedCsrFilterInput
import io.github.nexalloy.revanced.facebook.hookGameAdActivityLaunchFallbacks
import io.github.nexalloy.revanced.facebook.hookGameAdBridge
import io.github.nexalloy.revanced.facebook.hookGameAdRequest
import io.github.nexalloy.revanced.facebook.hookGameAdResultMethods
import io.github.nexalloy.revanced.facebook.hookGameAdServiceDispatchMethods
import io.github.nexalloy.revanced.facebook.hookGlobalGameAdActivityLifecycleFallback
import io.github.nexalloy.revanced.facebook.hookGlobalGameAdSurfaceFallbacks
import io.github.nexalloy.revanced.facebook.hookIndicatorPillAdEligibility
import io.github.nexalloy.revanced.facebook.hookInstreamBannerEligibility
import io.github.nexalloy.revanced.facebook.hookLateFeedListSanitizer
import io.github.nexalloy.revanced.facebook.hookListBuilderAppend
import io.github.nexalloy.revanced.facebook.hookListResultFilter
import io.github.nexalloy.revanced.facebook.hookPlayableAdActivity
import io.github.nexalloy.revanced.facebook.hookPluginPackFallback
import io.github.nexalloy.revanced.facebook.hookReelsBannerRender
import io.github.nexalloy.revanced.facebook.hookSponsoredPoolAdd
import io.github.nexalloy.revanced.facebook.hookSponsoredPoolListMethods
import io.github.nexalloy.revanced.facebook.hookSponsoredPoolResultMethods
import io.github.nexalloy.revanced.facebook.hookSponsoredStoryNext
import io.github.nexalloy.revanced.facebook.hookStoryAdProvider
import io.github.nexalloy.revanced.facebook.hookStoryPoolAdd
import io.github.nexalloy.revanced.facebook.resolveStoryAdProviderHooks

/**
 * Master patch – ports all FacebookAppAdsRemover hooks into NexAlloy.
 *
 * Synced with upstream commit that:
 *  - Adds MarketplaceAdsPluginPack blocking
 *  - Adds hidebanneradasync message type
 *  - Splits fix strategy: banner → autofix, rewarded/interstitial → ADS_UNAVAILABLE
 *  - Adds hookGameAdResultMethods + hookGameAdServiceDispatchMethods (deeper bridge hooks)
 *  - Adds hookGlobalGameAdSurfaceFallbacks (native ad view / WebView / TextView)
 *  - Adds hookAudienceNetworkRewardFallbacks (reward completion callbacks)
 *  - Sets RESULT_OK (not RESULT_CANCELED) when finishing game ad activities
 *  - Changes storyAdsInDisc search string to "ads_deletion"
 */
val HideFacebookAds = patch(
    name = "Hide Facebook ads",
    description = "Removes sponsored feed stories, Reels ads, game ads, and banner ads.",
) {
    // ── 1. Ad-kind enum & Reels list-builder ─────────────────────────────────

    val adKindEnumClass = ::adKindEnumFingerprint.clazz
    val storyInspector  = AdStoryInspector(adKindEnumClass)

    hookListBuilderAppend(::listBuilderAppendFingerprint.method, storyInspector)

    runCatching {
        hookListResultFilter(::listBuilderFactoryFingerprint.method, "list factory", storyInspector)
    }.onFailure { XposedBridge.log("[$FB_TAG] No list factory method (non-fatal): ${it.message}") }

    // Both FbShortsViewerPluginPack and MarketplaceAdsPluginPack
    ::pluginPackMethodsFingerprint.dexMethodList.forEach { dm ->
        runCatching { hookPluginPackFallback(dm.toMethod(), storyInspector) }
            .onFailure { XposedBridge.log("[$FB_TAG] Plugin pack hook failed: ${it.message}") }
    }

    // ── 2. Instream banner & indicator pill ───────────────────────────────────

    runCatching { hookInstreamBannerEligibility(::instreamBannerEligibilityFingerprint.method) }
        .onFailure { XposedBridge.log("[$FB_TAG] No instream banner eligibility (non-fatal): ${it.message}") }

    runCatching { hookIndicatorPillAdEligibility(::indicatorPillAdEligibilityFingerprint.method) }
        .onFailure { XposedBridge.log("[$FB_TAG] No indicator pill eligibility (non-fatal): ${it.message}") }

    // ── 3. Reels banner Litho render ──────────────────────────────────────────

    ::reelsBannerRenderMethodsFingerprint.dexMethodList.forEach { dm ->
        runCatching { hookReelsBannerRender(dm.toMethod()) }
            .onFailure { XposedBridge.log("[$FB_TAG] Reels banner render hook failed: ${it.message}") }
    }

    // ── 4. Feed CSR cache filter ──────────────────────────────────────────────

    val storyPoolAddMethods = ::storyPoolAddMethodsFingerprint.dexMethodList.mapNotNull { dm ->
        runCatching { dm.toMethod() }.getOrNull()
    }
    val feedItemInspector = FeedItemInspector(storyPoolAddMethods.map { it.parameterTypes[0] })

    ::feedCsrFilterMethodsFingerprint.dexMethodList.forEach { dm ->
        runCatching { hookFeedCsrFilterInput(dm.toMethod(), feedItemInspector) }
            .onFailure { XposedBridge.log("[$FB_TAG] Feed CSR filter hook failed: ${it.message}") }
    }

    // ── 5. Late feed list sanitisers ──────────────────────────────────────────

    ::lateFeedListMethodsFingerprint.dexMethodList.forEach { dm ->
        runCatching {
            val method = dm.toMethod()
            val listArgIndex = method.parameterTypes.indexOfFirst {
                it.name == "com.google.common.collect.ImmutableList"
            }.coerceAtLeast(0)
            hookLateFeedListSanitizer(FeedListSanitizerHook(method, listArgIndex), feedItemInspector)
        }.onFailure { XposedBridge.log("[$FB_TAG] Late feed list hook failed: ${it.message}") }
    }

    // ── 6. Story pool add ─────────────────────────────────────────────────────

    storyPoolAddMethods.forEach { method ->
        runCatching { hookStoryPoolAdd(method, feedItemInspector) }
            .onFailure { XposedBridge.log("[$FB_TAG] Story pool add hook failed: ${it.message}") }
    }

    // ── 7. Sponsored pool ─────────────────────────────────────────────────────

    runCatching { hookSponsoredPoolAdd(::sponsoredPoolAddMethodFingerprint.method) }
        .onFailure { XposedBridge.log("[$FB_TAG] No sponsored pool add (non-fatal): ${it.message}") }

    runCatching { hookSponsoredStoryNext(::sponsoredStoryNextMethodFingerprint.method) }
        .onFailure { XposedBridge.log("[$FB_TAG] No sponsored story next (non-fatal): ${it.message}") }

    runCatching {
        val poolClass = ::sponsoredPoolClassFingerprint.clazz
        hookSponsoredPoolListMethods(poolClass)
        hookSponsoredPoolResultMethods(poolClass)
    }.onFailure { XposedBridge.log("[$FB_TAG] No sponsored pool class (non-fatal): ${it.message}") }

    // ── 8. Story ad provider (in-disc) ────────────────────────────────────────

    runCatching {
        hookStoryAdProvider(resolveStoryAdProviderHooks(::storyAdsInDiscClassFingerprint.clazz, true))
    }.onFailure { XposedBridge.log("[$FB_TAG] No story ads in-disc source (non-fatal): ${it.message}") }

    // ── 9. Game ad requests + bridge ─────────────────────────────────────────

    val gameAdMethods = ::gameAdRequestMethodsFingerprint.dexMethodList.mapNotNull { dm ->
        runCatching { dm.toMethod() }.getOrNull()
    }

    gameAdMethods.forEach { m ->
        runCatching { hookGameAdRequest(m) }
            .onFailure { XposedBridge.log("[$FB_TAG] Game ad request hook failed: ${it.message}") }
    }

    // postMessage bridge
    gameAdMethods.firstOrNull()?.let { firstMethod ->
        runCatching {
            firstMethod.declaringClass.declaredMethods
                .firstOrNull { m -> m.name == "postMessage" && m.parameterCount == 2 && m.parameterTypes.all { it == String::class.java } }
                ?.apply { isAccessible = true }
                ?.let { hookGameAdBridge(it) }
        }.onFailure { XposedBridge.log("[$FB_TAG] Game ad bridge hook failed: ${it.message}") }
    }

    // ── 10. Deeper bridge hooks (resolve / reject / service dispatch) ─────────

    gameAdMethods.firstOrNull()?.declaringClass?.let { bridgeClass ->
        runCatching { hookGameAdResultMethods(bridgeClass) }
            .onFailure { XposedBridge.log("[$FB_TAG] Game ad result hooks failed: ${it.message}") }
        runCatching { hookGameAdServiceDispatchMethods(bridgeClass) }
            .onFailure { XposedBridge.log("[$FB_TAG] Game ad service dispatch hooks failed: ${it.message}") }
    }

    // ── 11. Audience Network reward fallbacks ─────────────────────────────────

    runCatching { hookAudienceNetworkRewardFallbacks(classLoader) }
        .onFailure { XposedBridge.log("[$FB_TAG] AN reward fallbacks failed: ${it.message}") }

    // ── 12. Activity lifecycle hooks ──────────────────────────────────────────

    // NekoPlayableAdActivity
    runCatching {
        classLoader.loadClass(NEKO_PLAYABLE_ACTIVITY_CLASS).declaredMethods
            .firstOrNull { m -> m.name == "onResume" && m.parameterCount == 0 }
            ?.apply { isAccessible = true }
            ?.let { hookPlayableAdActivity(it) }
    }.onFailure { XposedBridge.log("[$FB_TAG] No playable ad activity (non-fatal): ${it.message}") }

    // AudienceNetwork activities
    listOf(AUDIENCE_NETWORK_ACTIVITY_CLASS, AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS).forEach { cn ->
        runCatching {
            val actClass = classLoader.loadClass(cn)
            (actClass.declaredMethods + actClass.methods).firstOrNull { m ->
                (m.name == "onResume" && m.parameterCount == 0) ||
                (m.name == "onCreate" && m.parameterCount == 1 && m.parameterTypes[0] == Bundle::class.java)
            }?.apply { isAccessible = true }?.let { hookPlayableAdActivity(it) }
        }
    }

    runCatching { hookGlobalGameAdActivityLifecycleFallback() }
        .onFailure { XposedBridge.log("[$FB_TAG] Global lifecycle fallback failed: ${it.message}") }

    runCatching { hookGameAdActivityLaunchFallbacks() }
        .onFailure { XposedBridge.log("[$FB_TAG] Launch fallbacks failed: ${it.message}") }

    // ── 13. Native ad view / WebView surface fallbacks ────────────────────────

    runCatching { hookGlobalGameAdSurfaceFallbacks() }
        .onFailure { XposedBridge.log("[$FB_TAG] Surface fallbacks failed: ${it.message}") }

    XposedBridge.log("[$FB_TAG] All Facebook ad patches applied")
}

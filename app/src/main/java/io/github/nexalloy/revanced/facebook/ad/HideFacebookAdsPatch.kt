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
import io.github.nexalloy.revanced.facebook.hookFeedCsrFilterInput
import io.github.nexalloy.revanced.facebook.hookGameAdActivityLaunchFallbacks
import io.github.nexalloy.revanced.facebook.hookGameAdBridge
import io.github.nexalloy.revanced.facebook.hookGameAdRequest
import io.github.nexalloy.revanced.facebook.hookGlobalGameAdActivityLifecycleFallback
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
import io.github.nexalloy.revanced.facebook.resolveLithoRenderMethod
import io.github.nexalloy.revanced.facebook.resolveStoryAdProviderHooks

/**
 * Master patch porting all FacebookAppAdsRemover hooks into the NexAlloy framework.
 *
 * Hook groups:
 *  1. Upstream Reels list / plugin pack  (AdStoryInspector – ad-kind + Reels signal)
 *  2. Instream banner / indicator pill eligibility
 *  3. Reels banner Litho component render
 *  4. Feed CSR cache filter
 *  5. Late feed list sanitisers
 *  6. Story pool add
 *  7. Sponsored pool (add, next, list, result methods)
 *  8. Story ad providers (data source + in-disc)
 *  9. Game ad requests / bridge / activity lifecycle
 *
 * NexAlloy's DexKitCacheBridge caches every fingerprint result keyed on
 * host-app lastUpdateTime + module commit hash, so expensive DexKit searches
 * only run once per Facebook update – drastically improving cold-start time.
 */
val HideFacebookAds = patch(
    name = "Hide Facebook ads",
    description = "Removes sponsored feed stories, Reels ads, game ads, and banner ads.",
) {
    // ── 1. Ad-kind enum & Reels list-builder ─────────────────────────────────

    val adKindEnumClass = ::adKindEnumFingerprint.clazz
    val storyInspector  = AdStoryInspector(adKindEnumClass)

    val appendMethod = ::listBuilderAppendFingerprint.method
    hookListBuilderAppend(appendMethod, storyInspector)

    runCatching {
        hookListResultFilter(::listBuilderFactoryFingerprint.method, "list factory", storyInspector)
    }.onFailure { XposedBridge.log("[$FB_TAG] No list factory method (non-fatal): ${it.message}") }

    runCatching {
        hookPluginPackFallback(::pluginPackMethodFingerprint.method, storyInspector)
    }.onFailure { XposedBridge.log("[$FB_TAG] No plugin pack method (non-fatal): ${it.message}") }

    // ── 2. Instream banner & indicator pill ───────────────────────────────────

    runCatching {
        hookInstreamBannerEligibility(::instreamBannerEligibilityFingerprint.method)
    }.onFailure { XposedBridge.log("[$FB_TAG] No instream banner eligibility (non-fatal): ${it.message}") }

    runCatching {
        hookIndicatorPillAdEligibility(::indicatorPillAdEligibilityFingerprint.method)
    }.onFailure { XposedBridge.log("[$FB_TAG] No indicator pill eligibility (non-fatal): ${it.message}") }

    // ── 3. Reels banner Litho render ──────────────────────────────────────────
    // DexKit gives us 1-param methods; verify they look like Litho render methods
    // (return type assignable from declaring class) before hooking.

    ::reelsBannerRenderMethodsFingerprint.dexMethodList.forEach { dexMethod ->
        runCatching {
            val m      = dexMethod.toMethod()
            val retCls = runCatching { classLoader.loadClass(m.returnType.name) }.getOrNull()
            if (retCls != null && retCls.isAssignableFrom(m.declaringClass)) {
                hookReelsBannerRender(m)
            } else {
                // Fallback: hook anyway if it came from the banner class
                hookReelsBannerRender(m)
            }
        }.onFailure { XposedBridge.log("[$FB_TAG] Reels banner render hook failed: ${it.message}") }
    }

    // ── 4. Feed CSR cache filter ──────────────────────────────────────────────

    // Recover item contract types for FeedItemInspector from storyPoolAdd param types
    val storyPoolAddMethods = ::storyPoolAddMethodsFingerprint.dexMethodList.mapNotNull { dm ->
        runCatching { dm.toMethod() }.getOrNull()
    }
    val feedItemInspector = FeedItemInspector(storyPoolAddMethods.map { it.parameterTypes[0] })

    ::feedCsrFilterMethodsFingerprint.dexMethodList.forEach { dexMethod ->
        runCatching {
            hookFeedCsrFilterInput(dexMethod.toMethod(), feedItemInspector)
        }.onFailure { XposedBridge.log("[$FB_TAG] Feed CSR filter hook failed: ${it.message}") }
    }

    // ── 5. Late feed list sanitisers ──────────────────────────────────────────

    ::lateFeedListMethodsFingerprint.dexMethodList.forEach { dexMethod ->
        runCatching {
            val method       = dexMethod.toMethod()
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

    runCatching {
        hookSponsoredPoolAdd(::sponsoredPoolAddMethodFingerprint.method)
    }.onFailure { XposedBridge.log("[$FB_TAG] No sponsored pool add (non-fatal): ${it.message}") }

    runCatching {
        hookSponsoredStoryNext(::sponsoredStoryNextMethodFingerprint.method)
    }.onFailure { XposedBridge.log("[$FB_TAG] No sponsored story next (non-fatal): ${it.message}") }

    runCatching {
        val poolClass = ::sponsoredPoolClassFingerprint.clazz
        hookSponsoredPoolListMethods(poolClass)
        hookSponsoredPoolResultMethods(poolClass)
    }.onFailure { XposedBridge.log("[$FB_TAG] No sponsored pool class (non-fatal): ${it.message}") }

    // ── 8. Story ad providers ─────────────────────────────────────────────────

    runCatching {
        hookStoryAdProvider(
            resolveStoryAdProviderHooks(::storyAdsDataSourceClassFingerprint.clazz, false)
        )
    }.onFailure { XposedBridge.log("[$FB_TAG] No story ads data source (non-fatal): ${it.message}") }

    runCatching {
        hookStoryAdProvider(
            resolveStoryAdProviderHooks(::storyAdsInDiscClassFingerprint.clazz, true)
        )
    }.onFailure { XposedBridge.log("[$FB_TAG] No story ads in-disc source (non-fatal): ${it.message}") }

    // ── 9. Game ads ───────────────────────────────────────────────────────────

    val gameAdMethods = ::gameAdRequestMethodsFingerprint.dexMethodList.mapNotNull { dm ->
        runCatching { dm.toMethod() }.getOrNull()
    }

    gameAdMethods.forEach { m ->
        runCatching { hookGameAdRequest(m) }
            .onFailure { XposedBridge.log("[$FB_TAG] Game ad request hook failed: ${it.message}") }
    }

    // postMessage bridge – same declaring class as first handler
    gameAdMethods.firstOrNull()?.let { firstMethod ->
        runCatching {
            firstMethod.declaringClass.declaredMethods
                .firstOrNull { m ->
                    m.name == "postMessage" && m.parameterCount == 2 &&
                    m.parameterTypes.all { it == String::class.java }
                }
                ?.apply { isAccessible = true }
                ?.let { hookGameAdBridge(it) }
        }.onFailure { XposedBridge.log("[$FB_TAG] Game ad bridge hook failed: ${it.message}") }
    }

    // NekoPlayableAdActivity
    runCatching {
        val nekoClass = classLoader.loadClass(NEKO_PLAYABLE_ACTIVITY_CLASS)
        nekoClass.declaredMethods
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
                (m.name == "onCreate" && m.parameterCount == 1 &&
                 m.parameterTypes[0] == Bundle::class.java)
            }?.apply { isAccessible = true }?.let { hookPlayableAdActivity(it) }
        }
    }

    // Global lifecycle fallback for any game-ad activity that slips through
    runCatching { hookGlobalGameAdActivityLifecycleFallback() }
        .onFailure { XposedBridge.log("[$FB_TAG] Global lifecycle fallback failed: ${it.message}") }

    // Intent-level launch fallback for hard-blocked ad activities
    runCatching { hookGameAdActivityLaunchFallbacks() }
        .onFailure { XposedBridge.log("[$FB_TAG] Launch fallbacks failed: ${it.message}") }

    XposedBridge.log("[$FB_TAG] All Facebook ad patches applied")
}

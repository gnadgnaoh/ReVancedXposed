package io.github.nexalloy.revanced.facebook.ad

import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.AdStoryInspector
import io.github.nexalloy.revanced.facebook.FB_TAG
import io.github.nexalloy.revanced.facebook.FeedItemInspector
import io.github.nexalloy.revanced.facebook.FeedListSanitizerHook
import io.github.nexalloy.revanced.facebook.GAME_AD_MESSAGE_TYPES
import io.github.nexalloy.revanced.facebook.buildGameAdSuccessPayload
import io.github.nexalloy.revanced.facebook.buildImmutableListLike
import io.github.nexalloy.revanced.facebook.extractFeedItemsFromResult
import io.github.nexalloy.revanced.facebook.extractPromiseId
import io.github.nexalloy.revanced.facebook.filterAdItems
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
import io.github.nexalloy.revanced.facebook.NEKO_PLAYABLE_ACTIVITY_CLASS
import io.github.nexalloy.revanced.facebook.AUDIENCE_NETWORK_ACTIVITY_CLASS
import io.github.nexalloy.revanced.facebook.AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS
import io.github.nexalloy.revanced.facebook.replaceFeedItemsInResult
import io.github.nexalloy.revanced.facebook.resolveLithoRenderMethod
import io.github.nexalloy.revanced.facebook.resolveStoryAdProviderHooks
import java.lang.reflect.Modifier
import java.lang.reflect.Method
import org.json.JSONObject

/**
 * Master patch that ports all FacebookAppAdsRemover hooks into the NexAlloy framework.
 *
 * Hooks are grouped the same way as the original:
 *  1. Upstream Reels list / plugin pack (ad-kind + Reels signal)
 *  2. Instream banner / indicator pill eligibility
 *  3. Reels banner render (Litho component)
 *  4. Feed CSR cache filter
 *  5. Late feed list sanitizers
 *  6. Story pool add
 *  7. Sponsored pool (add, next, list, result methods)
 *  8. Story ad providers (data source + in-disc)
 *  9. Game ad requests / bridge / activity lifecycle
 *
 * NexAlloy's DexKitCacheBridge (via PatchExecutor) caches every fingerprint result
 * keyed on host-app lastUpdateTime + module commit hash, so the expensive DexKit
 * searches only run once per Facebook update.
 */
val HideFacebookAds = patch(
    name = "Hide Facebook ads",
    description = "Removes sponsored feed stories, Reels ads, game ads, and banner ads from the Facebook app.",
) {
    // ── 1. Ad-kind enum & Reels list-builder ─────────────────────────────────

    val adKindEnumClass = ::adKindEnumFingerprint.clazz
    val inspector       = AdStoryInspector(adKindEnumClass)

    val appendMethod = ::listBuilderAppendFingerprint.method
    hookListBuilderAppend(appendMethod, inspector)

    runCatching {
        val factoryMethod = ::listBuilderFactoryFingerprint.method
        hookListResultFilter(factoryMethod, "list factory", inspector)
    }.onFailure { XposedBridge.log("[$FB_TAG] No list factory method (non-fatal)") }

    runCatching {
        val pluginMethod = ::pluginPackMethodFingerprint.method
        hookPluginPackFallback(pluginMethod, inspector)
    }.onFailure { XposedBridge.log("[$FB_TAG] No plugin pack method (non-fatal)") }

    // ── 2. Instream banner & indicator pill eligibility ───────────────────────

    runCatching {
        hookInstreamBannerEligibility(::instreamBannerEligibilityFingerprint.method)
    }.onFailure { XposedBridge.log("[$FB_TAG] No instream banner eligibility method (non-fatal)") }

    runCatching {
        hookIndicatorPillAdEligibility(::indicatorPillAdEligibilityFingerprint.method)
    }.onFailure { XposedBridge.log("[$FB_TAG] No indicator pill eligibility method (non-fatal)") }

    // ── 3. Reels banner render (Litho) ────────────────────────────────────────

    ::reelsBannerRenderMethodsFingerprint.dexMethodList.forEach { dexMethod ->
        runCatching {
            hookReelsBannerRender(dexMethod.toMethod())
        }.onFailure { XposedBridge.log("[$FB_TAG] Failed to hook Reels banner render: ${it.message}") }
    }

    // ── 4. Feed CSR cache filter ──────────────────────────────────────────────

    // Resolve contract types from story-pool-add param types (needed by FeedItemInspector)
    val storyPoolAddMethods = ::storyPoolAddMethodsFingerprint.dexMethodList.mapNotNull { dm ->
        runCatching { dm.toMethod() }.getOrNull()
    }
    val feedItemInspector = FeedItemInspector(storyPoolAddMethods.map { it.parameterTypes[0] })

    ::feedCsrFilterMethodsFingerprint.dexMethodList.forEach { dexMethod ->
        runCatching {
            hookFeedCsrFilterInput(dexMethod.toMethod(), feedItemInspector)
        }.onFailure { XposedBridge.log("[$FB_TAG] Failed to hook feed CSR filter: ${it.message}") }
    }

    // ── 5. Late feed list sanitizers ──────────────────────────────────────────

    // We need the list arg index per method – recover from the fingerprint's paramTypes
    ::lateFeedListMethodsFingerprint.dexMethodList.forEach { dexMethod ->
        runCatching {
            val method    = dexMethod.toMethod()
            val listArgIndex = method.parameterTypes.indexOfFirst {
                it.name == "com.google.common.collect.ImmutableList"
            }.takeIf { it >= 0 } ?: 0
            hookLateFeedListSanitizer(FeedListSanitizerHook(method, listArgIndex), feedItemInspector)
        }.onFailure { XposedBridge.log("[$FB_TAG] Failed to hook late feed list: ${it.message}") }
    }

    // ── 6. Story pool add ─────────────────────────────────────────────────────

    storyPoolAddMethods.forEach { method ->
        runCatching {
            hookStoryPoolAdd(method, feedItemInspector)
        }.onFailure { XposedBridge.log("[$FB_TAG] Failed to hook story pool add: ${it.message}") }
    }

    // ── 7. Sponsored pool ─────────────────────────────────────────────────────

    runCatching {
        val poolAddMethod = ::sponsoredPoolAddMethodFingerprint.method
        hookSponsoredPoolAdd(poolAddMethod)
    }.onFailure { XposedBridge.log("[$FB_TAG] No sponsored pool add method (non-fatal)") }

    runCatching {
        val nextMethod = ::sponsoredStoryNextMethodFingerprint.method
        hookSponsoredStoryNext(nextMethod)
    }.onFailure { XposedBridge.log("[$FB_TAG] No sponsored story next method (non-fatal)") }

    runCatching {
        val poolClass = ::sponsoredPoolClassFingerprint.clazz
        hookSponsoredPoolListMethods(poolClass)
        hookSponsoredPoolResultMethods(poolClass)
    }.onFailure { XposedBridge.log("[$FB_TAG] No sponsored pool class (non-fatal)") }

    // ── 8. Story ad providers ─────────────────────────────────────────────────

    runCatching {
        val dataSourceClass = ::storyAdsDataSourceClassFingerprint.clazz
        hookStoryAdProvider(resolveStoryAdProviderHooks(dataSourceClass, includeInsertionTrigger = false))
    }.onFailure { XposedBridge.log("[$FB_TAG] No story ads data source (non-fatal)") }

    runCatching {
        val inDiscClass = ::storyAdsInDiscClassFingerprint.clazz
        hookStoryAdProvider(resolveStoryAdProviderHooks(inDiscClass, includeInsertionTrigger = true))
    }.onFailure { XposedBridge.log("[$FB_TAG] No story ads in-disc source (non-fatal)") }

    // ── 9. Game ads ───────────────────────────────────────────────────────────

    val gameAdMethods = ::gameAdRequestMethodsFingerprint.dexMethodList.mapNotNull { dm ->
        runCatching { dm.toMethod() }.getOrNull()
    }

    gameAdMethods.forEach { method ->
        runCatching { hookGameAdRequest(method) }
            .onFailure { XposedBridge.log("[$FB_TAG] Failed to hook game ad request: ${it.message}") }
    }

    // postMessage bridge (same declaring class as first game-ad handler)
    gameAdMethods.firstOrNull()?.let { firstMethod ->
        runCatching {
            val bridgePostMessage = firstMethod.declaringClass.declaredMethods.firstOrNull { m ->
                m.name == "postMessage" && m.parameterCount == 2 &&
                m.parameterTypes.all { it == String::class.java }
            }?.apply { isAccessible = true }
            bridgePostMessage?.let { hookGameAdBridge(it) }
        }.onFailure { XposedBridge.log("[$FB_TAG] Failed to hook game ad bridge: ${it.message}") }
    }

    // NekoPlayableAdActivity
    runCatching {
        val nekoClass = classLoader.loadClass(NEKO_PLAYABLE_ACTIVITY_CLASS)
        nekoClass.declaredMethods.firstOrNull { m -> m.name == "onResume" && m.parameterCount == 0 }
            ?.apply { isAccessible = true }
            ?.let { hookPlayableAdActivity(it) }
    }.onFailure { XposedBridge.log("[$FB_TAG] No playable ad activity (non-fatal)") }

    // AudienceNetwork activities (onResume / onStart / onCreate)
    listOf(AUDIENCE_NETWORK_ACTIVITY_CLASS, AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS).forEach { className ->
        runCatching {
            val actClass = classLoader.loadClass(className)
            (actClass.declaredMethods + actClass.methods).firstOrNull { m ->
                (m.name == "onResume"  && m.parameterCount == 0) ||
                (m.name == "onStart"   && m.parameterCount == 0) ||
                (m.name == "onCreate"  && m.parameterCount == 1 && m.parameterTypes[0] == Bundle::class.java)
            }?.apply { isAccessible = true }?.let { hookPlayableAdActivity(it) }
        }
    }

    // Global fallback: finish any game-ad activity that slips through
    runCatching { hookGlobalGameAdActivityLifecycleFallback() }
        .onFailure { XposedBridge.log("[$FB_TAG] Failed global game-ad lifecycle fallback: ${it.message}") }

    // Intent-level launch fallback (blocks hard-blocked ad activities before they start)
    runCatching { hookGameAdActivityLaunchFallbacks() }
        .onFailure { XposedBridge.log("[$FB_TAG] Failed game-ad launch fallbacks: ${it.message}") }

    XposedBridge.log("[$FB_TAG] All Facebook ad patches applied")
}

package io.github.nexalloy.revanced.facebook.ads

import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Modifier

/**
 * Removes sponsored content from the Facebook home feed, Stories, and Reels.
 *
 * Hooks:
 *  - List-builder append/factory       → strips ad stories via [AdStoryInspector]
 *  - FbShorts plugin pack              → returns empty list for ad-backed stories
 *  - InstreamBannerEligibility         → disables idle banner ads in Reels player      [NEW]
 *  - IndicatorPillAdEligibility        → disables floating CTA overlay (ReelsAdsFloatingCtaPlugin) [NEW]
 *  - Feed CSR cache filters            → strips sponsored feed items via [FeedItemInspector]
 *  - Late-stage feed sanitizers        → removes sponsored items from storage callbacks
 *    (bao gồm FbShortsCSRStorageLifecycle)                                              [NEW]
 *  - Story pool coordinators           → blocks sponsored items from entering pool
 *    (bao gồm FeedStoryPoolCoordinator)                                                 [NEW]
 *  - Sponsored pool (add / next)       → no-ops pool add; returns null for next()
 *  - Sponsored pool list/result methods→ return empty collections
 *  - Story ad providers (data source + in-disc) → suppress merge, fetch, deferred, insertion
 */
val HideAds = patch(
    name = "Hide feed ads",
    description = "Removes sponsored posts, promoted stories, banner ads, and injected ad units from the Facebook home feed, Stories, and Reels.",
) {
    // ── Shared state ─────────────────────────────────────────────────────────

    val adKindEnumClass = ::adKindEnumClassFingerprint.dexClass.toClass()
    val adInspector = AdStoryInspector(adKindEnumClass)

    // FeedItemInspector needs the item-contract type of each storyPoolAdd method
    val storyPoolAddMethods = ::storyPoolAddFingerprints.dexMethodList
        .mapNotNull { runCatching { it.toMethod() }.getOrNull() }
        .filter { !Modifier.isStatic(it.modifiers) }
    val feedItemInspector = FeedItemInspector(storyPoolAddMethods.map { it.parameterTypes[0] })

    // ── List builder: append ──────────────────────────────────────────────────

    ::listBuilderAppendFingerprint.hookMethod {
        before { param ->
            val list = param.args.getOrNull(5) as? List<*>
            param.setObjectExtra(FB_BEFORE_SIZE_EXTRA, list?.size ?: -1)
        }
        after { param ->
            val beforeSize = param.getObjectExtra(FB_BEFORE_SIZE_EXTRA) as? Int ?: return@after
            val list = param.args.getOrNull(5) as? MutableList<Any?> ?: return@after
            if (beforeSize < 0 || beforeSize > list.size) return@after
            var removed = 0
            for (i in list.lastIndex downTo beforeSize) {
                if (adInspector.containsAdStory(list[i])) { list.removeAt(i); removed++ }
            }
            if (removed > 0) Logger.printDebug { "FB Ads: removed $removed item(s) from list-builder append" }
        }
    }

    // ── List builder: factory ─────────────────────────────────────────────────

    ::listBuilderFactoryFingerprint.hookMethod {
        after { param ->
            val result = param.result as? MutableList<Any?> ?: return@after
            val removed = filterAdItems(result, adInspector)
            if (removed > 0) Logger.printDebug { "FB Ads: removed $removed item(s) from list-builder factory" }
        }
    }

    // ── Plugin pack (FbShorts / Reels) ────────────────────────────────────────

    ::pluginPackMethodFingerprint.hookMethod {
        before { param ->
            if (adInspector.containsAdStory(param.thisObject)) {
                param.result = arrayListOf<Any?>()
                Logger.printDebug { "FB Ads: returned empty plugin pack for ad story" }
            }
        }
        after { param ->
            val result = param.result as? MutableList<Any?> ?: return@after
            val removed = filterAdItems(result, adInspector)
            if (removed > 0) Logger.printDebug { "FB Ads: removed $removed ad plugin item(s)" }
        }
    }

    // ── InstreamAdIdleWithBannerState ─────────────────────────────────────────
    // Chặn banner ads hiển thị khi Reels player ở trạng thái idle.
    // Hook → luôn trả về false (ineligible) để tắt banner.
    //
    // Nguồn: Patches.kt hookInstreamBannerEligibility

    ::instreamBannerEligibilityFingerprint.memberOrNull?.let { method ->
        XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(false))
        Logger.printDebug { "FB Ads: hooked instream banner eligibility → false" }
    }

    // ── IndicatorPillAdEligibility (ReelsAdsFloatingCtaPlugin) ───────────────
    // Chặn floating CTA overlay xuất hiện khi xem Reels ads.
    // Method: static boolean(?, ?, ?) → hook → false.
    //
    // Nguồn: Patches.kt hookIndicatorPillAdEligibility

    ::indicatorPillAdEligibilityFingerprint.memberOrNull?.let { method ->
        XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(false))
        Logger.printDebug { "FB Ads: hooked indicator pill ad eligibility → false" }
    }

    // ── CSR cache filters ─────────────────────────────────────────────────────

    ::feedCsrFilterFingerprints.dexMethodList
        .mapNotNull { runCatching { it.toMethod() }.getOrNull() }
        .filter { !Modifier.isAbstract(it.modifiers) && !it.declaringClass.isInterface }
        .distinctBy { "${it.declaringClass.name}.${it.name}" }
        .forEach { method ->
            method.hookMethod {
                before { param ->
                    val original = param.args.getOrNull(1) as? Iterable<*> ?: return@before
                    val kept = ArrayList<Any?>()
                    var removed = 0
                    for (item in original) {
                        if (feedItemInspector.isSponsoredFeedItem(item)) removed++
                        else kept.add(item)
                    }
                    if (removed <= 0) return@before
                    val rebuilt = buildImmutableListLike(param.args[1], kept) ?: return@before
                    param.args[1] = rebuilt
                    Logger.printDebug { "FB Ads: removed $removed sponsored item(s) before ${method.declaringClass.simpleName}.${method.name}" }
                }
                after { param ->
                    val resultItems = extractFeedItemsFromResult(param.result) ?: return@after
                    val kept = ArrayList<Any?>()
                    var removed = 0
                    for (item in resultItems) {
                        if (feedItemInspector.isSponsoredFeedItem(item)) removed++
                        else kept.add(item)
                    }
                    if (removed > 0 && replaceFeedItemsInResult(param, kept)) {
                        Logger.printDebug { "FB Ads: removed $removed sponsored item(s) from result of ${method.declaringClass.simpleName}.${method.name}" }
                    }
                }
            }
        }

    // ── Late-stage feed sanitizers ────────────────────────────────────────────

    // handleStorageStories — ImmutableList is arg index 1
    ::lateFeedStorageFingerprint.memberOrNull?.let { method ->
        method.hookMethod {
            before { param ->
                val original = param.args.getOrNull(1) as? Iterable<*> ?: return@before
                val kept = original.filterNot { feedItemInspector.isSponsoredFeedItem(it) }
                val removed = (original as? Collection<*>)?.size?.minus(kept.size) ?: return@before
                if (removed <= 0) return@before
                param.args[1] = buildImmutableListLike(param.args[1], kept) ?: return@before
                Logger.printDebug { "FB Ads: late-stage removed $removed item(s) (storage)" }
            }
        }
    }

    // cancelVendingTimerAndAddToPool — ImmutableList is arg index 0
    ::lateFeedVendingFingerprint.memberOrNull?.let { method ->
        method.hookMethod {
            before { param ->
                val original = param.args.getOrNull(0) as? Iterable<*> ?: return@before
                val kept = original.filterNot { feedItemInspector.isSponsoredFeedItem(it) }
                val removed = (original as? Collection<*>)?.size?.minus(kept.size) ?: return@before
                if (removed <= 0) return@before
                param.args[0] = buildImmutableListLike(param.args[0], kept) ?: return@before
                Logger.printDebug { "FB Ads: late-stage removed $removed item(s) (vending)" }
            }
        }
    }

    // CSR storage lifecycle — ImmutableList is arg index 2
    // Bao gồm FbShortsCSRStorageLifecycle (thêm mới từ Patches.kt)
    ::lateFeedStorageLifecycleFingerprints.dexMethodList
        .mapNotNull { runCatching { it.toMethod() }.getOrNull() }
        .filter { !Modifier.isAbstract(it.modifiers) && !it.declaringClass.isInterface }
        .forEach { method ->
            method.hookMethod {
                before { param ->
                    val original = param.args.getOrNull(2) as? Iterable<*> ?: return@before
                    val kept = original.filterNot { feedItemInspector.isSponsoredFeedItem(it) }
                    val removed = (original as? Collection<*>)?.size?.minus(kept.size) ?: return@before
                    if (removed <= 0) return@before
                    param.args[2] = buildImmutableListLike(param.args[2], kept) ?: return@before
                    Logger.printDebug { "FB Ads: late-stage removed $removed item(s) (lifecycle)" }
                }
            }
        }

    // ── Story pool coordinators ───────────────────────────────────────────────
    // Bao gồm FeedStoryPoolCoordinator (thêm mới từ Patches.kt)

    storyPoolAddMethods.forEach { method ->
        method.hookMethod {
            before { param ->
                if (!feedItemInspector.isSponsoredFeedItem(param.args.getOrNull(0))) return@before
                param.result = false
                Logger.printDebug { "FB Ads: blocked sponsored item from story pool (${method.declaringClass.simpleName})" }
            }
        }
    }

    // ── Sponsored pool: add + next ────────────────────────────────────────────

    ::sponsoredPoolAddFingerprint.dexMethod.hookMethod(XC_MethodReplacement.returnConstant(false))

    ::sponsoredStoryNextFingerprint.dexMethod.hookMethod(XC_MethodReplacement.returnConstant(null))

    // ── Sponsored pool: list methods + result carriers ────────────────────────

    val sponsoredPoolClass = runCatching { ::sponsoredPoolClassFingerprint.dexClass.toClass() }.getOrNull()
    sponsoredPoolClass?.let { cls ->
        // Return empty list for any no-arg List getter
        cls.declaredMethods
            .filter { m -> !Modifier.isStatic(m.modifiers) && m.parameterCount == 0 && List::class.java.isAssignableFrom(m.returnType) }
            .forEach { m ->
                m.isAccessible = true
                m.hookMethod { before { param -> param.result = arrayListOf<Any?>() } }
                Logger.printDebug { "FB Ads: hooked pool list method ${cls.simpleName}.${m.name}" }
            }

        // Return empty sponsored result for result-carrier methods
        cls.declaredMethods
            .filter { m ->
                !Modifier.isStatic(m.modifiers) &&
                    isSponsoredResultCarrier(m.returnType) &&
                    (m.parameterCount == 0 ||
                        (m.parameterCount == 1 && m.parameterTypes[0] == Boolean::class.javaPrimitiveType))
            }
            .forEach { m ->
                m.isAccessible = true
                m.hookMethod {
                    before { param ->
                        buildSponsoredEmptyResult(m.returnType)?.let { param.result = it }
                    }
                }
                Logger.printDebug { "FB Ads: hooked pool result method ${cls.simpleName}.${m.name}" }
            }
    }

    // ── Story ads data source ─────────────────────────────────────────────────

    // merge: return original ImmutableList (arg 2) to suppress ad insertion
    ::storyAdsMergeFingerprint.memberOrNull?.hookMethod {
        before { param ->
            param.args.getOrNull(2)?.let { param.result = it }
            Logger.printDebug { "FB Ads: blocked story ad merge (data source)" }
        }
    }
    ::storyAdsFetchFingerprint.memberOrNull?.let { XposedBridge.hookMethod(it, XC_MethodReplacement.DO_NOTHING) }
    ::storyAdsDeferredFingerprint.memberOrNull?.let { XposedBridge.hookMethod(it, XC_MethodReplacement.DO_NOTHING) }

    // ── Story ads in-disc source ──────────────────────────────────────────────

    ::storyAdsInDiscMergeFingerprint.memberOrNull?.hookMethod {
        before { param ->
            param.args.getOrNull(2)?.let { param.result = it }
            Logger.printDebug { "FB Ads: blocked story ad merge (in-disc)" }
        }
    }
    ::storyAdsInDiscFetchFingerprint.memberOrNull?.let { XposedBridge.hookMethod(it, XC_MethodReplacement.DO_NOTHING) }
    ::storyAdsInDiscDeferredFingerprint.memberOrNull?.let { XposedBridge.hookMethod(it, XC_MethodReplacement.DO_NOTHING) }
    ::storyAdsInDiscInsertionFingerprint.memberOrNull?.let { XposedBridge.hookMethod(it, XC_MethodReplacement.DO_NOTHING) }
}

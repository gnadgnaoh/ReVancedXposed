package io.github.nexalloy.revanced.facebook

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ─── Constants ───────────────────────────────────────────────────────────────

const val FB_TAG = "NexAlloy/Facebook"
private const val BEFORE_SIZE_EXTRA = "nexalloy_fb_ads_before_size"
private const val GAME_AD_SUCCESS_INSTANCE_PREFIX = "nexalloy_fb_noop_ad"
private const val HOOK_HIT_LOG_EVERY = 25
private const val GAME_AD_RECENT_WINDOW_MS    = 30_000L
private const val GAME_AD_PROMISE_WINDOW_MS   = 10 * 60_000L
private const val AUDIENCE_NETWORK_REWARD_CLOSE_RETRY_WINDOW_MS = 35_000L

const val GRAPHQL_FEED_UNIT_EDGE_CLASS       = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
const val GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS  = "com.facebook.graphql.model.GraphQLFBMultiAdsFeedUnit"
const val GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS =
    "com.facebook.graphql.model.GraphQLQuickPromotionNativeTemplateFeedUnit"
const val AUDIENCE_NETWORK_ACTIVITY_CLASS        = "com.facebook.ads.AudienceNetworkActivity"
const val AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS = "com.facebook.ads.internal.ipc.AudienceNetworkRemoteActivity"
const val NEKO_PLAYABLE_ACTIVITY_CLASS           = "com.facebook.neko.playables.activity.NekoPlayableAdActivity"

const val GAME_AD_REJECTION_MESSAGE   = "Game ad request blocked"
const val GAME_AD_REJECTION_CODE      = "CLIENT_UNSUPPORTED_OPERATION"
const val GAME_AD_UNAVAILABLE_MESSAGE = "Rewarded ad unavailable"
const val GAME_AD_UNAVAILABLE_CODE    = "ADS_UNAVAILABLE"

val GAME_AD_MESSAGE_TYPES = setOf(
    "getinterstitialadasync", "getrewardedvideoasync", "getrewardedinterstitialasync",
    "loadadasync", "showadasync", "loadbanneradasync", "hidebanneradasync"
)

/** Only these types are auto-fixed (banner/hide); rewarded/interstitial get ADS_UNAVAILABLE. */
val GAME_AD_AUTOFIX_MESSAGE_TYPES = setOf("loadbanneradasync", "hidebanneradasync")

val GAME_AD_UNAVAILABLE_MESSAGE_TYPES = setOf("getrewardedvideoasync", "getrewardedinterstitialasync")

val GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
    AUDIENCE_NETWORK_ACTIVITY_CLASS,
    AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS,
    NEKO_PLAYABLE_ACTIVITY_CLASS
)

val HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES = setOf(NEKO_PLAYABLE_ACTIVITY_CLASS)

val AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES = setOf(
    "onRewardedVideoCompleted", "onRewardedAdCompleted", "onRewardedInterstitialCompleted",
    "onAdComplete", "onAdCompleted"
)

val AUDIENCE_NETWORK_CLOSE_LISTENER_CLASS_NAMES = setOf("X.mGv", "X.mGo", "p000X.mGv", "p000X.mGo")

val FEED_AD_CATEGORY_VALUES          = setOf("SPONSORED", "PROMOTION", "AD", "ADVERTISEMENT", "BANNER")
val FEED_SAFE_CONTAINER_CATEGORY_VALUES = setOf("FB_SHORTS", "MULTI_FB_STORIES_TRAY")

val FEED_AD_SIGNAL_TOKENS = listOf(
    "sponsored", "promotion", "multiads", "quickpromotion",
    "reels_banner_ad", "reelsbannerads", "reels_post_loop_deferred_card", "deferred_card",
    "adbreakdeferredcta", "instreamadidlewithbannerstate", "instream_legacy_banner_ad",
    "unified_player_banner_ad", "banner_ad_", "floatingcta"
)

val REELS_AD_SIGNAL_TOKENS = listOf(
    "sponsored", "promotion", "multiads", "quickpromotion",
    "reels_banner_ad", "reelsbannerads", "adbreakdeferredcta",
    "instreamadidlewithbannerstate", "instream_legacy_banner_ad",
    "unified_player_banner_ad", "banner_ad_"
)

val GAME_AD_METHOD_TAGS = listOf(
    "Invalid JSON content received by onGetInterstitialAdAsync: ",
    "Invalid JSON content received by onGetRewardedInterstitialAsync: ",
    "Invalid JSON content received by onRewardedVideoAsync: ",
    "Invalid JSON content received by onLoadAdAsync: ",
    "Invalid JSON content received by onShowAdAsync: "
)

// ─── Shared state ─────────────────────────────────────────────────────────────

val gameAdInstanceIds    = ConcurrentHashMap<String, String>()
val gameAdInstanceTypes  = ConcurrentHashMap<String, String>()
val gameAdPromiseSnapshots = ConcurrentHashMap<String, GameAdPromiseSnapshot>()
val recentGameAdTargets  = Collections.synchronizedMap(WeakHashMap<Any, Long>())
val recentGameAdPayloads = Collections.synchronizedList(ArrayList<GameAdPayloadSnapshot>())
val hookHitCounters      = ConcurrentHashMap<String, AtomicInteger>()
private val gameAdResultHooksInstalled         = AtomicInteger(0)
private val gameAdServiceDispatchHooksInstalled = AtomicInteger(0)
private val gameAdSurfaceHooksInstalled        = AtomicInteger(0)
private val audienceNetworkRewardHooksInstalled = AtomicInteger(0)
private val audienceNetworkRewardClassesHooked  = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
private val audienceNetworkRewardAdListeners    = Collections.synchronizedMap(WeakHashMap<Any, Any>())
private val scheduledGameAdActivityCloses       = Collections.synchronizedMap(WeakHashMap<Activity, Long>())
private val scheduledAudienceNetworkExitViews   = Collections.synchronizedMap(WeakHashMap<View, Long>())
private val lastGameAdActivityCloseMs    = AtomicLong(0L)
private val lastUnavailableGameAdMs      = AtomicLong(0L)
private val marketplaceAdsPackCache      = ConcurrentHashMap<String, Boolean>()

// ─── Data classes ─────────────────────────────────────────────────────────────

data class FeedListSanitizerHook(val method: Method, val listArgIndex: Int)

data class StoryAdProviderHooks(
    val providerClass: Class<*>,
    val mergeMethod: Method?,
    val fetchMoreAdsMethod: Method?,
    val deferredUpdateMethod: Method?,
    val insertionTriggerMethod: Method?
)

data class GameAdPayloadSnapshot(
    val target: Any,
    val payload: JSONObject,
    val messageType: String?,
    val timestampMs: Long
)

data class GameAdPromiseSnapshot(
    val payload: JSONObject,
    val messageType: String?,
    val timestampMs: Long
)

// ─── AdStoryInspector ─────────────────────────────────────────────────────────

class AdStoryInspector(private val adKindEnumClass: Class<*>) {
    private val enumMethodCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val fieldCache      = ConcurrentHashMap<Class<*>, List<Field>>()

    fun containsAdStory(
        value: Any?, depth: Int = 0, seen: IdentityHashMap<Any, Boolean> = IdentityHashMap()
    ): Boolean = containsAdKind(value, depth, seen) && containsReelsAdSignal(value, 0, IdentityHashMap())

    private fun containsAdKind(value: Any?, depth: Int, seen: IdentityHashMap<Any, Boolean>): Boolean {
        if (value == null || depth > 4) return false
        if (isAdKind(value)) return true
        val type = value.javaClass
        if (type.isPrimitive || value is String || value is Number || value is Boolean || value is CharSequence) return false
        if (seen.put(value, true) != null) return false
        if (value is Iterable<*>) { var n = 0; for (i in value) { if (containsAdKind(i, depth+1, seen)) return true; if (++n >= 8) break } }
        if (type.isArray) { val a = value as? Array<*>; if (a != null) { var n = 0; for (i in a) { if (containsAdKind(i, depth+1, seen)) return true; if (++n >= 8) break } } }
        for (m in enumMethodsFor(type)) if (isAdKind(runCatching { m.invoke(value) }.getOrNull())) return true
        for (f in fieldsFor(type)) if (containsAdKind(runCatching { f.get(value) }.getOrNull(), depth+1, seen)) return true
        return false
    }

    private fun containsReelsAdSignal(value: Any?, depth: Int, seen: IdentityHashMap<Any, Boolean>): Boolean {
        if (value == null || depth > 4) return false
        if (value is CharSequence) return isReelsAdSignalText(value.toString())
        val type = value.javaClass
        if (isReelsAdSignalText(type.name)) return true
        if (type.isEnum) return isReelsAdSignalText(value.toString())
        if (type.isPrimitive || value is Number || value is Boolean) return false
        if (seen.put(value, true) != null) return false
        if (value is Iterable<*>) { var n = 0; for (i in value) { if (containsReelsAdSignal(i, depth+1, seen)) return true; if (++n >= 8) break } }
        if (type.isArray) { val a = value as? Array<*>; if (a != null) { var n = 0; for (i in a) { if (containsReelsAdSignal(i, depth+1, seen)) return true; if (++n >= 8) break } } }
        if (isReelsAdSignalText(runCatching { value.toString() }.getOrNull())) return true
        for (m in stringMethodsFor(type)) if (isReelsAdSignalText(runCatching { m.invoke(value) as? String }.getOrNull())) return true
        for (f in fieldsFor(type)) if (containsReelsAdSignal(runCatching { f.get(value) }.getOrNull(), depth+1, seen)) return true
        return false
    }

    private fun isAdKind(v: Any?) = v != null && v.javaClass == adKindEnumClass && v.toString() == "AD"

    private fun enumMethodsFor(type: Class<*>) = enumMethodCache.getOrPut(type) {
        val map = LinkedHashMap<String, Method>()
        var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java) {
            cur.declaredMethods.forEach { m ->
                if (!Modifier.isStatic(m.modifiers) && m.parameterCount == 0 && m.returnType == adKindEnumClass) {
                    m.isAccessible = true; map.putIfAbsent("${cur.name}#${m.name}", m)
                }
            }; cur = cur.superclass
        }; map.values.toList()
    }

    private fun fieldsFor(type: Class<*>) = fieldCache.getOrPut(type) {
        val list = ArrayList<Field>(); var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java && list.size < 24) {
            cur.declaredFields.forEach { f -> if (!Modifier.isStatic(f.modifiers) && list.size < 24) { f.isAccessible = true; list.add(f) } }; cur = cur.superclass
        }; list
    }

    private fun stringMethodsFor(type: Class<*>) = allMethodsFor(type).asSequence()
        .filter { m -> m.parameterCount == 0 && m.returnType == String::class.java && m.name != "toString" }
        .take(12).onEach { it.isAccessible = true }.toList()

    private fun allMethodsFor(type: Class<*>): List<Method> {
        val map = LinkedHashMap<String, Method>(); var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java) {
            cur.declaredMethods.forEach { m -> if (!Modifier.isStatic(m.modifiers)) { m.isAccessible = true; map.putIfAbsent("${cur.name}#${m.name}/${m.parameterCount}", m) } }; cur = cur.superclass
        }; return map.values.toList()
    }

    private fun isReelsAdSignalText(v: String?): Boolean {
        if (v.isNullOrBlank()) return false
        val n = v.lowercase(); return REELS_AD_SIGNAL_TOKENS.any { n.contains(it) }
    }
}

// ─── FeedItemInspector ────────────────────────────────────────────────────────

class FeedItemInspector(itemContractTypes: Collection<Class<*>>) {
    private val itemModelAccessor    = resolveItemModelAccessor(itemContractTypes)
    private val itemEdgeAccessor     = resolveItemEdgeAccessor(itemContractTypes)
    private val itemNetworkAccessor  = resolveItemNetworkAccessor(itemContractTypes)
    private val categoryMethodCache  = ConcurrentHashMap<Class<*>, Method>()
    private val edgeAccessorCache    = ConcurrentHashMap<Class<*>, Method>()
    private val feedUnitAccessorCache = ConcurrentHashMap<Class<*>, Method>()
    private val typeNameMethodCache  = ConcurrentHashMap<Class<*>, Method>()
    private val stringAccessorCache  = ConcurrentHashMap<Class<*>, List<Method>>()
    private val stringFieldCache     = ConcurrentHashMap<Class<*>, List<Field>>()

    fun isSponsoredFeedItem(value: Any?): Boolean {
        if (isDefinitelySponsoredFeedItem(value)) return true
        val model = invokeNoThrow(itemModelAccessor, value); val edge = edgeFrom(value); val feedUnit = feedUnitFrom(edge)
        return containsKnownAdSignals(value) || containsKnownAdSignals(model) || containsKnownAdSignals(edge) || containsKnownAdSignals(feedUnit)
    }

    fun isDefinitelySponsoredFeedItem(value: Any?): Boolean {
        if (value == null) return false
        val model = invokeNoThrow(itemModelAccessor, value); val modelCategory = readCategory(model)
        if (isSafeFeedContainerCategory(modelCategory)) return false
        if (isSponsoredFeedCategory(modelCategory)) return true
        val edge = edgeFrom(value); val edgeCategory = readCategory(edge)
        if (isSafeFeedContainerCategory(edgeCategory)) return false
        if (isSponsoredFeedCategory(edgeCategory)) return true
        val feedUnit = feedUnitFrom(edge); val unitClassName = feedUnit?.javaClass?.name
        if (unitClassName == GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS || unitClassName == GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS) return true
        val typeName = readTypeName(feedUnit)
        return isLikelyAdTypeName(typeName) || isAdSignalText(unitClassName)
    }

    fun describe(item: Any?): String {
        if (item == null) return "null"
        val edge = edgeFrom(item); val feedUnit = feedUnitFrom(edge)
        val category = readCategory(invokeNoThrow(itemModelAccessor, item)) ?: readCategory(edge) ?: "unknown"
        val network = invokeNoThrow(itemNetworkAccessor, item)?.toString() ?: "unknown"
        return "cat=$category isAd=${isSponsoredFeedItem(item)} network=$network wrapper=${item.javaClass.name} unit=${feedUnit?.javaClass?.name ?: "null"} type=${readTypeName(feedUnit) ?: "unknown"}"
    }

    private fun edgeFrom(value: Any?): Any? {
        if (value == null) return null
        if (value.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS) return value
        invokeNoThrow(itemEdgeAccessor, value)?.let { d -> if (d.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS) return d }
        val fallback = cachedMethod(edgeAccessorCache, value.javaClass) {
            resolveChildAccessor(value) { it != null && it.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS }
        }
        return invokeNoThrow(fallback, value)
    }

    private fun feedUnitFrom(edge: Any?): Any? {
        if (edge == null) return null
        val accessor = cachedMethod(feedUnitAccessorCache, edge.javaClass) {
            resolveChildAccessor(edge) { v ->
                val cn = v?.javaClass?.name
                cn == GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS || cn == GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS ||
                readTypeName(v)?.let { it != "FeedUnitEdge" } == true
            }
        }
        return invokeNoThrow(accessor, edge)
    }

    private fun readCategory(value: Any?): String? {
        if (value == null) return null
        if (value.javaClass.isEnum) return value.toString()
        val accessor = cachedMethod(categoryMethodCache, value.javaClass) {
            allInstanceMethods(value.javaClass).firstOrNull { m ->
                m.parameterCount == 0 && m.returnType.isEnum &&
                m.returnType.enumConstants?.any { val n = it.toString(); n == "SPONSORED" || n == "PROMOTION" } == true
            }?.apply { isAccessible = true }
        }
        return invokeNoThrow(accessor, value)?.toString()
    }

    private fun readTypeName(value: Any?): String? {
        if (value == null) return null
        val accessor = cachedMethod(typeNameMethodCache, value.javaClass) {
            allInstanceMethods(value.javaClass).firstOrNull { m ->
                m.parameterCount == 0 && m.returnType == String::class.java && m.name == "getTypeName"
            }?.apply { isAccessible = true }
        }
        return invokeNoThrow(accessor, value) as? String
    }

    private fun cachedMethod(cache: ConcurrentHashMap<Class<*>, Method>, type: Class<*>, resolver: () -> Method?): Method? {
        cache[type]?.let { return it }; val resolved = resolver() ?: return null
        return cache.putIfAbsent(type, resolved) ?: resolved
    }

    private fun resolveItemModelAccessor(types: Collection<Class<*>>) = types.asSequence()
        .flatMap { allInstanceMethods(it).asSequence() }
        .firstOrNull { m -> m.parameterCount == 0 && !m.returnType.isPrimitive && m.returnType != Any::class.java && m.returnType != String::class.java && !m.returnType.isEnum }
        ?.apply { isAccessible = true }

    private fun resolveItemEdgeAccessor(types: Collection<Class<*>>) = types.asSequence()
        .flatMap { allInstanceMethods(it).asSequence() }
        .firstOrNull { m -> m.parameterCount == 0 && (m.returnType == Any::class.java || m.returnType.name == GRAPHQL_FEED_UNIT_EDGE_CLASS) }
        ?.apply { isAccessible = true }

    private fun resolveItemNetworkAccessor(types: Collection<Class<*>>) = types.asSequence()
        .flatMap { allInstanceMethods(it).asSequence() }
        .firstOrNull { m -> m.parameterCount == 0 && m.returnType == Boolean::class.javaPrimitiveType }
        ?.apply { isAccessible = true }

    private fun resolveChildAccessor(target: Any, acceptsValue: (Any?) -> Boolean): Method? =
        allInstanceMethods(target.javaClass).asSequence()
            .filter { m -> m.parameterCount == 0 && !m.returnType.isPrimitive && m.returnType != Void.TYPE && m.returnType != String::class.java && !m.returnType.isEnum && m.declaringClass != Any::class.java }
            .sortedByDescending { m -> when { m.returnType.name == GRAPHQL_FEED_UNIT_EDGE_CLASS -> 4; m.returnType.name.startsWith("com.facebook.graphql.model.") -> 3; m.returnType.name.startsWith("com.facebook.") -> 2; !m.returnType.name.startsWith("java.") && !m.returnType.name.startsWith("android.") && !m.returnType.name.startsWith("kotlin.") -> 1; else -> 0 } }
            .firstOrNull { m -> acceptsValue(invokeNoThrow(m.apply { isAccessible = true }, target)) }

    private fun containsKnownAdSignals(value: Any?): Boolean {
        if (value == null) return false
        if (value is CharSequence) return isAdSignalText(value.toString())
        val type = value.javaClass
        if (isAdSignalText(type.name)) return true
        if (type.isEnum) return isAdSignalText(value.toString())
        if (type.isPrimitive || value is Number || value is Boolean) return false
        if (isAdSignalText(runCatching { value.toString() }.getOrNull())) return true
        for (m in stringAccessorsFor(type)) if (isAdSignalText(invokeNoThrow(m, value) as? String)) return true
        for (f in stringFieldsFor(type)) if (isAdSignalText(runCatching { f.get(value) as? String }.getOrNull())) return true
        return false
    }

    private fun stringAccessorsFor(type: Class<*>) = stringAccessorCache.getOrPut(type) {
        allInstanceMethods(type).asSequence()
            .filter { m -> m.parameterCount == 0 && m.returnType == String::class.java && m.declaringClass != Any::class.java && m.name != "toString" }
            .take(12).onEach { m -> m.isAccessible = true }.toList()
    }

    private fun stringFieldsFor(type: Class<*>) = stringFieldCache.getOrPut(type) {
        val list = ArrayList<Field>(); var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java && list.size < 12) {
            cur.declaredFields.forEach { f -> if (!Modifier.isStatic(f.modifiers) && f.type == String::class.java && list.size < 12) { f.isAccessible = true; list.add(f) } }; cur = cur.superclass
        }; list
    }

    fun isAdSignalText(value: String?): Boolean {
        if (value.isNullOrBlank()) return false; val n = value.lowercase()
        return FEED_AD_SIGNAL_TOKENS.any { n.contains(it) }
    }

    private fun isSponsoredFeedCategory(v: String?)    = v != null && v in FEED_AD_CATEGORY_VALUES
    private fun isSafeFeedContainerCategory(v: String?) = v != null && v in FEED_SAFE_CONTAINER_CATEGORY_VALUES
    private fun isLikelyAdTypeName(v: String?)          = v != null && (v.contains("QuickPromotion", ignoreCase = true) || isAdSignalText(v))

    private fun allInstanceMethods(type: Class<*>): List<Method> {
        val map = LinkedHashMap<String, Method>(); var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java) {
            cur.declaredMethods.forEach { m -> if (!Modifier.isStatic(m.modifiers)) { m.isAccessible = true; map.putIfAbsent("${cur.name}#${m.name}/${m.parameterCount}", m) } }
            cur.interfaces.forEach { iface -> iface.declaredMethods.forEach { m -> if (!Modifier.isStatic(m.modifiers)) { m.isAccessible = true; map.putIfAbsent("${iface.name}#${m.name}/${m.parameterCount}", m) } } }
            cur = cur.superclass
        }; return map.values.toList()
    }

    private fun invokeNoThrow(method: Method?, target: Any?) =
        if (method == null || target == null) null else runCatching { method.invoke(target) }.getOrNull()
}

// ─── Logging ──────────────────────────────────────────────────────────────────

fun logHookHitThrottled(hookName: String, method: Method, detail: String? = null) {
    val hits = hookHitCounters.computeIfAbsent(hookName) { AtomicInteger(0) }.incrementAndGet()
    if (hits <= 3 || hits % HOOK_HIT_LOG_EVERY == 0)
        XposedBridge.log("[$FB_TAG] Hit $hookName #$hits at ${method.declaringClass.name}.${method.name}${detail?.let { " $it" } ?: ""}")
}

// ─── Hook installers – Reels / list-builder ───────────────────────────────────

fun hookListBuilderAppend(method: Method, inspector: AdStoryInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.setObjectExtra(BEFORE_SIZE_EXTRA, (param.args.getOrNull(5) as? List<*>)?.size ?: -1)
        }
        override fun afterHookedMethod(param: MethodHookParam) {
            val beforeSize = param.getObjectExtra(BEFORE_SIZE_EXTRA) as? Int ?: return
            val list = param.args.getOrNull(5) as? MutableList<Any?> ?: return
            if (beforeSize < 0 || beforeSize > list.size) return
            var removed = 0
            for (i in list.lastIndex downTo beforeSize) { if (inspector.containsAdStory(list[i])) { list.removeAt(i); removed++ } }
            if (removed > 0) XposedBridge.log("[$FB_TAG] Removed $removed ad item(s) from upstream list append")
        }
    })
}

fun hookListResultFilter(method: Method, source: String, inspector: AdStoryInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val result = param.result as? MutableList<Any?> ?: return
            val removed = filterAdItems(result, inspector)
            if (removed > 0) XposedBridge.log("[$FB_TAG] Removed $removed ad item(s) from $source")
        }
    })
}

fun hookPluginPackFallback(method: Method, inspector: AdStoryInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (isMarketplaceAdsPluginPack(param.thisObject)) {
                XposedBridge.log("[$FB_TAG] Returning empty plugin pack for marketplace ads")
                param.result = arrayListOf<Any?>(); return
            }
            if (inspector.containsAdStory(param.thisObject)) {
                XposedBridge.log("[$FB_TAG] Returning empty plugin pack for ad-backed story")
                param.result = arrayListOf<Any?>()
            }
        }
        override fun afterHookedMethod(param: MethodHookParam) {
            if (isMarketplaceAdsPluginPack(param.thisObject)) return
            val result = param.result as? MutableList<Any?> ?: return
            val removed = filterAdItems(result, inspector)
            if (removed > 0) XposedBridge.log("[$FB_TAG] Removed $removed ad plugin item(s)")
        }
    })
}

private fun isMarketplaceAdsPluginPack(instance: Any): Boolean {
    val className = instance.javaClass.name
    return marketplaceAdsPackCache.getOrPut(className) {
        runCatching {
            instance.javaClass.declaredMethods
                .filter { m -> m.parameterCount == 0 && m.returnType == String::class.java && !Modifier.isStatic(m.modifiers) }
                .any { m -> m.isAccessible = true; (m.invoke(instance) as? String)?.contains("Ads", ignoreCase = true) == true }
        }.getOrDefault(false)
    }
}

// ─── Hook installers – Feed CSR / late-list ───────────────────────────────────

fun hookFeedCsrFilterInput(method: Method, inspector: FeedItemInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val originalList = param.args.getOrNull(1) as? Iterable<*> ?: return
            val kept = ArrayList<Any?>(); var removed = 0
            for (item in originalList) { if (inspector.isSponsoredFeedItem(item)) removed++ else kept.add(item) }
            if (removed <= 0) return
            buildImmutableListLike(param.args.getOrNull(1), kept)?.let { param.args[1] = it }
            XposedBridge.log("[$FB_TAG] Removed $removed sponsored item(s) before ${method.declaringClass.name}.${method.name}")
        }
        override fun afterHookedMethod(param: MethodHookParam) {
            val resultItems = extractFeedItemsFromResult(param.result) ?: return
            val kept = ArrayList<Any?>(); var removed = 0
            for (item in resultItems) { if (inspector.isSponsoredFeedItem(item)) removed++ else kept.add(item) }
            if (removed > 0 && replaceFeedItemsInResult(param, kept))
                XposedBridge.log("[$FB_TAG] Removed $removed sponsored item(s) from result of ${method.declaringClass.name}.${method.name}")
        }
    })
}

fun hookLateFeedListSanitizer(hook: FeedListSanitizerHook, inspector: FeedItemInspector) {
    XposedBridge.hookMethod(hook.method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val originalList = param.args.getOrNull(hook.listArgIndex) as? Iterable<*> ?: return
            val kept = ArrayList<Any?>(); var removed = 0
            for (item in originalList) { if (inspector.isSponsoredFeedItem(item)) removed++ else kept.add(item) }
            if (removed <= 0) return
            buildImmutableListLike(param.args.getOrNull(hook.listArgIndex), kept)?.let {
                param.args[hook.listArgIndex] = it
                XposedBridge.log("[$FB_TAG] Late-removed $removed sponsored item(s) before ${hook.method.declaringClass.name}.${hook.method.name}")
            }
        }
    })
}

fun hookStoryPoolAdd(method: Method, inspector: FeedItemInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val item = param.args.getOrNull(0)
            if (!inspector.isDefinitelySponsoredFeedItem(item)) return
            param.result = false
            logHookHitThrottled("storyPoolBlock", method, inspector.describe(item))
        }
    })
}

fun hookInstreamBannerEligibility(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) { logHookHitThrottled("bannerState", method); param.result = false }
    })
}

fun hookIndicatorPillAdEligibility(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) { logHookHitThrottled("indicatorPill", method, "slot=${param.args.getOrNull(2) ?: "?"}"); param.result = false }
    })
}

fun hookReelsBannerRender(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) { logHookHitThrottled("reelsBannerRender", method); param.result = null }
    })
}

// ─── Hook installers – Sponsored pool ────────────────────────────────────────

fun hookSponsoredPoolAdd(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) { param.result = false }
    })
}

fun hookSponsoredStoryNext(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
    })
}

fun hookSponsoredPoolListMethods(poolClass: Class<*>) {
    var hooked = 0
    poolClass.declaredMethods.filter { m -> !Modifier.isStatic(m.modifiers) && m.parameterCount == 0 && List::class.java.isAssignableFrom(m.returnType) }.forEach { m ->
        m.isAccessible = true
        XposedBridge.hookMethod(m, object : XC_MethodHook() { override fun beforeHookedMethod(param: MethodHookParam) { param.result = arrayListOf<Any?>() } })
        hooked++
    }
    XposedBridge.log("[$FB_TAG] Hooked $hooked pool list method(s) on ${poolClass.name}")
}

fun hookSponsoredPoolResultMethods(poolClass: Class<*>) {
    var hooked = 0
    poolClass.declaredMethods.filter { m ->
        !Modifier.isStatic(m.modifiers) && isSponsoredResultCarrier(m.returnType) &&
        (m.parameterCount == 0 || (m.parameterCount == 1 && m.parameterTypes[0] == Boolean::class.javaPrimitiveType))
    }.forEach { m ->
        m.isAccessible = true
        XposedBridge.hookMethod(m, object : XC_MethodHook() { override fun beforeHookedMethod(param: MethodHookParam) { buildSponsoredEmptyResult(m.returnType)?.let { param.result = it } } })
        hooked++
    }
    XposedBridge.log("[$FB_TAG] Hooked $hooked pool result method(s) on ${poolClass.name}")
}

// ─── Hook installers – Story ad providers ────────────────────────────────────

fun hookStoryAdsNoOp(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() { override fun beforeHookedMethod(param: MethodHookParam) { param.result = null } })
}

fun hookStoryAdsMerge(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) { param.args.getOrNull(2)?.let { param.result = it } }
    })
}

fun hookStoryAdProvider(provider: StoryAdProviderHooks) {
    provider.mergeMethod?.let { hookStoryAdsMerge(it) }
    provider.fetchMoreAdsMethod?.let { hookStoryAdsNoOp(it) }
    provider.deferredUpdateMethod?.let { hookStoryAdsNoOp(it) }
    provider.insertionTriggerMethod?.let { hookStoryAdsNoOp(it) }
}

// ─── Hook installers – Game ads ───────────────────────────────────────────────

fun hookGameAdRequest(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val payload = param.args.getOrNull(0) ?: return
            val messageType = inferGameAdMessageType(method, payload)
            rememberGameAdPayload(param.thisObject, payload, messageType)
            if (rejectUnavailableGameAdPayloadIfNeeded(param.thisObject, payload, messageType)) { param.result = null; return }
            if (!shouldAutofixGameAdMessage(messageType)) return
            if (resolveGameAdPayload(param.thisObject, payload, messageType)) {
                dispatchPostResolveGameAdSignals(param.thisObject, payload, messageType)
                param.result = null
            } else if (rejectGameAdPayload(param.thisObject, payload)) {
                param.result = null
            }
        }
    })
}

fun hookGameAdBridge(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val raw = param.args.getOrNull(0) as? String ?: return
            val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return
            val type = payload.optString("type"); if (type !in GAME_AD_MESSAGE_TYPES) return
            rememberGameAdPayload(param.thisObject, payload, type)
            if (rejectUnavailableGameAdPayloadIfNeeded(param.thisObject, payload, type)) { param.result = null; return }
            if (!shouldAutofixGameAdMessage(type)) return
            if (resolveGameAdPayload(param.thisObject, payload, type)) {
                dispatchPostResolveGameAdSignals(param.thisObject, payload, type)
                param.result = null
            } else if (rejectGameAdPayload(param.thisObject, payload)) {
                param.result = null
            }
        }
    })
}

/** Hook resolve/reject methods on the bridge class for deeper interception. */
fun hookGameAdResultMethods(bridgeClass: Class<*>) {
    if (!gameAdResultHooksInstalled.compareAndSet(0, 1)) return
    val resolveMethod = resolveGameAdResolveMethod(bridgeClass)
    val rejectMethod  = resolveGameAdRejectMethod(bridgeClass)
    val bridgeRejectMethod = resolveGameAdBridgeRejectMethod(bridgeClass)
    var hooked = 0

    resolveMethod?.let { m ->
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val promiseId = param.args.getOrNull(0) as? String ?: return
                val snapshot = gameAdPromiseSnapshots[promiseId] ?: return
                if (snapshot.messageType !in GAME_AD_MESSAGE_TYPES) return
                if (!shouldAutofixGameAdMessage(snapshot.messageType)) return
                val original = param.args.getOrNull(1)
                param.args[1] = forceGameAdSuccessResult(promiseId, original, snapshot.payload, snapshot.messageType)
            }
        }); hooked++
    }

    if (rejectMethod != null && resolveMethod != null) {
        XposedBridge.hookMethod(rejectMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val promiseId = param.args.getOrNull(0) as? String ?: return
                val reason = param.args.drop(1).joinToString(" ") { it?.toString().orEmpty() }
                if (!shouldConvertGameAdRejectToSuccess(promiseId, reason)) return
                val snapshot = gameAdPromiseSnapshots[promiseId]
                val success = forceGameAdSuccessResult(promiseId, null, snapshot?.payload, snapshot?.messageType ?: gameAdPromiseTypeFromReason(reason))
                runCatching { XposedBridge.invokeOriginalMethod(resolveMethod, param.thisObject, arrayOf(promiseId, success)); param.result = null }
            }
        }); hooked++
    }

    if (bridgeRejectMethod != null && resolveMethod != null && bridgeRejectMethod != rejectMethod) {
        XposedBridge.hookMethod(bridgeRejectMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val payload = param.args.getOrNull(2) as? JSONObject ?: return
                val promiseId = extractPromiseId(payload) ?: return
                val reason = param.args.take(2).joinToString(" ") { it?.toString().orEmpty() }
                if (!shouldConvertGameAdRejectToSuccess(promiseId, reason)) return
                val snapshot = gameAdPromiseSnapshots[promiseId]
                val success = forceGameAdSuccessResult(promiseId, null, snapshot?.payload ?: payload, snapshot?.messageType ?: gameAdPromiseTypeFromReason(reason))
                runCatching { XposedBridge.invokeOriginalMethod(resolveMethod, param.thisObject, arrayOf(promiseId, success)); param.result = null }
            }
        }); hooked++
    }
    XposedBridge.log("[$FB_TAG] Hooked $hooked game ad result helper(s) on ${bridgeClass.name}")
}

/** Hook Bundle-based service dispatch methods on the bridge class. */
fun hookGameAdServiceDispatchMethods(bridgeClass: Class<*>) {
    if (!gameAdServiceDispatchHooksInstalled.compareAndSet(0, 1)) return
    val methods = (bridgeClass.declaredMethods + bridgeClass.methods).filter { m ->
        !Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE && m.parameterCount == 2 && m.parameterTypes[0] == Bundle::class.java
    }.distinctBy { m -> m.name + m.parameterTypes.joinToString { it.name } }
    var hooked = 0
    methods.forEach { m ->
        m.isAccessible = true
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val bundle = param.args.getOrNull(0) as? Bundle ?: return
                val messageType = param.args.getOrNull(1)?.toString()?.lowercase()?.takeIf { it in GAME_AD_MESSAGE_TYPES } ?: return
                val payload = buildGameAdPayloadFromServiceBundle(bundle, messageType)
                rememberGameAdPayload(param.thisObject, payload, messageType)
                if (rejectUnavailableGameAdPayloadIfNeeded(param.thisObject, payload, messageType)) { param.result = null; return }
                if (!shouldAutofixGameAdMessage(messageType)) return
                if (resolveGameAdPayload(param.thisObject, payload, messageType)) {
                    dispatchPostResolveGameAdSignals(param.thisObject, payload, messageType); param.result = null
                }
            }
        }); hooked++
    }
    XposedBridge.log("[$FB_TAG] Hooked $hooked game ad service dispatch method(s) on ${bridgeClass.name}")
}

fun hookPlayableAdActivity(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as? Activity ?: return
            if (activity.javaClass.name != method.declaringClass.name) return
            handleGameAdActivity(activity, "direct hook ${method.declaringClass.name}.${method.name}")
        }
    })
}

fun hookGlobalGameAdActivityLifecycleFallback() {
    val onResume = (Activity::class.java.declaredMethods + Activity::class.java.methods)
        .firstOrNull { m -> m.name == "onResume" && m.parameterCount == 0 }?.apply { isAccessible = true } ?: return
    XposedBridge.hookMethod(onResume, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as? Activity ?: return
            // Always schedule a surface sweep on resume (catches async ad loads)
            scheduleGameAdSurfaceSweep(activity.window?.decorView, "activity resume ${activity.javaClass.name}")
            if (activity.javaClass.name !in GAME_AD_ACTIVITY_CLASS_NAMES) return
            handleGameAdActivity(activity, "global lifecycle fallback")
        }
    })
    XposedBridge.log("[$FB_TAG] Hooked global game ad activity lifecycle fallback")
}

fun hookGameAdActivityLaunchFallbacks() {
    val methods = LinkedHashMap<String, Method>()
    listOf(Instrumentation::class.java, Activity::class.java, ContextWrapper::class.java).forEach { type ->
        (type.declaredMethods + type.methods).filter { m ->
            m.name in setOf("execStartActivity","startActivity","startActivityForResult","startActivityIfNeeded") &&
            m.parameterTypes.any { it == Intent::class.java }
        }.forEach { m -> m.isAccessible = true; methods.putIfAbsent("${m.declaringClass.name}.${m.name}(${m.parameterTypes.joinToString(",") { it.name }})", m) }
    }
    var hooked = 0
    methods.values.forEach { m ->
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                    val target = intent.component?.className ?: return
                    if (target !in GAME_AD_ACTIVITY_CLASS_NAMES) return
                    if (!shouldBlockGameAdActivityLaunch(target)) return
                    completeRecentGameAdRequests("launch fallback $target")
                    param.result = if (m.returnType == Boolean::class.javaPrimitiveType) false else null
                }
            }); hooked++
        }
    }
    XposedBridge.log("[$FB_TAG] Hooked $hooked game ad activity launch fallback(s)")
}

/** Hook ViewGroup.addView, TextView.setText, WebView methods to catch native ad views. */
fun hookGlobalGameAdSurfaceFallbacks() {
    if (!gameAdSurfaceHooksInstalled.compareAndSet(0, 1)) return
    var hooked = 0

    (ViewGroup::class.java.declaredMethods + ViewGroup::class.java.methods)
        .filter { m -> m.name == "addView" && m.parameterTypes.any { it == View::class.java } }
        .distinctBy { m -> m.name + m.parameterTypes.joinToString { it.name } }
        .forEach { m ->
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val child = param.args.firstOrNull { it is View } as? View ?: return
                    if (isPotentialNativeGameAdView(child)) {
                        hideLikelyGameAdContainer(child, "native ad view add ${child.javaClass.name}")
                        scheduleGameAdSurfaceSweep(child, "native ad view add ${child.javaClass.name}")
                    } else if (child is WebView) {
                        injectGameAdHidingScript(child)
                    }
                }
            }); hooked++
        }

    (TextView::class.java.declaredMethods + TextView::class.java.methods)
        .filter { m -> m.name == "setText" && m.parameterTypes.isNotEmpty() && CharSequence::class.java.isAssignableFrom(m.parameterTypes[0]) }
        .distinctBy { m -> m.name + m.parameterTypes.joinToString { it.name } }
        .forEach { m ->
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val tv = param.thisObject as? TextView ?: return
                    if (isGameAdMarkerText(tv.text)) hideLikelyGameAdContainer(tv, "ad marker text ${m.name}")
                }
            }); hooked++
        }

    (WebView::class.java.declaredMethods + WebView::class.java.methods)
        .filter { m -> m.name in setOf("loadUrl","loadData","loadDataWithBaseURL","onAttachedToWindow") }
        .distinctBy { m -> m.name + m.parameterTypes.joinToString { it.name } }
        .forEach { m ->
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val wv = param.thisObject as? WebView ?: return
                    injectGameAdHidingScript(wv)
                    scheduleGameAdSurfaceSweep(wv, "webview ${m.name}")
                }
            }); hooked++
        }

    XposedBridge.log("[$FB_TAG] Hooked $hooked global game ad surface fallback method(s)")
}

/** Hook Audience Network reward classes to fire completion callbacks. */
fun hookAudienceNetworkRewardFallbacks(classLoader: ClassLoader) {
    if (!audienceNetworkRewardHooksInstalled.compareAndSet(0, 1)) return

    listOf(
        "com.facebook.ads.RewardedVideoAd",
        "com.facebook.ads.RewardedInterstitialAd",
        "com.facebook.ads.RewardedVideoAdListener",
        "com.facebook.ads.RewardedInterstitialAdListener",
        "com.facebook.ads.RewardedVideoAd\$RewardedVideoAdLoadConfigBuilder",
        "com.facebook.ads.RewardedInterstitialAd\$RewardedInterstitialAdLoadConfigBuilder"
    ).forEach { cn -> runCatching { tryHookAudienceNetworkRewardClass(classLoader.loadClass(cn)) } }

    (ClassLoader::class.java.declaredMethods + ClassLoader::class.java.methods)
        .filter { m -> m.name == "loadClass" && m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == String::class.java }
        .distinctBy { m -> m.name + m.parameterTypes.joinToString { it.name } }
        .forEach { m ->
            m.isAccessible = true
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val clazz = param.result as? Class<*> ?: return
                    if (isAudienceNetworkRewardRelevantClass(clazz.name)) tryHookAudienceNetworkRewardClass(clazz)
                }
            })
        }
    XposedBridge.log("[$FB_TAG] Hooked Audience Network reward dynamic class fallback")
}

private fun tryHookAudienceNetworkRewardClass(clazz: Class<*>) {
    val className = clazz.name
    if (!isAudienceNetworkRewardRelevantClass(className) || !audienceNetworkRewardClassesHooked.add(className)) return
    var hooked = 0
    val methods = runCatching { clazz.declaredMethods + clazz.methods }.getOrDefault(emptyArray())
    methods.distinctBy { m -> m.name + m.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name } }
        .forEach { m ->
            runCatching {
                m.isAccessible = true
                if (isAudienceNetworkRewardShowMethod(clazz, m)) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val adObject = param.thisObject ?: return
                            if (!completeAudienceNetworkRewardObject(adObject, "show ${clazz.name}.${m.name}")) return
                            param.result = when (m.returnType) {
                                Boolean::class.javaPrimitiveType, Boolean::class.java -> true
                                else -> null
                            }
                        }
                    }); hooked++
                } else if (isAudienceNetworkRewardListenerRegistrationMethod(m)) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) { rememberAudienceNetworkRewardListeners(param.thisObject, param.args, m) }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            rememberAudienceNetworkRewardListeners(param.thisObject, param.args, m)
                            rememberAudienceNetworkRewardListeners(param.result, param.args, m)
                        }
                    }); hooked++
                } else if (isAudienceNetworkRewardLoadMethod(clazz, m)) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) { rememberAudienceNetworkRewardListeners(param.thisObject, param.args, m) }
                    }); hooked++
                }
            }.onFailure { XposedBridge.log("[$FB_TAG] Failed to hook AN reward method ${clazz.name}.${m.name}: ${it.message}") }
        }
    if (hooked > 0) XposedBridge.log("[$FB_TAG] Hooked $hooked Audience Network reward method(s) in $className")
}

private fun isAudienceNetworkRewardLoadMethod(clazz: Class<*>, method: Method) =
    clazz.name.lowercase().contains("reward") &&
    method.name.lowercase().contains("load") &&
    !Modifier.isStatic(method.modifiers) &&
    method.parameterCount >= 1

// ─── Game ad payload helpers ──────────────────────────────────────────────────

fun resolveGameAdPayload(target: Any?, payload: Any?, messageType: String? = null): Boolean {
    if (target == null || payload == null) return false
    val promiseId = extractPromiseId(payload) ?: return false
    val resolveMethod = resolveGameAdResolveMethod(target.javaClass) ?: return false
    val successPayload = buildGameAdSuccessPayload(payload, messageType)
    return runCatching { resolveMethod.invoke(target, promiseId, successPayload); true }.getOrElse { false }
}

fun rejectGameAdPayload(
    target: Any?, payload: Any?,
    message: String = GAME_AD_REJECTION_MESSAGE,
    code: String = GAME_AD_REJECTION_CODE
): Boolean {
    if (target == null || payload == null) return false
    resolveGameAdBridgeRejectMethod(target.javaClass)?.let { m ->
        if (runCatching { m.invoke(target, message, code, payload); true }.getOrElse { false }) return true
    }
    val promiseId = extractPromiseId(payload) ?: return false
    val rejectMethod = resolveGameAdRejectMethod(target.javaClass) ?: return false
    return runCatching { rejectMethod.invoke(target, promiseId, message, code); true }.getOrElse { false }
}

private fun rejectUnavailableGameAdPayloadIfNeeded(target: Any?, payload: Any?, messageType: String?): Boolean {
    if (!shouldMakeGameAdUnavailable(payload, messageType)) return false
    val rejected = rejectGameAdPayload(target, payload, GAME_AD_UNAVAILABLE_MESSAGE, GAME_AD_UNAVAILABLE_CODE)
    if (rejected) { lastUnavailableGameAdMs.set(System.currentTimeMillis()); XposedBridge.log("[$FB_TAG] Marked rewarded game ad unavailable type=$messageType") }
    return rejected
}

private fun shouldMakeGameAdUnavailable(payload: Any?, messageType: String?): Boolean {
    if (messageType in GAME_AD_UNAVAILABLE_MESSAGE_TYPES) return true
    if (messageType !in setOf("loadadasync", "showadasync")) return false
    val content = extractGameAdContent(payload)
    val adInstanceId = content?.optString("adInstanceID")?.takeIf { it.isNotBlank() }
    val knownType = adInstanceId?.let { gameAdInstanceTypes[it] }
    if (knownType in GAME_AD_UNAVAILABLE_MESSAGE_TYPES) return true
    val placementText = listOf(
        content?.optString("placementID").orEmpty(),
        content?.optString("adType").orEmpty(),
        content?.optString("type").orEmpty(),
        content?.optString("format").orEmpty()
    ).joinToString(" ").lowercase()
    if (placementText.contains("reward")) return true
    return payload?.toString()?.lowercase()?.contains("rewarded") == true
}

fun shouldAutofixGameAdMessage(messageType: String?) = messageType in GAME_AD_AUTOFIX_MESSAGE_TYPES

private fun shouldBlockGameAdActivityLaunch(className: String): Boolean {
    return className in HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES ||
        (className in setOf(AUDIENCE_NETWORK_ACTIVITY_CLASS, AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS) &&
         isRecentUnavailableGameAd())
}

private fun isRecentUnavailableGameAd(): Boolean {
    val rejectedAt = lastUnavailableGameAdMs.get()
    return rejectedAt > 0 && System.currentTimeMillis() - rejectedAt < GAME_AD_RECENT_WINDOW_MS
}

private fun shouldConvertGameAdRejectToSuccess(promiseId: String, reason: String): Boolean {
    val snapshot = gameAdPromiseSnapshots[promiseId]
    if (shouldAutofixGameAdMessage(snapshot?.messageType)) return true
    val closed = lastGameAdActivityCloseMs.get()
    return closed > 0 && System.currentTimeMillis() - closed < 15_000L && reason.lowercase().contains("banner")
}

fun rememberGameAdPayload(target: Any?, payload: Any?, messageType: String?) {
    if (target == null || payload !is JSONObject || messageType !in GAME_AD_MESSAGE_TYPES) return
    val now = System.currentTimeMillis()
    recentGameAdTargets[target] = now
    val snapshotPayload = runCatching { JSONObject(payload.toString()) }.getOrNull() ?: payload
    extractGameAdContent(snapshotPayload)?.optString("adInstanceID")?.takeIf { it.isNotBlank() }?.let { id ->
        messageType?.let { gameAdInstanceTypes[id] = it }
    }
    extractPromiseId(snapshotPayload)?.let { promiseId ->
        gameAdPromiseSnapshots.entries.removeIf { now - it.value.timestampMs > GAME_AD_PROMISE_WINDOW_MS }
        gameAdPromiseSnapshots[promiseId] = GameAdPromiseSnapshot(snapshotPayload, messageType, now)
    }
    synchronized(recentGameAdPayloads) {
        recentGameAdPayloads.removeAll { now - it.timestampMs > GAME_AD_RECENT_WINDOW_MS }
        recentGameAdPayloads.add(GameAdPayloadSnapshot(target, snapshotPayload, messageType, now))
        while (recentGameAdPayloads.size > 20) recentGameAdPayloads.removeAt(0)
    }
}

fun completeRecentGameAdRequests(source: String) {
    val now = System.currentTimeMillis()
    val snapshots = synchronized(recentGameAdPayloads) {
        recentGameAdPayloads.removeAll { now - it.timestampMs > GAME_AD_RECENT_WINDOW_MS }
        recentGameAdPayloads.toList()
    }
    var resolved = 0
    snapshots.asReversed().forEach { s ->
        if (shouldAutofixGameAdMessage(s.messageType) && resolveGameAdPayload(s.target, s.payload, s.messageType)) {
            dispatchPostResolveGameAdSignals(s.target, s.payload, s.messageType); resolved++
        }
    }
    val targets = synchronized(recentGameAdTargets) {
        recentGameAdTargets.entries.removeIf { now - it.value > GAME_AD_RECENT_WINDOW_MS }; recentGameAdTargets.keys.toList()
    }
    targets.forEach { t -> dispatchGameEvent(t, "hidebannerad", JSONObject().put("completed", true)) }
    if (resolved > 0) XposedBridge.log("[$FB_TAG] Re-resolved $resolved recent game ad request(s) via $source")
}

private fun dispatchPostResolveGameAdSignals(target: Any?, payload: Any?, messageType: String?) {
    if (messageType in setOf("loadbanneradasync", "hidebanneradasync")) {
        val content = buildGameAdSuccessPayload(payload, messageType)
        if (dispatchGameEvent(target, "hidebannerad", content)) XposedBridge.log("[$FB_TAG] Dispatched hidebannerad for type=$messageType")
    }
}

fun buildGameAdSuccessPayload(payload: Any?, messageType: String? = null): JSONObject {
    val effectiveMessageType = messageType ?: (payload as? JSONObject)?.optString("type").orEmpty()
    val content = extractGameAdContent(payload)
    val result = JSONObject()
    val placementId    = content?.optString("placementID")?.takeIf { it.isNotBlank() }
    val requestedInstId = content?.optString("adInstanceID")?.takeIf { it.isNotBlank() }
    val bannerPosition = content?.optString("bannerPosition")?.takeIf { it.isNotBlank() }
    result.put("success", true)
    if (effectiveMessageType?.contains("reward", ignoreCase = true) == true) {
        result.put("completed", true).put("didComplete", true).put("watched", true)
              .put("rewarded", true).put("completionGesture", "post")
    }
    if (placementId != null)    result.put("placementID", placementId)
    if (bannerPosition != null) result.put("bannerPosition", bannerPosition)
    val adInstanceId = when {
        requestedInstId != null -> { gameAdInstanceIds.putIfAbsent(requestedInstId, requestedInstId); requestedInstId }
        placementId != null && effectiveMessageType != "loadbanneradasync" ->
            resolveGameAdInstanceId(placementId, effectiveMessageType, bannerPosition)
        else -> null
    }
    if (adInstanceId != null) {
        result.put("adInstanceID", adInstanceId)
        effectiveMessageType.takeIf { it.isNotBlank() }?.let { type ->
            gameAdInstanceTypes.putIfAbsent(adInstanceId, type)
        }
    }
    return result
}

private fun forceGameAdSuccessResult(promiseId: String, original: Any?, payload: JSONObject?, messageType: String?): JSONObject {
    val result = (original as? JSONObject)?.let { copyJsonObject(it) } ?: JSONObject()
    val success = buildGameAdSuccessPayload(payload ?: JSONObject().put("content", JSONObject().put("promiseID", promiseId)), messageType)
    val keys = success.keys(); while (keys.hasNext()) { val k = keys.next(); result.put(k, success.opt(k)) }
    result.put("success", true)
    if (messageType?.contains("reward", ignoreCase = true) == true)
        result.put("completed", true).put("didComplete", true).put("watched", true).put("rewarded", true).put("completionGesture", "post")
    return result
}

private fun inferGameAdMessageType(method: Method, payload: Any?): String? {
    val payloadType = (payload as? JSONObject)?.optString("type")?.takeIf { it.isNotBlank() }
    if (payloadType != null) return payloadType
    return when (method.name) {
        "D3s" -> "getinterstitialadasync"; "D3x" -> "getrewardedinterstitialasync"
        "D3z" -> "getrewardedvideoasync"; "D55" -> "hidebanneradasync"
        "D9v" -> "loadadasync"; "D9x" -> "loadbanneradasync"; "DX0" -> "showadasync"
        else -> null
    }
}

private fun gameAdPromiseTypeFromReason(reason: String): String? {
    val n = reason.lowercase()
    return when {
        n.contains("reward") && n.contains("interstitial") -> "getrewardedinterstitialasync"
        n.contains("reward") -> "getrewardedvideoasync"
        n.contains("interstitial") -> "getinterstitialadasync"
        n.contains("banner") -> "loadbanneradasync"
        n.contains("show") || n.contains("watch") || n.contains("complete") -> "showadasync"
        n.contains("load") -> "loadadasync"
        else -> null
    }
}

// ─── Activity helpers ─────────────────────────────────────────────────────────

private fun handleGameAdActivity(activity: Activity, source: String) {
    when (activity.javaClass.name) {
        AUDIENCE_NETWORK_ACTIVITY_CLASS, AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS -> {
            forceAudienceNetworkRewardCompletion(activity, source)
            finishGameAdActivity(activity, source)
        }
        else -> finishGameAdActivity(activity, source)
    }
}

private fun buildGameAdActivityResultIntent(): Intent =
    Intent().apply { putExtra("success", true) }

private fun finishGameAdActivity(activity: Activity, source: String) {
    if (activity.isFinishing) return
    lastGameAdActivityCloseMs.set(System.currentTimeMillis())
    completeRecentGameAdRequests(source)
    if (activity.javaClass.name in GAME_AD_ACTIVITY_CLASS_NAMES) {
        activity.setResult(Activity.RESULT_OK, buildGameAdActivityResultIntent())
    } else {
        activity.setResult(Activity.RESULT_CANCELED, Intent())
    }
    activity.finish()
    XposedBridge.log("[$FB_TAG] Closed game ad activity ${activity.javaClass.name} via $source")
}

private fun forceAudienceNetworkRewardCompletion(activity: Activity, source: String) {
    val seen = IdentityHashMap<Any, Boolean>()
    val queue = java.util.ArrayDeque<Pair<Any, Int>>()
    queue.add(activity to 0)
    var inspected = 0; var invoked = 0
    while (!queue.isEmpty() && inspected < 96) {
        val (value, depth) = queue.removeFirst()
        if (seen.put(value, true) != null) continue; inspected++
        invoked += invokeAudienceNetworkRewardCompletionMethods(value)
        if (depth >= 5 || !shouldTraverseAudienceNetworkObject(value, value === activity)) continue
        audienceNetworkFieldsFor(value.javaClass).forEach { f ->
            val fv = runCatching { f.get(value) }.getOrNull() ?: return@forEach
            when (fv) {
                is Iterable<*> -> fv.take(12).forEach { item -> if (item != null && shouldQueueAudienceNetworkObject(item)) queue.add(item to depth + 1) }
                is Array<*>    -> fv.take(12).forEach { item -> if (item != null && shouldQueueAudienceNetworkObject(item)) queue.add(item to depth + 1) }
                else -> if (shouldQueueAudienceNetworkObject(fv)) queue.add(fv to depth + 1)
            }
        }
    }
    XposedBridge.log("[$FB_TAG] Forced AN reward callbacks invoked=$invoked inspected=$inspected via $source")
}

private fun invokeAudienceNetworkRewardCompletionMethods(target: Any): Int {
    var invoked = 0
    audienceNetworkMethodsFor(target.javaClass).filter { m ->
        !Modifier.isStatic(m.modifiers) && m.parameterCount == 0 &&
        (m.name in AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES || (m.name.contains("Reward", ignoreCase = true) && m.name.contains("Complete", ignoreCase = true)))
    }.forEach { m -> runCatching { m.invoke(target); invoked++ } }
    return invoked
}

private fun completeAudienceNetworkRewardObject(adObject: Any, source: String = "unknown"): Boolean {
    val listeners = LinkedHashSet<Any>()
    synchronized(audienceNetworkRewardAdListeners) { audienceNetworkRewardAdListeners[adObject]?.let { listeners.add(it) } }
    listeners.addAll(findAudienceNetworkRewardListeners(adObject))
    var invoked = 0
    listeners.forEach { listener -> invoked += invokeAudienceNetworkRewardListenerCallbacks(listener, adObject, source) }
    if (invoked > 0) { XposedBridge.log("[$FB_TAG] Completed AN reward callbacks invoked=$invoked listeners=${listeners.size} via $source"); completeRecentGameAdRequests(source); return true }
    XposedBridge.log("[$FB_TAG] No AN reward listener completed for ${adObject.javaClass.name} via $source")
    return false
}

private fun invokeAudienceNetworkRewardListenerCallbacks(listener: Any, adObject: Any, source: String): Int {
    var invoked = 0
    val methodGroups = listOf(
        setOf("onAdLoaded", "onLoggingImpression", "onInterstitialDisplayed"),
        setOf("onRewardedVideoCompleted", "onRewardedAdCompleted", "onRewardedInterstitialCompleted", "onAdComplete", "onAdCompleted"),
        setOf("onRewardedVideoClosed", "onRewardedInterstitialClosed", "onAdClosed", "onInterstitialDismissed")
    )
    methodGroups.forEach { group ->
        audienceNetworkRewardMethodsFor(listener.javaClass)
            .filter { m -> m.name in group }
            .forEach { m ->
                val args = audienceNetworkCallbackArgs(m, adObject) ?: return@forEach
                runCatching { m.invoke(listener, *args); invoked++ }
                    .onFailure { XposedBridge.log("[$FB_TAG] Failed AN callback ${listener.javaClass.name}.${m.name} via $source") }
            }
    }
    return invoked
}

private fun audienceNetworkCallbackArgs(method: Method, adObject: Any): Array<Any?>? =
    when (method.parameterCount) {
        0 -> emptyArray()
        1 -> { val pt = method.parameterTypes[0]; if (pt.isAssignableFrom(adObject.javaClass)) arrayOf(adObject) else null }
        else -> null
    }

/** Like audienceNetworkMethodsFor but also includes interface-declared methods — needed for listener callbacks. */
private fun audienceNetworkRewardMethodsFor(type: Class<*>): List<Method> {
    val map = LinkedHashMap<String, Method>()
    var cur: Class<*>? = type
    while (cur != null && cur != Any::class.java && cur != Activity::class.java) {
        (cur.declaredMethods + cur.methods).forEach { m ->
            if (!Modifier.isStatic(m.modifiers)) {
                m.isAccessible = true
                map.putIfAbsent("${m.name}/${m.parameterTypes.joinToString { it.name }}", m)
            }
        }
        cur = cur.superclass
    }
    return map.values.toList()
}

private fun findAudienceNetworkRewardListeners(root: Any?): List<Any> {
    if (root == null) return emptyList()
    val listeners = LinkedHashSet<Any>(); val seen = IdentityHashMap<Any, Boolean>()
    val queue = java.util.ArrayDeque<Pair<Any, Int>>(); queue.add(root to 0)
    var inspected = 0
    while (!queue.isEmpty() && inspected < 96 && listeners.size < 8) {
        val (value, depth) = queue.removeFirst()
        if (seen.put(value, true) != null) continue; inspected++
        if (value !== root && isAudienceNetworkRewardListenerObject(value)) { listeners.add(value); continue }
        if (depth >= 5 || !shouldQueueAudienceNetworkObject(value)) continue
        audienceNetworkFieldsFor(value.javaClass).forEach { f ->
            val fv = runCatching { f.get(value) }.getOrNull() ?: return@forEach
            when (fv) {
                is Iterable<*> -> fv.take(12).forEach { item -> if (item != null && (isAudienceNetworkRewardListenerObject(item) || shouldQueueAudienceNetworkObject(item))) queue.add(item to depth + 1) }
                is Array<*>    -> fv.take(12).forEach { item -> if (item != null && (isAudienceNetworkRewardListenerObject(item) || shouldQueueAudienceNetworkObject(item))) queue.add(item to depth + 1) }
                else -> if (isAudienceNetworkRewardListenerObject(fv) || shouldQueueAudienceNetworkObject(fv)) queue.add(fv to depth + 1)
            }
        }
    }
    return listeners.toList()
}

private fun rememberAudienceNetworkRewardListeners(owner: Any?, args: Array<Any?>?, method: Method) {
    if (owner == null || args == null) return
    args.forEach { arg ->
        if (arg != null && isAudienceNetworkRewardListenerObject(arg)) {
            audienceNetworkRewardAdListeners[owner] = arg
        } else {
            findAudienceNetworkRewardListeners(arg).firstOrNull()?.let { audienceNetworkRewardAdListeners[owner] = it }
        }
    }
}

private fun isAudienceNetworkRewardListenerObject(value: Any?): Boolean {
    if (value == null) return false
    val type = value.javaClass
    val cn = type.name.lowercase()
    if (cn.contains("listener") && (cn.contains("reward") || cn.contains("ad"))) return true
    if (audienceNetworkInterfacesFor(type).any { iface ->
            val ifn = iface.name.lowercase()
            ifn.contains("listener") && (ifn.contains("reward") || ifn.contains("ad"))
        }) return true
    return audienceNetworkMethodsFor(type).any { m ->
        m.name in AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES ||
        m.name.contains("Reward", ignoreCase = true)
    }
}

private fun audienceNetworkInterfacesFor(type: Class<*>): List<Class<*>> {
    val interfaces = LinkedHashSet<Class<*>>()
    fun collect(current: Class<*>?) {
        if (current == null || current == Any::class.java) return
        current.interfaces.forEach { iface -> if (interfaces.add(iface)) collect(iface) }
        collect(current.superclass)
    }
    collect(type)
    return interfaces.toList()
}

private fun isAudienceNetworkRewardRelevantClass(className: String): Boolean {
    val n = className.lowercase()
    return (n.startsWith("com.facebook.ads.") || n.startsWith("com.facebook.audiencenetwork.") || n.contains("audiencenetwork")) &&
           (n.contains("reward") || n.contains("adlistener") || n.contains("adconfig") || n.endsWith(".ad"))
}

private fun isAudienceNetworkRewardShowMethod(clazz: Class<*>, method: Method) =
    clazz.name.lowercase().contains("reward") &&
    method.name == "show" &&
    !Modifier.isStatic(method.modifiers) &&
    method.parameterCount <= 1 &&
    (method.returnType == Void.TYPE ||
     method.returnType == Boolean::class.javaPrimitiveType ||
     method.returnType == Boolean::class.java)

private fun isAudienceNetworkRewardListenerRegistrationMethod(method: Method): Boolean {
    if (Modifier.isStatic(method.modifiers) || method.parameterCount == 0) return false
    if (method.name.lowercase().contains("listener")) return true
    return method.parameterTypes.any { t -> t.name.lowercase().contains("listener") && (t.name.lowercase().contains("reward") || t.name.lowercase().contains("ad")) }
}

private fun shouldQueueAudienceNetworkObject(value: Any): Boolean {
    val type = value.javaClass
    if (type.isPrimitive || value is String || value is Number || value is Boolean || value is CharSequence) return false
    return shouldTraverseAudienceNetworkObject(value, false)
}

private fun shouldTraverseAudienceNetworkObject(value: Any, isRootActivity: Boolean): Boolean {
    if (isRootActivity) return true
    val cn = value.javaClass.name.lowercase()
    return cn.startsWith("com.facebook.ads.") || cn.startsWith("com.facebook.audiencenetwork.") ||
           cn.contains("audiencenetwork") || cn.contains("reward") || cn.contains("interstitial") ||
           cn.contains("fullscreen") || cn.contains("listener") || cn.contains(".ads.")
}

private fun audienceNetworkFieldsFor(type: Class<*>): List<Field> {
    val list = ArrayList<Field>(); var cur: Class<*>? = type
    while (cur != null && cur != Any::class.java && cur != Activity::class.java && list.size < 48) {
        cur.declaredFields.forEach { f -> if (!Modifier.isStatic(f.modifiers) && list.size < 48) { f.isAccessible = true; list.add(f) } }; cur = cur.superclass
    }; return list
}

private fun audienceNetworkMethodsFor(type: Class<*>): List<Method> {
    val map = LinkedHashMap<String, Method>(); var cur: Class<*>? = type
    while (cur != null && cur != Any::class.java && cur != Activity::class.java) {
        cur.declaredMethods.forEach { m -> if (!Modifier.isStatic(m.modifiers)) { m.isAccessible = true; map.putIfAbsent("${cur.name}.${m.name}/${m.parameterCount}", m) } }; cur = cur.superclass
    }; return map.values.toList()
}

// ─── Native ad view helpers ───────────────────────────────────────────────────

private val GAME_AD_WEBVIEW_HIDE_SCRIPT = """
(function(){
  if (window.__nexalloyFbAdSweep) return; window.__nexalloyFbAdSweep = true;
  function isAd(el) {
    var t = (el.innerText || el.textContent || '').toLowerCase();
    var a = ((el.id || '') + ' ' + (el.className || '') + ' ' + (el.getAttribute('aria-label') || '')).toLowerCase();
    if (t.indexOf('ads served by meta') >= 0 || t.indexOf('ad choices') >= 0) return true;
    return /audiencenetwork|adchoices|fbinstant.*ad|banner.?ad|ad.?banner|ad-container|ad_container|sponsored/.test(a);
  }
  function sweep() { try { document.querySelectorAll('iframe,div,section,aside,[id],[class]').forEach(function(el){ if(isAd(el)) { el.style.setProperty('display','none','important'); } }); } catch(e){} }
  sweep(); new MutationObserver(sweep).observe(document.documentElement||document.body,{childList:true,subtree:true}); setInterval(sweep,1000);
})();
""".trimIndent()

private fun scheduleGameAdSurfaceSweep(view: View?, reason: String) {
    val root = view?.rootView ?: view ?: return
    longArrayOf(0L, 250L, 1_000L, 2_500L, 5_000L).forEach { delayMs ->
        root.postDelayed({ sweepGameAdSurface(root, reason) }, delayMs)
    }
}

private fun sweepGameAdSurface(view: View?, reason: String): Boolean {
    if (view == null) return false
    var hidden = false
    if (view is WebView) injectGameAdHidingScript(view)
    if (isPotentialNativeGameAdView(view) || (view is TextView && isGameAdMarkerText(view.text))) {
        hidden = hideLikelyGameAdContainer(view, reason) || hidden
    }
    val group = view as? ViewGroup ?: return hidden
    for (i in 0 until group.childCount) {
        hidden = sweepGameAdSurface(group.getChildAt(i), reason) || hidden
    }
    return hidden
}

private fun injectGameAdHidingScript(webView: WebView) {
    webView.post { runCatching { webView.evaluateJavascript(GAME_AD_WEBVIEW_HIDE_SCRIPT, null) } }
}

private fun hideLikelyGameAdContainer(view: View, reason: String): Boolean {
    var hidden = false
    return runCatching {
        if (view.visibility != View.GONE) { view.visibility = View.GONE; hidden = true }
        view.minimumHeight = 0
        view.layoutParams?.let { p ->
            if (isLikelyBannerSized(view, view.rootView) || isPotentialNativeGameAdView(view)) {
                p.height = 0; view.layoutParams = p; hidden = true
            }
        }
        view.requestLayout()
        if (hidden) XposedBridge.log("[$FB_TAG] Hid game ad surface via $reason target=${view.javaClass.name}")
        hidden
    }.getOrDefault(hidden)
}

private fun isLikelyBannerSized(view: View, root: View?): Boolean {
    val rootHeight = root?.height?.takeIf { it > 0 } ?: return view.height in 1..360
    val height = view.height
    if (height <= 0 || height > maxOf(360, rootHeight / 3)) return false
    val location = IntArray(2)
    return runCatching {
        view.getLocationOnScreen(location)
        location[1] + height > rootHeight / 2
    }.getOrDefault(true)
}

private fun isPotentialNativeGameAdView(view: View?): Boolean {
    val cn = view?.javaClass?.name?.lowercase() ?: return false
    return cn == "com.facebook.ads.adview" || (cn.endsWith(".adview") && (cn.startsWith("com.facebook.ads.") || cn.contains("audiencenetwork"))) || cn.contains("adchoices")
}

private fun isGameAdMarkerText(value: CharSequence?): Boolean {
    if (value.isNullOrBlank()) return false
    val n = value.toString().lowercase()
    return n.contains("ads served by meta") || n.contains("ad choices") || n.contains("adchoices")
}

// ─── Resolve / reject helpers ─────────────────────────────────────────────────

private fun resolveGameAdResolveMethod(type: Class<*>?): Method? {
    if (type == null) return null
    val candidates = (type.declaredMethods + type.methods).filter { m ->
        !Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE && m.parameterCount == 2 &&
        m.parameterTypes[0] == String::class.java && !m.parameterTypes[1].isPrimitive
    }
    return (candidates.firstOrNull { it.parameterTypes[1] == Any::class.java }
        ?: candidates.firstOrNull { JSONObject::class.java.isAssignableFrom(it.parameterTypes[1]) }
        ?: candidates.firstOrNull())?.apply { isAccessible = true }
}

private fun resolveGameAdBridgeRejectMethod(type: Class<*>?): Method? {
    if (type == null) return null
    return (type.declaredMethods + type.methods).firstOrNull { m ->
        !Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE && m.parameterCount == 3 &&
        m.parameterTypes[0] == String::class.java && m.parameterTypes[1] == String::class.java && m.parameterTypes[2] == JSONObject::class.java
    }?.apply { isAccessible = true }
}

private fun resolveGameAdRejectMethod(type: Class<*>?): Method? {
    if (type == null) return null
    return (type.declaredMethods + type.methods).firstOrNull { m ->
        !Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE && m.parameterCount == 3 && m.parameterTypes.all { it == String::class.java }
    }?.apply { isAccessible = true }
}

private fun dispatchGameEvent(target: Any?, eventType: String, content: Any?): Boolean {
    if (target == null) return false
    val method = resolveGameEventDispatchMethod(target.javaClass) ?: return false
    val eventValue = resolveGameEventValue(method.parameterTypes[0], eventType) ?: return false
    return runCatching { method.invoke(target, eventValue, content ?: JSONObject.NULL); true }.getOrElse { false }
}

private fun resolveGameEventDispatchMethod(type: Class<*>?): Method? {
    if (type == null) return null
    return (type.declaredMethods + type.methods).firstOrNull { m ->
        !Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE && m.parameterCount == 2 &&
        m.parameterTypes[0] != String::class.java && m.parameterTypes[1] == Any::class.java
    }?.apply { isAccessible = true }
}

private fun resolveGameEventValue(eventType: Class<*>, eventName: String): Any? {
    val valuesMethod = (eventType.declaredMethods + eventType.methods).firstOrNull { m ->
        Modifier.isStatic(m.modifiers) && m.parameterCount == 0 && m.returnType.isArray && m.returnType.componentType == eventType
    }?.apply { isAccessible = true }
    val values = runCatching { valuesMethod?.invoke(null) as? Array<*> }.getOrNull().orEmpty()
    values.firstOrNull { it?.toString() == eventName }?.let { return it }
    return eventType.declaredFields.firstOrNull { f ->
        Modifier.isStatic(f.modifiers) && f.type == eventType &&
        runCatching { f.isAccessible = true; f.get(null)?.toString() == eventName }.getOrDefault(false)
    }?.let { f -> runCatching { f.get(null) }.getOrNull() }
}

fun extractPromiseId(payload: Any?): String? {
    val jClass = payload?.javaClass ?: return null
    if (jClass.name != "org.json.JSONObject") return null
    val getJSONObject = (jClass.declaredMethods + jClass.methods).firstOrNull { m -> m.name == "getJSONObject" && m.parameterCount == 1 && m.parameterTypes[0] == String::class.java }?.apply { isAccessible = true } ?: return null
    val getString = (jClass.declaredMethods + jClass.methods).firstOrNull { m -> m.name == "getString" && m.parameterCount == 1 && m.parameterTypes[0] == String::class.java }?.apply { isAccessible = true } ?: return null
    val content = runCatching { getJSONObject.invoke(payload, "content") }.getOrNull() ?: return null
    return runCatching { getString.invoke(content, "promiseID") as? String }.getOrNull()
}

private fun extractGameAdContent(payload: Any?): JSONObject? = (payload as? JSONObject)?.optJSONObject("content")

private fun buildGameAdPayloadFromServiceBundle(bundle: Bundle, messageType: String): JSONObject =
    JSONObject().put("type", messageType).put("content", bundleToJsonObject(bundle))

private fun bundleToJsonObject(bundle: Bundle): JSONObject {
    val json = JSONObject()
    runCatching { bundle.keySet().toList() }.getOrDefault(emptyList()).forEach { key ->
        when (val v = runCatching { bundle.get(key) }.getOrNull()) {
            null -> json.put(key, JSONObject.NULL)
            is String -> json.put(key, v); is Boolean -> json.put(key, v); is Number -> json.put(key, v)
            is JSONObject -> json.put(key, v); is Bundle -> json.put(key, bundleToJsonObject(v))
            else -> json.put(key, v.toString())
        }
    }; return json
}

private fun resolveGameAdInstanceId(placementId: String, messageType: String?, bannerPosition: String?): String {
    val key = listOf(messageType.orEmpty(), placementId, bannerPosition.orEmpty()).joinToString("|")
    return gameAdInstanceIds.computeIfAbsent(key) { "${GAME_AD_SUCCESS_INSTANCE_PREFIX}_${key.hashCode().toLong() and 0xffffffffL}" }
}

private fun copyJsonObject(source: JSONObject): JSONObject {
    val result = JSONObject(); val keys = source.keys()
    while (keys.hasNext()) { val k = keys.next(); result.put(k, source.opt(k)) }; return result
}

// ─── List / result manipulation helpers ──────────────────────────────────────

fun filterAdItems(list: MutableList<Any?>, inspector: AdStoryInspector): Int {
    var removed = 0; val it = list.iterator()
    while (it.hasNext()) { if (inspector.containsAdStory(it.next())) { it.remove(); removed++ } }; return removed
}

fun buildImmutableListLike(sample: Any?, items: List<Any?>): Any? {
    if (sample == null) return null
    return runCatching {
        val cl = Class.forName("com.google.common.collect.ImmutableList", false, sample.javaClass.classLoader)
        cl.getDeclaredMethod("copyOf", Iterable::class.java).invoke(null, items)
    }.getOrNull()
}

fun replaceFeedItemsInResult(param: XC_MethodHook.MethodHookParam, items: List<Any?>): Boolean {
    val result = param.result ?: return false; val rebuilt = rebuildFeedResult(result, items) ?: return false
    param.result = rebuilt; return true
}

private fun rebuildFeedResult(result: Any, items: List<Any?>): Any? {
    val type = result.javaClass
    val fields = runCatching { type.declaredFields.onEach { it.isAccessible = true } }.getOrNull() ?: return null
    val listField    = fields.firstOrNull { !Modifier.isStatic(it.modifiers) && Iterable::class.java.isAssignableFrom(it.type) } ?: return null
    val intArrayField = fields.firstOrNull { !Modifier.isStatic(it.modifiers) && it.type == IntArray::class.java } ?: return null
    val intFields    = fields.filter { !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
    if (intFields.size < 3) return null
    val originalList = runCatching { listField.get(result) }.getOrNull()
    val rebuiltList  = buildImmutableListLike(originalList, items) ?: return null
    val stats        = runCatching { intArrayField.get(result) as? IntArray }.getOrNull()?.clone() ?: return null
    val ints         = intFields.map { f -> runCatching { f.getInt(result) }.getOrNull() ?: return null }
    val ctor = type.declaredConstructors.firstOrNull { c ->
        c.parameterCount == 5 && c.parameterTypes.getOrNull(0)?.name == "com.google.common.collect.ImmutableList" &&
        c.parameterTypes.getOrNull(1) == IntArray::class.java && c.parameterTypes.drop(2).all { it == Int::class.javaPrimitiveType }
    } ?: return null
    ctor.isAccessible = true
    return runCatching { ctor.newInstance(rebuiltList, stats, ints[0], ints[1], ints[2]) }.getOrNull()
}

fun extractFeedItemsFromResult(result: Any?): Iterable<*>? {
    if (result == null) return null
    if (result is Iterable<*>) return result
    return runCatching {
        val f = result.javaClass.declaredFields.firstOrNull { Iterable::class.java.isAssignableFrom(it.type) } ?: return null
        f.isAccessible = true; f.get(result) as? Iterable<*>
    }.getOrNull()
}

// ─── Sponsored pool result type helpers ──────────────────────────────────────

fun isSponsoredResultCarrier(type: Class<*>): Boolean {
    val ctor = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return false
    val reasonType = ctor.parameterTypes.getOrNull(1) ?: return false
    return reasonType.enumConstants?.any { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" } == true
}

fun buildSponsoredEmptyResult(type: Class<*>): Any? {
    val ctor = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return null
    val reasonType = ctor.parameterTypes.getOrNull(1) ?: return null
    val emptyReason = reasonType.enumConstants?.firstOrNull { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" }
        ?: reasonType.enumConstants?.firstOrNull { it.toString() == "FAIL" } ?: return null
    ctor.isAccessible = true; return runCatching { ctor.newInstance(null, emptyReason) }.getOrNull()
}

// ─── Litho render method detection ───────────────────────────────────────────

fun resolveLithoRenderMethod(componentClass: Class<*>): Method? =
    componentClass.declaredMethods.firstOrNull { m ->
        !Modifier.isStatic(m.modifiers) && !m.isBridge && !m.isSynthetic && m.parameterCount == 1 &&
        !m.returnType.isPrimitive && m.returnType != Void.TYPE && m.returnType != Any::class.java &&
        m.returnType.isAssignableFrom(componentClass)
    }?.apply { isAccessible = true }

// ─── Story ad provider resolution ────────────────────────────────────────────

fun resolveStoryAdProviderHooks(providerClass: Class<*>, includeInsertionTrigger: Boolean): StoryAdProviderHooks {
    val methods = providerClass.declaredMethods + providerClass.methods
    val mergeMethod = methods.firstOrNull { m ->
        !Modifier.isStatic(m.modifiers) && m.parameterCount == 3 &&
        m.returnType.name == "com.google.common.collect.ImmutableList" &&
        m.parameterTypes[0].name == "com.facebook.auth.usersession.FbUserSession" &&
        m.parameterTypes[2].name == "com.google.common.collect.ImmutableList"
    }?.apply { isAccessible = true }
    val fetchMoreAdsMethod = methods.firstOrNull { m ->
        !Modifier.isStatic(m.modifiers) && m.parameterCount == 2 && m.returnType == Void.TYPE &&
        m.parameterTypes[0].name == "com.google.common.collect.ImmutableList" && m.parameterTypes[1] == Int::class.javaPrimitiveType
    }?.apply { isAccessible = true }
    val deferredUpdateMethod = methods.firstOrNull { m ->
        !Modifier.isStatic(m.modifiers) && m.parameterCount == 2 && m.returnType == Void.TYPE &&
        m.parameterTypes[1].name == "com.google.common.collect.ImmutableList"
    }?.apply { isAccessible = true }
    // insertionTrigger: method that logs/uses "ads_insertion" string
    val insertionTriggerMethod = if (!includeInsertionTrigger) null else {
        methods.firstOrNull { m -> !Modifier.isStatic(m.modifiers) && m.parameterCount == 0 && m.returnType == Void.TYPE }
            ?.apply { isAccessible = true }
    }
    return StoryAdProviderHooks(providerClass, mergeMethod, fetchMoreAdsMethod, deferredUpdateMethod, insertionTriggerMethod)
}

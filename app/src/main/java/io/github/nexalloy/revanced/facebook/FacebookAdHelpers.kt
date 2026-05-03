package io.github.nexalloy.revanced.facebook

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ─── Constants ───────────────────────────────────────────────────────────────

const val FB_TAG = "NexAlloy/Facebook"
private const val BEFORE_SIZE_EXTRA = "nexalloy_fb_ads_before_size"
private const val GAME_AD_SUCCESS_INSTANCE_PREFIX = "nexalloy_fb_noop_ad"
private const val HOOK_HIT_LOG_EVERY = 25

const val GRAPHQL_FEED_UNIT_EDGE_CLASS      = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
const val GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS = "com.facebook.graphql.model.GraphQLFBMultiAdsFeedUnit"
const val GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS =
    "com.facebook.graphql.model.GraphQLQuickPromotionNativeTemplateFeedUnit"
const val AUDIENCE_NETWORK_ACTIVITY_CLASS        = "com.facebook.ads.AudienceNetworkActivity"
const val AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS = "com.facebook.ads.internal.ipc.AudienceNetworkRemoteActivity"
const val NEKO_PLAYABLE_ACTIVITY_CLASS           = "com.facebook.neko.playables.activity.NekoPlayableAdActivity"

const val GAME_AD_REJECTION_MESSAGE = "Game ad request blocked"
const val GAME_AD_REJECTION_CODE    = "CLIENT_UNSUPPORTED_OPERATION"

val GAME_AD_MESSAGE_TYPES = setOf(
    "getinterstitialadasync", "getrewardedvideoasync", "getrewardedinterstitialasync",
    "loadadasync", "showadasync", "loadbanneradasync"
)

val GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
    AUDIENCE_NETWORK_ACTIVITY_CLASS,
    AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS,
    NEKO_PLAYABLE_ACTIVITY_CLASS
)

val HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES = setOf(NEKO_PLAYABLE_ACTIVITY_CLASS)

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

// ─── Shared mutable state ─────────────────────────────────────────────────────

val gameAdInstanceIds = ConcurrentHashMap<String, String>()
val hookHitCounters   = ConcurrentHashMap<String, AtomicInteger>()

// ─── Data classes ─────────────────────────────────────────────────────────────

data class FeedListSanitizerHook(val method: Method, val listArgIndex: Int)

data class StoryAdProviderHooks(
    val providerClass: Class<*>,
    val mergeMethod: Method?,
    val fetchMoreAdsMethod: Method?,
    val deferredUpdateMethod: Method?,
    val insertionTriggerMethod: Method?
)

// ─── AdStoryInspector ─────────────────────────────────────────────────────────

class AdStoryInspector(private val adKindEnumClass: Class<*>) {
    private val enumMethodCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val fieldCache      = ConcurrentHashMap<Class<*>, List<Field>>()

    fun containsAdStory(
        value: Any?,
        depth: Int = 0,
        seen: IdentityHashMap<Any, Boolean> = IdentityHashMap()
    ): Boolean = containsAdKind(value, depth, seen) && containsReelsAdSignal(value, 0, IdentityHashMap())

    private fun containsAdKind(
        value: Any?, depth: Int, seen: IdentityHashMap<Any, Boolean>
    ): Boolean {
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

    private fun containsReelsAdSignal(
        value: Any?, depth: Int, seen: IdentityHashMap<Any, Boolean>
    ): Boolean {
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
            }
            cur = cur.superclass
        }
        map.values.toList()
    }

    private fun fieldsFor(type: Class<*>) = fieldCache.getOrPut(type) {
        val list = ArrayList<Field>()
        var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java && list.size < 24) {
            cur.declaredFields.forEach { f -> if (!Modifier.isStatic(f.modifiers) && list.size < 24) { f.isAccessible = true; list.add(f) } }
            cur = cur.superclass
        }
        list
    }

    private fun stringMethodsFor(type: Class<*>) = allMethodsFor(type).asSequence()
        .filter { m -> m.parameterCount == 0 && m.returnType == String::class.java && m.name != "toString" }
        .take(12).onEach { it.isAccessible = true }.toList()

    private fun allMethodsFor(type: Class<*>): List<Method> {
        val map = LinkedHashMap<String, Method>()
        var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java) {
            cur.declaredMethods.forEach { m -> if (!Modifier.isStatic(m.modifiers)) { m.isAccessible = true; map.putIfAbsent("${cur.name}#${m.name}/${m.parameterCount}", m) } }
            cur = cur.superclass
        }
        return map.values.toList()
    }

    private fun isReelsAdSignalText(v: String?): Boolean {
        if (v.isNullOrBlank()) return false
        val n = v.lowercase()
        return REELS_AD_SIGNAL_TOKENS.any { n.contains(it) }
    }
}

// ─── FeedItemInspector ────────────────────────────────────────────────────────

class FeedItemInspector(itemContractTypes: Collection<Class<*>>) {
    private val itemModelAccessor   = resolveItemModelAccessor(itemContractTypes)
    private val itemEdgeAccessor    = resolveItemEdgeAccessor(itemContractTypes)
    private val itemNetworkAccessor = resolveItemNetworkAccessor(itemContractTypes)
    private val categoryMethodCache  = ConcurrentHashMap<Class<*>, Method>()
    private val edgeAccessorCache    = ConcurrentHashMap<Class<*>, Method>()
    private val feedUnitAccessorCache = ConcurrentHashMap<Class<*>, Method>()
    private val typeNameMethodCache  = ConcurrentHashMap<Class<*>, Method>()
    private val stringAccessorCache  = ConcurrentHashMap<Class<*>, List<Method>>()
    private val stringFieldCache     = ConcurrentHashMap<Class<*>, List<Field>>()

    fun isSponsoredFeedItem(value: Any?): Boolean {
        if (isDefinitelySponsoredFeedItem(value)) return true
        val model    = invokeNoThrow(itemModelAccessor, value)
        val edge     = edgeFrom(value)
        val feedUnit = feedUnitFrom(edge)
        return containsKnownAdSignals(value) || containsKnownAdSignals(model) ||
               containsKnownAdSignals(edge)  || containsKnownAdSignals(feedUnit)
    }

    fun isDefinitelySponsoredFeedItem(value: Any?): Boolean {
        if (value == null) return false
        val model         = invokeNoThrow(itemModelAccessor, value)
        val modelCategory = readCategory(model)
        if (isSafeFeedContainerCategory(modelCategory)) return false
        if (isSponsoredFeedCategory(modelCategory))     return true
        val edge         = edgeFrom(value)
        val edgeCategory = readCategory(edge)
        if (isSafeFeedContainerCategory(edgeCategory)) return false
        if (isSponsoredFeedCategory(edgeCategory))     return true
        val feedUnit      = feedUnitFrom(edge)
        val unitClassName = feedUnit?.javaClass?.name
        if (unitClassName == GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS || unitClassName == GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS) return true
        val typeName = readTypeName(feedUnit)
        return isLikelyAdTypeName(typeName) || isAdSignalText(unitClassName)
    }

    fun describe(item: Any?): String {
        if (item == null) return "null"
        val edge      = edgeFrom(item)
        val feedUnit  = feedUnitFrom(edge)
        val category  = readCategory(invokeNoThrow(itemModelAccessor, item)) ?: readCategory(edge) ?: "unknown"
        val network   = invokeNoThrow(itemNetworkAccessor, item)?.toString() ?: "unknown"
        val unitClass = feedUnit?.javaClass?.name ?: "null"
        val typeName  = readTypeName(feedUnit) ?: "unknown"
        return "cat=$category isAd=${isSponsoredFeedItem(item)} network=$network wrapper=${item.javaClass.name} unit=$unitClass type=$typeName"
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
        cache[type]?.let { return it }
        val resolved = resolver() ?: return null
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
            .sortedByDescending { m -> scoreChildAccessor(m.returnType) }
            .firstOrNull { m -> acceptsValue(invokeNoThrow(m.apply { isAccessible = true }, target)) }

    private fun scoreChildAccessor(type: Class<*>) = when {
        type.name == GRAPHQL_FEED_UNIT_EDGE_CLASS                         -> 4
        type.name.startsWith("com.facebook.graphql.model.")               -> 3
        type.name.startsWith("com.facebook.")                             -> 2
        !type.name.startsWith("java.") && !type.name.startsWith("javax.") &&
        !type.name.startsWith("android.") && !type.name.startsWith("kotlin.") -> 1
        else                                                               -> 0
    }

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
        val list = ArrayList<Field>()
        var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java && list.size < 12) {
            cur.declaredFields.forEach { f -> if (!Modifier.isStatic(f.modifiers) && f.type == String::class.java && list.size < 12) { f.isAccessible = true; list.add(f) } }
            cur = cur.superclass
        }
        list
    }

    fun isAdSignalText(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val n = value.lowercase()
        return FEED_AD_SIGNAL_TOKENS.any { n.contains(it) }
    }

    private fun isSponsoredFeedCategory(v: String?)    = v != null && v in FEED_AD_CATEGORY_VALUES
    private fun isSafeFeedContainerCategory(v: String?) = v != null && v in FEED_SAFE_CONTAINER_CATEGORY_VALUES
    private fun isLikelyAdTypeName(v: String?)          = v != null && (v.contains("QuickPromotion", ignoreCase = true) || isAdSignalText(v))

    private fun allInstanceMethods(type: Class<*>): List<Method> {
        val map = LinkedHashMap<String, Method>()
        var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java) {
            cur.declaredMethods.forEach { m -> if (!Modifier.isStatic(m.modifiers)) { m.isAccessible = true; map.putIfAbsent("${cur.name}#${m.name}/${m.parameterCount}", m) } }
            cur.interfaces.forEach { iface -> iface.declaredMethods.forEach { m -> if (!Modifier.isStatic(m.modifiers)) { m.isAccessible = true; map.putIfAbsent("${iface.name}#${m.name}/${m.parameterCount}", m) } } }
            cur = cur.superclass
        }
        return map.values.toList()
    }

    private fun invokeNoThrow(method: Method?, target: Any?) =
        if (method == null || target == null) null else runCatching { method.invoke(target) }.getOrNull()
}

// ─── Logging ──────────────────────────────────────────────────────────────────

fun logHookHitThrottled(hookName: String, method: Method, detail: String? = null) {
    val hits = hookHitCounters.computeIfAbsent(hookName) { AtomicInteger(0) }.incrementAndGet()
    if (hits <= 3 || hits % HOOK_HIT_LOG_EVERY == 0) {
        val extra = detail?.let { " $it" } ?: ""
        XposedBridge.log("[$FB_TAG] Hit $hookName #$hits at ${method.declaringClass.name}.${method.name}$extra")
    }
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
            if (inspector.containsAdStory(param.thisObject)) param.result = arrayListOf<Any?>()
        }
        override fun afterHookedMethod(param: MethodHookParam) {
            val result = param.result as? MutableList<Any?> ?: return
            val removed = filterAdItems(result, inspector)
            if (removed > 0) XposedBridge.log("[$FB_TAG] Removed $removed ad plugin item(s)")
        }
    })
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
    poolClass.declaredMethods.filter { m ->
        !Modifier.isStatic(m.modifiers) && m.parameterCount == 0 && List::class.java.isAssignableFrom(m.returnType)
    }.forEach { m ->
        m.isAccessible = true
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { param.result = arrayListOf<Any?>() }
        })
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
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { buildSponsoredEmptyResult(m.returnType)?.let { param.result = it } }
        })
        hooked++
    }
    XposedBridge.log("[$FB_TAG] Hooked $hooked pool result method(s) on ${poolClass.name}")
}

// ─── Hook installers – Story ad providers ────────────────────────────────────

fun hookStoryAdsNoOp(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
    })
}

fun hookStoryAdsMerge(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.args.getOrNull(2)?.let { param.result = it }
        }
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
            if (!resolveGameAdPayload(param.thisObject, payload))
                rejectGameAdPayload(param.thisObject, payload)
            param.result = null
        }
    })
}

fun hookGameAdBridge(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val raw     = param.args.getOrNull(0) as? String ?: return
            val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return
            val type    = payload.optString("type")
            if (type !in GAME_AD_MESSAGE_TYPES) return
            if (!resolveGameAdPayload(param.thisObject, payload, type))
                rejectGameAdPayload(param.thisObject, payload)
            param.result = null
        }
    })
}

fun hookPlayableAdActivity(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as? Activity ?: return
            if (activity.javaClass.name != method.declaringClass.name) return
            finishGameAdActivity(activity)
        }
    })
}

fun hookGlobalGameAdActivityLifecycleFallback() {
    val onResume = (Activity::class.java.declaredMethods + Activity::class.java.methods)
        .firstOrNull { m -> m.name == "onResume" && m.parameterCount == 0 }
        ?.apply { isAccessible = true } ?: return
    XposedBridge.hookMethod(onResume, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as? Activity ?: return
            if (activity.javaClass.name !in GAME_AD_ACTIVITY_CLASS_NAMES) return
            finishGameAdActivity(activity)
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
        }.forEach { m ->
            m.isAccessible = true
            val sig = "${m.declaringClass.name}.${m.name}(${m.parameterTypes.joinToString(",") { it.name }})"
            methods.putIfAbsent(sig, m)
        }
    }
    var hooked = 0
    methods.values.forEach { m ->
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
                    val target = intent.component?.className ?: return
                    if (target !in HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES) return
                    param.result = if (m.returnType == Boolean::class.javaPrimitiveType) false else null
                }
            })
            hooked++
        }
    }
    XposedBridge.log("[$FB_TAG] Hooked $hooked game ad activity launch fallback(s)")
}

// ─── Game ad payload helpers ──────────────────────────────────────────────────

fun resolveGameAdPayload(target: Any?, payload: Any?, messageType: String? = null): Boolean {
    if (target == null || payload == null) return false
    val promiseId     = extractPromiseId(payload) ?: return false
    val resolveMethod = resolveGameAdResolveMethod(target.javaClass) ?: return false
    val successPayload = buildGameAdSuccessPayload(payload, messageType)
    return runCatching { resolveMethod.invoke(target, promiseId, successPayload); true }.getOrElse { false }
}

fun rejectGameAdPayload(target: Any?, payload: Any?): Boolean {
    if (target == null || payload == null) return false
    resolveGameAdBridgeRejectMethod(target.javaClass)?.let { m ->
        if (runCatching { m.invoke(target, GAME_AD_REJECTION_MESSAGE, GAME_AD_REJECTION_CODE, payload); true }.getOrElse { false }) return true
    }
    val promiseId    = extractPromiseId(payload) ?: return false
    val rejectMethod = resolveGameAdRejectMethod(target.javaClass) ?: return false
    return runCatching { rejectMethod.invoke(target, promiseId, GAME_AD_REJECTION_MESSAGE, GAME_AD_REJECTION_CODE); true }.getOrElse { false }
}

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
        m.parameterTypes[0] == String::class.java && m.parameterTypes[1] == String::class.java &&
        m.parameterTypes[2] == JSONObject::class.java
    }?.apply { isAccessible = true }
}

private fun resolveGameAdRejectMethod(type: Class<*>?): Method? {
    if (type == null) return null
    return (type.declaredMethods + type.methods).firstOrNull { m ->
        !Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE &&
        m.parameterCount == 3 && m.parameterTypes.all { it == String::class.java }
    }?.apply { isAccessible = true }
}

fun buildGameAdSuccessPayload(payload: Any?, messageType: String? = null): JSONObject {
    val effectiveType      = messageType ?: (payload as? JSONObject)?.optString("type").orEmpty()
    val content            = (payload as? JSONObject)?.optJSONObject("content")
    val placementId        = content?.optString("placementID")?.takeIf { it.isNotBlank() }
    val requestedInstId    = content?.optString("adInstanceID")?.takeIf { it.isNotBlank() }
    val bannerPosition     = content?.optString("bannerPosition")?.takeIf { it.isNotBlank() }
    val result             = JSONObject()
    placementId?.let   { result.put("placementID", it) }
    bannerPosition?.let { result.put("bannerPosition", it) }
    val adInstanceId = when {
        requestedInstId != null -> { gameAdInstanceIds.putIfAbsent(requestedInstId, requestedInstId); requestedInstId }
        placementId != null && effectiveType != "loadbanneradasync" -> resolveGameAdInstanceId(placementId, effectiveType, bannerPosition)
        else -> null
    }
    adInstanceId?.let { result.put("adInstanceID", it) }
    return result
}

private fun resolveGameAdInstanceId(placementId: String, messageType: String?, bannerPosition: String?): String {
    val key = listOf(messageType.orEmpty(), placementId, bannerPosition.orEmpty()).joinToString("|")
    return gameAdInstanceIds.computeIfAbsent(key) {
        "${GAME_AD_SUCCESS_INSTANCE_PREFIX}_${key.hashCode().toLong() and 0xffffffffL}"
    }
}

fun extractPromiseId(payload: Any?): String? {
    val jClass = payload?.javaClass ?: return null
    if (jClass.name != "org.json.JSONObject") return null
    val getJSONObject = (jClass.declaredMethods + jClass.methods).firstOrNull { m -> m.name == "getJSONObject" && m.parameterCount == 1 && m.parameterTypes[0] == String::class.java }?.apply { isAccessible = true } ?: return null
    val getString     = (jClass.declaredMethods + jClass.methods).firstOrNull { m -> m.name == "getString"     && m.parameterCount == 1 && m.parameterTypes[0] == String::class.java }?.apply { isAccessible = true } ?: return null
    val content       = runCatching { getJSONObject.invoke(payload, "content") }.getOrNull() ?: return null
    return runCatching { getString.invoke(content, "promiseID") as? String }.getOrNull()
}

private fun finishGameAdActivity(activity: Activity) {
    if (activity.isFinishing) return
    activity.setResult(Activity.RESULT_CANCELED)
    activity.finish()
}

// ─── List / result manipulation helpers ──────────────────────────────────────

fun filterAdItems(list: MutableList<Any?>, inspector: AdStoryInspector): Int {
    var removed = 0
    val it = list.iterator()
    while (it.hasNext()) { if (inspector.containsAdStory(it.next())) { it.remove(); removed++ } }
    return removed
}

fun buildImmutableListLike(sample: Any?, items: List<Any?>): Any? {
    if (sample == null) return null
    return runCatching {
        val cl = Class.forName("com.google.common.collect.ImmutableList", false, sample.javaClass.classLoader)
        cl.getDeclaredMethod("copyOf", Iterable::class.java).invoke(null, items)
    }.getOrNull()
}

fun replaceFeedItemsInResult(param: XC_MethodHook.MethodHookParam, items: List<Any?>): Boolean {
    val result  = param.result ?: return false
    val rebuilt = rebuildFeedResult(result, items) ?: return false
    param.result = rebuilt
    return true
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
        c.parameterCount == 5 &&
        c.parameterTypes.getOrNull(0)?.name == "com.google.common.collect.ImmutableList" &&
        c.parameterTypes.getOrNull(1) == IntArray::class.java &&
        c.parameterTypes.drop(2).all { it == Int::class.javaPrimitiveType }
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
    val ctor       = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return false
    val reasonType = ctor.parameterTypes.getOrNull(1) ?: return false
    return reasonType.enumConstants?.any { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" } == true
}

fun buildSponsoredEmptyResult(type: Class<*>): Any? {
    val ctor       = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return null
    val reasonType = ctor.parameterTypes.getOrNull(1) ?: return null
    val emptyReason = reasonType.enumConstants?.firstOrNull { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" }
        ?: reasonType.enumConstants?.firstOrNull { it.toString() == "FAIL" } ?: return null
    ctor.isAccessible = true
    return runCatching { ctor.newInstance(null, emptyReason) }.getOrNull()
}

// ─── Litho render method detection ───────────────────────────────────────────

fun resolveLithoRenderMethod(componentClass: Class<*>): Method? =
    componentClass.declaredMethods.firstOrNull { m ->
        !Modifier.isStatic(m.modifiers) && !m.isBridge && !m.isSynthetic &&
        m.parameterCount == 1 && !m.returnType.isPrimitive && m.returnType != Void.TYPE &&
        m.returnType != Any::class.java && m.returnType.isAssignableFrom(componentClass)
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
        m.parameterTypes[0].name == "com.google.common.collect.ImmutableList" &&
        m.parameterTypes[1] == Int::class.javaPrimitiveType
    }?.apply { isAccessible = true }
    val deferredUpdateMethod = methods.firstOrNull { m ->
        !Modifier.isStatic(m.modifiers) && m.parameterCount == 2 && m.returnType == Void.TYPE &&
        m.parameterTypes[1].name == "com.google.common.collect.ImmutableList"
    }?.apply { isAccessible = true }
    val insertionTriggerMethod = if (!includeInsertionTrigger) null else {
        methods.firstOrNull { m -> !Modifier.isStatic(m.modifiers) && m.parameterCount == 0 && m.returnType == Void.TYPE }
            ?.apply { isAccessible = true }
    }
    return StoryAdProviderHooks(providerClass, mergeMethod, fetchMoreAdsMethod, deferredUpdateMethod, insertionTriggerMethod)
}

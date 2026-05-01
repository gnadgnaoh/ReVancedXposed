package io.github.nexalloy.revanced.facebook.ads

import android.app.Activity
import android.content.Intent
import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

// ─── Constants ────────────────────────────────────────────────────────────────

const val FB_BEFORE_SIZE_EXTRA = "nexalloy_fb_before_size"

const val GRAPHQL_FEED_UNIT_EDGE_CLASS = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
const val GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS = "com.facebook.graphql.model.GraphQLFBMultiAdsFeedUnit"
const val GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS =
    "com.facebook.graphql.model.GraphQLQuickPromotionNativeTemplateFeedUnit"

const val AUDIENCE_NETWORK_ACTIVITY_CLASS = "com.facebook.ads.AudienceNetworkActivity"
const val AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS =
    "com.facebook.ads.internal.ipc.AudienceNetworkRemoteActivity"
const val NEKO_PLAYABLE_ACTIVITY_CLASS =
    "com.facebook.neko.playables.activity.NekoPlayableAdActivity"

private const val GAME_AD_REJECTION_MESSAGE = "Game ad request blocked"
private const val GAME_AD_REJECTION_CODE = "CLIENT_UNSUPPORTED_OPERATION"
private const val GAME_AD_SUCCESS_INSTANCE_PREFIX = "nexalloy_fb_noop_ad"

val GAME_AD_MESSAGE_TYPES = setOf(
    "getinterstitialadasync",
    "getrewardedvideoasync",
    "getrewardedinterstitialasync",
    "loadadasync",
    "showadasync",
    "loadbanneradasync"
)

val GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
    AUDIENCE_NETWORK_ACTIVITY_CLASS,
    AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS,
    NEKO_PLAYABLE_ACTIVITY_CLASS
)

val HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES = setOf(NEKO_PLAYABLE_ACTIVITY_CLASS)

val GAME_AD_METHOD_TAGS = listOf(
    "Invalid JSON content received by onGetInterstitialAdAsync: ",
    "Invalid JSON content received by onGetRewardedInterstitialAsync: ",
    "Invalid JSON content received by onRewardedVideoAsync: ",
    "Invalid JSON content received by onLoadAdAsync: ",
    "Invalid JSON content received by onShowAdAsync: "
)

/**
 * Enum values recognized as sponsored/ad categories.
 * "BANNER" and "ADVERTISEMENT" catch banner ads in Reels.
 */
private val FEED_AD_CATEGORY_VALUES = setOf(
    "SPONSORED",
    "PROMOTION",
    "AD",
    "ADVERTISEMENT",
    "BANNER"
)

/**
 * Safe container categories that should never be filtered (e.g. organic Reels carousels).
 * Source: Patches.kt FEED_SAFE_CONTAINER_CATEGORY_VALUES [NEW]
 */
private val FEED_SAFE_CONTAINER_CATEGORY_VALUES = setOf(
    "FB_SHORTS",
    "MULTI_FB_STORIES_TRAY"
)

/**
 * Token nhận diện quảng cáo qua string scanning cho feed items.
 */
private val FEED_AD_SIGNAL_TOKENS = listOf(
    "sponsored",
    "promotion",
    "multiads",
    "quickpromotion",
    "reels_banner_ad",
    "reelsbannerads",
    "reels_post_loop_deferred_card",
    "deferred_card",
    "adbreakdeferredcta",
    "instreamadidlewithbannerstate",
    "instream_legacy_banner_ad",
    "unified_player_banner_ad",
    "banner_ad_",
    "floatingcta"
)

/**
 * Token nhận diện quảng cáo dành riêng cho Reels story objects.
 * Tách riêng khỏi FEED_AD_SIGNAL_TOKENS để tránh false-positive với
 * reels_post_loop_deferred_card / deferred_card / floatingcta (organic content).
 * Source: Patches.kt REELS_AD_SIGNAL_TOKENS [NEW]
 */
private val REELS_AD_SIGNAL_TOKENS = listOf(
    "sponsored",
    "promotion",
    "multiads",
    "quickpromotion",
    "reels_banner_ad",
    "reelsbannerads",
    "adbreakdeferredcta",
    "instreamadidlewithbannerstate",
    "instream_legacy_banner_ad",
    "unified_player_banner_ad",
    "banner_ad_"
)

private val gameAdInstanceIds = ConcurrentHashMap<String, String>()

// ─── AdStoryInspector ─────────────────────────────────────────────────────────

/**
 * Inspects arbitrary feed-story objects to detect ad-backed stories.
 *
 * Nâng cấp từ Patches.kt:
 *  - Dùng logic AND: containsAdKind() AND containsReelsAdSignal() để giảm false-positive
 *    với organic Reels carousels. Story chỉ bị lọc khi vừa có AD enum VÀ có signal token.
 *  - Tách riêng REELS_AD_SIGNAL_TOKENS (bộ token nhỏ hơn, chặt hơn).
 *  - Thêm stringMethodsFor() để scan String-returning methods trực tiếp.
 */
class AdStoryInspector(private val adKindEnumClass: Class<*>) {

    private val enumMethodCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val stringMethodCache = ConcurrentHashMap<Class<*>, List<Method>>()

    /**
     * Returns true only when the object BOTH contains an AD enum value
     * AND contains a known Reels ad signal token. The AND condition prevents
     * organic Reels carousels (which sometimes contain AD-like enum values for
     * unrelated reasons) from being incorrectly filtered.
     */
    fun containsAdStory(
        value: Any?,
        depth: Int = 0,
        seen: IdentityHashMap<Any, Boolean> = IdentityHashMap()
    ): Boolean {
        return containsAdKind(value, depth, seen) &&
            containsReelsAdSignal(value, 0, IdentityHashMap())
    }

    private fun containsAdKind(
        value: Any?,
        depth: Int = 0,
        seen: IdentityHashMap<Any, Boolean> = IdentityHashMap()
    ): Boolean {
        if (value == null || depth > 4) return false
        if (isAdKind(value)) return true

        val type = value.javaClass
        if (type.isPrimitive || value is String || value is Number ||
            value is Boolean || value is CharSequence
        ) return false
        if (seen.put(value, true) != null) return false

        if (value is Iterable<*>) {
            var checked = 0
            for (item in value) {
                if (containsAdKind(item, depth + 1, seen)) return true
                if (++checked >= 8) break
            }
        }
        if (type.isArray) {
            val array = value as? Array<*>
            if (array != null) {
                var checked = 0
                for (item in array) {
                    if (containsAdKind(item, depth + 1, seen)) return true
                    if (++checked >= 8) break
                }
            }
        }
        for (method in enumMethodsFor(type)) {
            if (isAdKind(runCatching { method.invoke(value) }.getOrNull())) return true
        }
        for (field in fieldsFor(type)) {
            if (containsAdKind(runCatching { field.get(value) }.getOrNull(), depth + 1, seen)) return true
        }
        return false
    }

    private fun containsReelsAdSignal(
        value: Any?,
        depth: Int,
        seen: IdentityHashMap<Any, Boolean>
    ): Boolean {
        if (value == null || depth > 4) return false

        if (value is CharSequence) return isReelsAdSignalText(value.toString())

        val type = value.javaClass
        if (isReelsAdSignalText(type.name)) return true
        if (type.isEnum) return isReelsAdSignalText(value.toString())
        if (type.isPrimitive || value is Number || value is Boolean) return false
        if (seen.put(value, true) != null) return false

        if (value is Iterable<*>) {
            var checked = 0
            for (item in value) {
                if (containsReelsAdSignal(item, depth + 1, seen)) return true
                if (++checked >= 8) break
            }
        }
        if (type.isArray) {
            val array = value as? Array<*>
            if (array != null) {
                var checked = 0
                for (item in array) {
                    if (containsReelsAdSignal(item, depth + 1, seen)) return true
                    if (++checked >= 8) break
                }
            }
        }
        if (isReelsAdSignalText(runCatching { value.toString() }.getOrNull())) return true
        for (method in stringMethodsFor(type)) {
            if (isReelsAdSignalText(runCatching { method.invoke(value) as? String }.getOrNull())) return true
        }
        for (field in fieldsFor(type)) {
            if (containsReelsAdSignal(runCatching { field.get(value) }.getOrNull(), depth + 1, seen)) return true
        }
        return false
    }

    private fun isAdKind(value: Any?) =
        value != null && value.javaClass == adKindEnumClass && value.toString() == "AD"

    private fun isReelsAdSignalText(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val normalized = value.lowercase()
        return REELS_AD_SIGNAL_TOKENS.any { token -> normalized.contains(token) }
    }

    private fun enumMethodsFor(type: Class<*>): List<Method> = enumMethodCache.getOrPut(type) {
        val methods = LinkedHashMap<String, Method>()
        var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java) {
            cur.declaredMethods.forEach { m ->
                if (!Modifier.isStatic(m.modifiers) && m.parameterCount == 0 && m.returnType == adKindEnumClass) {
                    m.isAccessible = true
                    methods.putIfAbsent("${cur!!.name}#${m.name}", m)
                }
            }
            cur = cur.superclass
        }
        methods.values.toList()
    }

    private fun fieldsFor(type: Class<*>): List<Field> = fieldCache.getOrPut(type) {
        val fields = ArrayList<Field>()
        var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java && fields.size < 24) {
            cur.declaredFields.forEach { f ->
                if (!Modifier.isStatic(f.modifiers) && fields.size < 24) {
                    f.isAccessible = true
                    fields.add(f)
                }
            }
            cur = cur.superclass
        }
        fields
    }

    private fun stringMethodsFor(type: Class<*>): List<Method> = stringMethodCache.getOrPut(type) {
        val methods = LinkedHashMap<String, Method>()
        var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java) {
            cur.declaredMethods.forEach { m ->
                if (!Modifier.isStatic(m.modifiers) && m.parameterCount == 0 &&
                    m.returnType == String::class.java && m.name != "toString"
                ) {
                    m.isAccessible = true
                    methods.putIfAbsent("${cur!!.name}#${m.name}", m)
                }
            }
            cur = cur.superclass
        }
        methods.values.take(12).toList()
    }
}

// ─── FeedItemInspector ────────────────────────────────────────────────────────

/**
 * Inspects CSR-filtered feed items to detect sponsored/promoted/banner content.
 *
 * Nâng cấp từ Patches.kt:
 *  - Thêm [isDefinitelySponsoredFeedItem] — strict check chỉ dùng category/class/typeName,
 *    không scan string tokens. Dùng cho storyPoolAdd để giảm false-positive Reels carousel.
 *  - Thêm [FEED_SAFE_CONTAINER_CATEGORY_VALUES] — sớm trả về false khi category là FB_SHORTS
 *    hay MULTI_FB_STORIES_TRAY để bảo vệ organic Reels.
 *  - Thêm [stringFieldsFor] — scan String fields thực sự của object (bổ sung cho string methods).
 *  - Thêm [cachedMethod] helper thread-safe cho ConcurrentHashMap caching.
 *  - Thêm [describe] — log helper cho debug.
 *  - Bỏ filter SAFE_STRING_METHOD_NAMES: scan toàn bộ String methods/fields thay vì whitelist.
 */
class FeedItemInspector(itemContractTypes: Collection<Class<*>>) {

    private val itemModelAccessor = resolveItemModelAccessor(itemContractTypes)
    private val itemEdgeAccessor = resolveItemEdgeAccessor(itemContractTypes)
    private val itemNetworkAccessor = resolveItemNetworkAccessor(itemContractTypes)

    private val categoryMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val edgeAccessorCache = ConcurrentHashMap<Class<*>, Method>()
    private val feedUnitAccessorCache = ConcurrentHashMap<Class<*>, Method>()
    private val typeNameMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val stringAccessorCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val stringFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    /**
     * Full sponsored check: category + class name + type name + string signal scan.
     * Dùng cho CSR filter, late-stage sanitizer.
     */
    fun isSponsoredFeedItem(value: Any?): Boolean {
        if (isDefinitelySponsoredFeedItem(value)) return true

        // String-signal scan on all layers (catches banner/deferred-card/floatingCTA)
        if (containsKnownAdSignals(value)) return true
        if (containsKnownAdSignals(invokeNoThrow(itemModelAccessor, value))) return true
        val edge = edgeFrom(value)
        if (containsKnownAdSignals(edge)) return true
        if (containsKnownAdSignals(feedUnitFrom(edge))) return true

        return false
    }

    /**
     * Strict sponsored check: only category enum, known ad class names, and type name.
     * Does NOT scan string tokens to avoid false-positives with organic Reels carousels.
     * Dùng cho storyPoolAdd hook (nguồn: Patches.kt hookStoryPoolAdd).
     */
    fun isDefinitelySponsoredFeedItem(value: Any?): Boolean {
        if (value == null) return false

        val model = invokeNoThrow(itemModelAccessor, value)
        val modelCategory = readCategory(model)
        if (isSafeFeedContainerCategory(modelCategory)) return false
        if (isSponsoredFeedCategory(modelCategory)) return true

        val edge = edgeFrom(value)
        val edgeCategory = readCategory(edge)
        if (isSafeFeedContainerCategory(edgeCategory)) return false
        if (isSponsoredFeedCategory(edgeCategory)) return true

        val feedUnit = feedUnitFrom(edge)
        val unitClassName = feedUnit?.javaClass?.name
        if (unitClassName == GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS ||
            unitClassName == GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS
        ) return true

        val typeName = readTypeName(feedUnit)
        if (isLikelyAdTypeName(typeName) || isAdSignalText(unitClassName)) return true

        return false
    }

    /**
     * Debug description of a feed item.
     */
    fun describe(item: Any?): String {
        if (item == null) return "null"
        val edge = edgeFrom(item)
        val feedUnit = feedUnitFrom(edge)
        val category = readCategory(invokeNoThrow(itemModelAccessor, item))
            ?: readCategory(edge)
            ?: "unknown"
        val network = invokeNoThrow(itemNetworkAccessor, item)?.toString() ?: "unknown"
        val unitClass = feedUnit?.javaClass?.name ?: "null"
        val typeName = readTypeName(feedUnit) ?: "unknown"
        return "cat=$category isAd=${isSponsoredFeedItem(item)} network=$network wrapper=${item.javaClass.name} unit=$unitClass type=$typeName"
    }

    private fun edgeFrom(value: Any?): Any? {
        if (value == null) return null
        if (value.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS) return value
        invokeNoThrow(itemEdgeAccessor, value)
            ?.takeIf { it.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS }
            ?.let { return it }
        val fallback = cachedMethod(edgeAccessorCache, value.javaClass) {
            resolveChildAccessor(value) { it != null && it.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS }
        }
        return invokeNoThrow(fallback, value)
    }

    private fun feedUnitFrom(edge: Any?): Any? {
        if (edge == null) return null
        val accessor = cachedMethod(feedUnitAccessorCache, edge.javaClass) {
            resolveChildAccessor(edge) { v ->
                val n = v?.javaClass?.name
                n == GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS ||
                    n == GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS ||
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
                    m.returnType.enumConstants?.any { c ->
                        val n = c.toString(); n == "SPONSORED" || n == "PROMOTION"
                    } == true
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

    /**
     * Thread-safe nullable method cache helper.
     * Avoids repeated reflection on the same class even when the result is null.
     * Source: Patches.kt cachedMethod [NEW]
     */
    private fun cachedMethod(
        cache: ConcurrentHashMap<Class<*>, Method>,
        type: Class<*>,
        resolver: () -> Method?
    ): Method? {
        cache[type]?.let { return it }
        val resolved = resolver() ?: return null
        return cache.putIfAbsent(type, resolved) ?: resolved
    }

    private fun resolveItemModelAccessor(types: Collection<Class<*>>) =
        types.asSequence().flatMap { allInstanceMethods(it).asSequence() }
            .firstOrNull { m ->
                m.parameterCount == 0 && !m.returnType.isPrimitive &&
                    m.returnType != Any::class.java && m.returnType != String::class.java && !m.returnType.isEnum
            }?.apply { isAccessible = true }

    private fun resolveItemEdgeAccessor(types: Collection<Class<*>>) =
        types.asSequence().flatMap { allInstanceMethods(it).asSequence() }
            .firstOrNull { m ->
                m.parameterCount == 0 &&
                    (m.returnType == Any::class.java || m.returnType.name == GRAPHQL_FEED_UNIT_EDGE_CLASS)
            }?.apply { isAccessible = true }

    private fun resolveItemNetworkAccessor(types: Collection<Class<*>>) =
        types.asSequence().flatMap { allInstanceMethods(it).asSequence() }
            .firstOrNull { m ->
                m.parameterCount == 0 && m.returnType == Boolean::class.javaPrimitiveType
            }?.apply { isAccessible = true }

    private fun resolveChildAccessor(target: Any, accepts: (Any?) -> Boolean): Method? =
        allInstanceMethods(target.javaClass).asSequence()
            .filter { m ->
                m.parameterCount == 0 && !m.returnType.isPrimitive &&
                    m.returnType != Void.TYPE && m.returnType != String::class.java &&
                    !m.returnType.isEnum && m.declaringClass != Any::class.java
            }
            .sortedByDescending { scoreChildAccessor(it.returnType) }
            .firstOrNull { accepts(invokeNoThrow(it.apply { isAccessible = true }, target)) }

    private fun scoreChildAccessor(type: Class<*>) = when {
        type.name == GRAPHQL_FEED_UNIT_EDGE_CLASS -> 4
        type.name.startsWith("com.facebook.graphql.model.") -> 3
        type.name.startsWith("com.facebook.") -> 2
        !type.name.startsWith("java.") && !type.name.startsWith("javax.") &&
            !type.name.startsWith("android.") && !type.name.startsWith("kotlin.") -> 1
        else -> 0
    }

    private fun isSponsoredFeedCategory(v: String?) =
        v != null && v in FEED_AD_CATEGORY_VALUES

    private fun isSafeFeedContainerCategory(v: String?) =
        v != null && v in FEED_SAFE_CONTAINER_CATEGORY_VALUES

    private fun isLikelyAdTypeName(value: String?): Boolean {
        if (value == null) return false
        if (value.contains("QuickPromotion", ignoreCase = true)) return true
        return isAdSignalText(value)
    }

    private fun containsKnownAdSignals(value: Any?): Boolean {
        if (value == null) return false
        if (value is CharSequence) return isAdSignalText(value.toString())
        val type = value.javaClass
        if (isAdSignalText(type.name)) return true
        if (type.isEnum) return isAdSignalText(value.toString())
        if (type.isPrimitive || value is Number || value is Boolean) return false
        if (isAdSignalText(runCatching { value.toString() }.getOrNull())) return true
        for (method in stringAccessorsFor(type)) {
            if (isAdSignalText(invokeNoThrow(method, value) as? String)) return true
        }
        // Scan String fields directly (nguồn: Patches.kt stringFieldsFor [NEW])
        for (field in stringFieldsFor(type)) {
            if (isAdSignalText(runCatching { field.get(value) as? String }.getOrNull())) return true
        }
        return false
    }

    private fun stringAccessorsFor(type: Class<*>): List<Method> =
        stringAccessorCache.getOrPut(type) {
            allInstanceMethods(type).asSequence()
                .filter { m ->
                    m.parameterCount == 0 &&
                        m.returnType == String::class.java &&
                        m.declaringClass != Any::class.java &&
                        m.name != "toString"
                }
                .take(12)
                .onEach { m -> m.isAccessible = true }
                .toList()
        }

    /**
     * Scan non-static String fields directly — catches obfuscated fields that have no accessor.
     * Source: Patches.kt stringFieldsFor [NEW]
     */
    private fun stringFieldsFor(type: Class<*>): List<Field> =
        stringFieldCache.getOrPut(type) {
            val fields = ArrayList<Field>()
            var cur: Class<*>? = type
            while (cur != null && cur != Any::class.java && fields.size < 12) {
                cur.declaredFields.forEach { f ->
                    if (!Modifier.isStatic(f.modifiers) && f.type == String::class.java && fields.size < 12) {
                        f.isAccessible = true
                        fields.add(f)
                    }
                }
                cur = cur.superclass
            }
            fields
        }

    private fun isAdSignalText(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val normalized = value.lowercase()
        return FEED_AD_SIGNAL_TOKENS.any { token -> normalized.contains(token) }
    }

    private fun allInstanceMethods(type: Class<*>): List<Method> {
        val methods = LinkedHashMap<String, Method>()
        var cur: Class<*>? = type
        while (cur != null && cur != Any::class.java) {
            cur.declaredMethods.forEach { m ->
                if (!Modifier.isStatic(m.modifiers)) {
                    m.isAccessible = true
                    methods.putIfAbsent("${cur!!.name}#${m.name}/${m.parameterCount}", m)
                }
            }
            cur.interfaces.forEach { iface ->
                iface.declaredMethods.forEach { m ->
                    if (!Modifier.isStatic(m.modifiers)) {
                        m.isAccessible = true
                        methods.putIfAbsent("${iface.name}#${m.name}/${m.parameterCount}", m)
                    }
                }
            }
            cur = cur.superclass
        }
        return methods.values.toList()
    }

    private fun invokeNoThrow(method: Method?, target: Any?) =
        if (method == null || target == null) null
        else runCatching { method.invoke(target) }.getOrNull()
}

// ─── Feed helpers ─────────────────────────────────────────────────────────────

fun filterAdItems(list: MutableList<Any?>, inspector: AdStoryInspector): Int {
    var removed = 0
    val it = list.iterator()
    while (it.hasNext()) {
        if (inspector.containsAdStory(it.next())) { it.remove(); removed++ }
    }
    return removed
}

fun buildImmutableListLike(sample: Any?, items: List<Any?>): Any? {
    if (sample == null) return null
    return runCatching {
        val cls = Class.forName(
            "com.google.common.collect.ImmutableList", false, sample.javaClass.classLoader
        )
        cls.getDeclaredMethod("copyOf", Iterable::class.java).invoke(null, items)
    }.getOrNull()
}

fun replaceFeedItemsInResult(param: MethodHookParam, items: List<Any?>): Boolean {
    val result = param.result ?: return false
    param.result = rebuildFeedResult(result, items) ?: return false
    return true
}

private fun rebuildFeedResult(result: Any, items: List<Any?>): Any? {
    val type = result.javaClass
    val fields = runCatching { type.declaredFields.onEach { it.isAccessible = true } }.getOrNull() ?: return null
    val listField = fields.firstOrNull { !Modifier.isStatic(it.modifiers) && Iterable::class.java.isAssignableFrom(it.type) } ?: return null
    val intArrayField = fields.firstOrNull { !Modifier.isStatic(it.modifiers) && it.type == IntArray::class.java } ?: return null
    val intFields = fields.filter { !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
    if (intFields.size < 3) return null
    val originalList = runCatching { listField.get(result) }.getOrNull()
    val rebuiltList = buildImmutableListLike(originalList, items) ?: return null
    val stats = runCatching { intArrayField.get(result) as? IntArray }.getOrNull()?.clone() ?: return null
    val ints = intFields.map { f -> runCatching { f.getInt(result) }.getOrNull() ?: return null }
    val ctor = type.declaredConstructors.firstOrNull { c ->
        c.parameterCount == 5 &&
            c.parameterTypes[0]?.name == "com.google.common.collect.ImmutableList" &&
            c.parameterTypes[1] == IntArray::class.java &&
            c.parameterTypes.drop(2).all { it == Int::class.javaPrimitiveType }
    } ?: return null
    ctor.isAccessible = true
    return runCatching { ctor.newInstance(rebuiltList, stats, ints[0], ints[1], ints[2]) }.getOrNull()
}

fun extractFeedItemsFromResult(result: Any?): Iterable<*>? {
    if (result == null) return null
    if (result is Iterable<*>) return result
    return runCatching {
        val field = result.javaClass.declaredFields.firstOrNull { Iterable::class.java.isAssignableFrom(it.type) } ?: return null
        field.isAccessible = true
        field.get(result) as? Iterable<*>
    }.getOrNull()
}

fun isSponsoredResultCarrier(type: Class<*>): Boolean {
    val ctor = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return false
    return ctor.parameterTypes.getOrNull(1)?.enumConstants?.any { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" } == true
}

fun buildSponsoredEmptyResult(type: Class<*>): Any? {
    val ctor = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return null
    val reasonType = ctor.parameterTypes.getOrNull(1) ?: return null
    val emptyReason = reasonType.enumConstants?.firstOrNull { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" }
        ?: reasonType.enumConstants?.firstOrNull { it.toString() == "FAIL" } ?: return null
    ctor.isAccessible = true
    return runCatching { ctor.newInstance(null, emptyReason) }.getOrNull()
}

// ─── Game ad helpers ──────────────────────────────────────────────────────────

fun resolveGameAdPayload(target: Any?, payload: Any?, messageType: String? = null): Boolean {
    if (target == null || payload == null) return false
    val promiseId = extractPromiseId(payload) ?: run {
        Logger.printDebug { "FB GameAds: unable to extract promiseID for resolve" }; return false
    }
    val resolveMethod = resolveGameAdResolveMethod(target.javaClass) ?: run {
        Logger.printDebug { "FB GameAds: unable to find resolve helper" }; return false
    }
    val successPayload = buildGameAdSuccessPayload(payload, messageType)
    return runCatching { resolveMethod.invoke(target, promiseId, successPayload); true }
        .getOrElse { Logger.printException { "FB GameAds: resolve failed" }; false }
}

fun rejectGameAdPayload(target: Any?, payload: Any?): Boolean {
    if (target == null || payload == null) return false
    val bridgeReject = resolveGameAdBridgeRejectMethod(target.javaClass)
    if (bridgeReject != null) {
        val ok = runCatching { bridgeReject.invoke(target, GAME_AD_REJECTION_MESSAGE, GAME_AD_REJECTION_CODE, payload); true }
            .getOrElse { false }
        if (ok) return true
    }
    val promiseId = extractPromiseId(payload) ?: return false
    val rejectMethod = resolveGameAdRejectMethod(target.javaClass) ?: return false
    return runCatching { rejectMethod.invoke(target, promiseId, GAME_AD_REJECTION_MESSAGE, GAME_AD_REJECTION_CODE); true }
        .getOrElse { false }
}

private fun resolveGameAdResolveMethod(type: Class<*>?): Method? {
    if (type == null) return null
    val candidates = (type.declaredMethods + type.methods).filter { m ->
        !Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE &&
            m.parameterCount == 2 && m.parameterTypes[0] == String::class.java && !m.parameterTypes[1].isPrimitive
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
    val effectiveType = messageType ?: (payload as? JSONObject)?.optString("type").orEmpty()
    val content = (payload as? JSONObject)?.optJSONObject("content")
    val result = JSONObject()
    val placementId = content?.optString("placementID")?.takeIf { it.isNotBlank() }
    val requestedId = content?.optString("adInstanceID")?.takeIf { it.isNotBlank() }
    val bannerPos = content?.optString("bannerPosition")?.takeIf { it.isNotBlank() }
    if (placementId != null) result.put("placementID", placementId)
    if (bannerPos != null) result.put("bannerPosition", bannerPos)
    val adInstanceId = when {
        requestedId != null -> { gameAdInstanceIds.putIfAbsent(requestedId, requestedId); requestedId }
        placementId != null && effectiveType != "loadbanneradasync" ->
            resolveGameAdInstanceId(placementId, effectiveType, bannerPos)
        else -> null
    }
    if (adInstanceId != null) result.put("adInstanceID", adInstanceId)
    return result
}

private fun resolveGameAdInstanceId(placementId: String, messageType: String?, bannerPos: String?): String {
    val key = "${messageType.orEmpty()}|$placementId|${bannerPos.orEmpty()}"
    return gameAdInstanceIds.computeIfAbsent(key) {
        "${GAME_AD_SUCCESS_INSTANCE_PREFIX}_${key.hashCode().toLong() and 0xFFFFFFFFL}"
    }
}

fun extractPromiseId(payload: Any?): String? {
    val cls = payload?.javaClass ?: return null
    if (cls.name != "org.json.JSONObject") return null
    val getJSONObject = (cls.declaredMethods + cls.methods)
        .firstOrNull { it.name == "getJSONObject" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
        ?.apply { isAccessible = true } ?: return null
    val getString = (cls.declaredMethods + cls.methods)
        .firstOrNull { it.name == "getString" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
        ?.apply { isAccessible = true } ?: return null
    val content = runCatching { getJSONObject.invoke(payload, "content") }.getOrNull() ?: return null
    return runCatching { getString.invoke(content, "promiseID") as? String }.getOrNull()
}

// ─── Activity helpers ─────────────────────────────────────────────────────────

fun finishGameAdActivity(activity: Activity, source: String) {
    if (activity.isFinishing) return
    activity.setResult(Activity.RESULT_CANCELED)
    activity.finish()
    Logger.printDebug { "FB GameAds: closed ${activity.javaClass.name} via $source" }
}

fun resolveBlockedGameAdActivity(intent: Intent): String? {
    val target = intent.component?.className ?: return null
    return target.takeIf { it in GAME_AD_ACTIVITY_CLASS_NAMES }
}

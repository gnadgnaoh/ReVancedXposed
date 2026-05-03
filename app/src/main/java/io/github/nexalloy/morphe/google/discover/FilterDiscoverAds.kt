package io.github.nexalloy.morphe.google.discover

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

val FilterDiscoverAds = patch(
    name = "Filter Discover ads",
    description = "Filters promoted ad cards from the Google Discover feed.",
) {
    ::streamRenderableListFingerprint.hookMethod {
        after { param ->
            val items = param.result as? List<*> ?: return@after
            if (items.isEmpty()) return@after

            val fp = DiscoverAdFilter.fastFingerprint(items)
            if (fp == DiscoverAdFilter.lastFingerprint) {
                DiscoverAdFilter.lastFilteredSnapshot?.let { param.result = it }
                return@after
            }

            var removed = 0
            val filtered = ArrayList<Any?>(items.size)
            for (item in items) {
                if (item == null) {
                    filtered += null
                    continue
                }
                val key = DiscoverAdFilter.stableItemKey(item)
                if (key != null && DiscoverAdFilter.isAdItem(key)) {
                    removed++
                    Logger.printDebug { "Discover: blocked ad key=$key" }
                } else {
                    filtered += item
                }
            }

            DiscoverAdFilter.lastFingerprint = fp

            if (removed == 0) {
                DiscoverAdFilter.lastFilteredSnapshot = null
            } else {
                Logger.printDebug { "Discover: removed $removed ad(s) from ${items.size} items" }
                DiscoverAdFilter.lastFilteredSnapshot = filtered
                param.result = filtered
            }
        }
    }
}

/**
 * Stateless-ish helper that holds the filter runtime caches.
 *
 * All maps use ConcurrentHashMap so they are safe if the stream method is ever
 * called from multiple threads (original repo used the same approach).
 */
internal object DiscoverAdFilter {
    // The obfuscated field name that carries a stable content-ID string.
    // Survives across AGSA versions because it is a proto-wire field accessor.
    private const val CONTENT_ID_FIELD = "f122746b"

    // Ad-slot cluster tokens found in Discover content IDs.
    private val adClusterTokens = setOf("feedads")

    // --- caches (mirrors of StreamSliceFilterHook) ---
    private val decisionCache = ConcurrentHashMap<String, Boolean>()
    private val contentIdFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val noContentIdClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val stringFieldsCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val nonSliceClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    @Volatile var lastFingerprint: Long = Long.MIN_VALUE
    @Volatile var lastFilteredSnapshot: List<Any?>? = null

    // ------------------------------------------------------------------ public API

    fun isAdItem(key: String): Boolean {
        decisionCache[key]?.let { return it }
        val lower = key.lowercase(Locale.ROOT)
        val isAd = adClusterTokens.any { it in lower }
        decisionCache[key] = isAd
        return isAd
    }

    fun stableItemKey(item: Any): String? {
        val cls = item.javaClass
        if (cls in nonSliceClasses) return null

        // Fast path: look up the known stable content-ID field.
        contentId(item, cls)?.let { return it }

        // Fallback: first non-blank String field (mirrors original repo).
        val fields = stringFieldsCache.getOrPut(cls) { resolveStringFields(cls) }
        for (f in fields) {
            val value = try { f.get(item) as? String } catch (_: Exception) { null }
            if (!value.isNullOrBlank()) return "${cls.simpleName}#$value"
        }

        nonSliceClasses.add(cls)
        return null
    }

    /**
     * Fast identity-based fingerprint of the list so we can skip re-filtering
     * when the exact same list object is returned again (common in re-draws).
     */
    fun fastFingerprint(items: List<*>): Long {
        var hash = items.size.toLong()
        val step = (items.size / 3).coerceAtLeast(1)
        var i = 0
        var n = 0
        while (i < items.size && n < 4) {
            hash = hash * 31L + System.identityHashCode(items[i]).toLong()
            i += step
            n++
        }
        return hash
    }

    // ------------------------------------------------------------------ private helpers

    private fun contentId(item: Any, cls: Class<*>): String? {
        if (cls in noContentIdClasses) return null
        val field = contentIdFieldCache[cls] ?: run {
            val found = findFieldInHierarchy(cls, CONTENT_ID_FIELD)
            if (found == null) {
                noContentIdClasses.add(cls)
                return null
            }
            contentIdFieldCache[cls] = found
            found
        }
        return try { field.get(item) as? String } catch (_: Exception) { null }
            ?.takeIf { it.isNotBlank() }
    }

    private fun findFieldInHierarchy(start: Class<*>, name: String): Field? {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            try {
                return c.getDeclaredField(name).also { it.isAccessible = true }
            } catch (_: NoSuchFieldException) {
                // walk up the hierarchy
            }
            c = c.superclass
        }
        return null
    }

    private fun resolveStringFields(cls: Class<*>): List<Field> = buildList {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.isSynthetic) continue
                try { f.isAccessible = true } catch (_: Exception) { continue }
                add(f)
            }
            c = c.superclass
        }
    }
}

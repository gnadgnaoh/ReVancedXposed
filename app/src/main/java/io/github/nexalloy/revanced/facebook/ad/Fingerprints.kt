package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.revanced.facebook.GRAPHQL_FEED_UNIT_EDGE_CLASS
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

/**
 * Mirrors upstream's post-resolution filter used in resolveFeedCsrFilterMethods,
 * resolveLateFeedListHooks and resolveStoryPoolAddMethods: excludes constructors and
 * any method that is abstract, or declared on an interface/abstract class, since
 * those can't be hooked directly — Xposed needs the concrete implementing method.
 * DexKit's MethodData/ClassData expose `modifiers` directly from the dex, so this
 * can run entirely at fingerprint-resolution time (no classLoader needed).
 */
private fun MethodData.isConcreteHookTarget(): Boolean {
    if (isConstructor || Modifier.isAbstract(modifiers)) return false
    val ownerModifiers = declaredClass?.modifiers ?: return true
    return !Modifier.isInterface(ownerModifiers) && !Modifier.isAbstract(ownerModifiers)
}

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
    // Primary: structural — the class must contain methods matching ALL of the shapes
    // below. Only trusted when it resolves to a SINGLE unambiguous class.
    //
    // VERIFIED against the shipped FB 575.0.0.45.73 dex (2026-08): three of the six
    // original shapes no longer matched anything. Facebook prepended an `FbUserSession`
    // first parameter to most methods on this class between FB573 and FB575, so every
    // pinned parameter shape shifted right by exactly one:
    //
    //   FB573 (was pinned)                       FB575 (X.564, observed)
    //   static ArrayList(?,?,?,?, boolean)   ->  static ArrayList(?,?,?,?,?, boolean)   A06
    //   ArrayList(?,?,?, Iterable)           ->  ArrayList(?,?,?,?, Iterable)           A0D
    //   List(?,?,?, boolean)                 ->  List(?,?,?,?, boolean)                 A0E/A0F
    //
    // The structural search therefore returned ZERO classes on FB575 and the entire Reels
    // list-builder layer was being carried by the string fallback alone — one log-message
    // rename away from silent death, with nothing to report the loss.
    //
    // Fixed by dropping the pinned parameter positions and keeping return type plus an
    // arity RANGE, which spans both generations. Checked against the shipped dex: this
    // set still resolves to exactly one class (X.564), so the `singleOrNull()` guard
    // below is not weakened in practice. If a future build makes it ambiguous the guard
    // sends resolution to the string fallback, exactly as before.
    val structural = findClass {
        matcher {
            methods {
                matchType = MatchType.Contains
                // The builder append: void, six-ish parameters, one of them a List.
                add {
                    returnType = "void"
                    paramCount(5, 7)
                }
                // The list factory: static, returns ArrayList, trailing boolean flag.
                add {
                    modifiers = Modifier.STATIC
                    returnType = "java.util.ArrayList"
                    paramCount(5, 7)
                }
                // The bulk copy: returns ArrayList, takes an Iterable of stories.
                add {
                    returnType = "java.util.ArrayList"
                    paramCount(4, 6)
                }
                // The public entry point: returns List, trailing boolean flag.
                add {
                    returnType = "java.util.List"
                    paramCount(4, 6)
                }
            }
        }
    }

    // Fallback: string-based — only consulted when the structural search above is
    // ambiguous (0 or 2+ matches), exactly mirroring upstream's
    // `structuralCandidates.singleOrNull() ?: batchCandidates.firstOrNull() ?: error(...)`.
    structural.singleOrNull()
        ?: findClass {
            matcher { usingStrings("Non ads story fall into ads rendering logic, StoryType=%s, StoryId=%s") }
        }.firstOrNull()
        ?: error("Unable to resolve the upstream Facebook reels list-builder class")
}

// NOTE: listBuilderAppendFingerprint / listBuilderFactoryFingerprint were removed.
// Upstream now resolves these two methods via plain reflection + a scoring heuristic
// over every method on the already-resolved listBuilderClass (no rigid param-shape
// match), because Facebook occasionally ships variants with a different parameter
// count/order. That scoring logic needs a real java.lang.reflect.Method (List
// subtype checks via Class.isAssignableFrom), which only exists once classLoader is
// available — see resolveListBuilderAppendMethod / resolveListBuilderFactoryMethod
// in FacebookAdHelpers.kt, called from the patch body with
// ::listBuilderClassFingerprint.clazz (still DexKit-cached) as input.

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
// Upstream resolves the CLASS first via a structural "0-arg String-returning method
// that uses this tag" shape (findClassesByZeroArgStringTags), then picks the actual
// boolean()/0-param eligibility method via plain reflection — preferring a non-static
// method declared on/inherited by that class, falling back to walking the superclass
// chain if none is found directly. That second part needs a real Class<*>
// (classLoader), so it lives in resolveInstreamBannerEligibilityMethod in
// FacebookAdHelpers.kt, called from the patch body with this class as input.

val instreamBannerEligibilityClassFingerprint = findClassDirect {
    findClass {
        matcher {
            methods {
                matchType = MatchType.Contains
                add { returnType = "java.lang.String"; paramCount = 0; usingStrings("InstreamAdIdleWithBannerState") }
            }
        }
    }.firstOrNull() ?: error("Unable to resolve the instream banner eligibility class")
}

// ─── Indicator pill eligibility ──────────────────────────────────────────────
// Upstream requires the CLASS to use BOTH strings (the render-path string and the
// fully-qualified plugin class name), then finds the static boolean(3-param) method
// inside that class — it doesn't require the method itself to reference either string.

val indicatorPillAdEligibilityFingerprint = findMethodDirect {
    val candidates = findClass {
        matcher {
            usingStrings(
                "IndicatorPillComponent.render",
                "com.facebook.feedback.comments.plugins.indicatorpill.reelsadsfloatingcta.ReelsAdsFloatingCtaPlugin"
            )
        }
    }
    candidates.firstNotNullOfOrNull { cls ->
        cls.findMethod {
            findFirst = true
            matcher { modifiers = Modifier.STATIC; returnType = "boolean"; paramCount = 3 }
        }.firstOrNull()
    } ?: error("Unable to resolve the Reels indicator pill ad eligibility method")
}

// ─── Reels banner render methods ─────────────────────────────────────────────

val reelsBannerRenderMethodsFingerprint = findMethodListDirect {
    val bannerRenders = runCatching {
        methodsUsingAnyOf(listOf("ReelsBannerAdsComponent", "ReelsBannerAdsNativeComponent"))
            .filter { m -> m.paramTypeNames.size == 1 && !m.isConstructor }
    }.getOrDefault(emptyList())

    // Hai truy vấn dưới đây từng là findMethod{}/findClass{} riêng lẻ. Mỗi truy vấn như vậy
    // tốn một lượt đi hết string index (~110ms trên dex đã đo), kể cả khi nó không khớp gì —
    // và nhánh slot-queue đúng là không khớp gì trên bản FB được audit (class có tồn tại,
    // nhưng không còn method void 1-tham-số nào). Chuyển sang hai helper batch giữ nguyên
    // ngữ nghĩa mà không phải trả giá cho một tag đã chết.
    val asyncAdsDispatch = runCatching {
        methodsUsingAnyOf(listOf("TRENDING_ADS_TRIGGERED_INTERSTITIAL"))
            .filter { m -> m.returnTypeName == "void" && !m.isConstructor && !Modifier.isAbstract(m.modifiers) }
    }.getOrDefault(emptyList())

    val sponsoredSlotQueueAdds = runCatching {
        classesUsingAnyOf(listOf("FbShortsCSRSponsoredSlotQueue")).flatMap { cls ->
            cls.findMethod { matcher { returnType = "void"; paramCount = 1 } }
        }.filter { m -> !m.isConstructor && !Modifier.isAbstract(m.modifiers) }
    }.getOrDefault(emptyList())

    (bannerRenders + asyncAdsDispatch + sponsoredSlotQueueAdds).distinctBy { it.descriptor }
}

// ─── Profile Reels async ad query ─────────────────────────────────────────────

val profileReelsAsyncAdsQueryFingerprint = findMethodDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramTypes(
                "com.facebook.auth.usersession.FbUserSession",
                "java.lang.Integer",
                "java.lang.Integer",
                "boolean"
            )
            usingStrings("ProfileReelsAsyncAdsQuery")
        }
    }.first { !it.isConstructor }
}

// ─── Feed CSR cache filter ────────────────────────────────────────────────────
// Upstream now also matches a newer 4-param variant — (FbUserSession, ?, ImmutableList, int) —
// in addition to the original 3-param (FbUserSession, ImmutableList, int) shape.
// We search both shapes per candidate class; HideFacebookAdsPatch derives the correct
// listArgIndex afterwards from each resolved Method's real parameter types.

/**
 * Cache-filter tags, one per feed surface.
 *
 * The dated `FeedCSRCacheFilter…` names are Facebook's own half-yearly renames and are
 * all kept, because only one of them exists on any given build and keeping the others
 * costs a search that finds nothing.
 *
 * `FriendlyFeedCacheFilter` and `FbShortsCSRCacheFilter` were added after checking which
 * cache-filter tags the app actually ships against which ones were being searched for:
 * the professional-mode profile feed and the Shorts feed each run their own filter, and
 * neither was reached by the news-feed tags, so sponsored items survived the filter stage
 * on both surfaces.
 */
private val FEED_CSR_FILTER_TAGS = listOf(
    // Present on FB 575.0.0.45.73 (verified against the shipped dex):
    //   FeedCSRCacheFilter2026H1  -> X.28W  (BI3 returns the tag, A00 is the filter)
    //   FbShortsCSRCacheFilter    -> X.57o
    // The bare "FeedCSRCacheFilter" entry still earns its place: DexKit matches strings
    // by containment, so it reaches whichever dated variant the build actually ships,
    // including one named after a half-year nobody has added to this list yet.
    "FeedCSRCacheFilter",
    "FeedCSRCacheFilter2025H1",
    "FeedCSRCacheFilter2026H1",
    "FeedCSRCacheFilter2026H2",
    "FeedCSRCacheFilter2027H1",
    "FeedCSRCacheFilter2027H2",
    // Not present on FB575 — kept because it costs a search that finds nothing, and the
    // professional-mode profile feed has carried its own filter on past builds.
    "FriendlyFeedCacheFilter",
    "FbShortsCSRCacheFilter",
)

val feedCsrFilterMethodsFingerprint = findMethodListDirect {
    classesUsingAnyOf(FEED_CSR_FILTER_TAGS).flatMap { cls ->
        run {
            // NOTE: older builds returned the filtered ImmutableList directly. Current
            // builds return a result WRAPPER instead — e.g.
            //   AnH(FbUserSession, <ctx>, ImmutableList, int) -> LX/2iE
            // where the filtered list sits in a field of that wrapper. Pinning
            // returnType to ImmutableList therefore matched NOTHING and the whole feed
            // CSR filter hook silently never installed (runCatching swallowed it),
            // which is why sponsored items still reached the profile feed.
            // We no longer constrain the return type at all; the hook only needs the
            // ImmutableList PARAMETER, which it rewrites in beforeHookedMethod. The
            // param shape plus the class-level tag string is specific enough.
            val fourParam = cls.findMethod {
                matcher {
                    paramTypes(
                        "com.facebook.auth.usersession.FbUserSession",
                        null,
                        "com.google.common.collect.ImmutableList",
                        "int"
                    )
                }
            }
            if (fourParam.isNotEmpty()) fourParam else cls.findMethod {
                matcher {
                    paramTypes(
                        "com.facebook.auth.usersession.FbUserSession",
                        "com.google.common.collect.ImmutableList",
                        "int"
                    )
                }
            }
        }
    }.distinctBy { it.descriptor }.filter { it.isConcreteHookTarget() }
}

// ─── Late feed list sanitisers ────────────────────────────────────────────────

/**
 * Tag của mọi tầng "dọn danh sách feed muộn" — sanitiser chạy sau khi feed đã được dựng.
 *
 * Ba tag đầu trước đây nằm trong hai truy vấn findClass{} riêng, và một trong hai truy vấn ấy
 * đòi class phải dùng CẢ HAI chuỗi "handleStorageStories" và "Empty Storage List".
 *
 * Quét dex cho thấy vì sao điều kiện AND đó là một cái bẫy: trên bản Facebook được audit,
 * "handleStorageStories" và "cancelVendingTimerAndAddToPool_" đã biến mất khỏi code, còn
 * "Empty Storage List" thì vẫn còn — và class dùng nó chính là class mà fingerprint muốn tìm,
 * đầy đủ cả method `void(?, ImmutableList, int)` lẫn `getStorageController` /
 * `getCsrStoryCollectionWorker` bên cạnh. Nói cách khác nhánh này không chết, nó chỉ mất một
 * nửa điều kiện, và điều kiện AND biến mất-một-nửa thành mất-tất-cả. Hook đã im lặng không
 * cài suốt từ lúc đó.
 *
 * Gộp lại thành MỘT lượt batchFindClassUsingStrings với các lifecycle tag vừa lấy lại được
 * hook đã mất, vừa bỏ được hai truy vấn riêng (~110ms mỗi truy vấn mỗi lần cold scan), và tag
 * nào đã biến mất thì từ nay chỉ tốn đúng 0 query thay vì làm hỏng cả nhánh.
 */
private val LATE_FEED_LIST_TAGS = listOf(
    // Không còn được code nào dùng trên bản được audit — giữ lại vì miễn phí:
    "handleStorageStories",
    "cancelVendingTimerAndAddToPool_",
    // Còn sống, và một mình nó định danh đúng class storage-stories:
    "Empty Storage List",
    // Bốn lifecycle class, mỗi class ba shape:
    "CSRNoOpStorageLifecycleImpl",
    "FeedCSRStorageLifecycle",
    "FriendlyFeedCSRStorageLifecycle",
    "FbShortsCSRStorageLifecycle",
)

val lateFeedListMethodsFingerprint = findMethodListDirect {
    val fbUserSession = "com.facebook.auth.usersession.FbUserSession"
    val immutableList = "com.google.common.collect.ImmutableList"

    // Mọi shape đã từng được liệt kê, thử lần lượt trên từng class khớp thay vì cột chặt
    // shape nào đi với tag nào. Nới rộng như vậy là an toàn vì hook tiêu thụ danh sách này
    // là loại có kiểm tra item: nó chỉ bỏ đi story tự nhận diện được là quảng cáo, nên một
    // method không bao giờ thấy quảng cáo thì cũng không bao giờ bị ảnh hưởng. listArgIndex
    // được HideFacebookAdsPatch suy ra từ tham số ImmutableList thật của method.
    val shapes: List<List<String?>> = listOf(
        listOf(null, immutableList, "int"),
        listOf(immutableList, "java.lang.String"),
        listOf(fbUserSession, null, immutableList),
        listOf(fbUserSession, null, null, immutableList),
        listOf(immutableList),
    )

    val results = ArrayList<org.luckypray.dexkit.result.MethodData>()
    classesUsingAnyOf(LATE_FEED_LIST_TAGS).forEach { cls ->
        shapes.forEach { shape ->
            runCatching {
                cls.findMethod { matcher { returnType = "void"; paramTypes(shape) } }
            }.getOrDefault(emptyList()).forEach { results.add(it) }
        }
    }

    results.distinctBy { it.descriptor }.filter { it.isConcreteHookTarget() }
}

// ─── Story pool add ───────────────────────────────────────────────────────────

/**
 * Pools and queues that admit a story into an ad slot.
 *
 * Safe to widen freely: the hook that consumes this is item-aware — it inspects the story
 * being offered and only refuses one it can positively identify as sponsored. A tag that
 * turns out to hold organic stories therefore costs nothing, which is why the Shorts and
 * Friendly-feed sponsored pools are included even though their exact semantics were never
 * confirmed at runtime.
 */
val STORY_POOL_TAGS = listOf(
    "CSRStoryPoolCoordinator",
    "FeedStoryPoolCoordinator",
    "FbShortsSponsoredPool",
    "FBShortsSponsoredPool",
    "FbShortsIFUSponsoredPool",
    "FriendlyFeedSponsoredPool",
    "FbShortsCSRSponsoredSlotQueue",
    "FbShortsCSRCacheFilter",

    // ── Added after listing every pool, queue and coordinator tag in the app ──────
    //
    // These are the story pools that were shipping without a corresponding tag here.
    // Several of them ("Stories", "Hoist", "Offline", the friendly-feed pool) hold
    // organic stories most of the time, which is exactly why they are safe to list:
    // the hook is item-aware, so a pool that never offers an ad is never affected,
    // while the one occasion it does is now covered.
    "StoryPoolCoordinator",
    "FbShortsPoolContainerAdapter",
    "FBShortsStoryPool",
    "FriendlyFeedStoryPool",
    "StoriesStoryPool",
    "HoistStoryPool",
    "OfflineFeedStoryPool",
)

val storyPoolAddMethodsFingerprint = findMethodListDirect {
    classesUsingAnyOf(STORY_POOL_TAGS).flatMap { cls ->
        cls.findMethod { matcher { returnType = "boolean"; paramCount = 1 } }
    }.distinctBy { it.descriptor }.filter { it.isConcreteHookTarget() }
}

// ─── Sponsored pool ───────────────────────────────────────────────────────────
// Upstream requires the CLASS to use BOTH strings, then verifies the
// boolean(GraphQLFeedUnitEdge) method shape exists somewhere in that class.

val sponsoredPoolClassFingerprint = findClassDirect {
    val candidates = findClass {
        matcher { usingEqStrings("SponsoredPoolContainerAdapter", "Edge type mismatch; not added") }
    }
    candidates.firstOrNull { cls ->
        cls.findMethod {
            matcher { returnType = "boolean"; paramTypes("com.facebook.graphql.model.GraphQLFeedUnitEdge") }
        }.isNotEmpty()
    } ?: error("Unable to resolve the Facebook sponsored pool class")
}

val sponsoredPoolAddMethodFingerprint = findMethodDirect {
    sponsoredPoolClassFingerprint().findMethod {
        matcher { returnType = "boolean"; paramTypes("com.facebook.graphql.model.GraphQLFeedUnitEdge") }
    }.single()
}

// ─── Sponsored story manager ──────────────────────────────────────────────────
// Upstream requires the CLASS to use BOTH strings, then verifies the
// GraphQLFeedUnitEdge()/0-param method shape exists somewhere in that class.

val sponsoredStoryManagerClassFingerprint = findClassDirect {
    val candidates = findClass {
        matcher { usingEqStrings("FeedSponsoredStoryHolder.onPositionReset", "freshFeedStoryHolder") }
    }
    candidates.firstOrNull { cls ->
        cls.findMethod {
            matcher { returnType = "com.facebook.graphql.model.GraphQLFeedUnitEdge"; paramCount = 0 }
        }.isNotEmpty()
    } ?: error("Unable to resolve the Facebook sponsored story manager class")
}

val sponsoredStoryNextMethodFingerprint = findMethodDirect {
    sponsoredStoryManagerClassFingerprint().findMethod {
        matcher { returnType = "com.facebook.graphql.model.GraphQLFeedUnitEdge"; paramCount = 0 }
    }.single()
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

/**
 * The specific 0-param void method inside storyAdsInDiscClass that triggers ad insertion.
 * Upstream finds this via usingStrings("ads_insertion") — we replicate that here.
 */
val storyAdsInsertionTriggerMethodFingerprint = findMethodDirect {
    storyAdsInDiscClassFingerprint().findMethod {
        matcher {
            returnType = "void"
            paramCount = 0
            usingStrings("ads_insertion")
        }
    }.firstOrNull()
        ?: storyAdsInDiscClassFingerprint().findMethod {
            // Fallback: first 0-param void method if string not found (obfuscated builds)
            matcher { returnType = "void"; paramCount = 0 }
        }.first()
}

// ─── Game ad request methods ──────────────────────────────────────────────────

/**
 * The Instant Games JavaScript ad bridge — the methods a game calls to ask for an ad.
 *
 * **AUDIT 2026-08 — ghi chú cũ ở đây đã SAI và được thay.** Bản trước viết rằng cả năm anchor
 * đều vắng mặt trên FB575 và việc fingerprint này không khớp gì là "kết quả mong đợi". Đối
 * chiếu trực tiếp với dex đang chạy cho thấy ngược lại: BỐN trong năm anchor còn sống, tất cả
 * trên cùng một class bridge (60 method, trong đó 42 method `void(JSONObject)`, kèm
 * `postMessage(String, String)` mà mục 9 của [HideFacebookAds] cần). Nghĩa là mục 9 và 10 CÓ
 * chạy trên bản này.
 *
 * Anchor duy nhất mất thật là `onGetRewardedInterstitialAsync` — trong dex cũng không còn
 * chuỗi `getrewardedinterstitialasync` nào, nên entry cùng tên trong
 * [GAME_AD_UNAVAILABLE_MESSAGE_TYPES] là code chết vô hại. Bốn anchor còn lại được giữ
 * nguyên; anchor mất được giữ lại vì nó không tốn thêm truy vấn nào (cả năm đi chung một lượt
 * batch) và vẫn bắt được thiết bị còn cache module cũ.
 *
 * Lớp phòng thủ một tầng thấp hơn — [quicksilverAdsVoltronGateFingerprint] và
 * [quicksilverBannerAdLoaderMethodsFingerprint] — cũng đã được xác nhận có mặt, nên hai hướng
 * bổ trợ cho nhau chứ không thay thế nhau: gate chặn module ads được nạp, bridge trả lời game
 * nào đã kịp nạp module từ trước.
 *
 * Bài học rút ra cho lần audit sau: một `dexMethodList` rỗng KHÔNG bao giờ là bằng chứng rằng
 * target đã biến mất — nó chỉ là một danh sách rỗng bị `runCatching` nuốt mất. Muốn biết thì
 * phải quét dex.
 */
val gameAdRequestMethodsFingerprint = findMethodListDirect {
    listOf(
        "Invalid JSON content received by onGetInterstitialAdAsync: ",
        "Invalid JSON content received by onGetRewardedInterstitialAsync: ",
        "Invalid JSON content received by onRewardedVideoAsync: ",
        "Invalid JSON content received by onLoadAdAsync: ",
        "Invalid JSON content received by onShowAdAsync: "
    ).let { tags ->
        methodsUsingAnyOf(tags).filter {
            it.returnTypeName == "void" && it.paramTypeNames == listOf("org.json.JSONObject")
        }
    }.distinctBy { it.descriptor }.filter { !it.isConstructor }
}

// ─── Feed collection edge filter ──────────────────────────────────────────────
// Replaces FB571_FEED_COLLECTION_TARGETS (was pinned to X.1vr). "addNewEdgeToCollection"
// is one of the very few feed methods that survives ProGuard with its real name, so it
// can be matched by name + shape on any build. Verified on FB 573:
//   X.1vy.addNewEdgeToCollection(ImmutableList$Builder, GraphQLFeedUnitEdge, X.1cS): boolean
val feedCollectionAddEdgeMethodFingerprint = findMethodDirect {
    val byShape = findMethod {
        matcher {
            name = "addNewEdgeToCollection"
            returnType = "boolean"
            paramTypes(null, "com.facebook.graphql.model.GraphQLFeedUnitEdge", null)
        }
    }.filter { it.isConcreteHookTarget() }

    byShape.firstOrNull()
        // Looser fallback: any concrete addNewEdgeToCollection that takes an edge
        // somewhere in its parameter list (param count/order occasionally shifts).
        ?: findMethod {
            matcher { name = "addNewEdgeToCollection"; returnType = "boolean" }
        }.first {
            it.isConcreteHookTarget() &&
                it.paramTypeNames.any { p -> p == "com.facebook.graphql.model.GraphQLFeedUnitEdge" }
        }
}

// ─── Story ad source providers (all of them) ──────────────────────────────────
// Upstream pinned SIX provider classes by name (FB571_STORY_AD_SOURCE_CLASSES) because
// the single-class DexKit lookup missed the split pipelines. This returns EVERY class
// that both logs "ads_deletion" and carries the provider shape, so no name is needed.
// Verified on FB 573: three classes log "ads_deletion", exactly one carries the shape.
val storyAdsInDiscMethodsFingerprint = findMethodListDirect {
    findMethod {
        matcher { usingStrings("ads_deletion") }
    }.filter { md ->
        val cls = md.declaredClass ?: return@filter false
        cls.findMethod {
            matcher {
                returnType = "com.google.common.collect.ImmutableList"
                paramTypes("com.facebook.auth.usersession.FbUserSession", null, "com.google.common.collect.ImmutableList")
            }
        }.isNotEmpty() && cls.findMethod {
            matcher { returnType = "void"; paramTypes(null, "com.google.common.collect.ImmutableList") }
        }.isNotEmpty()
    }.distinctBy { it.declaredClass?.name }
}

// ─── Video plugin system: packs, descriptors, static builders ─────────────────
//
// Everything below targets the layer that serves ads INSIDE a video, as opposed to ads
// that arrive as their own feed story. A runtime trace established that this layer, and
// not the ad-break subsystem, is what delivers the sponsored clip that replaces a
// creator's video and the sponsored card that sits under it: with 21 ad-break resolver
// accessors and 50 ad-break state machine methods hooked, not one of them was ever called
// while those ads were on screen.
//
// None of these fingerprints pin a pack or descriptor name. They resolve the SHAPE of the
// plugin API and let the hooks decide per instance, because some ad packs assemble their
// name at runtime and can never be matched by a literal.

/**
 * Every plugin-list getter in the video plugin system.
 *
 * Resolved by shape from a known pack rather than by method name: the 0-argument List
 * getter that plugin packs expose. Includes getters inherited from a shared base, which
 * ad packs and organic packs use in common — hence the per-instance filtering in
 * [hookPluginPackList].
 */
val allPluginPackListMethodsFingerprint = findMethodListDirect {
    val seed = listOf("FbShortsViewerPluginPack", "MarketplaceAdsPluginPack", "AdBreakPluginPack")
        .firstNotNullOfOrNull { tag ->
            runCatching {
                findClass {
                    matcher {
                        methods {
                            add { returnType = "java.lang.String"; paramCount = 0; usingStrings(tag) }
                            add { returnType = "java.util.List"; paramCount = 0 }
                        }
                    }
                }.firstOrNull()
            }.getOrNull()
        } ?: error("No plugin pack to seed the list-getter shape from")

    val getter = seed.methods.firstOrNull {
        it.paramTypeNames.isEmpty() && it.returnTypeName == "java.util.List"
    } ?: error("Plugin pack list getter shape not found")

    findMethod {
        matcher { name = getter.name; paramCount = 0; returnType = "java.util.List" }
    }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }
}

/**
 * The eligibility gate shared by every video plugin descriptor — the boolean the player
 * calls to ask a descriptor whether it applies to the current video.
 *
 * Shape is learnt from a descriptor known to be ads-only, so the obfuscated method name is
 * never pinned. There are many implementations (165 on the build this was written
 * against), which is exactly why [hookPluginDescriptorGate] filters per instance instead
 * of trying to fingerprint the ad ones.
 */
val pluginDescriptorGateMethodsFingerprint = findMethodListDirect {
    val seed = listOf("PlayableAdOverlayPluginDescriptor", "AdsSmartOverlayPluginDescriptor")
        .firstNotNullOfOrNull { tag ->
            runCatching { findClass { matcher { usingStrings(tag) } }.firstOrNull() }.getOrNull()
        } ?: error("No ad plugin descriptor to seed the gate shape from")

    val gate = seed.methods.firstOrNull {
        it.returnTypeName == "boolean" &&
            it.paramTypeNames.size == 4 &&
            it.paramTypeNames[1] == "com.facebook.video.common.playerorigin.PlayerOrigin"
    } ?: error("Plugin descriptor gate shape not found")

    findMethod {
        matcher {
            name = gate.name
            returnType = "boolean"
            paramTypes(null, "com.facebook.video.common.playerorigin.PlayerOrigin", null, null)
        }
    }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }
}

/**
 * Direct-monetization ad plugins — the in-video ads a creator monetises with.
 *
 * These come from a plain static builder rather than from a pack object, so neither a
 * pack-level nor a descriptor-level hook reaches them; the builder is matched by the one
 * literal it carries.
 */
val directMonetizationAdsPluginListFingerprint = findMethodListDirect {
    findMethod {
        matcher {
            returnType = "com.google.common.collect.ImmutableList"
            usingStrings("REELS_DIRECT_MONETIZATION_ADS")
        }
    }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }
}


// ─── Ad-only Litho components ─────────────────────────────────────────────────
//
// A component that exists solely to draw an advertisement can be suppressed by making its
// render return nothing; Litho treats a null layout as "draw nothing". This is a blunt
// instrument, so three guards stand in front of it.

/**
 * Return types that prove a 1-argument method is NOT a render.
 *
 * Facebook ships generated string-table classes with signatures like `A00(int): String`
 * that mention nearly every tag in the app. Without this filter, adding tags below would
 * hook those and corrupt unrelated text. A render always returns a Component or a Section.
 */
private val NON_RENDER_RETURN_TYPES = setOf(
    "java.lang.String", "void", "boolean", "int", "long", "float", "double", "char", "byte", "short"
)

/**
 * Components that render ORGANIC content. Any class referencing one of these is shared
 * infrastructure, not an ad component, even when it also mentions an ad component name —
 * Facebook's generated feed components carry several names at once.
 *
 * This guard is not theoretical. "SponsoredNewsFeedUnitComponent" reads like an ad and is
 * still in the tag list below, but on this build it resolves to the generic news feed
 * story component; suppressing it leaves the feed stuck on its loading skeleton forever.
 * The guard catches it by name-independent means, so the mistake cannot be repeated by
 * adding a plausible-looking tag.
 */
private val ORGANIC_COMPONENT_MARKERS = listOf(
    "NewsFeedFeedUnitComponent",
    "FeedGraphQLStoryRootComponent",
    "FeedNonGraphQLRootStoryComponent",
    "FeedStoryUFIFeedbackSummaryComponent",
    "InlineComposerV2RootComponent",
    "ReactFeedStoryComponent",
    // Reels / Shorts in-feed unit. Added after a regression: an "…AdsMedia…" tag resolved
    // to the class that also renders the tray's body wrapper and the Reels row in the feed
    // went blank. On the newer build that same class is still shared, so the guard keeps
    // earning its place.
    "ShowcaseFbShortsBodyWrapperComponent",
    // Hai tag cuối không khớp class nào trên bản FB được audit (đã quét đủ 21 dex).
    // "ReactFeedStoryComponent" ở trên thì NGƯỢC LẠI: lượt quét thiếu dex ban đầu tưởng nó đã
    // biến mất, quét đủ mới thấy nó còn nguyên.
    //
    // Cả ba đều ở lại, và lần nhầm đó cho thấy vì sao: đây là hàng rào BẢO VỆ. Xoá một cái
    // không tiết kiệm được gì (cả list chạy trong một lượt batch), còn nếu component vẫn tồn
    // tại — hoặc quay lại ở bản sau — thì hậu quả là một surface organic bị làm trắng, đúng
    // loại regression mà list này sinh ra để chặn.
    "ShowcaseFbShortsRootComponent",
    "FbShortsIfuTileComponent",
)

private fun MethodData.isRenderShaped(): Boolean =
    !isConstructor && returnTypeName !in NON_RENDER_RETURN_TYPES

/**
 * Every class that uses at least one of [tags], found in a SINGLE native pass.
 *
 * The obvious way to write this is a loop of `findClass { usingStrings(tag) }`, one query
 * per tag, and that is how it used to be written. Measured against the shipped FB575 dex
 * (16 files, 130 MB), a DexKit class query costs a flat ~110 ms floor whatever it matches,
 * because each one walks the whole string index. At 220 component tags that is 24 seconds
 * of the module's ~36-second cold scan — two thirds of the total, spent re-walking the
 * same index 220 times.
 *
 * `batchFindClassUsingStrings` takes every tag as its own named group and answers them all
 * in one walk. Same inputs, same per-tag grouping, one pass. Measured on the same dex:
 * 24 s -> under 1 s.
 *
 * The group name is the tag itself; results are flattened because callers only ever want
 * the union. Falls back to the per-tag loop if the batch call fails, so a DexKit version
 * without the batch API degrades to the old behaviour rather than to nothing.
 */
private fun DexKitBridge.classesUsingAnyOf(tags: List<String>): List<ClassData> {
    if (tags.isEmpty()) return emptyList()
    runCatching {
        batchFindClassUsingStrings { groups(tags.associateWith { listOf(it) }) }
    }.getOrNull()?.let { batched ->
        return batched.values.flatten().distinctBy { it.descriptor }
    }
    return tags.flatMap { tag ->
        runCatching { findClass { matcher { usingStrings(tag) } }.toList() }.getOrDefault(emptyList())
    }.distinctBy { it.descriptor }
}

/**
 * Every method that uses at least one of [tags], found in a SINGLE native pass.
 *
 * The method-level twin of [classesUsingAnyOf], and it exists for the same measured
 * reason: `findMethod { usingStrings(tag) }` costs the same flat ~110 ms index walk per
 * call, so a fingerprint anchored on four logging tags spends four walks to answer one
 * question. `batchFindMethodUsingStrings` answers all of them in one.
 *
 * The batch API filters on strings only, so the shape constraint that used to live in the
 * matcher — a return type, a parameter list — is applied by the caller afterwards against
 * `returnTypeName` / `paramTypeNames`. That is the same test the matcher performed, just
 * evaluated in Kotlin over a much smaller set: the handful of methods that carry the tag,
 * rather than every method in the app.
 *
 * Falls back to the per-tag loop when the batch call fails, so behaviour degrades to the
 * old path rather than to an empty result.
 */
private fun DexKitBridge.methodsUsingAnyOf(tags: List<String>): List<MethodData> {
    if (tags.isEmpty()) return emptyList()
    runCatching {
        batchFindMethodUsingStrings { groups(tags.associateWith { listOf(it) }) }
    }.getOrNull()?.let { batched ->
        return batched.values.flatten().distinctBy { it.descriptor }
    }
    return tags.flatMap { tag ->
        runCatching { findMethod { matcher { usingStrings(tag) } }.toList() }.getOrDefault(emptyList())
    }.distinctBy { it.descriptor }
}

private fun DexKitBridge.rejectSharedFeedComponents(methods: List<MethodData>): List<MethodData> {
    val shared = classesUsingAnyOf(ORGANIC_COMPONENT_MARKERS).mapTo(mutableSetOf()) { it.name }
    return methods.filter { it.className !in shared }
}

/**
 * The obfuscated Litho render return type for this build, derived rather than pinned: it
 * is simply the return type of a render method already located by string. Both the
 * Component and the Section flavour are resolved this way.
 */
private fun DexKitBridge.renderReturnTypeFrom(seedTags: List<String>): String? =
    seedTags.firstNotNullOfOrNull { tag ->
        runCatching {
            findClass { matcher { usingStrings(tag) } }
                .flatMap { cls -> cls.methods.filter { it.paramTypeNames.size == 1 } }
                .firstOrNull { it.isRenderShaped() }
                ?.returnTypeName
        }.getOrNull()
    }

/**
 * Resolves the render method of ad-only components identified by [tags].
 *
 * Matching is at CLASS level, not method level. Matching the tag on the render method
 * itself misses every component whose tag literal lives in a sibling method — the
 * in-player banner is exactly that: its render carries no string at all, the tag sits in
 * an eleven-argument setup method on the same class.
 *
 * Class-level matching is looser, so the result is narrowed twice: the method must return
 * the render type derived above (which excludes the string-table classes), and
 * [rejectSharedFeedComponents] drops anything that also renders organic content.
 */
private fun DexKitBridge.adRenderMethodsFor(tags: List<String>, seedTags: List<String>): List<MethodData> {
    val renderType = renderReturnTypeFrom(seedTags) ?: return emptyList()
    val methods = classesUsingAnyOf(tags).flatMap { cls ->
        cls.methods.filter { it.paramTypeNames.size == 1 && it.returnTypeName == renderType }
    }.filter { it.isRenderShaped() }.distinctBy { it.descriptor }
    return rejectSharedFeedComponents(methods)
}

val AD_SURFACE_RENDER_TAGS = listOf(
    // AdBreak (in-stream video) (11)
    "AdBreakCallToActionButtonComponent",
    "AdBreakContextCardComponent",
    "AdBreakContextCardSponsorInfoComponent",
    "AdBreakContextStoryOverlayComponent",
    "AdBreakCountdownWithTextComponent",
    "AdBreakDeferredCTACardComponent",
    "AdBreakDeferredCTAPoliticalAdSponsorInfoComponent",
    "AdBreakInPlayerAnimatedSingleImageComponent",
    "AdBreakNonInterruptiveCardComponent",
    "AdBreakPostRollEndingScreenComponent",
    "AdBreakTransitionWithAnimationComponent",
    // Other (8)
    "AdsSocialContextComponent",
    "AdsTextOverlay",
    "BKBloksAdsUgcPermalinkPostTextComponent",
    "InContentAdsSidebarCountdownTimerComponent",
    "SponsoredNewsFeedUnitComponent",
    "bk.action.HideAdsOverlay",
    // Carousel / horizontal scroll (3)
    "CarouselAdsAttachmentHScrollComponent",
    "FbAdsHscrollFooterComponent",
    "FbAdsHscrollItemComponent",
    // Multi-ads card (5)
    "FBMultiAdsFeedUnitKComponent",
    "MultiAdsAdCardFooterKComponent",
    "MultiAdsAdCardHeaderKComponent",
    "MultiAdsAdCardMediaKComponent",
    "MultiAdsAndBrowseFallbackKComponent",
    // Shorts / Reels (50)
    "FbShortsAdsAuthorProfilePictureComponent",
    "FbShortsAdsAuthorWithFDSComponent",
    "FbShortsAdsCTAKComponent",
    "FbShortsAdsCTAStickerComponent",
    "FbShortsAdsCTMEditableEndSceneKComponent",
    "FbShortsAdsCreativeProductStickerCTAComponent",
    "FbShortsAdsCreativeStickerImageComponent",
    "FbShortsAdsDLPProductCardComponent",
    "FbShortsAdsDotsCarouselPlayerComponent",
    "FbShortsAdsHScrollComponent",
    "FbShortsAdsHscrollAlbumLastCardComponent",
    "FbShortsAdsIABFragmentWrapperComponent",
    "FbShortsAdsIABReentryMidsceneCardComponent",
    "FbShortsAdsIABScreenshotEndSceneComponent",
    "FbShortsAdsLeadGenFirstQuestionComponent",
    "FbShortsAdsLeadGenPIIComponent",
    "FbShortsAdsLeadGenSAQComponent",
    "FbShortsAdsMidSceneBizAgentComponent",
    "FbShortsAdsMidSceneSiteExtensionComponent",
    "FbShortsAdsMidsceneCardComponent",
    "FbShortsAdsMidsceneContainerComponent",
    "FbShortsAdsMixedMediaCardKComponent",
    "FbShortsAdsMixedMediaTileComponent",
    "FbShortsAdsMultiAdsGridCardComponent",
    "FbShortsAdsMultiAdsGridComponent",
    "FbShortsAdsMultiAdsVerticalCardComponent",
    "FbShortsAdsMultiAdsVerticalComponent",
    "FbShortsAdsNativeSlideshowImageComponent",
    "FbShortsAdsNativeSlideshowPlayerComponent",
    "FbShortsAdsPhotoCardComponent",
    "FbShortsAdsPhotoKComponent",
    "FbShortsAdsPostScrollNudgeBizAiAgentComponent",
    "FbShortsAdsPostScrollNudgeLeadGenPIIComponent",
    "FbShortsAdsPostScrollNudgeLeadGenSAQComponent",
    "FbShortsAdsPostScrollNudgeLeadGenSingleSelectComponent",
    "FbShortsAdsPostScrollNudgeScreenShotComponent",
    "FbShortsAdsPostScrollNudgeTrustSignalComponent",
    "FbShortsAdsRealTimeIntentComponent",
    "FbShortsAdsRtiSingleCardKComponent",
    "FbShortsAdsStickerCTAComponent",
    "FbShortsAdsSwipeLeftComponent",
    "FbShortsAdsXAndBrowseProgressRingComponent",
    "FbShortsAdsXAndBrowseStartingIndicatorComponent",
    "FbShortsImageAdsTextOverlayKComponent",
    "FbShortsShoppableAdsItemComponent",
    "FbShortsVideoAdsTextOverlayKComponent",
    "FbShortsViewerVideoAdsMusicComponent",
    "ReelsAdsCaptionCommentComponent",
    "SearchResultsSponsoredStoryBloksCaptionComponent",
    "SearchResultsSponsoredStoryBloksFooterLithoComponent",
    "SearchResultsSponsoredStoryComponent",
    "SearchResultsSponsoredStoryContentComponent",
    "SearchResultsSponsoredStoryHeaderComponent",
    "SearchResultsSponsoredStoryMultiShareItemComponent",
    // Stories viewer (11)
    "StoryViewerAdsBackgroundImageComponent",
    "StoryViewerAdsCardStyleMediaComponent",
    "StoryViewerAdsCollectionPhotoComponent",
    "StoryViewerAdsExpandableCaptionComponent",
    "StoryViewerAdsExpandableCarouselOptInComponent",
    "StoryViewerAdsFollowBySocialContextComponent",
    "StoryViewerAdsMultiPartComponent",
    "StoryViewerAdsOptInComponent",
    "StoryViewerAdsProductHighlightPhotoComponent",
    "StoryViewerAdsRootContainerComponent",
    "StoryViewerAdsTopBarComponent",
    // Video ads CTA / attachment (8)
    "VideoAdsActionComponent",
    "VideoAdsAttachmentFooterComponent",
    "VideoAdsAttachmentFooterTextOptimizedComponent",
    "VideoAdsButtonComponent",
    "VideoAdsCallToActionAttachmentActionButtonComponent",
    "VideoAdsCallToActionComponent",
    "VideoAdsCallToActionDelayedWrapperComponent",
    "VideoAdsPageLikeCallToActionComponent",
    // Watch immersive (3)
    "WatchSponsoredImmersiveAttachmentCallToActionComponent",
    "WatchSponsoredImmersiveAttachmentFooterComponent",
    "WatchSponsoredImmersiveHeaderComponent",

    // ── Added after re-scanning a newer build ────────────────────────────────
    //
    // Tags whose only effect is removing an ad's LABEL are deliberately absent: the
    // sponsored label, subtitle, disclaimer, info chips, tooltips and status pills. They
    // do not remove an advertisement, they remove the thing that identifies it as one —
    // and where the ad is a tile in a list, the result is an unlabelled tile that opens a
    // black screen. Removing an ad is worth doing; making it harder to recognise is not.
    // Every tag below was verified on that build: the class carrying it declares a
    // 1-argument method returning the Litho render type, and none of them also renders
    // organic content. Three candidates were rejected by that second check and are NOT
    // here: MarketplaceVideoAdsComponent and ShowcaseFbShortsAdsMediaComponent now share
    // a class with organic tray/feed rendering, and StoryAdsSmoothSwipeIABWrapperComponent
    // likewise. Suppressing those blanks real content — the tray regression came from
    // exactly that mistake.
    // AdBreak / in-stream video (15)
    "AdBreakCollapsedNonInterruptiveComponent",
    "AdBreakCollectionFooterItemComponent",
    "AdBreakFooterAndStarsTickerComponent",
    "AdBreakImmersiveContextCardComponent",
    "AdBreakInPlayerAnimatedCarouselComponent",
    "AdBreakLandscapeDeferredCtaCardComponent",
    "AdBreakMidRollStartingIndicatorComponent",
    "AdBreakNonInterruptiveCompactCardComponent",
    "AdBreakPostRollTransitionComponent",
    "AdBreakTransitionComponent",
    "AdBreakVideoThumbnailComponent",
    "AdBreakVideoThumbnailForLongerAdComponent",
    "InstreamAdsImmersiveOverlayVerticalGradientComponent",
    "InstreamContextCardSponsorInfoComponent",
    "InstreamDeferredCTAPoliticalAdSponsorInfoComponent",
    // Carousel / horizontal scroll (1)
    "FbAdsHscrollFooterMediaComponent",
    // Shorts / Reels (20)
    "FbShortsAdsEndSceneMetadataCardComponent",
    "FbShortsAdsHscrollCardComponent",
    "FbShortsAdsMidCardSurveyComponent",
    "FbShortsAdsPostScrollNudgeBusinessSummaryCardComponent",
    "FbShortsAdsPostScrollNudgeFeaturedOfferingComponent",
    "FbShortsAdsPostScrollNudgeLinkPreviewComponent",
    "FbShortsAdsPostScrollNudgeMAIComponent",
    "FbShortsAdsPostScrollNudgeMAIContentCardComponent",
    "FbShortsAdsPostScrollNudgeMultiImageCollageComponent",
    "FbShortsAdsPostScrollNudgeProductExtensionsComponent",
    "FbShortsAdsPostScrollNudgeWebUMultiImageComponent",
    // The post-scroll nudge's horizontal-scroll carousel, new on FB575. Each part is a
    // separate render class, and none of them shares a class with a tag already listed
    // above — verified against the shipped dex, not assumed from the name prefix.
    "FbShortsAdsPostScrollNudgeHscrollBlurredBackgroundComponent",
    "FbShortsAdsPostScrollNudgeHscrollCtaComponent",
    "FbShortsAdsPostScrollNudgeHscrollHeaderComponent",
    "FbShortsAdsPostScrollNudgeHscrollImage",
    "FbShortsAdsPostScrollNudgeHscrollMetadataCardComponent",
    "FbShortsAdsPostScrollNudgeMultiImageCollageTile",
    // "ForNorthStar" and "WithSmoothSwipe" are alternative renders of nudge cards whose
    // plain variants were already listed. They are separate classes, so listing the plain
    // name never reached them, and they were being missed on FB574 as well.
    //
    // The SmoothSwipe pair is listed despite StoryAdsSmoothSwipeIABWrapperComponent being
    // rejected earlier for the opposite reason: that one shares its class with organic
    // story rendering, while these two are ad-only — one carries nothing but advertiser
    // trust-signal fields (APP_ADS_METADATACARD, PAGE_CATEGORY), the other nothing but
    // its own tag. Same word in the name, different classes, opposite verdicts.
    "FbShortsAdsPostScrollNudgeProductExtensionsComponentForNorthStar",
    "FbShortsAdsPostScrollNudgeScreenShotComponentForNorthStar",
    "FbShortsAdsPostScrollNudgeTrustSignalComponentForNorthStar",
    "FbShortsAdsPostScrollNudgeTrustSignalComponentWithSmoothSwipe",
    "FbShortsAdsPostScrollNudgeWebUMultiImageComponentWithSmoothSwipe",
    // Reels ad chrome: the chip, card and overlay layers drawn over a Reels ad. All
    // ad-only classes. The Sponsored label, subtitle and GenAI-transparency components
    // sit right next to these in the dex and stay out, as identification.
    "FbShortsAdsActionChip",
    "FbShortsAdsProductExtensionsCard",
    "FbShortsAdsGlimmerCardsComponentSpec",
    "FbShorts9x16ImageAdsTextOverlayComponentSpec",
    "ReelsAdsWatchAndBrowseInfoChip",
    "ReelsAdsPauseOverlayNuxInfoChip",
    "ReelsConvAdOverlay",
    "SurfaceAdsFloatingCtaContainer",
    "AppAdsEndSceneScreenshot",
    // Comment ads — the call-to-action row rendered inside a comment thread. A surface
    // with no coverage at all before: AdsCommentSection suppresses the section that
    // holds them, but these render classes are reached directly too.
    "CommentAdsCTA",
    "CommentAdsCTAAttachment",
    "CommentAdsCTAProfilePicture",
    // Search results sponsored stories, beyond the attachment tags already listed.
    "SearchResultsSponsoredMultiStoryItem",
    "SearchResultsSponsoredStoryCallToActionAttachment",
    "SearchResultsSponsoredStoryCallToActionButtonComponentSpec",
    // Watch feed sponsored rows. Both classes are ad-only: their own logging
    // (sponsored_watch_feed_util_component, ad_validate_image) carries nothing organic.
    "WatchFeedSponsoredAttachmentRow",
    "WatchFeedSponsoredImmersiveAttachmentRow",
    // Stories viewer, video ad and Marketplace video ad chrome.
    "StoryViewerAdsCaptionBottomsheetCtaButton",
    "VideoAdsSecondarySaveCtaButton",
    "MarketplaceVideoAdsGrootLayout",
    // Polls shown inside a video advertisement.
    "com.facebook.feed.video.inline.polling.VideoAdsPollComponentSpec",
    "com.facebook.feed.video.inline.polling.VideoAdsPollVideoExtensionFooterComponentSpec",
    "FbShortsViewerChevronForSentimentFriendlyBannerAdsComponent",
    // Other (3)
    "GridAdsSingleGridKComponent",
    "ProfessionalRatingStoryAdsStoryImageComponent",
    "SponsorshipCoverImageComponent",
    // Multi-ads card (3)
    "MultiAdsAdCardKComponent",
    "MultiAdsAdDescriptionKComponent",
    "MultiAdsCategoryCardKComponent",
    // Search results (3)
    "SearchResultsSponsoredMultiStoryComponent",
    "SearchResultsSponsoredStoryImageComponent",
    "SearchResultsSponsoredStoryLargeAttachmentComponent",
    // Stories viewer (5)
    "StoryViewerAdsLiveVideoComponent",
    "StoryViewerAdsPhotoComponent",
    // Video ads (1)
    "VideoAdsToggleButtonComponent",
    // Watch immersive (2)
    "WatchSponsoredImmersiveActionComponent",
    "WatchSponsoredImmersiveAttachmentComponent",

    // ── Added after auditing the shipped dex against this list ───────────────────
    //
    // Every component and section name in the app was tokenised on CamelCase boundaries
    // and kept when a token was Ad/Ads/Sponsored/Promotion — which is how "Add", "Admin"
    // and "Adaptive" stay out — then the ones already listed above were subtracted. Each
    // survivor was checked the same way the runtime checks them: the class carrying the
    // tag must declare a one-argument method returning the Litho render type, and must
    // not also carry an organic marker. Everything below passed both.
    //
    // Four groups were dropped on judgement rather than on evidence, following the rules
    // this list was written under:
    //
    //   Playback controls. InstreamAdSkipButtonComponent, AdVideoPlayerComponent,
    //     AdBreakPlayerComponent, AdBreakViewCoordinatorComponent, AdBreakControlComponent,
    //     NonLiveWasLiveAdBreakControlComponent, AdBreakPlayIndicatorComponent,
    //     AdBreakPostHideAdCountDownComponent, StoryViewerAdsInlineControlComponent and the
    //     AdBreak progress bars. Suppressing these removes the means of escaping an ad or
    //     stalls the player waiting for a break that never finishes drawing.
    //   Labels. FbShortsAdsSponsoredLabelComponent, FbShortsAdsSponsoredSubtitleComponent,
    //     FbShortsViewerVideoSponsorLabelComponent, FbShortsViewerAdGenAiTransparencyComponent,
    //     StoryViewerAdsDisclaimerComponent, StoryViewerAdsPillComponent,
    //     StoryViewerAdsVerifiedVoiceComponent, AdDisclaimerBannerComponent and
    //     AdDisclaimerFooterComponent. These do not remove an advertisement, they remove the
    //     thing that identifies it as one.
    //   The Ad Activity screen. Every AdActivity* component and section: a settings surface
    //     for reviewing ads you have already seen, not an advertisement.
    //   Creator and advertiser tooling. CreatorComposerMonetizationComponent,
    //     FbShortsShareSheetMonetizationComponent, FacecastLiveVideoAdsStatusPillComponent,
    //     StoryPromotionComponent, InspirationSproutPromotionComponent and
    //     BentoPromotionalElementBlockComponent are how a user promotes their OWN post.
    //   Logging shells. AdsRenderingReliabilityLoggingComponent and
    //     VideoAdsRenderingReliabilityVisibilityLoggingComponent draw nothing to begin with.

    // In-stream video, the "Instream*" naming (18)
    //
    // Facebook is migrating the AdBreak* components to an Instream* name and currently
    // ships both. The AdBreak half was already covered and the Instream half was not, so
    // an in-stream ad on the newer render path passed through untouched. These are the
    // direct counterparts of entries above — the context card, the CTA button, the
    // countdown, the overlay, the long-ad thumbnail — plus the bumper and immersive
    // variants that have no AdBreak equivalent.
    "InstreamAdCallToActionButtonComponent",
    "InstreamAdContextCardComponent",
    "InstreamAdCountDownWithTextComponent",
    "InstreamBumperAdRendererAdOverlayComponent",
    "InstreamNonInterruptiveAdRendererFooterComponent",
    "InstreamVideoAdImmersiveDeferredCtaCardComponent",
    "InstreamVideoAdRendererAdOverlayComponent",
    "InstreamVideoThumbnailForLongerAdComponent",
    "InContentAdsHeaderPillCountdownTimerComponent",
    "PauseAdComponent",
    "AdVideoOverlayKComponent",
    "AdFadeTransitionComponent",
    "AdAttachmentComponent",
    "AdImageComponent",
    "MarketplaceVideoAdsComponent",
    // Resolves to the Marketplace in-video ad's own render, despite the "Query" suffix —
    // the literal is the component's query name and the class carrying it is the component.
    "MarketplaceVideoAdQuery",

    // Watch feed real-time intent ads (4)
    //
    // A surface with no coverage at all until now: the ad unit injected into the Watch
    // feed in response to something the user just did. The Reels equivalent
    // (FbShortsAdsRealTimeIntentComponent) was already listed; the Watch one was not.
    "WatchFeedRealTimeIntentAdUnitComponent",
    "WatchFeedRealTimeIntentAdImageCardComponent",
    "WatchFeedRealTimeIntentAdVideoPlayerComponent",
    "WarionWatchNonVideoAdHeaderComponent",

    // Playable ads, second generation (2)
    //
    // The activity that hosts playable ads was already blocked; these are the Litho
    // chrome of its newer in-feed form, which never goes through that activity.
    "NekoPlayableAdV2BackgroundComponent",
    "NekoPlayableAdV2FooterComponent",

    // Search results (3)
    //
    // Sponsored results in the search page itself, as opposed to the sponsored *stories*
    // in search results that were already covered.
    "SearchSerpAdComponent",
    "SearchSerpAdGridCellComponent",
    "SearchAdCardComponent",

    // Ad extensions (4)
    //
    // The card rail an advertiser attaches under an ad — offers, products, locations.
    // A whole subsystem with no entry here before.
    "AdExtensionsComponent",
    "AdExtensionsCardItemComponent",
    "AdExtensionsCardItemWithTextUnderneathComponent",
    "AdExtensionsCardWithTextOnSideItemComponent",

    // Shorts / Reels (4)
    //
    // FbShortsAdsRootKComponent is the root of a Shorts advertisement, so suppressing it
    // is strictly better than suppressing the forty-odd pieces it assembles: the same
    // reel is skipped either way, but nothing is drawn on the way there. The two touch
    // components exist only to route taps on an ad into its landing page.
    "FbShortsAdsRootKComponent",
    "FbShortsAdsDirectConversionTouchComponent",
    "FbShortsAdsTooltipTouchComponent",
    "FbShortsQuickPromotionBloksComponent",

    // Quick Promotion (3)
    //
    // Server-rendered promo cards. The feed already treats ENGAGEMENT_QP as a sponsored
    // category, so the renderers are handled on the same terms for the surfaces where no
    // category is available to test.
    "QuickPromotionFeedUnitServerRendererComponent",
    "QuickPromotionServerRendererComponent",
    "MibQuickPromotionBannerComponent",

    // Stories viewer (1)
    "StoryViewerAdEndSceneOverlayComponent",

    // ── Added from a full string scan of the shipped FB 575.0.0.45.73 dex (2026-08) ──
    //
    // All 220 tags above were verified still present on this build, and 200 of them still
    // resolve to a real render method, so this block is purely additive.
    //
    // It is short on purpose. A string scan of FB575 turned up 93 component names that
    // look ad-only and had no tag reaching them, and 33 of those survived the usual three
    // exclusions. Resolving each one against the dex then showed that only the four below
    // reach a render method at all: the other 29 carry their tag inside a shared
    // string-table class that has no `render(1 arg)` on it, so `adRenderMethodsFor` drops
    // them — which is the filter working, but it also means listing them would be dead
    // weight rather than coverage. They are named at the end of this comment so the next
    // audit does not spend the same afternoon rediscovering them.
    //
    // NOT added, by the same three rules the list already follows:
    //
    //  - Playback controls. AdBreakSkipButtonComponent, AdBreakPlayerComponent,
    //    AdBreakControlComponent, AdBreakViewCoordinatorComponent,
    //    AdBreakPlayIndicatorComponent, AdBreakPostHideAdCountDownComponent,
    //    InstreamAdSkipButtonComponent, AdVideoPlayerComponent, AdProgressBarComponent,
    //    StoryViewerAdsInlineControlComponent, StoryViewerAdsProgressBarComponent, the
    //    AdBreakBMR* countdown/starting-indicator family, AladdinAdBreakProgressBar-
    //    Component and NonLiveWasLiveAdBreakControlComponent. Suppressing any of these
    //    removes the means of escaping an ad, or stalls the player on a break that never
    //    finishes drawing.
    //  - Identification. FbShortsAdsSponsoredLabelComponent, FbShortsAdsSponsored-
    //    SubtitleComponent, FbShortsViewerVideoSponsorLabelComponent,
    //    FbShortsViewerAdGenAiTransparencyComponent, AdDisclaimerBannerComponent,
    //    AdDisclaimerFooterComponent, StoryViewerAdsDisclaimerComponent,
    //    StoryViewerAdsVerifiedVoiceComponent, FacecastLiveVideoAdsStatusPillComponent.
    //    Same reasoning the list already applies to the Sponsored label: hiding the
    //    disclosure and leaving the advertisement is the wrong half to remove.
    //  - Not an advertisement. The AdActivity* family (a settings screen for reviewing
    //    ads you have seen), the React*AdPreview* family (advertiser-side creative
    //    preview — a tool, not an ad), AdsRenderingReliabilityLoggingComponent and
    //    VideoAdsRenderingReliabilityVisibilityLoggingComponent (logging only), and
    //    ShowcaseFbShortsAdsMediaComponent and StoryAdsSmoothSwipeIABWrapperComponent,
    //    which share a class with organic rendering — the first is already covered by the
    //    "…AdsMedia…" entry in ORGANIC_COMPONENT_MARKERS, the second was rejected by name
    //    in the FB574 audit for exactly this reason.
    //
    // Ad-only but unreachable on FB575 (tag lives in a shared string-table class, no
    // render method to hook — re-check on a future build rather than adding them now):
    //    AdAttachmentFooterComponent, AdAttachmentFooterTextComponent,
    //    AdBreakCollectionFooterWrapperComponent, AdBreakInPlayerAnimatedAdsThumbnail-
    //    Component, AdBreakNonInterruptiveInfoCardComponent(Spec), AdCallToActionComponent,
    //    AdExtensionsLoadingSpinnerComponent, CarouselAdsAttachmentHScrollKComponent,
    //    CarouselAdsAttachmentHScrollDwellTimeTriggerWrapperComponent,
    //    FbAdsHscrollFooterTextComponent, FbShortsAdsDLPCombinedContentIABWrapper-
    //    Component, FbShortsAdsDLPFragmentWrapperComponent, FbShortsAdsNoteBubbleCTA-
    //    Component, FbShortsAdsPhotoBuilderComponent, FbStoriesAdsCaptionComponentSpec,
    //    InstreamInPlayerAnimatedAdsThumbnailComponent, InstreamVideoAdRendererImmersive-
    //    AdOverlayComponent, MultiAdsKComponent, MultiAdsAndBrowseKComponent,
    //    PlayableAdOverlayContainerComponent, RelatedAdCardKComponent,
    //    SearchResultsSponsoredStoryAttachmentComponent(+TextComponent, +VideoComponent),
    //    StoryViewerAdsStaticTappableMediaComponent, VideoAdsAttachmentFooterTextComponent,
    //    VideoAdsLeadGenCallToActionComponent,
    //    WatchSponsoredImmersiveCallToActionAttachmentActionButtonComponent.

    // Verified to resolve to a real render method on FB575:
    "AdBreakInfoCardRootComponentSpec",            // X.Ejb -> render
    "AdBreakUniversalCTAInfoComponentSpec",        // X.SyE -> A19
    "AdsCallBadgeComponentSpec",                   // X.PqC -> render
    "FbShortsAdsAuthorKComponent",                 // X.WCt -> render

    // ── Audit trên dex của bản FB đang chạy (2026-08) ────────────────────────
    //
    // Bốn tag dưới đây được chọn theo đúng ba luật list này vẫn dùng, và mỗi tag đã được
    // đối chiếu với dex: class mang tag có một method 1-tham-số trả về kiểu render Litho,
    // class KHÔNG mang literal organic nào, và render method đó CHƯA nằm trong 215 method
    // mà list hiện tại đã chặn (nghĩa là mỗi dòng thêm coverage thật, không phải bí danh
    // của một tag đã có).
    //
    // Bản Instream của cặp AdBreakInPlayerAnimated* đã có sẵn ở trên: Facebook đang đổi tên
    // AdBreak* -> Instream* và ship cả hai, nửa Instream của hai wrapper này chưa ai chạm tới.
    "InstreamInPlayerAnimatedAdComponentWrapperSpec",          // X.TUK -> render
    "InstreamInPlayerAnimatedCarouselAdComponentWrapperSpec",  // X.Tha -> A18
    // Anh em của MibQuickPromotionBannerComponent đã có ở trên. Mig != Mib: hai class khác
    // nhau, nên tag kia không bao giờ với tới class này.
    "MigQuickPromotionBannerSpec",                             // X.ZeD -> A18
    // Bản Native của banner ads trong Reels. [reelsBannerRenderMethodsFingerprint] khớp ở
    // mức METHOD (method phải tự chứa literal) nên nó chỉ bắt được X.SZQ.A18 của
    // ReelsBannerAdsComponent; ở bản Native literal nằm trong một method khác của class, nên
    // render của nó (X.TcT) chưa từng bị chặn. Tag ở đây khớp mức CLASS nên với tới.
    "ReelsBannerAdsNativeComponent",                           // X.TcT -> render
)

/**
 * Seeds for deriving the obfuscated Litho *Section* return type.
 *
 * Kept separate from [AD_SECTION_TAGS] now that the latter has grown: the seed only has
 * to be one tag that reliably resolves on every build, and reusing the whole list would
 * make the derivation depend on entries added for unrelated surfaces.
 */
private val AD_SECTION_SEED_TAGS = listOf(
    "AdsCommentSection",
    "BizDiscoCollageSponsoredSection",
)

val AD_SECTION_TAGS = listOf(
    "AdsCommentSection",
    "BizDiscoCollageSponsoredSection",

    // ── Added in the same audit as the component tags above ──────────────────────
    // Sections rather than components, so they resolve against the Section render type.
    // NewUserPYMKPromotionSection is deliberately absent: it is the "people you may
    // know" rail, which is a suggestion rather than an advertisement. The AdActivity
    // sections are absent for the same reason as their components.
    "WatchFeedRealTimeIntentAdHScrollSection",
    "SearchResultsSponsoredMultiStorySection",
    // Hai tag này ĐANG hoạt động, và suýt nữa thì bị xoá.
    //
    // Lượt audit đầu chỉ quét được 16 trong 21 file dex của app, và trong 16 file đó không có
    // class nào dùng hai chuỗi này — kết luận rút ra khi ấy là "đã chết, giữ lại chỉ vì miễn
    // phí". Quét lại với đủ 21 dex thì cả hai đều có, và cả hai đều resolve ra một section
    // render thật. Xoá theo kết luận cũ là mất đúng hai hook đang chạy.
    //
    // Bài học không nằm ở hai dòng này mà ở cách đọc kết quả quét: một tag không tìm thấy chỉ
    // chứng minh được điều gì đó khi tập dex quét là ĐẦY ĐỦ, còn tìm thấy thì luôn chắc chắn.
    "AdExtensionsSectionComponent",
    "AdExtensionsPaginationSectionComponent",
)

val adSurfaceRenderMethodsFingerprint = findMethodListDirect {
    adRenderMethodsFor(
        AD_SURFACE_RENDER_TAGS,
        seedTags = listOf("ReelsBannerAdsComponent", "FbShortsAdsRootKComponent.render", "AdBreakContextCardComponent"),
    )
}

/**
 * Litho Sections rather than Components — a different return type, same idea.
 * The user's own "Ad Activity" history screen is deliberately excluded: it is a settings
 * surface for reviewing ads, not an advertisement.
 */
val adSectionRenderMethodsFingerprint = findMethodListDirect {
    adRenderMethodsFor(AD_SECTION_TAGS, seedTags = AD_SECTION_SEED_TAGS)
}


// ─── Stories ads ──────────────────────────────────────────────────────────────
// Sponsored slides between friends' stories. Anchored on a real, unobfuscated class name
// rather than on anything version-specific.

private const val AD_STORY_CLASS = "com.facebook.audience.snacks.model.AdStory"

/**
 * Litho components holding an [AD_STORY_CLASS] field — the caption, label, CTA and overlay
 * pieces of a story ad. Structural rather than tag-based, so it needs no per-component
 * list and does not go stale when Facebook renames things.
 */
val storyAdComponentRenderMethodsFingerprint = findMethodListDirect {
    val renderType = renderReturnTypeFrom(
        listOf("ReelsBannerAdsComponent", "FbShortsAdsRootKComponent.render")
    ) ?: return@findMethodListDirect emptyList()

    val methods = runCatching {
        findClass {
            matcher { fields { add { type = AD_STORY_CLASS } } }
        }.flatMap { cls ->
            cls.findMethod { matcher { paramCount = 1; returnType = renderType } }
        }
    }.getOrDefault(emptyList()).filter { it.isRenderShaped() }.distinctBy { it.descriptor }

    rejectSharedFeedComponents(methods)
}

/**
 * The Stories ad pagination fetch — stops story ads being requested at all, which is
 * cheaper and less visible than removing them once they have arrived.
 */
val storiesAdsPaginationMethodFingerprint = findMethodListDirect {
    findMethod {
        matcher {
            paramTypes(
                "com.facebook.auth.usersession.FbUserSession",
                null,
                "com.google.common.collect.ImmutableList",
                "com.google.common.collect.ImmutableList"
            )
            usingStrings("FBStoriesAdsPaginatingQuery")
        }
    }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }
}

/**
 * The profile timeline story component's render.
 *
 * Suppressing this component wholesale blanks the entire "all posts" section — it draws
 * organic posts, customised stories and featured highlights as well as ads — so the hook
 * that uses this decides per story rather than per component.
 */
val timelineStoryRenderMethodFingerprint = findMethodDirect {
    val renderType = renderReturnTypeFrom(
        listOf("ReelsBannerAdsComponent", "FbShortsAdsRootKComponent.render")
    ) ?: error("Litho render type not found")

    findClass {
        matcher { usingStrings("sponsored_timeline_stories_test_key") }
    }.flatMap { cls ->
        cls.findMethod { matcher { paramCount = 1; returnType = renderType } }
    }.first { it.isConcreteHookTarget() }
}

// NOTE: fingerprints for the news feed's Reels row (the in-feed-unit pools and the tray
// component that holds the tiles) were removed from this build. They only ever located
// the tile list; nothing consumed them, and a fingerprint that no patch calls resolves
// nothing and blocks nothing. Restoring them is only useful together with a patch that
// drops ad entries FROM that list — suppressing a tile's render leaves it in the tray as
// an empty, unlabelled box.



/**
 * The Search "AI mode" ads query — a surface that did not exist on the earlier build.
 *
 * Matched only by its own literal, with no shape constraint, because it is reached through
 * a Kotlin lambda (`invoke(): Object`) rather than a named method, so parameter and return
 * types say nothing useful about it.
 */
val searchAiModeAdsQueryFingerprint = findMethodListDirect {
    findMethod {
        matcher { usingStrings("SearchAIModeAdsQuery") }
    }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }
}


// ─── The ad REQUEST layer ─────────────────────────────────────────────────────
//
// Everything above this line removes an advertisement that has already been delivered.
// Everything below stops one being asked for.
//
// The two are not alternatives. A filter has to recognise an ad, and recognition is
// where every regression in this module has come from; a request that never happens
// needs to recognise nothing. So the request hooks carry the surfaces where an
// identifiable fetch exists, and the filters stay in place behind them for the ads that
// arrive by some other route.
//
// Every fingerprint here is anchored on a logging literal of the form
// `ClassName.methodName` — the strings Facebook's own tracing writes. Those survive
// ProGuard because they are string constants rather than symbols, they name the method
// they sit in, and they change far less often than the obfuscated names around them.
// Each is additionally constrained by return type, because the hook that consumes it
// has to be able to produce a value the caller can use.

// ─── Tầng chặn request: MỘT lượt quét chung ──────────────────────────────────
//
// Mọi fingerprint của [BlockFacebookAdRequests] neo vào chuỗi đều được trả lời trong đúng
// MỘT `batchFindMethodUsingStrings`, cộng đúng MỘT `batchFindClassUsingStrings` cho hai
// mỏ neo ở mức class. Trước đây là 19 lượt duyệt toàn cục — mỗi lượt tốn sàn ~110 ms vì
// phải đi hết string index — nay còn 2.
//
// Đây chính là kỹ thuật mà [classesUsingAnyOf] đã dùng để kéo tầng component từ 24 s
// xuống dưới 1 s, chỉ là trước đây chưa áp cho tầng request. Điểm khác biệt duy nhất so
// với các fingerprint cũ: ràng buộc hình dạng (kiểu trả về, số tham số) không còn nằm
// trong matcher mà được áp bằng Kotlin sau đó, trên đúng nhúm method mang tag — cùng một
// phép thử, chỉ chạy trên tập nhỏ hơn rất nhiều.
//
// Hai mỏ neo `FeedAsyncAdsController.*` được dùng lại làm seed cho lớp cơ sở ad-channel
// thay vì hỏi lại DexKit, nên hai lượt duyệt trùng lặp trước đây cũng biến mất.
//
// Group nào mang NHIỀU chuỗi thì `StringMatchersGroup` hiểu là AND — đó là cách hai mỏ neo
// của trình phát toàn màn hình được diễn đạt, vốn cần đúng ngữ nghĩa "đi cùng nhau" mà
// chú thích của chúng đã giải thích.

private const val TAG_FEED_ASYNC_DO_REQUEST = "FeedAsyncAdsController.doAsyncAdRequest"
private const val TAG_FEED_ASYNC_MAYBE_REQUEST = "FeedAsyncAdsController.maybeDoAdChannelRequest"
private const val TAG_FEED_ASYNC_ON_NEXT = "FeedAsyncAdsController.onNext"
private const val TAG_STORY_VIEWER_FETCH_MORE = "StoryViewerAdsPaginatingDataManager.fetchMoreAds"
private const val TAG_AD_BUCKET_FETCH = "AdPaginatingBucketStaticInsertionDataSource.fetchAds"
private const val TAG_AD_BUCKET_FETCH_MORE = "AdPaginatingBucketStaticInsertionDataSource.fetchMoreAds"
private const val TAG_REELS_VIDEO_ADS_QUERY = "FBFetchReelsVideoAdsQuery"
private const val TAG_VIDEO_HOME_RTI_INSERT =
    "VideoHomeDataControllerAdsUtil.maybeInsertFbShortsRealtimeIntentItem"
private const val TAG_POS_ONE_ELIGIBLE = "NewsFeedPosOneAdStats.isPosOneAdEligibleInSession"
private const val TAG_FEED_NETWORK_AD_CHANNEL = "FeedNetworkController.doAdChannelNetworkRequest"
private const val TAG_VIDEO_HOME_AD_CHANNEL = "VideoHomeCSRNetworkRequester.doAdChannelNetworkRequest"
private const val TAG_EXTRA_SPONSORED_FETCH =
    "AdsChannelNetworkHandlerHelper.doFetchAdditionalSponsoredStoriesFromNetwork"
private const val TAG_ASYNC_ADS_TAIL_LOAD = "MainFeedCSRDataLoaderImpl.maybeDoAsyncAdsTailLoad"
private const val TAG_VIDEO_HOME_INSERT_ADS = "VideoHomeDataControllerImpl.maybeInsertAds"
private const val TAG_VIDEO_HOME_FETCH_ADS = "VideoHomeDataFetcher.fetchAds"
private const val TAG_VIDEO_HOME_RTI_RENDER =
    "VideoHomeDataControllerImpl.renderFbShortsRealtimeIntentAds"
private const val TAG_VIDEO_HOME_MIDCARD_SURVEY =
    "VideoHomeDataControllerImpl.maybeTriggerFbShortsAdsMidCardSurveyRequest"
private const val TAG_STORY_VIEWER_FETCH_PAYLOAD =
    "StoryViewerAdsPaginatingDataManager.fetchAdsWithPayload"
private const val TAG_AD_BUCKET_BUILD_PAYLOAD =
    "AdPaginatingBucketStaticInsertionDataSource.buildAdsRequestPayload"
private const val TAG_SEARCH_AI_AD_STORY = "SearchAIModeAdStoryQuery"
private const val TAG_ADS_SUBSCRIBER_ON_NEXT = "AdsChannelRequestSubscriber.onNext"
private const val TAG_CACHED_AD_DATA_PARAMS = "FetchNewsFeedMethod.addCachedAdDataParams"
private const val TAG_RERANK_WHEN_ADDING = "FeedSponsoredStoryHolder.rerankWhenAddingStory"

private const val TAG_ASYNC_ADS_REQUEST_TYPE = "async_ads_request_type"
private const val TAG_DEEP_DIVE_SURFACE = "fb_shorts_video_deep_dive"
private const val TAG_SFD_CHAINING = "sfd_chaining"

private const val GROUP_DEEP_DIVE_ASYNC = "deepDiveAsyncAdRequest"
private const val GROUP_DEEP_DIVE_CHAIN = "deepDiveChainAd"

private const val IMMUTABLE_LIST = "com.google.common.collect.ImmutableList"

/** Kết quả của lượt quét chung, đã nhóm sẵn theo tag. */
private class BlockRequestScan(
    private val methodHits: Map<String, List<MethodData>>,
    private val classHits: Map<String, List<ClassData>>,
) {
    /** Hợp của các group đã cho, khử trùng theo descriptor. */
    fun tagged(vararg groups: String): List<MethodData> =
        groups.flatMap { methodHits[it].orEmpty() }.distinctBy { it.descriptor }

    fun classesFor(group: String): List<ClassData> = classHits[group].orEmpty()
}

/**
 * Một `batchFindMethodUsingStrings` cho toàn bộ [spec]; khoá của map là tên group, giá trị
 * là các chuỗi mà method phải mang ĐỦ (AND).
 *
 * Lùi về vòng lặp từng group nếu API batch hỏng, để một bản DexKit không có batch API thì
 * suy giảm thành hành vi cũ chứ không thành rỗng — giống hệt [methodsUsingAnyOf].
 */
private fun DexKitBridge.batchMethodGroups(
    spec: Map<String, List<String>>
): Map<String, List<MethodData>> {
    if (spec.isEmpty()) return emptyMap()
    runCatching { batchFindMethodUsingStrings { groups(spec) } }.getOrNull()?.let { batched ->
        return batched.mapValues { (_, hits) -> hits.distinctBy { it.descriptor } }
    }
    return spec.mapValues { (_, strings) ->
        runCatching {
            findMethod { matcher { usingStrings(strings) } }.distinctBy { it.descriptor }
        }.getOrDefault(emptyList())
    }
}

/** Bản mức class của [batchMethodGroups]. */
private fun DexKitBridge.batchClassGroups(
    spec: Map<String, List<String>>
): Map<String, List<ClassData>> {
    if (spec.isEmpty()) return emptyMap()
    runCatching { batchFindClassUsingStrings { groups(spec) } }.getOrNull()?.let { batched ->
        return batched.mapValues { (_, hits) -> hits.distinctBy { it.descriptor } }
    }
    return spec.mapValues { (_, strings) ->
        runCatching {
            findClass { matcher { usingStrings(strings) } }.distinctBy { it.descriptor }
        }.getOrDefault(emptyList())
    }
}


/**
 * The news feed's asynchronous ad channel.
 *
 * A pipeline with no coverage in this module at all until now, and the one that delivers
 * most feed ads on current builds. Rather than arriving inside the feed response, ads are
 * fetched by a second request on their own channel and spliced into the story stream as
 * they come back — which is why they slip past the CSR filter and the sponsored pool
 * alike, both of which only ever see the feed response.
 *
 * Two entry points, both `void`, so both can simply not run:
 *  - `doAsyncAdRequest`, the request itself;
 *  - `maybeDoAdChannelRequest`, the newer gate that decides to make one.
 */
private fun BlockRequestScan.resolveFeedAsyncAdRequest(): List<MethodData> =
    tagged(TAG_FEED_ASYNC_DO_REQUEST, TAG_FEED_ASYNC_MAYBE_REQUEST).filter { it.returnTypeName == "void" && it.isConcreteHookTarget() }

/**
 * Where the ad channel's response is turned into feed edges.
 *
 * Hooked as well as the request, not instead of it, because the channel is also fed by
 * a prefetch that runs before this module's patches are installed: without this, a
 * response already in flight would still be spliced in. Returns the empty list, which is
 * what the caller sees on every request that legitimately finds no ad to serve.
 */
private fun BlockRequestScan.resolveFeedAsyncAdResult(): List<MethodData> =
    tagged(TAG_FEED_ASYNC_ON_NEXT)
        .filter { it.returnTypeName == IMMUTABLE_LIST && it.isConcreteHookTarget() }

/**
 * The Stories viewer's ad pagination, on the data-manager side.
 *
 * [storiesAdsPaginationMethodFingerprint] already blocks the GraphQL query, but only on
 * the provider that logs "ads_deletion" — and the current build ships a second, newer
 * paginating source that does not. That one holds its own ad buckets and refills them
 * itself, so it kept inserting sponsored slides between friends' stories.
 */
private fun BlockRequestScan.resolveStoryViewerAdsFetch(): List<MethodData> =
    tagged(TAG_STORY_VIEWER_FETCH_MORE, TAG_AD_BUCKET_FETCH, TAG_AD_BUCKET_FETCH_MORE)
        .filter { it.returnTypeName == "void" && it.isConcreteHookTarget() }

/**
 * The Reels video-ad fetch.
 *
 * Distinct from the Reels chaining and profile-Reels queries: this is the one that tops
 * up the ad supply for the main Reels viewer.
 */
private fun BlockRequestScan.resolveReelsVideoAdsFetch(): List<MethodData> =
    tagged(TAG_REELS_VIDEO_ADS_QUERY).filter { it.returnTypeName == "void" && it.isConcreteHookTarget() }

/**
 * Insertion of a real-time-intent ad into the Video Home / Reels item stream.
 *
 * The last step of that pipeline rather than the first: it takes an ad that has already
 * been fetched and places it in the list the viewer scrolls. Skipping it leaves the
 * stream as it was, with no slot to collapse.
 */
private fun BlockRequestScan.resolveVideoHomeAdInsertion(): List<MethodData> =
    tagged(TAG_VIDEO_HOME_RTI_INSERT).filter { it.returnTypeName == "void" && it.isConcreteHookTarget() }

/**
 * The ad-channel *network* layer, one level below the async-ad controller.
 *
 * [resolveFeedAsyncAdRequest] stops the controller deciding to make a
 * request. These are the methods that actually put one on the wire, and they are reached
 * by callers the controller does not own — which is how the news feed and Reels kept
 * receiving ads on a build where the controller hooks landed. Verified on the shipped
 * dex: each of these methods references its tag and nothing else that is organic.
 *
 *  - `FeedNetworkController.doAdChannelNetworkRequest` — the news feed's ad channel.
 *    Its own logging (`fresh_feed_ads_channel_fetch`, `startAdChannelRequestRequest`,
 *    `RELATED_ADS`, `MULTI_ADS`) confirms it serves nothing else, and the app already
 *    has a no-op path for it: it logs "doAdChannelNetworkRequest disabled" and returns.
 *  - `VideoHomeCSRNetworkRequester.doAdChannelNetworkRequest` — the same request for
 *    Reels and Video Home (`reels_head`, `reels_tail`, `fb_shorts_similar_ad`).
 *  - `AdsChannelNetworkHandlerHelper.doFetchAdditionalSponsoredStoriesFromNetwork` —
 *    the top-up fetch that pulls more sponsored stories mid-session.
 *  - `MainFeedCSRDataLoaderImpl.maybeDoAsyncAdsTailLoad` — the tail load that requests
 *    ads as the user scrolls past the end of the current page.
 *
 * All four are `void`, so [hookAdRequestNoOp] can simply skip them.
 */
private fun BlockRequestScan.resolveAdChannelNetworkRequest(): List<MethodData> =
    tagged(
        TAG_FEED_NETWORK_AD_CHANNEL,
        TAG_VIDEO_HOME_AD_CHANNEL,
        TAG_EXTRA_SPONSORED_FETCH,
        TAG_ASYNC_ADS_TAIL_LOAD,
    ).filter { it.returnTypeName == "void" && it.isConcreteHookTarget() }

/**
 * The Video Home / Reels ad pipeline, beyond the single real-time-intent insertion point
 * already covered by [resolveVideoHomeAdInsertion].
 *
 *  - `VideoHomeDataControllerImpl.maybeInsertAds` — the general insertion step. The RTI
 *    hook only covers one ad kind; this is the one every other Reels ad goes through.
 *  - `VideoHomeDataFetcher.fetchAds` — the fetch itself (`TRIGGERED_BY_AD`, `ad_ids`).
 *  - `VideoHomeDataControllerImpl.renderFbShortsRealtimeIntentAds` — the delayed render
 *    that fires after the RTI request lands, on a timer callback the insertion hook
 *    never sees.
 *  - `VideoHomeDataControllerImpl.maybeTriggerFbShortsAdsMidCardSurveyRequest` — the
 *    "how was this ad" survey card. An ad-only interruption in the Reels stream.
 *
 * `maybeRemoveAdsMidCardSurveyAndPreventFutureTriggers` is deliberately NOT here: it is
 * the method that takes the survey away again.
 */
private fun BlockRequestScan.resolveVideoHomeAdsPipeline(): List<MethodData> =
    tagged(
        TAG_VIDEO_HOME_INSERT_ADS,
        TAG_VIDEO_HOME_FETCH_ADS,
        TAG_VIDEO_HOME_RTI_RENDER,
        TAG_VIDEO_HOME_MIDCARD_SURVEY,
    ).filter { it.returnTypeName == "void" && it.isConcreteHookTarget() }

/**
 * The Stories viewer's *payload* ad fetch.
 *
 * [resolveStoryViewerAdsFetch] covers `fetchAds` and `fetchMoreAds`. On this
 * build both resolve to one method and a second, separate method carries the payload
 * flavour — `fetchAdsWithPayload` and `buildAdsRequestPayload` share it — so the viewer
 * still had a route to top up its ad buckets with the other one blocked.
 *
 * As before, only the fetch is stopped. `updatePositionAndGetAdBucketList` is left alone
 * on purpose: it returns the list the viewer actually pages through, organic stories
 * included, and emptying it ends the story session instead of removing ads from it.
 *
 * `StoryViewerAdsPrefetchController.onPrefetchAdJobEnter` looked like an obvious fourth
 * entry and is deliberately absent. It resolves to a shared app-job dispatcher carrying
 * dozens of unrelated tags — crash reporting, login, notifications, message expiry — so
 * skipping it would disable app startup work that has nothing to do with advertising.
 */
private fun BlockRequestScan.resolveStoryViewerAdsPayloadFetch(): List<MethodData> =
    tagged(TAG_STORY_VIEWER_FETCH_PAYLOAD, TAG_AD_BUCKET_BUILD_PAYLOAD).filter { it.returnTypeName == "void" && it.isConcreteHookTarget() }

/**
 * The search "AI mode" ad *story* query.
 *
 * A second query alongside [searchAiModeAdsQueryFingerprint]'s `SearchAIModeAdsQuery`:
 * that one asks which ads to show, this one fetches the story behind an ad already
 * chosen. Blocking only the first left the second able to hydrate ads from cache.
 */
private fun BlockRequestScan.resolveSearchAiModeAdStoryQuery(): List<MethodData> =
    tagged(TAG_SEARCH_AI_AD_STORY).filter { it.returnTypeName == "void" && it.isConcreteHookTarget() }

/**
 * The sponsored-story vendor: the two methods the feed calls to pick which advertisement
 * to drop into the next slot.
 *
 * Both return a `GraphQLFeedUnitEdge`, and both already have a no-ad path — the vendor
 * logs `empty_pool` when the pool has nothing eligible and the feed simply continues with
 * organic stories. Returning null puts them permanently on that path, which is a cleaner
 * outcome than filtering the ad out further downstream: no slot is allocated, so nothing
 * has to be collapsed afterwards.
 *
 * Constrained to the `GraphQLFeedUnitEdge` return type rather than matched on the tag
 * alone, so a tag appearing in a logging helper cannot be hooked by mistake.
 */
val sponsoredStoryVendorMethodsFingerprint = findMethodListDirect {
    methodsUsingAnyOf(
        listOf(
            "FeedSponsoredStoryHolder.getTopValidAd",
            "FeedSponsoredStoryHolder.rerankAdsForGetBestAdStory",
        )
    ).filter {
        it.returnTypeName == GRAPHQL_FEED_UNIT_EDGE_CLASS && it.isConcreteHookTarget()
    }.distinctBy { it.descriptor }
}

/**
 * The Instant Games ad module's load gate and its banner-ad loader — the two halves of
 * that surface that are obfuscated, so they cannot be reached by name like
 * [QUICKSILVER_ADS_LOADER_CLASS] can.
 *
 *  - `quicksilverAdsVoltronGateFingerprint` is the waiter that blocks until the ads
 *    module finishes loading and reports whether it did. A 0-argument boolean, so
 *    answering "it did not" is a state the caller already handles — it is the same
 *    answer an interrupted load produces, which the method itself logs.
 *  - `quicksilverBannerAdLoaderMethodsFingerprint` is the runnable that builds the
 *    banner ad view over a running game. Whole-class ad code: the only other strings it
 *    carries are its own error message and the placement. `void run()`, so it can simply
 *    not run, and the game keeps its full viewport.
 */
val quicksilverAdsVoltronGateFingerprint = findMethodListDirect {
    findMethod {
        matcher {
            returnType = "boolean"
            paramCount = 0
            usingStrings("QuicksilverAdsVoltronModule")
        }
    }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }
}

val quicksilverBannerAdLoaderMethodsFingerprint = findMethodListDirect {
    findMethod {
        matcher {
            returnType = "void"
            paramCount = 0
            usingStrings("com.facebook.quicksilver.adscommon.QuicksilverBannerAdsHandlerImpl")
        }
    }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }
}

/**
 * Eligibility for the position-one feed ad — the advert in the very first slot of the
 * news feed, allocated by its own session-level budget rather than by the feed ranker.
 *
 * A zero-argument boolean, so answering "not eligible" is exactly what the app already
 * does for a session that has spent its budget. No slot is created, so nothing has to be
 * filtered out of the feed afterwards.
 */
private fun BlockRequestScan.resolveNewsFeedPosOneAdEligibility(): List<MethodData> =
    tagged(TAG_POS_ONE_ELIGIBLE).filter {
        it.returnTypeName == "boolean" && it.paramTypeNames.isEmpty() && it.isConcreteHookTarget()
    }

// ─── Ad channel: lớp cơ sở dùng chung và subscriber ───────────────────────────
//
// Hai fingerprint dưới đây ra đời từ một lần quét dex thật, và cấu trúc của chúng là kết quả
// của việc kiểm chứng lại một giả thuyết SAI — đáng ghi lại vì lần audit sau rất dễ đi vào
// đúng cái bẫy ấy.
//
// Giả thuyết ban đầu: "CSRAdChannelControllerImpl là một controller thứ hai, ngang hàng với
// FeedAsyncAdsController, và chưa được hook." Nhìn shape thì rất hợp lý — nó có đúng bộ
// `void(2 tham số)` phát request cộng `ImmutableList(2 tham số)` nhận kết quả.
//
// Đọc kỹ dex thì không phải vậy. Class đó là LỚP CƠ SỞ TRỪU TƯỢNG, và FeedAsyncAdsController
// chính là một trong hai lớp con của nó:
//
//   CSRAdChannelControllerImpl (abstract)   <- Cwr / Dgq ở đây là code THẬT, không abstract
//     ├── FeedAsyncAdsController (final)    <- GHI ĐÈ cả hai; đã bị chặn từ trước
//     └── lớp con thứ hai (final)           <- KHÔNG ghi đè; chạy thẳng code của lớp cơ sở
//
// Lớp con thứ hai không mang literal nào có chữ "Ad" trong tên, nên không một tìm kiếm theo
// chuỗi nào chạm tới nó được; thứ nói lên bản chất của nó là ba literal riêng —
// "bestCSRRankingScoreInPool", "bestTotalBidInPool", "num_long_vpvd_in_session" — toàn bộ đều
// là từ vựng của phiên đấu giá quảng cáo. Nó đi qua đúng hai method của lớp cơ sở.
//
// Vì vậy hook đặt vào chính lớp cơ sở: nó phủ lớp con thứ hai và bất kỳ lớp con nào sau này
// không ghi đè, trong khi FeedAsyncAdsController vẫn do
// [resolveFeedAsyncAdRequest] và [resolveFeedAsyncAdResult] lo — bản
// ghi đè của nó là một method khác hoàn toàn, hook lớp cơ sở không đụng tới.
//
// Tên method obfuscated không bị pin: nó được SUY RA từ chính FeedAsyncAdsController, vốn đã
// được định danh bằng literal riêng. Lớp con và lớp cha bắt buộc dùng chung tên method, nên
// khi Facebook đổi tên obfuscated giữa hai bản thì cả hai đổi cùng lúc.

private const val CSR_AD_CHANNEL_TAG = "CSRAdChannelControllerImpl"

/**
 * Method hook được, xét ở mức method chứ không mức class.
 *
 * Khác [isConcreteHookTarget] ở đúng một điểm, và đó là điểm mấu chốt ở đây: hàm kia loại bỏ
 * mọi method có class khai báo là abstract. Với phần lớn fingerprint thì đúng — một method
 * abstract không có thân để hook. Nhưng một method KHÔNG abstract nằm trong một class abstract
 * thì lại chính là bản cài đặt thật, và là bản duy nhất mà những lớp con không ghi đè sẽ chạy.
 * Đó đúng là trường hợp của hai method ad-channel ở trên.
 */
private fun MethodData.isHookableMethod(): Boolean =
    !isConstructor && !Modifier.isAbstract(modifiers)

private fun BlockRequestScan.resolveCsrAdChannelRequest(): List<MethodData> {
    val seed = tagged(TAG_FEED_ASYNC_MAYBE_REQUEST).firstOrNull { it.returnTypeName == "void" }
        ?: return emptyList()

    return classesFor(CSR_AD_CHANNEL_TAG).flatMap { cls ->
        cls.findMethod {
            matcher {
                name = seed.name
                returnType = "void"
                paramCount = seed.paramTypeNames.size
            }
        }
    }.filter { it.isHookableMethod() }.distinctBy { it.descriptor }
}

private fun BlockRequestScan.resolveCsrAdChannelResult(): List<MethodData> {
    val seed = tagged(TAG_FEED_ASYNC_ON_NEXT).firstOrNull { it.returnTypeName == IMMUTABLE_LIST }
        ?: return emptyList()

    return classesFor(CSR_AD_CHANNEL_TAG).flatMap { cls ->
        cls.findMethod {
            matcher {
                name = seed.name
                returnType = IMMUTABLE_LIST
                paramCount = seed.paramTypeNames.size
            }
        }
    }.filter { it.isHookableMethod() }.distinctBy { it.descriptor }
}

/**
 * Subscriber nhận response của ad channel.
 *
 * Đây là mảnh còn thiếu của lập luận "chặn request thôi chưa đủ" mà [BlockFacebookAdRequests]
 * đã viết ra: kênh ads được hâm nóng bởi một prefetch chạy trước khi module kịp cài hook, nên
 * một response đang bay vẫn ghép được vào feed. [resolveFeedAsyncAdResult] chặn
 * chỗ controller biến response thành edge; cái này chặn sớm hơn một bước, ngay tại subscriber
 * nhận response.
 *
 * Trên dex được audit, class này mang ĐÚNG ba literal và cả ba đều là tên method của chính nó
 * (`onNext` / `onError` / `onCompleted`) — không một literal organic nào — nên chặn thẳng
 * `onNext` không thể chạm tới nội dung tự nhiên. `onError` cố ý không đụng tới: nó là đường
 * app tự xử lý khi request hỏng, và để nguyên thì app kết thúc luồng đúng như mọi lần lỗi mạng.
 */
private fun BlockRequestScan.resolveAdsChannelSubscriberNext(): List<MethodData> =
    tagged(TAG_ADS_SUBSCRIBER_ON_NEXT).filter { it.returnTypeName == "void" && it.isConcreteHookTarget() }

/** Method biến response của subscriber thành danh sách edge — trả list rỗng. */
private fun BlockRequestScan.resolveAdsChannelSubscriberResult(): List<MethodData> =
    classesFor(TAG_ADS_SUBSCRIBER_ON_NEXT).flatMap { cls ->
        cls.findMethod { matcher { returnType = IMMUTABLE_LIST; paramCount = 1 } }
    }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }

/**
 * Hai method `void` phụ trợ của tầng request, mỗi cái đã được kiểm tra từng literal một.
 *
 *  - `FetchNewsFeedMethod.addCachedAdDataParams` — method chỉ để nhét tham số dữ liệu quảng
 *    cáo đã cache vào request feed. Trên dex được audit, method này mang ĐÚNG một literal là
 *    tên của chính nó, nên bỏ qua nó chỉ làm request thiếu phần tham số quảng cáo.
 *  - `FeedSponsoredStoryHolder.rerankWhenAddingStory` — xếp lại thứ hạng quảng cáo khi thêm
 *    story vào holder. Class này chính là sponsored story holder mà
 *    [sponsoredStoryNextMethodFingerprint] đã hook, tức toàn bộ class thuộc về quảng cáo.
 *
 * `FetchNewsFeedMethod.addCachedStoryAndAdParams` trông như ứng viên thứ ba và CỐ Ý không có
 * ở đây: method mang literal đó còn mang thêm `addCachedStoriesData` và
 * `addAdditionalQueryParams` — nó là bộ dựng tham số dùng chung cho cả feed, bỏ qua nó là bỏ
 * luôn tham số của story tự nhiên. Đúng một chữ "And" trong tên tách hai method này ra làm
 * hai số phận ngược nhau, và chỉ có dex mới nói được điều đó.
 */
private fun BlockRequestScan.resolveFeedAdRequestParam(): List<MethodData> =
    tagged(TAG_CACHED_AD_DATA_PARAMS, TAG_RERANK_WHEN_ADDING).filter { it.returnTypeName == "void" && it.isConcreteHookTarget() }

// ─── Trình phát TOÀN MÀN HÌNH (short-form deep dive) ──────────────────────────
//
// Surface mà mọi thứ ở trên bỏ sót: bấm "Toàn màn hình" trên một video trong feed sẽ mở
// trình phát deep-dive nằm ngang, có hàng đợi "TIẾP THEO" riêng — và hàng đợi đó tự xin
// quảng cáo của nó, bằng một pipeline không dùng chung literal nào với feed hay với Reels
// viewer. Đó là lý do một người dùng bật đủ mọi patch vẫn thấy quảng cáo toàn màn hình.
//
// Tìm ra bằng cách đọc thân method trong dex chứ không phải đoán theo tên. Class fetch
// quảng cáo của chuỗi phát dựng một GraphQL query với đúng các tham số này:
//
//     "async_ads_request_type"      = "INTERSTITIAL"
//     "page_type"                   = "PAGE_MOBILE_REELS"
//     "feed_story_render_location"  = "fb_shorts_video_deep_dive"
//     ... và log lỗi của nó là "Failed to fetch ad for chain "
//
// Ba lambda anh em cùng họ dựng các biến thể khác của cùng một request, trong đó một cái
// mang "POE_TRIGGERED_INTERSTITIAL" và "POST_ROLL_TRIGGERED_INTERSTITIAL" — quảng cáo xen
// giữa và quảng cáo chạy sau khi video kết thúc, đúng thứ chen vào giữa hai video trong
// hàng đợi.
//
// Neo bằng CẶP chuỗi chứ không phải một chuỗi: "async_ads_request_type" một mình xuất hiện
// ở nhiều tầng ads khác, "fb_shorts_video_deep_dive" một mình có ở cả code organic. Đi cùng
// nhau thì chỉ còn đúng năm method, và cả năm đều là request quảng cáo. Một trong năm cái đó
// là doAdChannelNetworkRequest vốn đã bị mục 6 chặn — trùng lặp vô hại vì hook helper tự khử
// theo method.

private fun BlockRequestScan.resolveDeepDiveAsyncAdRequest(): List<MethodData> =
    tagged(GROUP_DEEP_DIVE_ASYNC).filter { it.isConcreteHookTarget() }

/**
 * Bước CHÈN quảng cáo vào hàng đợi phát tiếp, nằm cùng class với bước fetch ở trên.
 *
 * Chặn fetch là đủ cho một phiên sạch, nhưng chuỗi phát còn có đường prefetch riêng (log lỗi
 * "Failed to prefetch ad for chain ") có thể đã cầm sẵn một quảng cáo từ trước khi hook kịp
 * cài. Bước chèn là chỗ duy nhất cả hai đường đều phải đi qua.
 *
 * Class này chỉ có đúng hai method static void — một fetch, một chèn — và không mang literal
 * nào ngoài từ vựng quảng cáo, nên chặn cả hai không đụng tới nội dung tự nhiên. Nếu một bản
 * Facebook sau này nhét thêm việc khác vào đây thì cách nhận ra là hàng đợi "TIẾP THEO" ngừng
 * nạp video mới; tắt [BlockFacebookAdRequests] là quay lại như cũ.
 */
private fun BlockRequestScan.resolveDeepDiveChainAd(): List<MethodData> =
    tagged(GROUP_DEEP_DIVE_CHAIN)
        .mapNotNull { it.declaredClass }
        .distinctBy { it.name }
        .flatMap { cls ->
            cls.findMethod { matcher { modifiers = Modifier.STATIC; returnType = "void" } }
        }.filter { it.isConcreteHookTarget() }.distinctBy { it.descriptor }

// ─── Điểm vào duy nhất của [BlockFacebookAdRequests] ─────────────────────────
//
// MỘT fingerprint, nên MỘT cache key, cho toàn bộ tầng chặn request.
//
// Lý do là hiệu năng, và nó nằm ở `SharedPrefCache`: `putStringList(key, emptyList())` ghi
// `emptyList().joinToString("|")` == "", còn `getStringList` lọc `takeIf(String::isNotBlank)`
// rồi trả `default` = null. Trong `CacheBridgeStore.getCachedList`, null nghĩa là "KHÔNG CÓ
// key trong cache" chứ không phải "cache rỗng" — nên nó gọi loader và quét lại DexKit.
//
// Hệ quả: mọi fingerprint trả về list rỗng đều bị quét lại MỖI LẦN mở app, vĩnh viễn. Và vì
// `CacheBridgeRuntime` tạo bridge native theo kiểu lazy, chỉ cần MỘT lần quét như vậy là
// `DexKitBridge.create(classLoader, true)` phải parse lại toàn bộ ~16 dex của Facebook —
// trên main thread, trong `KatanaDexGate`, một lần cho mỗi attempt.
//
// Trước đây patch này mở 17 cache key riêng, tức 17 cơ hội dính một key rỗng. Gộp thành một
// key thì chỉ cần MỘT nhóm resolve được là cả danh sách được lưu, và những lần mở sau không
// còn chạm tới DexKit nữa.
//
// Cái giá: một lần resolve trên dex nạp dở sẽ được cache nguyên trạng (trước đây kết quả
// rỗng vô tình không bao giờ được lưu). Vì vậy [BlockFacebookAdRequests] bắt buộc phải có
// readiness gate như HideFacebookAds. Nếu vẫn lỡ cache thiếu, cache tự lành ở lần build
// module kế tiếp: khoá cache mang theo `BuildConfig.COMMIT_HASH`.
//
// Kiểu trả về của từng method quyết định cách hook ở phía patch, đúng như từng mục vẫn làm.
val blockAdRequestTargetsFingerprint = findMethodListDirect {
    val scan = BlockRequestScan(
        methodHits = batchMethodGroups(
            mapOf(
                TAG_FEED_ASYNC_DO_REQUEST to listOf(TAG_FEED_ASYNC_DO_REQUEST),
                TAG_FEED_ASYNC_MAYBE_REQUEST to listOf(TAG_FEED_ASYNC_MAYBE_REQUEST),
                TAG_FEED_ASYNC_ON_NEXT to listOf(TAG_FEED_ASYNC_ON_NEXT),
                TAG_STORY_VIEWER_FETCH_MORE to listOf(TAG_STORY_VIEWER_FETCH_MORE),
                TAG_AD_BUCKET_FETCH to listOf(TAG_AD_BUCKET_FETCH),
                TAG_AD_BUCKET_FETCH_MORE to listOf(TAG_AD_BUCKET_FETCH_MORE),
                TAG_REELS_VIDEO_ADS_QUERY to listOf(TAG_REELS_VIDEO_ADS_QUERY),
                TAG_VIDEO_HOME_RTI_INSERT to listOf(TAG_VIDEO_HOME_RTI_INSERT),
                TAG_POS_ONE_ELIGIBLE to listOf(TAG_POS_ONE_ELIGIBLE),
                TAG_FEED_NETWORK_AD_CHANNEL to listOf(TAG_FEED_NETWORK_AD_CHANNEL),
                TAG_VIDEO_HOME_AD_CHANNEL to listOf(TAG_VIDEO_HOME_AD_CHANNEL),
                TAG_EXTRA_SPONSORED_FETCH to listOf(TAG_EXTRA_SPONSORED_FETCH),
                TAG_ASYNC_ADS_TAIL_LOAD to listOf(TAG_ASYNC_ADS_TAIL_LOAD),
                TAG_VIDEO_HOME_INSERT_ADS to listOf(TAG_VIDEO_HOME_INSERT_ADS),
                TAG_VIDEO_HOME_FETCH_ADS to listOf(TAG_VIDEO_HOME_FETCH_ADS),
                TAG_VIDEO_HOME_RTI_RENDER to listOf(TAG_VIDEO_HOME_RTI_RENDER),
                TAG_VIDEO_HOME_MIDCARD_SURVEY to listOf(TAG_VIDEO_HOME_MIDCARD_SURVEY),
                TAG_STORY_VIEWER_FETCH_PAYLOAD to listOf(TAG_STORY_VIEWER_FETCH_PAYLOAD),
                TAG_AD_BUCKET_BUILD_PAYLOAD to listOf(TAG_AD_BUCKET_BUILD_PAYLOAD),
                TAG_SEARCH_AI_AD_STORY to listOf(TAG_SEARCH_AI_AD_STORY),
                TAG_ADS_SUBSCRIBER_ON_NEXT to listOf(TAG_ADS_SUBSCRIBER_ON_NEXT),
                TAG_CACHED_AD_DATA_PARAMS to listOf(TAG_CACHED_AD_DATA_PARAMS),
                TAG_RERANK_WHEN_ADDING to listOf(TAG_RERANK_WHEN_ADDING),
                // Hai group AND: một chuỗi đứng riêng thì không đủ hẹp, đi cùng nhau mới đủ.
                GROUP_DEEP_DIVE_ASYNC to listOf(TAG_ASYNC_ADS_REQUEST_TYPE, TAG_DEEP_DIVE_SURFACE),
                GROUP_DEEP_DIVE_CHAIN to listOf(TAG_ASYNC_ADS_REQUEST_TYPE, TAG_SFD_CHAINING),
            )
        ),
        classHits = batchClassGroups(
            mapOf(
                CSR_AD_CHANNEL_TAG to listOf(CSR_AD_CHANNEL_TAG),
                TAG_ADS_SUBSCRIBER_ON_NEXT to listOf(TAG_ADS_SUBSCRIBER_ON_NEXT),
            )
        ),
    )

    listOf(
        scan::resolveFeedAsyncAdRequest,          // 1  void
        scan::resolveFeedAsyncAdResult,           // 2  ImmutableList
        scan::resolveStoryViewerAdsFetch,         // 3  void
        scan::resolveReelsVideoAdsFetch,          // 4  void
        scan::resolveVideoHomeAdInsertion,        // 5  void
        scan::resolveNewsFeedPosOneAdEligibility, // 6  boolean
        scan::resolveAdChannelNetworkRequest,     // 7  void
        scan::resolveVideoHomeAdsPipeline,        // 8  void
        scan::resolveStoryViewerAdsPayloadFetch,  // 9  void
        scan::resolveSearchAiModeAdStoryQuery,    // 10 void
        scan::resolveCsrAdChannelRequest,         // 11 void
        scan::resolveCsrAdChannelResult,          // 12 ImmutableList
        scan::resolveAdsChannelSubscriberNext,    // 13 void
        scan::resolveAdsChannelSubscriberResult,  // 14 ImmutableList
        scan::resolveFeedAdRequestParam,          // 15 void
        scan::resolveDeepDiveAsyncAdRequest,      // 16 void | Object
        scan::resolveDeepDiveChainAd,             // 17 static void
    ).flatMap { resolve ->
        runCatching { resolve() }.getOrDefault(emptyList())
    }.distinctBy { it.descriptor }
}

// ─── Search results ───────────────────────────────────────────────────────────
//
// Surface riêng: kết quả search đi qua query của chính nó, không qua feed response,
// nên không filter nào trong [HideFacebookAds] nhìn thấy nó. Cả ba fingerprint dưới
// đây đều được đối chiếu trực tiếp với dex FB đang chạy, không đoán theo tên.

/**
 * Enum module-role của kết quả search — 549 giá trị, 11 trong số đó là quảng cáo.
 *
 * Neo bằng bốn giá trị đi cùng nhau. Một giá trị đứng riêng không đủ hẹp
 * ("SEARCH_ADS" còn xuất hiện ở logger và ở Marketplace); bốn giá trị cùng lúc thì
 * chỉ còn đúng enum này.
 */
val searchModuleRoleEnumFingerprint = findClassDirect {
    findClass {
        matcher {
            usingEqStrings(
                "SEARCH_ADS",
                "TOP_POSITION_SEARCH_ADS",
                "DEPENDENT_SEARCH_ADS",
                "MARKETPLACE_SEARCH_ADS",
            )
        }
    }.firstOrNull() ?: error("Unable to resolve the search module role enum")
}

/**
 * Chỗ danh sách kết quả search đi qua khi response được xử lý.
 *
 * Class được định danh bằng "SearchCombinedResultsEdge" — tên type GraphQL, sống sót
 * ProGuard vì là string constant. Ba class dùng chuỗi này trên dex được audit; hai
 * class kia bị loại bởi ràng buộc hình dạng: một là string-table
 * (`A00(int) -> String`), một là `void(FbUserSession, ?, ?)`.
 *
 * Đây là điểm lọc, KHÔNG phải render. Hook tiêu thụ nó xét từng item và chỉ bỏ item
 * tự nhận diện được bằng role, nên một list toàn kết quả organic không bao giờ bị đụng.
 */
val searchResultsEdgeListFingerprint = findMethodDirect {
    findClass {
        matcher { usingStrings("SearchCombinedResultsEdge") }
    }.firstNotNullOfOrNull { cls ->
        cls.findMethod {
            matcher {
                modifiers = Modifier.STATIC
                returnType = "com.google.common.collect.ImmutableList"
                paramTypes("com.google.common.collect.ImmutableList")
            }
        }.firstOrNull()
    } ?: error("Unable to resolve the search results edge list method")
}

/**
 * Request quảng cáo của surface search. Cả ba đều `void` và đều đã được kiểm tra
 * từng literal một trên dex — không method nào mang literal organic:
 *
 *  - `SearchTopPositionAdsQuery` — carousel quảng cáo ở đầu trang kết quả. Method
 *    mang `number_of_ads` và `excluded_ad_ids`, không gì khác.
 *  - `SearchInstantIntentAdsGraphQL` — fetch quảng cáo theo intent, kèm `ad_id` và
 *    `fetch_instant_intent_ads`.
 *  - `SearchAIModeAdStoryQuery` — story đằng sau một quảng cáo AI mode đã được chọn.
 *
 * `SearchAIModeAdsQuery` CỐ Ý vắng mặt. Method mang nó là một lambda Kotlin dùng
 * chung, cũng mang `LunaTopUpdatesSnapshotBeforeQuery` và `HeaderInlineMessageQuery`;
 * chặn nó là chặn luôn tin nhắn inline ở header profile. Cùng lý do mà
 * `FetchNewsFeedMethod.addCachedStoryAndAdParams` bị loại khỏi tầng request feed.
 */
val searchAdRequestMethodsFingerprint = findMethodListDirect {
    methodsUsingAnyOf(
        listOf(
            "SearchInstantIntentAdsGraphQL",
            "SearchAIModeAdStoryQuery",
        )
    ).filter { it.returnTypeName == "void" && it.isConcreteHookTarget() }
        .distinctBy { it.descriptor }
}

package io.github.nexalloy.revanced.facebook.ads

import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import org.luckypray.dexkit.query.enums.MatchType
import java.lang.reflect.Modifier

// ─── Feed list builder ────────────────────────────────────────────────────────

/**
 * The Facebook ad-kind enum: contains "AD", "UGC", "PARADE", "MIDCARD" constants.
 * Used by [AdStoryInspector] to identify ad-backed story objects.
 */
val adKindEnumClassFingerprint = findClassDirect {
    findClass {
        matcher { usingEqStrings("AD", "UGC", "PARADE", "MIDCARD") }
    }.first { candidate ->
        candidate.superClass?.name == "java.lang.Enum"
    }
}

/**
 * The static append method on the reels/home-feed list builder:
 *   static void append(?, ListBuilder, ?, ?, ?, List)
 * Hooked to intercept ad items appended to the feed list.
 */
val listBuilderAppendFingerprint = findMethodDirect {
    val cls = findClass {
        matcher {
            methods {
                matchType = MatchType.Contains
                add {
                    modifiers = Modifier.STATIC
                    returnType = "void"
                    paramTypes = listOf(null, null, null, null, null, "java.util.List")
                }
                add {
                    modifiers = Modifier.STATIC
                    returnType = "java.util.ArrayList"
                    paramTypes = listOf(null, null, null, null, "boolean")
                }
                add {
                    returnType = "java.util.ArrayList"
                    paramTypes = listOf(null, null, null, "java.lang.Iterable")
                }
            }
        }
    }.singleOrNull() ?: findClass {
        matcher { usingEqStrings("Non ads story fall into ads rendering logic, StoryType=%s, StoryId=%s") }
    }.single()

    cls.findMethod {
        matcher {
            modifiers = Modifier.STATIC
            returnType = "void"
            paramTypes = listOf(null, cls.name, null, null, null, "java.util.List")
        }
    }.single()
}

/**
 * The static factory method on the same list builder class:
 *   static ArrayList factory(ListBuilder, ?, ?, ?, boolean)
 * Hooked to filter ad items from the resulting list.
 */
val listBuilderFactoryFingerprint = findMethodDirect {
    val cls = findClass {
        matcher {
            methods {
                matchType = MatchType.Contains
                add {
                    modifiers = Modifier.STATIC
                    returnType = "void"
                    paramTypes = listOf(null, null, null, null, null, "java.util.List")
                }
                add {
                    modifiers = Modifier.STATIC
                    returnType = "java.util.ArrayList"
                    paramTypes = listOf(null, null, null, null, "boolean")
                }
            }
        }
    }.singleOrNull() ?: findClass {
        matcher { usingEqStrings("Non ads story fall into ads rendering logic, StoryType=%s, StoryId=%s") }
    }.single()

    cls.findMethod {
        matcher {
            modifiers = Modifier.STATIC
            returnType = "java.util.ArrayList"
            paramTypes = listOf(cls.name, null, null, null, "boolean")
        }
    }.singleOrNull() ?: cls.findMethod {
        matcher {
            returnType = "java.util.ArrayList"
            paramTypes = listOf(null, null, null, "java.lang.Iterable")
        }
    }.single()
}

/**
 * The "get plugins" method on FbShortsViewerPluginPack:
 *   List getPlugins()
 * Hooked to return an empty list when the pack represents an ad story.
 */
val pluginPackMethodFingerprint = findMethodDirect {
    val cls = findClass {
        matcher {
            methods {
                matchType = MatchType.Contains
                add {
                    returnType = "java.lang.String"
                    paramCount = 0
                    usingStrings("FbShortsViewerPluginPack")
                }
                add {
                    returnType = "java.util.List"
                    paramCount = 0
                }
            }
        }
    }.firstOrNull() ?: findClass {
        matcher { usingEqStrings("FbShortsViewerPluginPack") }
    }.first()

    cls.findMethod {
        matcher {
            returnType = "java.util.List"
            paramCount = 0
        }
    }.single()
}

// ─── Banner / Instream ads ────────────────────────────────────────────────────

/**
 * InstreamAdIdleWithBannerState — phương thức boolean() trả về eligibility
 * của banner hiển thị trong trạng thái idle của Reels player.
 *
 * Tìm class chứa string "InstreamAdIdleWithBannerState", lấy method boolean()
 * không tham số, không static. Hook → return false để tắt banner.
 */
val instreamBannerEligibilityFingerprint = findMethodDirect {
    findClass {
        matcher {
            methods {
                matchType = MatchType.Contains
                add {
                    returnType = "java.lang.String"
                    paramCount = 0
                    usingStrings("InstreamAdIdleWithBannerState")
                }
            }
        }
    }.asSequence().mapNotNull { cls ->
        cls.findMethod {
            matcher {
                returnType = "boolean"
                paramCount = 0
            }
        }.firstOrNull { !Modifier.isStatic(it.modifiers) }
    }.first()
}

/**
 * IndicatorPillComponent / ReelsAdsFloatingCtaPlugin — static boolean(?, ?, ?)
 * điều khiển eligibility của floating CTA overlay khi xem Reels có ads.
 *
 * Tìm class chứa đồng thời:
 *   - "IndicatorPillComponent.render"
 *   - "com.facebook.feedback.comments.plugins.indicatorpill.reelsadsfloatingcta.ReelsAdsFloatingCtaPlugin"
 * rồi lấy static boolean method có 3 tham số. Hook → return false.
 */
val indicatorPillAdEligibilityFingerprint = findMethodDirect {
    findClass {
        matcher {
            usingStrings(
                "IndicatorPillComponent.render",
                "com.facebook.feedback.comments.plugins.indicatorpill.reelsadsfloatingcta.ReelsAdsFloatingCtaPlugin"
            )
        }
    }.asSequence().mapNotNull { cls ->
        cls.findMethod {
            matcher {
                modifiers = Modifier.STATIC
                returnType = "boolean"
                paramCount = 3
            }
        }.firstOrNull()
    }.first()
}

/**
 * ReelsBannerAdsComponent / ReelsBannerAdsNativeComponent — Litho render method.
 *
 * Hook render() → return null để ẩn banner ads component trong Reels player.
 * Điều này loại bỏ banner ads được render trực tiếp qua Litho component tree,
 * bổ sung cho instreamBannerEligibility (chặn từ nguồn) và CSR filter (chặn từ list).
 *
 * Tìm class có string "ReelsBannerAdsComponent" hoặc "ReelsBannerAdsNativeComponent",
 * rồi lấy render method: non-static, non-bridge, 1 param, return type gán được cho class.
 *
 * Source: Patches.kt resolveReelsBannerRenderMethods + resolveLithoRenderMethod [NEW]
 */
val reelsBannerRenderFingerprints = findMethodListDirect {
    val results = LinkedHashMap<String, org.luckypray.dexkit.result.MethodData>()

    listOf("ReelsBannerAdsComponent", "ReelsBannerAdsNativeComponent").forEach { componentName ->
        findClass {
            matcher { usingStrings(componentName) }
        }.forEach { cls ->
            // Lấy Litho render method: non-static, non-bridge, 1 param, return type assignable from class
            cls.findMethod {
                matcher {
                    paramCount = 1
                    returnType = cls.name
                }
            }.filter { m ->
                !Modifier.isStatic(m.modifiers)
            }.forEach { m ->
                results.putIfAbsent("${cls.name}.${m.name}", m)
            }
        }
    }
    results.values.toList()
}

// ─── Feed CSR filter ──────────────────────────────────────────────────────────

/**
 * All concrete implementations of FeedCSRCacheFilter / FeedCSRCacheFilter2025H1 /
 * FeedCSRCacheFilter2026H1. Each has a method:
 *   filter(FbUserSession, ImmutableList, int) → ?
 * Hooked to strip sponsored items before and after the filter runs.
 */
val feedCsrFilterFingerprints = findMethodListDirect {
    val results = LinkedHashMap<String, org.luckypray.dexkit.result.MethodData>()

    listOf("FeedCSRCacheFilter", "FeedCSRCacheFilter2025H1", "FeedCSRCacheFilter2026H1").forEach { tag ->
        findClass {
            matcher {
                methods {
                    matchType = MatchType.Contains
                    add {
                        returnType = "java.lang.String"
                        paramCount = 0
                        usingStrings(tag)
                    }
                }
            }
        }.forEach { cls ->
            cls.findMethod {
                matcher {
                    paramTypes = listOf(
                        "com.facebook.auth.usersession.FbUserSession",
                        "com.google.common.collect.ImmutableList",
                        "int"
                    )
                }
            }.filter { !Modifier.isStatic(it.modifiers) }
             .forEach { m -> results.putIfAbsent("${cls.name}.${m.name}", m) }
        }
    }
    results.values.toList()
}

// ─── Late-stage feed sanitizers ───────────────────────────────────────────────

/**
 * handleStorageStories — void(?, ImmutableList, int).
 * Hooked to strip sponsored items from arg index 1 (ImmutableList).
 */
val lateFeedStorageFingerprint = findMethodDirect {
    findClass {
        matcher { usingStrings("handleStorageStories", "Empty Storage List") }
    }.first().findMethod {
        matcher {
            returnType = "void"
            paramTypes = listOf(null, "com.google.common.collect.ImmutableList", "int")
        }
    }.single()
}

/**
 * cancelVendingTimerAndAddToPool — void(ImmutableList, String).
 * Hooked to strip sponsored items from arg index 0 (ImmutableList).
 */
val lateFeedVendingFingerprint = findMethodDirect {
    findClass {
        matcher { usingStrings("cancelVendingTimerAndAddToPool_") }
    }.first().findMethod {
        matcher {
            returnType = "void"
            paramTypes = listOf("com.google.common.collect.ImmutableList", "java.lang.String")
        }
    }.single()
}

/**
 * CSR storage lifecycle impls — void(FbUserSession, ?, ImmutableList).
 * Hooked to strip sponsored items from arg index 2 (ImmutableList).
 *
 * Bao gồm:
 *  - CSRNoOpStorageLifecycleImpl
 *  - FeedCSRStorageLifecycle
 *  - FriendlyFeedCSRStorageLifecycle
 *  - FbShortsCSRStorageLifecycle
 */
val lateFeedStorageLifecycleFingerprints = findMethodListDirect {
    val results = LinkedHashMap<String, org.luckypray.dexkit.result.MethodData>()

    listOf(
        "CSRNoOpStorageLifecycleImpl",
        "FeedCSRStorageLifecycle",
        "FriendlyFeedCSRStorageLifecycle",
        "FbShortsCSRStorageLifecycle"
    ).forEach { tag ->
        findClass {
            matcher {
                methods {
                    matchType = MatchType.Contains
                    add {
                        returnType = "java.lang.String"
                        paramCount = 0
                        usingStrings(tag)
                    }
                }
            }
        }.forEach { cls ->
            cls.findMethod {
                matcher {
                    returnType = "void"
                    paramTypes = listOf(
                        "com.facebook.auth.usersession.FbUserSession",
                        null,
                        "com.google.common.collect.ImmutableList"
                    )
                }
            }.filter { !Modifier.isStatic(it.modifiers) }
             .forEach { m -> results.putIfAbsent("${cls.name}.${m.name}", m) }
        }
    }
    results.values.toList()
}

// ─── Story pool ───────────────────────────────────────────────────────────────

/**
 * CSRStoryPoolCoordinator + FeedStoryPoolCoordinator — boolean add(item).
 * Hooked to return false (block) when item is a definitely sponsored feed item.
 * Sử dụng isDefinitelySponsoredFeedItem() (strict) để tránh false-positive Reels carousel.
 */
val storyPoolAddFingerprints = findMethodListDirect {
    val results = LinkedHashMap<String, org.luckypray.dexkit.result.MethodData>()

    listOf("CSRStoryPoolCoordinator", "FeedStoryPoolCoordinator").forEach { tag ->
        findClass {
            matcher {
                methods {
                    matchType = MatchType.Contains
                    add {
                        returnType = "java.lang.String"
                        paramCount = 0
                        usingStrings(tag)
                    }
                }
            }
        }.forEach { cls ->
            cls.findMethod {
                matcher {
                    returnType = "boolean"
                    paramCount = 1
                }
            }.filter { !Modifier.isStatic(it.modifiers) }
             .forEach { m -> results.putIfAbsent("${cls.name}.${m.name}", m) }
        }
    }
    results.values.toList()
}

/**
 * FeedSponsoredStoryHolder sponsored pool — boolean add(GraphQLFeedUnitEdge).
 * Hook → always return false to block entry.
 */
val sponsoredPoolAddFingerprint = findMethodDirect {
    findClass {
        matcher {
            usingEqStrings(
                "SponsoredPoolContainerAdapter",
                "Edge type mismatch; not added",
                "Sponsored Pool"
            )
        }
    }.first { candidate ->
        candidate.findMethod {
            matcher {
                returnType = "boolean"
                paramTypes = listOf("com.facebook.graphql.model.GraphQLFeedUnitEdge")
            }
        }.isNotEmpty()
    }.findMethod {
        matcher {
            returnType = "boolean"
            paramTypes = listOf("com.facebook.graphql.model.GraphQLFeedUnitEdge")
        }
    }.single()
}

/**
 * FeedSponsoredStoryHolder: GraphQLFeedUnitEdge next() — always returns null.
 */
val sponsoredStoryNextFingerprint = findMethodDirect {
    findClass {
        matcher { usingEqStrings("FeedSponsoredStoryHolder.onPositionReset", "freshFeedStoryHolder") }
    }.first { candidate ->
        candidate.findMethod {
            matcher {
                returnType = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
                paramCount = 0
            }
        }.isNotEmpty()
    }.findMethod {
        matcher {
            returnType = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
            paramCount = 0
        }
    }.single()
}

/**
 * Sponsored pool class — used to hook all List-returning no-arg methods
 * and result-carrier methods on the pool.
 */
val sponsoredPoolClassFingerprint = findClassDirect {
    findClass {
        matcher {
            usingEqStrings(
                "SponsoredPoolContainerAdapter",
                "Edge type mismatch; not added",
                "Sponsored Pool"
            )
        }
    }.first { candidate ->
        candidate.findMethod {
            matcher {
                returnType = "boolean"
                paramTypes = listOf("com.facebook.graphql.model.GraphQLFeedUnitEdge")
            }
        }.isNotEmpty()
    }
}

// ─── Story ads data source ────────────────────────────────────────────────────

/**
 * AdPaginatingBucketStaticInsertionDataSource / StoryViewerAdsPaginatingDataManager:
 *   ImmutableList merge(FbUserSession, ?, ImmutableList)
 * Returns the original ImmutableList (arg 2) to suppress ad insertion.
 */
val storyAdsMergeFingerprint = findMethodDirect {
    findClass {
        matcher {
            usingEqStrings(
                "AdPaginatingBucketStaticInsertionDataSource.fetchMoreAds",
                "StoryViewerAdsPaginatingDataManager.fetchMoreAds"
            )
            methods {
                matchType = MatchType.Contains
                add {
                    returnType = "com.google.common.collect.ImmutableList"
                    paramTypes = listOf(
                        "com.facebook.auth.usersession.FbUserSession",
                        null,
                        "com.google.common.collect.ImmutableList"
                    )
                }
            }
        }
    }.first().findMethod {
        matcher {
            returnType = "com.google.common.collect.ImmutableList"
            paramTypes = listOf(
                "com.facebook.auth.usersession.FbUserSession",
                null,
                "com.google.common.collect.ImmutableList"
            )
        }
    }.single()
}

/**
 * Story ads data source: void fetchMoreAds(ImmutableList, int) — no-op.
 */
val storyAdsFetchFingerprint = findMethodDirect {
    findClass {
        matcher {
            usingEqStrings(
                "AdPaginatingBucketStaticInsertionDataSource.fetchMoreAds",
                "StoryViewerAdsPaginatingDataManager.fetchMoreAds"
            )
        }
    }.first().findMethod {
        matcher {
            returnType = "void"
            paramTypes = listOf("com.google.common.collect.ImmutableList", "int")
        }
    }.single()
}

/**
 * Story ads data source: void deferredUpdate(?, ImmutableList) — no-op.
 */
val storyAdsDeferredFingerprint = findMethodDirect {
    findClass {
        matcher {
            usingEqStrings(
                "AdPaginatingBucketStaticInsertionDataSource.fetchMoreAds",
                "StoryViewerAdsPaginatingDataManager.fetchMoreAds"
            )
        }
    }.first().findMethod {
        matcher {
            returnType = "void"
            paramTypes = listOf(null, "com.google.common.collect.ImmutableList")
        }
    }.single()
}

// ─── Story ads in-disc source ─────────────────────────────────────────────────

/**
 * FbStoryAdInDiscStoreImpl: ImmutableList merge(...) — returns original ImmutableList.
 */
val storyAdsInDiscMergeFingerprint = findMethodDirect {
    findClass {
        matcher {
            usingEqStrings("FbStoryAdInDiscStoreImpl", "ads_insertion", "ads_deletion")
            methods {
                matchType = MatchType.Contains
                add {
                    returnType = "com.google.common.collect.ImmutableList"
                    paramTypes = listOf(
                        "com.facebook.auth.usersession.FbUserSession",
                        null,
                        "com.google.common.collect.ImmutableList"
                    )
                }
            }
        }
    }.first().findMethod {
        matcher {
            returnType = "com.google.common.collect.ImmutableList"
            paramTypes = listOf(
                "com.facebook.auth.usersession.FbUserSession",
                null,
                "com.google.common.collect.ImmutableList"
            )
        }
    }.single()
}

/**
 * FbStoryAdInDiscStoreImpl: void fetchMoreAds(ImmutableList, int) — no-op.
 */
val storyAdsInDiscFetchFingerprint = findMethodDirect {
    findClass {
        matcher { usingEqStrings("FbStoryAdInDiscStoreImpl", "ads_insertion", "ads_deletion") }
    }.first().findMethod {
        matcher {
            returnType = "void"
            paramTypes = listOf("com.google.common.collect.ImmutableList", "int")
        }
    }.single()
}

/**
 * FbStoryAdInDiscStoreImpl: void deferredUpdate(?, ImmutableList) — no-op.
 */
val storyAdsInDiscDeferredFingerprint = findMethodDirect {
    findClass {
        matcher { usingEqStrings("FbStoryAdInDiscStoreImpl", "ads_insertion", "ads_deletion") }
    }.first().findMethod {
        matcher {
            returnType = "void"
            paramTypes = listOf(null, "com.google.common.collect.ImmutableList")
        }
    }.single()
}

/**
 * FbStoryAdInDiscStoreImpl: void insertionTrigger() — no-op.
 */
val storyAdsInDiscInsertionFingerprint = findMethodDirect {
    findClass {
        matcher { usingEqStrings("FbStoryAdInDiscStoreImpl", "ads_insertion", "ads_deletion") }
    }.first().findMethod {
        matcher {
            returnType = "void"
            paramCount = 0
            usingStrings("ads_insertion")
        }
    }.single()
}

// ─── Game ads ─────────────────────────────────────────────────────────────────

/**
 * All game-ad bridge handler methods:
 *   void onGetInterstitialAdAsync(JSONObject)
 *   void onGetRewardedVideoAsync(JSONObject)
 *   … etc.
 */
val gameAdRequestFingerprints = findMethodListDirect {
    val results = LinkedHashMap<String, org.luckypray.dexkit.result.MethodData>()
    GAME_AD_METHOD_TAGS.forEach { tag ->
        findMethod {
            matcher {
                returnType = "void"
                paramTypes = listOf("org.json.JSONObject")
                usingStrings(tag)
            }
        }.filter { m -> m.name !in setOf("<init>", "<clinit>") }
         .forEach { m -> results.putIfAbsent("${m.declaredClassName}.${m.name}", m) }
    }
    results.values.toList()
}

package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.morphe.findMethodDirect
import io.github.nexalloy.morphe.findMethodListDirect
import io.github.nexalloy.morphe.findClassDirect
import io.github.nexalloy.morphe.strings
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

// ─── Ad-kind enum ─────────────────────────────────────────────────────────────

/**
 * Locates the Facebook internal enum that carries the AD / UGC / PARADE / MIDCARD constants.
 * Used by AdStoryInspector to detect ad-backed story objects.
 */
val adKindEnumFingerprint = findClassDirect {
    findClass {
        matcher {
            usingEqStrings("AD", "UGC", "PARADE", "MIDCARD")
        }
    }.first { candidate ->
        val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: return@first false
        val constants = clazz.enumConstants?.map { it.toString() }.orEmpty()
        clazz.isEnum && "AD" in constants && "UGC" in constants
    }
}

// ─── Reels list-builder ───────────────────────────────────────────────────────

/**
 * Finds the upstream Reels list-builder class by structural signature.
 * The "append" static method (6-param, returns void) is the main hook target.
 */
val listBuilderClassFingerprint = findClassDirect {
    findClass {
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
                add {
                    returnType = "java.util.List"
                    paramTypes = listOf(null, null, null, "boolean")
                }
            }
        }
    }.single()
}

/**
 * List-builder "append" static method: (?, ListBuilder, ?, ?, ?, List) -> void
 */
val listBuilderAppendFingerprint = findMethodDirect {
    val cls = listBuilderClassFingerprint.run()!!
    cls.findMethod {
        findFirst = true
        matcher {
            modifiers = Modifier.STATIC
            returnType = "void"
            // 6th param is the mutable List being built
            paramTypes = listOf(null, cls.name, null, null, null, "java.util.List")
        }
    }.single()
}

/**
 * List-builder factory method: (ListBuilder, ?, ?, ?, boolean) -> ArrayList
 */
val listBuilderFactoryFingerprint = findMethodDirect {
    val cls = listBuilderClassFingerprint.run()!!
    cls.findMethod {
        findFirst = true
        matcher {
            modifiers = Modifier.STATIC
            returnType = "java.util.ArrayList"
            paramTypes = listOf(cls.name, null, null, null, "boolean")
        }
    }.single()
}

// ─── Plugin pack ──────────────────────────────────────────────────────────────

/**
 * FbShortsViewerPluginPack – provides the list of Shorts viewer plugins for a story.
 * Hooked to suppress the ad plugin when the backing story is an ad.
 */
val pluginPackClassFingerprint = findClassDirect {
    findClass {
        findFirst = true
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
    }.first()
}

val pluginPackMethodFingerprint = findMethodDirect {
    val cls = pluginPackClassFingerprint.run()!!
    cls.findMethod {
        findFirst = true
        matcher { returnType = "java.util.List"; paramCount = 0 }
    }.single()
}

// ─── Instream banner state ────────────────────────────────────────────────────

/**
 * Zero-arg boolean method inside the class that logs "InstreamAdIdleWithBannerState".
 * Returning false disables banner eligibility checks.
 */
val instreamBannerEligibilityFingerprint = findMethodDirect {
    findClass {
        matcher { usingStrings("InstreamAdIdleWithBannerState") }
    }.flatMap { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher { returnType = "boolean"; paramCount = 0 }
        }
    }.first()
}

// ─── Indicator pill ───────────────────────────────────────────────────────────

/**
 * Static 3-param boolean method that decides whether the floating-CTA pill is shown.
 */
val indicatorPillAdEligibilityFingerprint = findMethodDirect {
    findClass {
        matcher {
            usingStrings(
                "IndicatorPillComponent.render",
                "com.facebook.feedback.comments.plugins.indicatorpill.reelsadsfloatingcta.ReelsAdsFloatingCtaPlugin"
            )
        }
    }.flatMap { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher { modifiers = Modifier.STATIC; returnType = "boolean"; paramCount = 3 }
        }
    }.first()
}

// ─── Reels banner render ──────────────────────────────────────────────────────

/**
 * Returns all Litho render methods found inside ReelsBannerAds(Native)Component classes.
 */
val reelsBannerRenderMethodsFingerprint = findMethodListDirect {
    listOf("ReelsBannerAdsComponent", "ReelsBannerAdsNativeComponent").flatMap { name ->
        findClass { matcher { usingStrings(name) } }
            .mapNotNull { candidate ->
                runCatching { candidate.getInstance(classLoader) }.getOrNull()
            }
            .flatMap { clz ->
                clz.declaredMethods.filter { m ->
                    !Modifier.isStatic(m.modifiers) && !m.isBridge && !m.isSynthetic &&
                    m.parameterCount == 1 && !m.returnType.isPrimitive &&
                    m.returnType != Void.TYPE && m.returnType != Any::class.java &&
                    m.returnType.isAssignableFrom(clz)
                }
            }
            .mapNotNull { m ->
                // convert back to MethodData via descriptor for caching
                runCatching {
                    findMethod {
                        matcher {
                            declaredClass = m.declaringClass.name
                            name = m.name
                        }
                    }.firstOrNull()
                }.getOrNull()
            }
    }
}

// ─── Feed CSR filters ─────────────────────────────────────────────────────────

/**
 * All concrete FeedCSRCacheFilter* classes – the main feed ad injection pathway.
 */
val feedCsrFilterMethodsFingerprint = findMethodListDirect {
    listOf("FeedCSRCacheFilter", "FeedCSRCacheFilter2025H1", "FeedCSRCacheFilter2026H1").flatMap { tag ->
        findClass { matcher { usingStrings(tag) } }
            .flatMap { candidate ->
                candidate.findMethod {
                    findFirst = true
                    matcher {
                        paramTypes = listOf(
                            "com.facebook.auth.usersession.FbUserSession",
                            "com.google.common.collect.ImmutableList",
                            "int"
                        )
                    }
                }
            }
    }.distinctBy { it.descriptor }
     .filter { md ->
         val m = runCatching { md.getMethodInstance(classLoader) }.getOrNull() ?: return@filter false
         !Modifier.isAbstract(m.modifiers) && !m.declaringClass.isInterface &&
         !Modifier.isAbstract(m.declaringClass.modifiers)
     }
}

// ─── Late feed list sanitizers ────────────────────────────────────────────────

/**
 * Methods that receive the final feed list before it is displayed (three search strategies).
 */
val lateFeedListMethodsFingerprint = findMethodListDirect {
    val results = mutableListOf<org.luckypray.dexkit.result.MethodData>()

    // Strategy 1 – handleStorageStories
    findClass { matcher { usingStrings("handleStorageStories", "Empty Storage List") } }
        .forEach { candidate ->
            candidate.findMethod {
                findFirst = true
                matcher { returnType = "void"; paramTypes = listOf(null, "com.google.common.collect.ImmutableList", "int") }
            }.forEach { results.add(it) }
        }

    // Strategy 2 – cancelVendingTimerAndAddToPool
    findClass { matcher { usingStrings("cancelVendingTimerAndAddToPool_") } }
        .forEach { candidate ->
            candidate.findMethod {
                findFirst = true
                matcher { returnType = "void"; paramTypes = listOf("com.google.common.collect.ImmutableList", "java.lang.String") }
            }.forEach { results.add(it) }
        }

    // Strategy 3 – CSR storage lifecycle variants
    listOf("CSRNoOpStorageLifecycleImpl", "FeedCSRStorageLifecycle", "FriendlyFeedCSRStorageLifecycle", "FbShortsCSRStorageLifecycle")
        .forEach { tag ->
            findClass { matcher { usingStrings(tag) } }
                .forEach { candidate ->
                    candidate.findMethod {
                        findFirst = true
                        matcher { returnType = "void"; paramTypes = listOf("com.facebook.auth.usersession.FbUserSession", null, "com.google.common.collect.ImmutableList") }
                    }.forEach { results.add(it) }
                }
        }

    results.distinctBy { it.descriptor }
           .filter { md ->
               val m = runCatching { md.getMethodInstance(classLoader) }.getOrNull() ?: return@filter false
               !Modifier.isAbstract(m.modifiers) && !m.declaringClass.isInterface &&
               !Modifier.isAbstract(m.declaringClass.modifiers)
           }
}

// ─── Story pool add ───────────────────────────────────────────────────────────

/**
 * boolean add(FeedItem) methods on CSRStoryPoolCoordinator / FeedStoryPoolCoordinator.
 */
val storyPoolAddMethodsFingerprint = findMethodListDirect {
    listOf("CSRStoryPoolCoordinator", "FeedStoryPoolCoordinator").flatMap { tag ->
        findClass { matcher { usingStrings(tag) } }
            .flatMap { candidate ->
                candidate.findMethod {
                    findFirst = true
                    matcher { returnType = "boolean"; paramTypes = listOf(null) }
                }
            }
    }.distinctBy { it.descriptor }
     .filter { md ->
         val m = runCatching { md.getMethodInstance(classLoader) }.getOrNull() ?: return@filter false
         !Modifier.isAbstract(m.modifiers) && !m.declaringClass.isInterface &&
         !Modifier.isAbstract(m.declaringClass.modifiers)
     }
}

// ─── Sponsored pool ───────────────────────────────────────────────────────────

val sponsoredPoolClassFingerprint = findClassDirect {
    findClass {
        matcher { usingEqStrings("SponsoredPoolContainerAdapter", "Edge type mismatch; not added", "Sponsored Pool") }
    }.first { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher { returnType = "boolean"; paramTypes = listOf("com.facebook.graphql.model.GraphQLFeedUnitEdge") }
        }.isNotEmpty()
    }
}

val sponsoredPoolAddMethodFingerprint = findMethodDirect {
    val cls = sponsoredPoolClassFingerprint.run()!!
    cls.findMethod {
        findFirst = true
        matcher { returnType = "boolean"; paramTypes = listOf("com.facebook.graphql.model.GraphQLFeedUnitEdge") }
    }.single()
}

// ─── Sponsored story manager ──────────────────────────────────────────────────

val sponsoredStoryManagerClassFingerprint = findClassDirect {
    findClass {
        matcher { usingEqStrings("FeedSponsoredStoryHolder.onPositionReset", "freshFeedStoryHolder") }
    }.first { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher { returnType = "com.facebook.graphql.model.GraphQLFeedUnitEdge"; paramCount = 0 }
        }.isNotEmpty()
    }
}

val sponsoredStoryNextMethodFingerprint = findMethodDirect {
    val cls = sponsoredStoryManagerClassFingerprint.run()!!
    cls.findMethod {
        findFirst = true
        matcher { returnType = "com.facebook.graphql.model.GraphQLFeedUnitEdge"; paramCount = 0 }
    }.single()
}

// ─── Story ads data sources ───────────────────────────────────────────────────

val storyAdsDataSourceClassFingerprint = findClassDirect {
    findClass {
        matcher { usingEqStrings("AdPaginatingBucketStaticInsertionDataSource.fetchMoreAds", "StoryViewerAdsPaginatingDataManager.fetchMoreAds") }
    }.first { c ->
        c.findMethod { findFirst = true; matcher { returnType = "com.google.common.collect.ImmutableList"; paramTypes = listOf("com.facebook.auth.usersession.FbUserSession", null, "com.google.common.collect.ImmutableList") } }.isNotEmpty() &&
        c.findMethod { findFirst = true; matcher { returnType = "void"; paramTypes = listOf(null, "com.google.common.collect.ImmutableList") } }.isNotEmpty()
    }
}

val storyAdsInDiscClassFingerprint = findClassDirect {
    findClass {
        matcher { usingEqStrings("FbStoryAdInDiscStoreImpl", "ads_insertion", "ads_deletion") }
    }.first { c ->
        c.findMethod { findFirst = true; matcher { returnType = "com.google.common.collect.ImmutableList"; paramTypes = listOf("com.facebook.auth.usersession.FbUserSession", null, "com.google.common.collect.ImmutableList") } }.isNotEmpty() &&
        c.findMethod { findFirst = true; matcher { returnType = "void"; paramTypes = listOf(null, "com.google.common.collect.ImmutableList") } }.isNotEmpty()
    }
}

// ─── Game ad request methods ──────────────────────────────────────────────────

/**
 * The bridge handler methods that process incoming game-ad JSON payloads.
 */
val gameAdRequestMethodsFingerprint = findMethodListDirect {
    listOf(
        "Invalid JSON content received by onGetInterstitialAdAsync: ",
        "Invalid JSON content received by onGetRewardedInterstitialAsync: ",
        "Invalid JSON content received by onRewardedVideoAsync: ",
        "Invalid JSON content received by onLoadAdAsync: ",
        "Invalid JSON content received by onShowAdAsync: "
    ).flatMap { tag ->
        findMethod {
            matcher {
                returnType = "void"
                paramTypes = listOf("org.json.JSONObject")
                strings(tag)
            }
        }
    }.distinctBy { it.descriptor }
     .filter { md ->
         val m = runCatching { md.getMethodInstance(classLoader) }.getOrNull() ?: return@filter false
         !Modifier.isStatic(m.modifiers) && m.name != "<init>" && m.name != "<clinit>"
     }
}

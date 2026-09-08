package io.github.nexalloy.revanced.facebook.ad

import io.github.nexalloy.patch
import io.github.nexalloy.revanced.facebook.hookAdComponentRender
import io.github.nexalloy.revanced.facebook.hookAdQueryFetch

/**
 * Suppresses ad-only Litho components across every surface found in the app: Shorts and
 * Reels ad chrome, in-stream ad break cards, the Stories viewer, search results, Watch
 * immersive, multi-ads carousels, horizontal-scroll ad rails and the ads-in-comments
 * section.
 *
 * Kept as its own toggle rather than folded into [HideFacebookAds] for one reason: it is
 * by far the broadest rule in the module, roughly a hundred components, and its failure
 * mode is silent. A wrongly included component does not crash — the surface simply stops
 * drawing, which is easy to miss for weeks and hard to trace back afterwards. One switch
 * reverts all of it while the feed, plugin and descriptor layers keep working.
 *
 * The tag list is curated, not the raw output of a string scan. Three categories were
 * deliberately left out:
 *
 *  - **Playback controls.** Skip buttons, the ad break player, its view coordinator and
 *    control components, the post-hide countdown and the play indicator. Suppressing
 *    these removes the means of escaping an ad, or stalls the player waiting for a break
 *    that never finishes drawing. On this build three of them share one class, so
 *    hooking any single one would take out playback control for all three.
 *  - **False positives from substring matching.** "ads" hides inside Threads and Heads,
 *    and "interstitial" in this app also means login and zero-rating interstitials.
 *    Suppressing those breaks messaging and sign-in, not advertising.
 *  - **The Ad Activity screen.** A settings surface for reviewing ads you have seen, not
 *    an advertisement.
 */
val HideFacebookAdComponents = patch(
    name = "Hide ad-only components",
    description = "Removes Litho components that exist purely to draw ads, across Reels, Shorts, Stories, Watch, search and in-stream video. Turn off if any video or feed surface stops rendering.",
) {
    runCatching { ::adSurfaceRenderMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookAdComponentRender(dm.toMethod()) } }

    runCatching { ::adSectionRenderMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookAdComponentRender(dm.toMethod()) } }

    // Stories ads. Resolved structurally, by the AdStory field a component carries,
    // rather than from a list of component names.
    runCatching { ::storyAdComponentRenderMethodsFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookAdComponentRender(dm.toMethod()) } }

    // Stop requesting story ads in the first place, so none of the UI above is ever built.
    // Search "AI mode" ads: block the query rather than the UI, same as the Stories and
    // Profile Reels ad queries. Returning null reads as "nothing came back".
    runCatching { ::searchAiModeAdsQueryFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookAdQueryFetch(dm.toMethod()) } }

    runCatching { ::storiesAdsPaginationMethodFingerprint.dexMethodList }.getOrNull().orEmpty()
        .forEach { dm -> runCatching { hookAdQueryFetch(dm.toMethod()) } }
}

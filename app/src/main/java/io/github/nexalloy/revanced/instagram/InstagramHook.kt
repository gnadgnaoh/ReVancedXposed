package io.github.nexalloy.revanced.instagram

import io.github.nexalloy.revanced.instagram.ads.HideAds
import io.github.nexalloy.revanced.instagram.network.BlockNetwork
import io.github.nexalloy.revanced.instagram.tracking.SanitizeTrackingLinks
import io.github.nexalloy.revanced.instagram.ghost.GhostInterceptor
import io.github.nexalloy.revanced.instagram.ghost.GhostScreenshot
import io.github.nexalloy.revanced.instagram.ghost.GhostSeenState
import io.github.nexalloy.revanced.instagram.ghost.GhostTypingStatus
import io.github.nexalloy.revanced.instagram.ghost.GhostViewOnce
import io.github.nexalloy.revanced.instagram.ghost.GhostViewStory
import io.github.nexalloy.revanced.instagram.ghost.GhostEphemeralKeep
import io.github.nexalloy.revanced.instagram.ghost.GhostPermanentView
import io.github.nexalloy.revanced.instagram.ghost.GhostReplayLimit
import io.github.nexalloy.revanced.instagram.ghost.ScreenshotPermission

val InstagramPatches = arrayOf(
    HideAds,
    SanitizeTrackingLinks,
    BlockNetwork,
    GhostInterceptor,
    GhostScreenshot,
    GhostSeenState,
    GhostTypingStatus,
    GhostViewOnce,
    GhostViewStory,
    GhostEphemeralKeep,
    GhostPermanentView,
    GhostReplayLimit,
    ScreenshotPermission,
)

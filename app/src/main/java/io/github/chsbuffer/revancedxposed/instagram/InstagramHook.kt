package io.github.chsbuffer.revancedxposed.instagram

import io.github.chsbuffer.revancedxposed.instagram.ads.HideAds
import io.github.chsbuffer.revancedxposed.instagram.network.BlockNetwork
import io.github.chsbuffer.revancedxposed.instagram.tracking.SanitizeTrackingLinks
import io.github.chsbuffer.revancedxposed.instagram.ghost.GhostInterceptor
import io.github.chsbuffer.revancedxposed.instagram.ghost.GhostScreenshot
import io.github.chsbuffer.revancedxposed.instagram.ghost.GhostSeenState
import io.github.chsbuffer.revancedxposed.instagram.ghost.GhostTypingStatus
import io.github.chsbuffer.revancedxposed.instagram.ghost.GhostViewOnce
import io.github.chsbuffer.revancedxposed.instagram.ghost.GhostViewStory

val InstagramPatches = arrayOf(HideAds, SanitizeTrackingLinks, BlockNetwork, GhostInterceptor, GhostScreenshot, GhostSeenState, GhostTypingStatus, GhostViewOnce, GhostViewStory)

package io.github.nexalloy.revanced.facebook

import io.github.nexalloy.revanced.facebook.ad.BlockFacebookAdRequests
import io.github.nexalloy.revanced.facebook.ad.HideFacebookAdComponents
import io.github.nexalloy.revanced.facebook.ad.HideFacebookAds
import io.github.nexalloy.revanced.facebook.ad.HideProfileTimelineAds

val FacebookPatches = arrayOf(
    HideFacebookAds,
    HideFacebookAdComponents,
    HideProfileTimelineAds,
    BlockFacebookAdRequests,
)

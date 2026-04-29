package io.github.nexalloy

import io.github.nexalloy.morphe.google.GoogleDiscoverPatches
import io.github.nexalloy.morphe.music.YTMusicPatches
import io.github.nexalloy.morphe.reddit.RedditPatches
import io.github.nexalloy.morphe.youtube.YouTubePatches
import io.github.nexalloy.revanced.googlephotos.GooglePhotosPatches
import io.github.nexalloy.revanced.instagram.InstagramPatches
import io.github.nexalloy.revanced.threads.ThreadsPatches
import io.github.nexalloy.revanced.facebook.FacebookPatches
import io.github.nexalloy.revanced.photomath.PhotomathPatches
import io.github.nexalloy.revanced.strava.StravaPatches

class AppPatchInfo(val appName: String, val packageName: String, val patches: Array<Patch>)

val appPatchConfigurations = listOf(
    AppPatchInfo("YouTube", "com.google.android.youtube", YouTubePatches),
    AppPatchInfo("YT Music", "com.google.android.apps.youtube.music", YTMusicPatches),
    AppPatchInfo("Reddit", "com.reddit.frontpage", RedditPatches),
    AppPatchInfo("Google Photos", "com.google.android.apps.photos", GooglePhotosPatches),
    AppPatchInfo("Photomath", "com.microblink.photomath", PhotomathPatches),
    AppPatchInfo("Instagram", "com.instagram.android", InstagramPatches),
    AppPatchInfo("Threads", "com.instagram.barcelona", ThreadsPatches),
    AppPatchInfo("Strava", "com.strava", StravaPatches),
    AppPatchInfo("Facebook", "com.facebook.katana", FacebookPatches),
    AppPatchInfo("Google (Discover)", "com.google.android.googlequicksearchbox", GoogleDiscoverPatches),
)

val patchesByPackage = appPatchConfigurations.associate { it.packageName to it.patches }

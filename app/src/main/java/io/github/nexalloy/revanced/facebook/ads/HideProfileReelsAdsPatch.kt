package io.github.nexalloy.revanced.facebook.ads

import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import java.lang.reflect.Modifier

val HideProfileReelsAds = patch(
    name = "Hide profile Reels ads",
    description = "Blocks sponsored Reels and RTI ads shown when viewing Reels on a user's profile page.",
) {
    // 1. Chặn Query kích hoạt tải Ads
    val queryTriggerMethods = ::profileReelsAdsQueryTriggerFingerprints.dexMethodList
        .mapNotNull { runCatching { it.toMethod() }.getOrNull() }
        .filter { !Modifier.isAbstract(it.modifiers) && !it.declaringClass.isInterface }
        .distinctBy { "${it.declaringClass.name}.${it.name}" }

    queryTriggerMethods.forEach { method ->
        XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
    }

    // 2. Chặn Insert Ads (maybeInsertAds)
    val maybeInsertMethods = ::vhdcMaybeInsertAdsFingerprints.dexMethodList
        .mapNotNull { runCatching { it.toMethod() }.getOrNull() }
        .filter { !Modifier.isAbstract(it.modifiers) && !it.declaringClass.isInterface }
        .distinctBy { "${it.declaringClass.name}.${it.name}" }

    maybeInsertMethods.forEach { method ->
        XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
    }

    // 3. Chặn Insert Ads qua AdsUtil (LX/508)
    val adsUtilMethods = ::vhdcAdsUtilInsertFingerprints.dexMethodList
        .mapNotNull { runCatching { it.toMethod() }.getOrNull() }
        .filter { !Modifier.isAbstract(it.modifiers) && !it.declaringClass.isInterface }
        .distinctBy { "${it.declaringClass.name}.${it.name}" }

    adsUtilMethods.forEach { method ->
        XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
    }
}

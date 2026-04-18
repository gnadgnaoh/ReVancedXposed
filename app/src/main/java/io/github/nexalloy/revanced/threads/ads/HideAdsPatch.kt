package io.github.nexalloy.revanced.threads.ads

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch

val HideAds = patch(
    name = "Hide ads",
    description = "Hides injected ads and sponsored content in the feed." // Thêm description cho chuẩn form nhé
) {
    ::adInjectorFingerprint.hookMethod(XC_MethodReplacement.DO_NOTHING)

    ::adSponsoredContentFingerprint.hookMethod(XC_MethodReplacement.returnConstant(false))
}

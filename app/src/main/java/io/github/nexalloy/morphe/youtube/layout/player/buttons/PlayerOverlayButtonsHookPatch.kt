package io.github.nexalloy.morphe.youtube.layout.player.buttons

import android.view.View
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch
import io.github.nexalloy.morphe.youtube.misc.playercontrols.fullscreen_button_id
import io.github.nexalloy.patch
import io.github.nexalloy.scopedHook
import org.luckypray.dexkit.wrap.DexMethod

private val initializeButtonList = mutableListOf<(view: View) -> Unit>()

fun addPlayerBottomButton(initializeButton: (view: View) -> Unit) {
    initializeButtonList.add(initializeButton)
}

val playerOverlayButtonsHook = patch {

    ExploderUIFullscreenButtonFingerprint.hookMethod(scopedHook(DexMethod("Landroid/view/View;->findViewById(I)Landroid/view/View;").toMember()) {
        val fullscreenButtonId = fullscreen_button_id
        after {
            if (innerDepth != 0) return@after
            if (it.args[0] == fullscreenButtonId) {
                val view = it.result as? View ?: return@after
                initializeButtonList.forEach { func -> func(view) }
                LegacyPlayerControlsPatch.setFullscreenCloseButton(view)
            }
        }
    })
}

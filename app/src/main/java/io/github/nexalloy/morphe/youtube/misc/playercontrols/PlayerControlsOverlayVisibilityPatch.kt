package io.github.nexalloy.morphe.youtube.misc.playercontrols

import app.morphe.extension.youtube.patches.PlayerControlsVisibilityHookPatch
import io.github.nexalloy.patch

val PlayerControlsOverlayVisibility = patch {
    PlayerControlsVisibilityEntityModelInit.hookMethod {
        val getPlayerControlsVisibilityMethod =
            PlayerControlsVisibilityEntityModelFingerprint.method
        after {
            PlayerControlsVisibilityHookPatch.setPlayerControlsVisibility(
                getPlayerControlsVisibilityMethod(it.thisObject) as Enum<*>?
            )
        }
    }
}
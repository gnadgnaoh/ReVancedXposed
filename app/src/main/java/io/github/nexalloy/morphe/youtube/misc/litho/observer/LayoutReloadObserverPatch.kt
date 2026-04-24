package io.github.nexalloy.morphe.youtube.misc.litho.observer

import app.morphe.extension.youtube.patches.LayoutReloadObserverPatch
import io.github.nexalloy.morphe.youtube.misc.litho.node.TreeNodeElementHook
import io.github.nexalloy.morphe.youtube.misc.litho.node.hookTreeNodeResult
import io.github.nexalloy.patch


val LayoutReloadObserver = patch(
    description = "Hooks a method to detect in the extension when the RecyclerView at the bottom of the player is redrawn.",
) {
    dependsOn(
        TreeNodeElementHook
    )

    hookTreeNodeResult(LayoutReloadObserverPatch::onLazilyConvertedElementLoaded)
}
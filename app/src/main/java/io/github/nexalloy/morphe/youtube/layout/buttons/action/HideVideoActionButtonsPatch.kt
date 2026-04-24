package io.github.nexalloy.morphe.youtube.layout.buttons.action

import app.morphe.extension.youtube.innertube.NextResponseOuterClass
import app.morphe.extension.youtube.patches.components.VideoActionButtonsFilter
import io.github.nexalloy.callMethod
import io.github.nexalloy.morphe.shared.misc.settings.preference.PreferenceScreenPreference
import io.github.nexalloy.morphe.shared.misc.settings.preference.SwitchPreference
import io.github.nexalloy.morphe.youtube.misc.litho.filter.LithoFilter
import io.github.nexalloy.morphe.youtube.misc.litho.filter.addLithoFilter
import io.github.nexalloy.morphe.youtube.misc.litho.node.TreeNodeElementHook
import io.github.nexalloy.morphe.youtube.misc.litho.node.hookTreeNodeResult
import io.github.nexalloy.morphe.youtube.misc.settings.PreferenceScreen
import io.github.nexalloy.morphe.youtube.shared.WatchNextResponseParserFingerprint
import io.github.nexalloy.morphe.youtube.video.information.VideoInformationPatch
import io.github.nexalloy.patch

val HideVideoActionButtons = patch(
    name = "Hide video action buttons",
    description = "Adds options to hide action buttons (such as the Download button) under videos.",
) {
    dependsOn(
        LithoFilter,
        TreeNodeElementHook,
        VideoInformationPatch
    )

    PreferenceScreen.PLAYER.addPreferences(
        PreferenceScreenPreference(
            "morphe_hide_buttons_screen",
            preferences = setOf(
                SwitchPreference("morphe_disable_like_subscribe_glow"),
                SwitchPreference("morphe_hide_action_bar"),
                SwitchPreference("morphe_hide_ask_button"),
                SwitchPreference("morphe_hide_clip_button"),
                SwitchPreference("morphe_hide_comments_button"),
                SwitchPreference("morphe_hide_download_button"),
                SwitchPreference("morphe_hide_hype_button"),
                SwitchPreference("morphe_hide_like_dislike_button"),
                SwitchPreference("morphe_hide_promote_button"),
                SwitchPreference("morphe_hide_remix_button"),
                SwitchPreference("morphe_hide_report_button"),
                SwitchPreference("morphe_hide_save_button"),
                SwitchPreference("morphe_hide_share_button"),
                SwitchPreference("morphe_hide_shop_button"),
                SwitchPreference("morphe_hide_stop_ads_button"),
                SwitchPreference("morphe_hide_thanks_button"),
            )
        )
    )

    addLithoFilter(VideoActionButtonsFilter())

    hookTreeNodeResult { identifier, list ->
        VideoActionButtonsFilter.onLazilyConvertedElementLoaded(identifier, list)
    }

    WatchNextResponseParserFingerprint.hookMethod {
        before {
            val messageLite = it.args[0]

            val nextResponse =
                NextResponseOuterClass.NextResponse.parseFrom(messageLite.callMethod("toByteArray") as ByteArray)

            if (!nextResponse.hasPrimaryContents()) {
                return@before
            }

            val primaryContents = nextResponse.primaryContents
            if (primaryContents.hasSingleColumnWatchNextResults()) {
                VideoActionButtonsFilter.onSingleColumnWatchNextResultsLoaded(primaryContents.singleColumnWatchNextResults)
            }
        }
    }
}

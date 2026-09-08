package io.github.nexalloy.hoodles.morphe.protonvpn.delay

import io.github.nexalloy.patch

val RemoveChangeServerDelay = patch(
    name = "Remove delay",
    description = "Removes the imposed delay when changing VPN servers.",
) {
    GetLongDelayFingerprint.hookMethod {
        before { param ->
            param.result = 0
        }
    }

    GetLongDelayLegacyFingerprint.hookMethod {
        before { param ->
            param.result = 0
        }
    }

    GetShortDelayFingerprint.hookMethod {
        before { param ->
            param.result = 0
        }
    }

    GetShortDelayLegacyFingerprint.hookMethod {
        before { param ->
            param.result = 0
        }
    }
}

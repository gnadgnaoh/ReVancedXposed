package io.github.nexalloy.hoodles.morphe.protonvpn

import io.github.nexalloy.hoodles.morphe.protonvpn.delay.RemoveChangeServerDelay
import io.github.nexalloy.hoodles.morphe.protonvpn.telemetry.DisableTelemetry

val ProtonVpnPatches = arrayOf(
    RemoveChangeServerDelay,
    DisableTelemetry,
)

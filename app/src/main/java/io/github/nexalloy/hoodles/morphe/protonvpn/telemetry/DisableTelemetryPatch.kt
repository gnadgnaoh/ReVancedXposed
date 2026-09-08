package io.github.nexalloy.hoodles.morphe.protonvpn.telemetry

import io.github.nexalloy.patch

val DisableTelemetry = patch(
    name = "Disable telemetry",
    description = "Blocks all telemetry, analytics, and observability data collection.",
) {
    TelemetryWorkerEnqueueFingerprint.hookMethod {
        before { param ->
            param.result = Unit
        }
    }

    SendObservabilityFingerprint.hookMethod {
        before { param ->
            param.result = kotlin.Unit
        }
    }

    VpnTelemetryAddEventFingerprint.hookMethod {
        before { param ->
            param.result = kotlin.Unit
        }
    }
}

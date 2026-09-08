package io.github.nexalloy.hoodles.morphe.protonvpn.delay

import io.github.nexalloy.morphe.Fingerprint

object GetLongDelayFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/appconfig/AppConfigResponse;",
    name = "getChangeServerLongDelayInSeconds",
    returnType = "I",
)

object GetLongDelayLegacyFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/appconfig/AppConfigResponseLegacyStorage;",
    name = "getChangeServerLongDelayInSeconds",
    returnType = "I",
)

object GetShortDelayFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/appconfig/AppConfigResponse;",
    name = "getChangeServerShortDelayInSeconds",
    returnType = "I",
)

object GetShortDelayLegacyFingerprint : Fingerprint(
    definingClass = "Lcom/protonvpn/android/appconfig/AppConfigResponseLegacyStorage;",
    name = "getChangeServerShortDelayInSeconds",
    returnType = "I",
)

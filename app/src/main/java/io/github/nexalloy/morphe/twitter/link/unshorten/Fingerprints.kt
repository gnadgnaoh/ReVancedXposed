package io.github.nexalloy.morphe.twitter.link.unshorten

import io.github.nexalloy.morphe.Fingerprint

internal object UrlEntityToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("UrlEntity(displayUrl="),
)

internal object UrlEntityConstructorFingerprint : Fingerprint(
    classFingerprint = UrlEntityToStringFingerprint,
    name = "<init>",
    custom = { paramCount = 5 },
)

internal object UrlEntitySerialConstructorFingerprint : Fingerprint(
    classFingerprint = UrlEntityToStringFingerprint,
    name = "<init>",
    custom = { paramCount = 6 },
)

internal object OpenExternalUrlFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf(
        "ExternalScreenNav",
        "Unable to start Intent",
        "No activity found for Intent",
    ),
)

internal object LinkWithPostDetailArgsToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("LinkWithPostDetailArgs(url="),
)

internal object WebViewArgsToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("WebViewArgs(url="),
)

package io.github.nexalloy.revanced.threads.network

import io.github.nexalloy.morphe.fingerprint

val networkInterceptorFingerprint = fingerprint {
    definingClass("Lcom/instagram/api/tigon/TigonServiceLayer;")
    name("startRequest")
}

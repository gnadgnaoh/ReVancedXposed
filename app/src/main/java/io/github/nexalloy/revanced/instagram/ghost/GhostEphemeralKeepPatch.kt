package io.github.nexalloy.revanced.instagram.ghost

import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.patch
import java.lang.reflect.Field

/**
 * Prevents disappearing/vanish-mode messages from being deleted locally.
 *
 * Three sub-hooks:
 *  1. vanishLocalDelete  — no-ops the method that drives local deletion of
 *                          vanish/ephemeral messages (via "igThreadIgid" fingerprint).
 *  2. serverPing         — blocks the outgoing mark_ephemeral_item_ranges_viewed
 *                          server call (belt-and-suspenders with GhostInterceptor).
 *  3. expiryParser       — zeroes message_expiration_timestamp_ms long fields after
 *                          model parsing so no local countdown timer is started.
 */
val GhostEphemeralKeep = patch(
    name = "Ghost ephemeral keep",
    description = "Prevents vanish/ephemeral (disappearing) messages from being deleted locally.",
) {
    // Hook 1: block local deletion of vanish-mode messages
    ::ephemeralVanishLocalDeleteFingerprint.hookMethod {
        before { param ->
            Logger.printDebug { "Ghost: ephemeral vanish-local-delete blocked" }
            param.result = null
        }
    }

    // Hook 2: block server ping that marks ephemeral ranges as viewed
    ::ephemeralServerPingFingerprint.hookMethod {
        before { param ->
            Logger.printDebug { "Ghost: ephemeral server-ping blocked" }
            param.result = null
        }
    }

    // Hook 3: zero out expiry timestamps on parsed model objects
    ::ephemeralExpiryParserFingerprintList.dexMethodList.forEach { dexMethod ->
        dexMethod.hookMethod {
            after { param ->
                clearExpiryTimestamp(param.thisObject)
                val result = param.result
                if (result != null && result !== param.thisObject) clearExpiryTimestamp(result)
            }
        }
    }
}

/**
 * Zeroes any long field on [obj] whose value looks like a future epoch-ms timestamp,
 * so the local expiry countdown never starts.
 * Future epoch: must be greater than now and less than year 2100 (~4 102 444 800 000 ms).
 */
private fun clearExpiryTimestamp(obj: Any?) {
    obj ?: return
    val now = System.currentTimeMillis()
    val year2100 = 4_102_444_800_000L
    try {
        for (f in obj.javaClass.declaredFields) {
            if (f.type != Long::class.javaPrimitiveType) continue
            f.isAccessible = true
            val value = f.getLong(obj)
            if (value in (now + 1) until year2100) {
                f.setLong(obj, 0L)
                Logger.printDebug { "Ghost: zeroed expiry field ${f.name} = $value" }
            }
        }
    } catch (_: Throwable) {}
}

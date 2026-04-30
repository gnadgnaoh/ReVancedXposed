package io.github.nexalloy.revanced.instagram.ghost

import app.morphe.extension.shared.Logger
import io.github.nexalloy.patch

/**
 * Removes the replay limit for replayable (view-twice) DM media.
 *
 * Three sub-hooks:
 *  1. replayUpdate      — no-ops the method that marks the visual message as seen in the
 *                         local thread model (keeps local "seen" state at 0).
 *  2. replayParseJson   — after parsing "seen_count" / "tap_models", zeroes small int fields
 *                         on the result object (replay counters, not IDs/timestamps which are longs).
 *  3. replaySync        — no-ops the synchronized method that commits the seen/replay count
 *                         to local store.
 */
val GhostReplayLimit = patch(
    name = "Ghost replay limit",
    description = "Allows unlimited replays of replayable (view-twice) DM media.",
) {
    // Hook 1: block local thread-entry update that marks visual message as seen
    ::replayUpdateFingerprint.hookMethod {
        before { param ->
            Logger.printDebug { "Ghost: replay-update blocked" }
            param.result = null
        }
    }

    // Hook 2: zero replay-counter int fields after parseFromJson runs
    ::replayParseFromJsonFingerprintList.dexMethodList.forEach { dexMethod ->
        dexMethod.hookMethod {
            after { param ->
                zeroReplayCountFields(param.thisObject)
                val result = param.result
                if (result != null && result !== param.thisObject) zeroReplayCountFields(result)
            }
        }
    }

    // Hook 3: block synchronized local-store commit
    ::replaySyncFingerprint.hookMethod {
        before { param ->
            Logger.printDebug { "Ghost: replay-sync blocked" }
            param.result = null
        }
    }
}

/**
 * Zeroes int fields whose value is in [1, 10] on [obj].
 * Replay/seen counts are always tiny (1 or 2); IDs and timestamps are longs and won't match.
 */
private fun zeroReplayCountFields(obj: Any?) {
    obj ?: return
    try {
        for (f in obj.javaClass.declaredFields) {
            if (f.type != Int::class.javaPrimitiveType) continue
            f.isAccessible = true
            val value = f.getInt(obj)
            if (value in 1..10) {
                f.setInt(obj, 0)
                Logger.printDebug { "Ghost: zeroed replay-count field ${f.name} = $value" }
            }
        }
    } catch (_: Throwable) {}
}

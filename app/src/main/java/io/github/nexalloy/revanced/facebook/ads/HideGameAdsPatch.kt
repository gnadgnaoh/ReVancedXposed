package io.github.nexalloy.revanced.facebook.ads

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import app.morphe.extension.shared.Logger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.nexalloy.hookMethod
import io.github.nexalloy.patch
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Blocks game (Audience Network) ads inside the Facebook app.
 *
 * Hooks:
 *  - onGetInterstitialAdAsync / onGetRewardedVideoAsync / onLoadAdAsync / … (JSONObject)
 *    → resolves (fakes success) or rejects the ad request silently
 *  - postMessage on the same bridge class → intercepts JSON ad messages
 *  - NekoPlayableAdActivity.onResume → immediately finishes the activity
 *  - AudienceNetworkActivity / AudienceNetworkRemoteActivity → close on resume
 *  - Activity.onResume global fallback → closes any remaining ad activity
 *  - Instrumentation / Activity / ContextWrapper startActivity* → blocks hard-blocked classes
 */
val HideGameAds = patch(
    name = "Hide game ads",
    description = "Blocks Audience Network interstitial, rewarded, banner and playable ads inside the Facebook app.",
) {
    // ── Game ad request handlers ──────────────────────────────────────────────

    val gameAdMethods = ::gameAdRequestFingerprints.dexMethodList
        .mapNotNull { runCatching { it.toMethod() }.getOrNull() }
        .filter { !Modifier.isStatic(it.modifiers) }

    gameAdMethods.forEach { method ->
        method.hookMethod {
            before { param ->
                val payload = param.args.getOrNull(0) ?: return@before
                when {
                    resolveGameAdPayload(param.thisObject, payload) ->
                        Logger.printDebug { "FB GameAds: resolved ${method.name} as success" }
                    rejectGameAdPayload(param.thisObject, payload) ->
                        Logger.printDebug { "FB GameAds: rejected ${method.name}" }
                    else ->
                        Logger.printDebug { "FB GameAds: unable to handle ${method.name}" }
                }
                param.result = null
            }
        }
    }

    // ── postMessage on game ad bridge ─────────────────────────────────────────

    val bridgeClass = gameAdMethods.firstOrNull()?.declaringClass
    bridgeClass?.declaredMethods?.firstOrNull { m ->
        m.name == "postMessage" && m.parameterCount == 2 && m.parameterTypes.all { it == String::class.java }
    }?.apply { isAccessible = true }?.hookMethod {
        before { param ->
            val rawMessage = param.args.getOrNull(0) as? String ?: return@before
            val payload = runCatching { JSONObject(rawMessage) }.getOrNull() ?: return@before
            val messageType = payload.optString("type").lowercase()
            if (messageType !in GAME_AD_MESSAGE_TYPES) return@before
            when {
                resolveGameAdPayload(param.thisObject, payload, messageType) ->
                    Logger.printDebug { "FB GameAds: resolved bridge message type=$messageType" }
                rejectGameAdPayload(param.thisObject, payload) ->
                    Logger.printDebug { "FB GameAds: rejected bridge message type=$messageType" }
            }
            param.result = null
        }
    }

    // ── NekoPlayableAdActivity ────────────────────────────────────────────────

    runCatching {
        val cls = classLoader.loadClass(NEKO_PLAYABLE_ACTIVITY_CLASS)
        cls.declaredMethods.firstOrNull { it.name == "onResume" && it.parameterCount == 0 }
            ?.apply { isAccessible = true }
            ?.hookMethod {
                after { param ->
                    val activity = param.thisObject as? Activity ?: return@after
                    if (activity.javaClass.name != NEKO_PLAYABLE_ACTIVITY_CLASS) return@after
                    finishGameAdActivity(activity, "NekoPlayableAdActivity.onResume direct hook")
                }
            }
    }

    // ── AudienceNetwork activities ────────────────────────────────────────────

    listOf(AUDIENCE_NETWORK_ACTIVITY_CLASS, AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS).forEach { className ->
        runCatching {
            val cls = classLoader.loadClass(className)
            val hookMethod = (cls.declaredMethods + cls.methods).firstOrNull { m ->
                (m.name == "onResume" && m.parameterCount == 0) ||
                    (m.name == "onStart" && m.parameterCount == 0) ||
                    (m.name == "onCreate" && m.parameterCount == 1 && m.parameterTypes[0] == Bundle::class.java)
            }
            hookMethod?.apply { isAccessible = true }?.hookMethod {
                after { param ->
                    val activity = param.thisObject as? Activity ?: return@after
                    if (activity.javaClass.name != className) return@after
                    finishGameAdActivity(activity, "$className.${hookMethod!!.name} direct hook")
                }
            }
        }
    }

    // ── Global Activity.onResume fallback ─────────────────────────────────────

    runCatching {
        val onResume = (Activity::class.java.declaredMethods + Activity::class.java.methods)
            .firstOrNull { it.name == "onResume" && it.parameterCount == 0 }
            ?.apply { isAccessible = true }

        onResume?.let { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name !in GAME_AD_ACTIVITY_CLASS_NAMES) return
                    finishGameAdActivity(activity, "global Activity.onResume fallback")
                }
            })
            Logger.printDebug { "FB GameAds: hooked global Activity.onResume fallback" }
        }
    }.onFailure { Logger.printDebug { "FB GameAds: global lifecycle fallback failed: $it" } }

    // ── Launch intent fallbacks ───────────────────────────────────────────────

    val launchSources: List<Class<*>> = listOf(
        Instrumentation::class.java,
        Activity::class.java,
        ContextWrapper::class.java
    )
    val launchMethods = LinkedHashMap<String, Method>()
    launchSources.forEach { type ->
        (type.declaredMethods + type.methods)
            .filter { m ->
                m.name in setOf(
                    "execStartActivity", "startActivity",
                    "startActivityForResult", "startActivityIfNeeded"
                ) && m.parameterTypes.any { it == Intent::class.java }
            }
            .forEach { m ->
                m.isAccessible = true
                val sig = "${m.declaringClass.name}.${m.name}(${m.parameterTypes.joinToString(",") { it.name }})"
                launchMethods.putIfAbsent(sig, m)
            }
    }

    var hookedLaunch = 0
    launchMethods.values.forEach { method ->
        runCatching {
            method.hookMethod {
                before { param ->
                    val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return@before
                    val blocked = resolveBlockedGameAdActivity(intent)
                        ?.takeIf { it in HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES } ?: return@before
                    param.result = if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                    Logger.printDebug { "FB GameAds: blocked launch to $blocked via ${method.declaringClass.simpleName}.${method.name}" }
                }
            }
            hookedLaunch++
        }.onFailure { Logger.printDebug { "FB GameAds: failed to hook launch ${method.name}: $it" } }
    }
    Logger.printDebug { "FB GameAds: hooked $hookedLaunch launch fallback method(s)" }
}

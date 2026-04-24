package io.github.nexalloy.morphe.youtube.misc.litho.filter

import app.morphe.extension.youtube.patches.components.Filter
import app.morphe.extension.youtube.patches.components.LithoFilterPatch
import app.morphe.extension.youtube.shared.ConversionContext
import io.github.nexalloy.morphe.youtube.misc.playservice.VersionCheck
import io.github.nexalloy.morphe.youtube.misc.playservice.is_20_22_or_greater
import io.github.nexalloy.morphe.youtube.misc.playservice.is_21_15_or_greater
import io.github.nexalloy.morphe.youtube.misc.verticalscroll.FixVerticalScroll
import io.github.nexalloy.new
import io.github.nexalloy.patch
import io.github.nexalloy.scopedHook
import java.nio.ByteBuffer

lateinit var addLithoFilter: (Filter) -> Unit
    private set

val LithoFilter = patch(
    description = "Hooks the method which parses the bytes into a ComponentContext to filter components.",
) {
    dependsOn(
        FixVerticalScroll,
        VersionCheck,
    )

    addLithoFilter = { filter ->
        LithoFilterPatch.addFilter(filter)
    }

    //region Pass the buffer into extension.
    if (!is_20_22_or_greater) {
        // Non-native buffer.
        ProtobufBufferReferenceFingerprint.hookMethod {
            before { param ->
                LithoFilterPatch.setProtoBuffer(param.args[1] as ByteBuffer)
            }
        }
    }

    //endregion

    // region Hook the method that parses bytes into a ComponentContext.

    // Return an EmptyComponent instead of the original component if the filterState method returns true.

    val buttonViewModelThreadLocal = ThreadLocal<Any?>()
    ComponentCreateFingerprint.hookMethod(scopedHook(::buttonViewModelReceiver.method) {
        before {
            buttonViewModelThreadLocal.set(it.args[0])
        }
    })

    ComponentCreateFingerprint.hookMethod {
        val identifierField = ::identifierFieldData.field
        val pathBuilderField = ::pathBuilderFieldData.field
        val emptyComponentClazz = ::emptyComponentClass.clazz
        val protoBufferEncodeMethod = ProtobufBufferEncodeFingerprint.method
        val protoBufferEncodeClass = ProtobufBufferEncodeFingerprint.declaredClass
        val accessibilityIdMethod = ::AccessibilityIdMethod.method
        val accessibilityTextMethod = ::accessibilityTextMethod.method
        after { param ->
            val conversion = param.args[1]
            val bufferParent = param.args[2]
            // Verify it's the expected subclass just in case.
            val buffer = if (protoBufferEncodeClass.isInstance(bufferParent)) {
                protoBufferEncodeMethod(bufferParent) as ByteArray?
            } else byteArrayOf()
            val buttonViewModel = buttonViewModelThreadLocal.get()
            buttonViewModelThreadLocal.remove()
            val accessibilityId = buttonViewModel?.let { accessibilityIdMethod(it) as String? }
            val accessibilityText = buttonViewModel?.let { accessibilityTextMethod(it) as String? }

            val contextWrapper = object : ConversionContext.ContextInterface {
                override fun patch_getPathBuilder() =
                    pathBuilderField.get(conversion) as StringBuilder

                override fun patch_getIdentifier() =
                    identifierField.get(conversion) as? String ?: ""

                override fun toString() = conversion.toString()
            }

            if (LithoFilterPatch.isFiltered(
                    contextWrapper,
                    buffer,
                    accessibilityId,
                    accessibilityText
                )
            ) {
                param.result = emptyComponentClazz.new()
            }
        }
    }

    //endregion

    // region Change Litho thread executor to 1 thread to fix layout issue in unpatched YouTube.

    ::lithoThreadExecutorFingerprint.hookMethod {
        before {
            it.args[0] = LithoFilterPatch.getExecutorCorePoolSize(it.args[0] as Int)
            it.args[1] = LithoFilterPatch.getExecutorMaxThreads(it.args[1] as Int)
        }
    }

    // endregion

    // region A/B test of new Litho native code.

    // Turn off a feature flag that enables native code of protobuf parsing (Upb protobuf).
    // If this is enabled, then the litho protobuffer hook will always show an empty buffer
    // since it's no longer handled by the hooked Java code.
    if (!is_21_15_or_greater) {
        ::featureFlagCheck.hookMethod {
            before {
                if (it.args[0] == 45419603L) it.result = false
            }
        }
    }

    // endregion
}
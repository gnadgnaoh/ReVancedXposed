package io.github.nexalloy.morphe.youtube.misc.litho.node

import io.github.nexalloy.morphe.AccessFlags
import io.github.nexalloy.morphe.Fingerprint
import io.github.nexalloy.morphe.Opcode
import io.github.nexalloy.morphe.methodCall
import io.github.nexalloy.morphe.youtube.layout.hide.general.ParseElementFromBufferFingerprint

internal object TreeNodeResultListFingerprint : Fingerprint(
    classFingerprint = ParseElementFromBufferFingerprint,
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Ljava/util/List;",
    filters = listOf(
        methodCall(name = "nCopies", opcode = Opcode.INVOKE_STATIC),
    )
)

package io.github.nexalloy.revanced.googlephotos.misc.backup

import io.github.nexalloy.morphe.Fingerprint

internal object isDCIMFolderBackupControlMethod : Fingerprint(
    strings = listOf(
        "/dcim",
        "/mars_files/"
    ),
    returnType = "Z"
)
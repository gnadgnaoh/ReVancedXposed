package io.github.nexalloy.revanced.googlephotos.misc.backup

import de.robv.android.xposed.XC_MethodReplacement
import io.github.nexalloy.patch

val EnableDCIMFoldersBackupControl = patch(
    name = "Enable DCIM folders backup control",
    description = "Disables always on backup for the Camera and other DCIM folders, allowing you to control backup " +
            "for each folder individually. This will make the app default to having no folders backed up.",
    use = false,
) {
    isDCIMFolderBackupControlMethod.hookMethod(XC_MethodReplacement.returnConstant(false))
}
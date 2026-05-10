package me.bestvibes.exiftoolwrapper

import android.content.Intent


class FileUtils {
    companion object {
        fun makeImagePickerIntent(): Intent {
            // ACTION_OPEN_DOCUMENT (not ACTION_PICK) so the URIs come back with
            // FLAG_GRANT_READ_URI_PERMISSION + FLAG_GRANT_WRITE_URI_PERMISSION.
            // ACTION_PICK on modern Android returns Photo Picker URIs that are
            // read-only and not writeable via createWriteRequest, which forces
            // a separate permission dance for write modes; ACTION_OPEN_DOCUMENT
            // gives a unified read+write URI in a single picker.
            return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
    }
}

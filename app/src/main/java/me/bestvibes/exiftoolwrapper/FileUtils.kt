package me.bestvibes.exiftoolwrapper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File


class FileUtils {
    companion object {
        private const val PATH_TREE = "tree"
        private const val PRIMARY_TYPE = "primary"
        private const val RAW_TYPE = "raw"

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

        fun makeDirectoryPickerIntent(): Intent {
            return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        }

        @Throws(java.lang.IllegalArgumentException::class)
        fun getDirectoryPathFromUri(context: Context, uri: Uri): String {
            if ("file" == uri.scheme) {
                return uri.path!!
            }

            getTreeDocumentId(uri)?.let {
                val paths = it.split(":")
                val type = paths[0]
                val subPath = if (paths.size == 2) paths[1] else ""
                return when {
                    RAW_TYPE.equals(type, true) -> {
                        it.substring(it.indexOf(File.separator))
                    }
                    PRIMARY_TYPE.equals(type, true) -> {
                        val rootPath = Environment.getExternalStorageDirectory().absolutePath
                        rootPath + File.separator + subPath
                    }
                    else -> {
                        if (paths.size == 1) {
                            getRemovableStorageRootPath(context, paths[0])
                        } else {
                            val rootPath = getRemovableStorageRootPath(context, paths[0])
                            rootPath + File.separator + paths[1]
                        }
                    }
                }
            } ?: throw IllegalArgumentException("Uri provided not a tree: $uri")
        }


        private fun getRemovableStorageRootPath(context: Context, storageId: String): String {
            val rootPath = StringBuilder()
            val externalFilesDirs: Array<File> =
                ContextCompat.getExternalFilesDirs(context, null)
            for (fileDir in externalFilesDirs) {
                if (fileDir.path.contains(storageId)) {
                    for (segment in fileDir.path.split(File.separator)) {
                        if (segment == storageId) {
                            rootPath.append(storageId)
                            break
                        }
                        rootPath.append(segment).append(File.separator)
                    }
                    break
                }
            }
            return rootPath.toString()
        }

        private fun getTreeDocumentId(uri: Uri): String? {
            return if (isTreeUri(uri)) {
                uri.pathSegments[1]
            } else null
        }


        private fun isTreeUri(uri: Uri): Boolean {
            val paths = uri.pathSegments
            return paths.size == 2 && PATH_TREE == paths[0]
        }
    }
}

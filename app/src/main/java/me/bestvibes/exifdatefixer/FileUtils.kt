package me.bestvibes.exifdatefixer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.lang.reflect.Method


class FileUtils {
    companion object {
        private const val PATH_TREE = "tree"
        private const val PRIMARY_TYPE = "primary"
        private const val RAW_TYPE = "raw"

        fun getFilesInstallDir(context: Context): String {
            return context.filesDir.absolutePath
        }

        fun chmod(path: String, mode: Int): Int {
            val fileUtils = Class.forName("android.os.FileUtils")
            val setPermissions: Method = fileUtils.getMethod(
                "setPermissions",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            return setPermissions.invoke(null, path, mode, -1, -1) as Int
        }

        fun makeImagePickerIntent(): Intent {
            val i = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            i.type = "image/*"
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            return i
        }

        fun makeDirectoryPickerIntent(): Intent {
            return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        }

        fun convertUrisToFilePaths(
            contentResolver: ContentResolver,
            uris: ArrayList<Uri>
        ): ArrayList<String> {
            val res = arrayListOf<String>()

            val filePathColumn = arrayOf(MediaStore.Images.Media.DATA)
            for (selectedUri in uris) {
                contentResolver.query(
                    selectedUri,
                    filePathColumn,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val imageColumnIndex = cursor.getColumnIndex(filePathColumn[0])
                        res.add(cursor.getString(imageColumnIndex))
                    }
                }
            }

            return res
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
                    //rootPath.append(fileDir.getPath().split("/Android")[0]); // faster
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
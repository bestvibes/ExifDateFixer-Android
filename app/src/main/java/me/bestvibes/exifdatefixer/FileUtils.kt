package me.bestvibes.exifdatefixer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import java.lang.reflect.Method


class FileUtils {
    companion object {
        fun getFilesInstallDir(context: Context) : String {
            return context.filesDir.absolutePath
//            return Environment.getExternalStorageDirectory().absolutePath + "/AOKPDelta"
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

        fun makeImagePickerIntent() : Intent {
            val i = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            i.type = "image/*"
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            return i
        }

        fun convertUrisToFilePaths(contentResolver: ContentResolver, uris : ArrayList<Uri>) : ArrayList<String> {
            val res = arrayListOf<String>()

            val filePathColumn = arrayOf(MediaStore.Images.Media.DATA)
            for (selectedUri in uris) {
                contentResolver.query(selectedUri,
                    filePathColumn,
                    null,
                    null,
                    null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val imageColumnIndex = cursor.getColumnIndex(filePathColumn[0])
                        res.add(cursor.getString(imageColumnIndex))
                    }
                }
            }

            return res
        }
    }
}
package me.bestvibes.exifdatefixer

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rauschig.jarchivelib.ArchiveFormat
import org.rauschig.jarchivelib.ArchiverFactory
import org.rauschig.jarchivelib.CompressionType
import java.io.File
import java.io.IOException
import java.util.*


class PerlUtils {
    companion object {
        fun getAbiToUse(): String {
            return Build.SUPPORTED_ABIS[0].toLowerCase(Locale.getDefault())
        }

        @Throws(IOException::class)
        private fun getPerlZipToInstall(): Int {
            val abi = getAbiToUse()
            return when {
                "arm" in abi -> {
                    R.raw.perl_arm_pie
                }
                "x86" in abi -> {
                    R.raw.perl_x86_pie
                }
                else -> {
                    throw IOException("Unknown ABI: $abi")
                }
            }
        }

        fun isPerlInstalled(context: Context): Boolean {
            return File(getPerlPath(context)).exists()
        }

        fun isExiftoolInstalled(context: Context): Boolean {
            return File(getExiftoolPath(context)).exists()
        }

        private fun getPerlPath(context: Context) : String {
            val filesDirPath = FileUtils.getFilesInstallDir(context)
            return "$filesDirPath/${context.resources.getString(R.string.perl_path)}"
        }

        private fun getPerlLibPath(context: Context) : String {
            val filesDirPath = FileUtils.getFilesInstallDir(context)
            return "$filesDirPath/${context.resources.getString(R.string.perl_lib_path)}"
        }

        private fun getExiftoolPath(context: Context) : String {
            val filesDirPath = FileUtils.getFilesInstallDir(context)
            return "$filesDirPath/${context.resources.getString(R.string.exiftool_path)}"
        }

        suspend fun installPerlAndExifTool(context: Context) {
            withContext(Dispatchers.IO) {
                Log.d("PerlUtils", "Installing perl and exiftool...")
                val zipsToInstall: MutableList<Int> =
                    mutableListOf(R.raw.exiftool, R.raw.perl_libs, getPerlZipToInstall())

                val archiver = ArchiverFactory.createArchiver(ArchiveFormat.TAR, CompressionType.XZ)
                val filesDir = File(FileUtils.getFilesInstallDir(context))

                for (zipToInstall in zipsToInstall) {
                    Log.d("PerlUtils", "Extracting: ${context.resources.getText(zipToInstall)}")
                    try {
                        context.resources.openRawResource(zipToInstall).use {
                            it.reset()
                            archiver.extract(it, filesDir)
                        }
                    } catch (e: IOException) {
                        throw IOException(
                            "Failed to extract ${context.resources.getText(
                                zipToInstall
                            )}!", e
                        )
                    }
                }

                Log.d("PerlUtils", "Setting perl permissions to 493...")
                FileUtils.chmod(getPerlPath(context), 493)
            }
        }

        fun buildExiftoolCommand(context: Context, command: ArrayList<String>): ArrayList<String> {
            val exiftoolCommand = if (command[0] == "exiftool") {
                arrayListOf(getExiftoolPath(context), *command.subList(1, command.size).toTypedArray())
            } else {
                arrayListOf(getExiftoolPath(context), *command.toTypedArray())
            }

            return buildPerlCommand(context, exiftoolCommand)
        }

        fun buildPerlCommand(context: Context, userCommand: ArrayList<String>) : ArrayList<String> {
            val perlCommand = arrayListOf(getPerlPath(context), "-I", getPerlLibPath(context))
            perlCommand.addAll(userCommand)
            return perlCommand
        }

    }
}
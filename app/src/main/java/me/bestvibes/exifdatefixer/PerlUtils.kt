package me.bestvibes.exifdatefixer

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rauschig.jarchivelib.ArchiveFormat
import org.rauschig.jarchivelib.ArchiverFactory
import org.rauschig.jarchivelib.CompressionType
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.util.*


class PerlUtils {
    companion object {
        fun getAbiToUse(): String {
            return Build.SUPPORTED_ABIS[0].toLowerCase(Locale.getDefault())
        }

        @Throws(IOException::class)
        private fun getPerlZipToInstall(context: Context): Int {
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

        suspend fun installPerlAndExifTool(context: Context) {
            withContext(Dispatchers.IO) {
                Log.d("PerlUtils", "Installing perl and exiftool...")
                val zipsToInstall: MutableList<Int> =
                    mutableListOf(R.raw.exiftool, R.raw.perl_libs, getPerlZipToInstall(context))

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

                Log.d("PerlUtils", "Setting bin/perl permissions to 493...")
                FileUtils.chmod("${filesDir.absolutePath}/perl/bin/perl", 493)
            }
        }

        suspend fun runExiftoolCommand(context: Context, command: String): String {
            val filesDirPath = FileUtils.getFilesInstallDir(context)
            val exiftoolBinary = "$filesDirPath/exiftool/exiftool"

            val exiftoolCommand = if ("exiftool" in command) {
                command.replace("exiftool", exiftoolBinary)
            } else {
                "$exiftoolBinary $command"
            }

            return runPerlCommand(context, exiftoolCommand)
        }

        suspend fun runPerlCommand(context: Context, userCommand: String): String {
            return withContext(Dispatchers.Default) {
                val filesDirPath = FileUtils.getFilesInstallDir(context)
                val perlBinary = "$filesDirPath/perl/bin/perl"
                val perlLibDirectory = "$filesDirPath/perl/lib/perl5/5.22.1"

                val fullCommand = listOf(perlBinary, "-I", perlLibDirectory, userCommand, "2>&1")
//                return@withContext fullCommand

                val processBuilder = ProcessBuilder(fullCommand)
//            processBuilder.environment()["PERL5LIB"] = context.getFilesDir().getAbsolutePath() + "/perl/lib/";
                val process = processBuilder.start()
                process.waitFor()

                val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)

                process.destroy()

                return@withContext stdout
            }
        }
    }
}
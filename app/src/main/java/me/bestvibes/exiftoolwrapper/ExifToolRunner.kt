package me.bestvibes.exiftoolwrapper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File

/**
 * Builds and runs an exiftool invocation against perl shipped via jniLibs.
 *
 * Argv layout:
 *
 *     ${nativeLibraryDir}/libperl.so            # the perl interpreter, exec mount
 *     -I ${filesDir}/perl5/lib                  # exiftool's bundled lib tree
 *     ${filesDir}/perl5/exiftool                # the exiftool script
 *     <user args>                               # presets + (sanitized) custom field
 *     <target paths>                            # files or directory the user picked
 *
 * No shell is involved — ProcessBuilder takes a List<String> directly. There is
 * no way for a token in <user args> or <target paths> to escape into a shell
 * metacharacter. This is the foundation the CommandSanitizer relies on.
 */
object ExifToolRunner {

    fun perlBinary(context: Context): String =
        "${context.applicationInfo.nativeLibraryDir}/libperl.so"

    fun isInstalled(context: Context): Boolean =
        File(perlBinary(context)).exists() && AssetExtractor.isInstalled(context)

    /**
     * Build the full argv. Caller passes in `userArgs` (already sanitized for the
     * custom field, or fixed for a preset) and `targetPaths` (file paths or a
     * single directory path).
     */
    fun buildCommand(
        context: Context,
        userArgs: List<String>,
        targetPaths: List<String>
    ): List<String> = buildBaseCommand(context, userArgs) + targetPaths

    fun buildBaseCommand(context: Context, userArgs: List<String>): List<String> {
        val perl5 = AssetExtractor.perl5Dir(context).absolutePath
        return buildList {
            add(perlBinary(context))
            // archlib first (XS .so symlinks live under arch/auto/), then privlib.
            // Match the layout the perl install was configured with in native/build.sh.
            add("-I"); add("$perl5/arch")
            add("-I"); add("$perl5/lib")
            add("$perl5/exiftool")
            addAll(userArgs)
        }
    }

    suspend fun run(command: List<String>): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
        process.waitFor()
        output
    }
}

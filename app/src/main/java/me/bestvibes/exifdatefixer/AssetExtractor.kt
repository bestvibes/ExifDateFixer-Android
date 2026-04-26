package me.bestvibes.exifdatefixer

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Extracts assets/perl5.tar (the exiftool source + perl @INC tree, including
 * the .pm wrappers for XS modules) into filesDir/perl5/, and creates symlinks
 * for each XS .so under filesDir/perl5/arch/auto/<dist>/<dist>.so pointing at
 * the corresponding libperl_xs_*.so in nativeLibraryDir.
 *
 * Why symlinks: dlopen() needs the .so on an exec mount, which only
 * nativeLibraryDir provides. perl's DynaLoader looks for XS modules at the
 * canonical archlib auto/ paths inside @INC, so we materialize that path
 * structure in filesDir/perl5/ but the actual .so file is the symlink target
 * in nativeLibraryDir.
 */
object AssetExtractor {

    private const val TAG = "AssetExtractor"
    private const val ASSET_NAME = "perl5.tar"
    private const val MARKER_FILE = "perl5.version"
    private const val XS_LINKED_DIR_FILE = "xs_linked.dir"
    private const val XS_PREFIX = "perl_xs_"

    fun perl5Dir(context: Context): File =
        File(context.filesDir, "perl5")

    fun exiftoolScript(context: Context): File =
        File(perl5Dir(context), "exiftool")

    fun isInstalled(context: Context): Boolean {
        val marker = File(context.filesDir, MARKER_FILE)
        if (!marker.exists()) return false
        val saved = runCatching { marker.readText().trim().toInt() }.getOrNull() ?: return false
        if (saved != BuildConfig.PERL5_ASSET_VERSION) return false
        // Sanity-check that the expected layout actually landed — guards against a
        // partially-completed previous extraction or a stale marker from an earlier
        // app version with a different bundle layout.
        val perl5 = perl5Dir(context)
        return File(perl5, "exiftool").exists() &&
               File(perl5, "lib").isDirectory &&
               File(perl5, "xs_manifest.txt").exists()
    }

    suspend fun ensureInstalled(context: Context) = withContext(Dispatchers.IO) {
        val dest = perl5Dir(context)

        if (!isInstalled(context)) {
            Log.d(TAG, "Extracting $ASSET_NAME (version ${BuildConfig.PERL5_ASSET_VERSION})")
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()
            context.assets.open(ASSET_NAME).use { input ->
                TarReader.extract(input, dest)
            }
            File(context.filesDir, MARKER_FILE)
                .writeText(BuildConfig.PERL5_ASSET_VERSION.toString())
            Log.d(TAG, "Extraction complete")
        }

        // Re-wire XS symlinks only when nativeLibraryDir has actually changed
        // (every `adb install -r` and Play upgrade randomizes that path, but
        // a normal app resume doesn't). The path is stamped after a successful
        // wire-up; if it matches the current one, all symlinks are still valid.
        val currentLibDir = context.applicationInfo.nativeLibraryDir
        val stampFile = File(context.filesDir, XS_LINKED_DIR_FILE)
        if (stampFile.exists() && stampFile.readText().trim() == currentLibDir) {
            return@withContext
        }
        wireUpXsModules(context, dest, currentLibDir)
        stampFile.writeText(currentLibDir)
    }

    /**
     * Reads `perl5/xs_manifest.txt` (lines of "<rel> <soname>", produced by
     * native/build.sh) and creates a symlink at `perl5/arch/auto/<rel>` pointing
     * to `nativeLibDir/<soname>`. perl's DynaLoader walks @INC looking for
     * `arch/auto/<dist>/<dist>.so` and dlopen()s whatever it finds there.
     */
    private fun wireUpXsModules(context: Context, perl5: File, nativeLibDir: String) {
        val manifest = File(perl5, "xs_manifest.txt")
        if (!manifest.exists()) {
            Log.w(TAG, "xs_manifest.txt missing; XS modules will not be loadable")
            return
        }
        val archAuto = File(perl5, "arch/auto")
        archAuto.mkdirs()

        var linked = 0
        manifest.forEachLine { line ->
            val parts = line.trim().split(' ')
            if (parts.size != 2) return@forEachLine
            val (rel, soname) = parts
            val target = File(nativeLibDir, soname)
            if (!target.exists()) {
                Log.w(TAG, "manifest references $soname but it's missing in $nativeLibDir")
                return@forEachLine
            }
            val link = File(archAuto, rel)
            link.parentFile?.mkdirs()
            // File.exists() follows symlinks — a dead symlink (target missing)
            // returns false, so the standard exists()/delete() pattern silently
            // skips. Os.remove() acts on the symlink itself regardless of whether
            // the target is reachable.
            try { Os.remove(link.absolutePath) } catch (_: Exception) {}
            try {
                Os.symlink(target.absolutePath, link.absolutePath)
                linked++
            } catch (e: Exception) {
                Log.w(TAG, "symlink failed for $rel → $soname: ${e.message}")
            }
        }
        Log.d(TAG, "Linked $linked XS .so files (Android API ${Build.VERSION.SDK_INT})")
    }
}

package me.bestvibes.exiftoolwrapper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Predicts whether running a parsed [Command] is likely to mutate the user's
 * Target-slot files. The footer + run-confirm dialog both rely on this, so
 * regressing it would either over-warn (annoying) or under-warn (dangerous).
 */
class MayMutateTargetsTest {

    private fun parse(input: String): Command =
        (CommandParser.parse(input) as CommandParser.Result.Ok).command

    @Test fun emptyTemplateIsReadOnly() {
        // "Show metadata" preset.
        assertFalse(parse("").mayMutateTargets())
    }

    @Test fun bareTagReadIsReadOnly() {
        // "Check EXIF date" preset — print a tag, no = or <.
        assertFalse(parse("-DateTimeOriginal").mayMutateTargets())
    }

    @Test fun overwriteOriginalFlagsMutation() {
        assertTrue(parse("-DateTimeOriginal<Filename -overwrite_original").mayMutateTargets())
    }

    @Test fun tagAssignmentFlagsMutation() {
        // Without -overwrite_original, exiftool still writes the tag and
        // creates a _original sidecar in cache; the runner's writeback would
        // push the modified cache back. User must be warned.
        assertTrue(parse("-DateTimeOriginal=2023:01:01").mayMutateTargets())
    }

    @Test fun tagCopyFlagsMutation() {
        assertTrue(parse("-DateTimeOriginal<FileModifyDate").mayMutateTargets())
    }

    @Test fun groupedTagWriteFlagsMutation() {
        assertTrue(parse("-EXIF:Make=Canon").mayMutateTargets())
    }

    @Test fun copyMetadataPresetFlagsMutation() {
        // Real preset shape: clears all, copies all, overwrites.
        val cmd = parse("-all= -tagsFromFile {source} -all:all -overwrite_original {target}")
        assertTrue(cmd.mayMutateTargets())
    }

    @Test fun noTargetSlotIsReadOnlyEvenWithWriteFlags() {
        // If a custom command somehow has no Target binding the runner can't
        // write anything back, so we shouldn't warn. (Not constructible via
        // the parser today since the implicit-trailing-target desugar always
        // adds one, but defended in the predicate for safety.)
        val cmd = Command(
            template = listOf(Token.Literal("-overwrite_original")),
            slots = emptyList(),
        )
        assertFalse(cmd.mayMutateTargets())
    }

    @Test fun sidecarOutputOverWarnsButThatsFine() {
        // -csv=… looks like a tag-write to the heuristic. We accept this
        // false positive: warning on a sidecar-output run is harmless;
        // missing a real write would not be.
        val cmd = parse("-csv={sidecar:csv} {target}")
        assertTrue(cmd.mayMutateTargets())
    }
}

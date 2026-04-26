package me.bestvibes.exifdatefixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSanitizerTest {

    private fun expectOk(input: String, vararg expected: String) {
        val r = CommandSanitizer.sanitize(input)
        assertTrue("Expected Ok for '$input', got $r", r is CommandSanitizer.Result.Ok)
        assertEquals(expected.toList(), (r as CommandSanitizer.Result.Ok).tokens)
    }

    private fun expectRejected(input: String, reasonContains: String) {
        val r = CommandSanitizer.sanitize(input)
        assertTrue("Expected Rejected for '$input', got $r", r is CommandSanitizer.Result.Rejected)
        val msg = (r as CommandSanitizer.Result.Rejected).reason
        assertTrue("Reason '$msg' should contain '$reasonContains'", msg.contains(reasonContains))
    }

    @Test fun emptyInputIsOk() = expectOk("")
    @Test fun whitespaceOnlyIsOk() = expectOk("    ")
    @Test fun simplePresetTokensPass() =
        expectOk("-EXIF:Make=Test -overwrite_original",
            "-EXIF:Make=Test", "-overwrite_original")

    @Test fun multipleSpacesCollapse() =
        expectOk("  -datetimeoriginal<filename    -overwrite_original  ",
            "-datetimeoriginal<filename", "-overwrite_original")

    @Test fun rejectsConfigFlag() =
        expectRejected("-config /tmp/evil.pl -datetimeoriginal", "-config")

    @Test fun rejectsConfigEqualsForm() =
        expectRejected("-config=/tmp/evil.pl", "-config")

    @Test fun rejectsArgFile() =
        expectRejected("-@ /tmp/args.txt", "-@")

    @Test fun rejectsArgFileEqualsForm() =
        expectRejected("-@=/tmp/args.txt", "-@")

    @Test fun rejectsExecute() =
        expectRejected("-execute -datetimeoriginal", "-execute")

    @Test fun rejectsExecute1() =
        expectRejected("-execute1 -datetimeoriginal", "-execute")

    @Test fun rejectsStayOpen() =
        expectRejected("-stay_open True -@ args", "-stay_open")

    @Test fun rejectsLeadingPipe() =
        expectRejected("|cat /etc/passwd", "shell metacharacter")

    @Test fun rejectsLeadingRedirect() =
        expectRejected(">/tmp/owned", "shell metacharacter")

    @Test fun rejectsLeadingBacktick() =
        expectRejected("`whoami` -datetimeoriginal", "shell metacharacter")

    @Test fun rejectsLeadingDollar() =
        expectRejected("\$IFS -datetimeoriginal", "shell metacharacter")

    @Test fun shellMetaInsideTokenIsAllowed() {
        // Common in exiftool tag-copy syntax: -datetimeoriginal<filename
        // The '<' is a literal character to exiftool, not a shell redirect.
        // We only block tokens that *start* with shell metacharacters.
        expectOk("-datetimeoriginal<filename", "-datetimeoriginal<filename")
    }

    @Test fun complexValidTagAssignment() =
        expectOk("-EXIF:GPSLatitude=37.7749 -EXIF:GPSLatitudeRef=N",
            "-EXIF:GPSLatitude=37.7749", "-EXIF:GPSLatitudeRef=N")
}

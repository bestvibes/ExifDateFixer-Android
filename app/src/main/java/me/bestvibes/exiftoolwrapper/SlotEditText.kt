package me.bestvibes.exiftoolwrapper

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import com.google.android.material.textfield.TextInputEditText

/**
 * EditText that treats each [SlotChipSpan] as one indivisible glyph from the
 * user's point of view: a single backspace anywhere inside or at the trailing
 * edge of a chip removes the whole `{source}`-style token (plus an adjacent
 * space, if any), instead of nibbling one character at a time.
 *
 * Why this lives here and not in a TextWatcher: soft keyboards send the
 * deletion through [InputConnection] (`sendKeyEvent` for KEYCODE_DEL or
 * `deleteSurroundingText(1, 0)`), and those are the only two places where we
 * can intercept *before* the IME has already mangled the token text. Doing it
 * post-hoc in afterTextChanged would have to reverse-engineer "the user just
 * deleted one char from inside a chip" from index math that gets gnarly fast.
 */
class SlotEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.editTextStyle,
) : TextInputEditText(context, attrs, defStyleAttr) {

    init {
        // Auto-space watcher: catches insertion paths the InputConnection
        // overrides miss (hardware keys, `adb shell input text`, paste, plus
        // any IME that bypasses commitText). Mutating in afterTextChanged
        // re-fires watchers — `applyingSpace` blocks recursion.
        addTextChangedListener(object : TextWatcher {
            private var applyingSpace = false
            private var insertStart = -1
            private var insertCount = 0

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (applyingSpace) return
                insertStart = start
                insertCount = count
            }

            override fun afterTextChanged(s: Editable?) {
                if (applyingSpace || s == null) return
                if (insertCount <= 0 || insertStart < 0 || insertStart >= s.length) return
                val firstChar = s[insertStart]
                if (firstChar.isWhitespace()) return
                if (insertStart <= 0) return
                val prev = s[insertStart - 1]
                if (prev.isWhitespace()) return
                val spans = s.getSpans(insertStart - 1, insertStart, SlotChipSpan::class.java)
                val touchesChipEnd = spans.any { s.getSpanEnd(it) == insertStart }
                if (!touchesChipEnd) return
                applyingSpace = true
                try {
                    s.insert(insertStart, " ")
                } finally {
                    applyingSpace = false
                }
            }
        })
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(ic, true) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN &&
                    event.keyCode == KeyEvent.KEYCODE_DEL &&
                    deleteChipAtCursor()
                ) return true
                return super.sendKeyEvent(event)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // Only intercept the canonical "single backspace" shape. Larger
                // selections / forward deletes pass through normally.
                if (beforeLength == 1 && afterLength == 0 && deleteChipAtCursor()) {
                    return true
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                super.commitText(separatorPaddedFor(text), newCursorPosition)

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
                super.setComposingText(separatorPaddedFor(text), newCursorPosition)
        }
    }

    /**
     * If the cursor sits flush against a chip boundary and the IME is about to
     * insert non-whitespace text, prepend a space so the chip token stays in
     * its own argv slot. Without this, tapping mid-chip (which snaps the
     * cursor to the chip end) and typing flags produces e.g. `{target...}-foo`,
     * which trips the parser's variadic-must-stand-alone check.
     */
    private fun separatorPaddedFor(text: CharSequence?): CharSequence? {
        if (text.isNullOrEmpty()) return text
        if (text[0].isWhitespace()) return text
        val buf: Editable = this.text ?: return text
        val sel = selectionStart
        if (sel <= 0 || sel != selectionEnd) return text
        val prev = buf.getOrNull(sel - 1) ?: return text
        if (prev.isWhitespace()) return text
        val spans = buf.getSpans(sel - 1, sel, SlotChipSpan::class.java)
        val touchesChipEnd = spans.any { buf.getSpanEnd(it) == sel }
        return if (touchesChipEnd) " $text" else text
    }

    /**
     * If the cursor sits inside or at the trailing edge of a chip span,
     * delete the whole span (and one adjacent space, on whichever side has
     * one) and return true so the IME stops trying to delete a character.
     */
    private fun deleteChipAtCursor(): Boolean {
        val text: Editable = text ?: return false
        val sel = selectionStart
        if (sel != selectionEnd || sel <= 0) return false

        val spans = text.getSpans(0, text.length, SlotChipSpan::class.java)
        for (sp in spans) {
            val start = text.getSpanStart(sp)
            val end = text.getSpanEnd(sp)
            // Cursor strictly *after* the chip start and at most at its end.
            // Cursor exactly at start means it's positioned before the chip;
            // backspace there should delete whatever character precedes the
            // chip, not the chip itself.
            if (sel in (start + 1)..end) {
                var rangeStart = start
                var rangeEnd = end
                when {
                    rangeEnd < text.length && text[rangeEnd] == ' ' -> rangeEnd++
                    rangeStart > 0 && text[rangeStart - 1] == ' ' -> rangeStart--
                }
                text.delete(rangeStart, rangeEnd)
                return true
            }
        }
        return false
    }
}

package me.bestvibes.exiftoolwrapper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.ReplacementSpan
import android.widget.EditText

/**
 * A chip-styled inline span for slot tokens like `{source}` inside an EditText.
 * The underlying text remains the canonical token (so copy-paste still gives
 * the user the literal `{source}` to share), but visually it renders as a
 * pill-shaped chip with the slot's display label.
 *
 * Apply via [installSlotChipDecoration].
 */
class SlotChipSpan(
    private val label: String,
    private val backgroundColor: Int,
    private val textColor: Int,
    private val strokeColor: Int,
) : ReplacementSpan() {

    private val padHorizontal = 14f
    private val padVertical = 4f
    private val radius = 18f
    private val strokeWidth = 1.5f

    private fun measureLabel(paint: Paint): Float {
        val tp = TextPaint(paint).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = paint.textSize * 0.92f
        }
        return tp.measureText(label) + padHorizontal * 2f
    }

    override fun getSize(
        paint: Paint, text: CharSequence, start: Int, end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        if (fm != null) {
            val origFm = paint.fontMetricsInt
            fm.ascent = origFm.ascent - padVertical.toInt()
            fm.top = origFm.top - padVertical.toInt()
            fm.descent = origFm.descent + padVertical.toInt()
            fm.bottom = origFm.bottom + padVertical.toInt()
        }
        return measureLabel(paint).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        val width = measureLabel(paint)
        val rect = RectF(
            x,
            top.toFloat() + 2f,
            x + width,
            bottom.toFloat() - 2f,
        )

        val fillPaint = Paint(paint).apply {
            color = backgroundColor
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        val strokePaint = Paint(paint).apply {
            color = strokeColor
            style = Paint.Style.STROKE
            this.strokeWidth = this@SlotChipSpan.strokeWidth
            isAntiAlias = true
        }
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        val labelPaint = TextPaint(paint).apply {
            color = textColor
            typeface = Typeface.DEFAULT_BOLD
            textSize = paint.textSize * 0.92f
            isAntiAlias = true
        }
        // Center label vertically within the rect.
        val baseline = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(label, x + padHorizontal, baseline, labelPaint)
    }
}

/**
 * Wires a TextWatcher onto [editText] that re-applies [SlotChipSpan]s every
 * time the text changes. Spans are recomputed from scratch on each edit
 * (cheap — slot tokens are short and few).
 *
 * The matcher uses a regex equivalent to the parser's [CommandParser.SLOT_REGEX]
 * (sans negative lookbehind on `$`, which is enforced separately). Only tokens
 * with a known [SlotRole] get chipped; unknown ones (`{foo}`) render as plain
 * text — they'll surface as a parse error in the error label.
 *
 * [onTextChanged] fires after spans are applied, so callers can re-parse and
 * update the slot picker UI.
 */
fun installSlotChipDecoration(
    editText: EditText,
    chipBackground: Int = Color.parseColor("#E8EAF6"),
    chipText: Int = Color.parseColor("#283593"),
    chipStroke: Int = Color.parseColor("#7986CB"),
    labelFor: (FileSlot, allSlots: List<FileSlot>) -> String = { slot, _ -> slot.label() },
    onTextChanged: (String) -> Unit = {},
) {
    val regex = Regex("""(?<!\$)\{([a-zA-Z]+)(?::([a-zA-Z0-9_]+))?(\.\.\.)?\}""")
    var applying = false

    editText.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            if (applying || s == null) return
            applying = true
            try {
                // Strip any existing chip spans before reapplying.
                val existing = s.getSpans(0, s.length, SlotChipSpan::class.java)
                for (sp in existing) s.removeSpan(sp)

                // Two-pass: collect all slots first so the resolver has the
                // full set when making context-aware label decisions (e.g.
                // {target} reads as "Destination" only when {source} is
                // present in the same command).
                data class Match(val range: IntRange, val slot: FileSlot)
                val matches = mutableListOf<Match>()
                for (m in regex.findAll(s)) {
                    val (roleName, variant, dots) = m.destructured
                    val role = SlotRole.fromTokenName(roleName) ?: continue
                    val variantOrNull = if (variant.isEmpty()) null else variant
                    if (role == SlotRole.SIDECAR && variantOrNull == null) continue
                    val key = SlotKey(role, variantOrNull)
                    val count = if (dots.isNotEmpty()) SlotCount.MANY else SlotCount.ONE
                    val kind = if (role == SlotRole.OUTDIR) SlotKind.DIR else SlotKind.FILE
                    matches += Match(m.range, FileSlot(key, kind, count))
                }
                val allSlots = matches.map { it.slot }
                for ((range, slot) in matches) {
                    val baseLabel = labelFor(slot, allSlots)
                    val span = SlotChipSpan(
                        label = baseLabel + if (slot.count == SlotCount.MANY) "…" else "",
                        backgroundColor = chipBackground,
                        textColor = chipText,
                        strokeColor = chipStroke,
                    )
                    s.setSpan(span, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                onTextChanged(s.toString())
            } finally {
                applying = false
            }
        }
    })
}

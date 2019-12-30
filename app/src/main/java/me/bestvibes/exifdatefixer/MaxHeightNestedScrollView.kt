package me.bestvibes.exifdatefixer

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView


class MaxHeightNestedScrollView : NestedScrollView {
    private var maxHeight = -1
    private val defaultHeight = 250

    constructor(context: Context) : super(context) {
        init(context, null)
    }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs)
    }
    constructor(
        context: Context, attrs: AttributeSet?,
        defStyleAttr: Int
    ) : super(context, attrs, defStyleAttr) {
        init(context, attrs)
    }

    private fun init(context: Context, attrs: AttributeSet?) {
        if (attrs == null) {
            return
        }

        val a = context.obtainStyledAttributes(attrs, R.styleable.MaxHeightNestedScrollView)

        try {
            maxHeight = (
                a.getDimensionPixelSize(
                    R.styleable.MaxHeightNestedScrollView_maxHeight,
                    defaultHeight
                )
            )
        } finally {
            a.recycle()
        }
    }

    fun setMaxHeightDp(dp: Int) {
        maxHeight = (dp * context.resources.displayMetrics.density).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val newHeightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, newHeightMeasureSpec)
    }
}
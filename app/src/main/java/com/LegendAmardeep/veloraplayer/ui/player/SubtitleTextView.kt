package com.LegendAmardeep.veloraplayer.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class SubtitleTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var strokeWidthValue: Float = 0f
        set(value) {
            field = value
            invalidate()
        }
    
    var strokeColorValue: Int = Color.BLACK
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (strokeWidthValue > 0) {
            val originalColor = textColors
            
            // 1. Draw the stroke (outline)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidthValue
            super.setTextColor(strokeColorValue)
            super.onDraw(canvas)

            // 2. Draw the actual text (fill) on top
            paint.style = Paint.Style.FILL
            super.setTextColor(originalColor)
            super.onDraw(canvas)
        } else {
            super.onDraw(canvas)
        }
    }
}

package com.autolyrics.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import java.lang.ref.WeakReference

class SettingInfoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { showInfoBubble() }
    }

    override fun onDetachedFromWindow() {
        val popup = activePopup?.get()
        if (popup?.contentView?.tag === this) {
            popup.dismiss()
        }
        super.onDetachedFromWindow()
    }

    private fun showInfoBubble() {
        val message = contentDescription?.toString()?.takeIf { it.isNotBlank() } ?: return

        activePopup?.get()?.dismiss()

        val bubble = TextView(context).apply {
            text = message
            setTextColor(Color.parseColor("#E0E0EE"))
            textSize = 12f
            includeFontPadding = false
            setLineSpacing(0f, 1.15f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A2A3E"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#46465C"))
            }
            tag = this@SettingInfoView
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val popupWidth = minOf(dp(300), screenWidth - dp(16))
        bubble.maxWidth = popupWidth

        val popup = PopupWindow(
            bubble,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }

        activePopup = WeakReference(popup)
        popup.setOnDismissListener {
            if (activePopup?.get() === popup) {
                activePopup = null
            }
        }

        val anchorLocation = IntArray(2)
        getLocationOnScreen(anchorLocation)
        val edgeMargin = dp(8)
        val desiredLeft = anchorLocation[0] + width / 2 - popupWidth / 2
        val maxLeft = (screenWidth - popupWidth - edgeMargin).coerceAtLeast(edgeMargin)
        val popupLeft = desiredLeft.coerceIn(edgeMargin, maxLeft)
        val xOffset = popupLeft - anchorLocation[0]

        popup.showAsDropDown(this, xOffset, dp(4))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private var activePopup: WeakReference<PopupWindow>? = null
    }
}

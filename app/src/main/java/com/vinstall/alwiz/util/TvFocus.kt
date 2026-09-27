package com.vinstall.alwiz.util

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.vinstall.alwiz.R

/** Small, view-system-only focus treatment shared by TV RecyclerView rows. */
object TvFocus {
    fun install(view: View) {
        if (!DeviceProfile.isTv(view.context)) return

        view.isFocusable = true
        view.isFocusableInTouchMode = true
        val button = view as? MaterialButton
        val restingButtonTint = button?.backgroundTintList
        val restingButtonStroke = button?.strokeColor
        val restingButtonStrokeWidth = button?.strokeWidth ?: 0
        val restingButtonText = button?.textColors
        val restingButtonIcon = button?.iconTint
        view.setOnFocusChangeListener { focusedView, hasFocus ->
            (focusedView as? MaterialCardView)?.let { card ->
                card.strokeWidth = focusedView.resources.getDimensionPixelSize(
                    if (hasFocus) R.dimen.tv_focus_stroke else R.dimen.tv_resting_stroke
                )
                card.strokeColor = ContextCompat.getColor(
                    focusedView.context,
                    if (hasFocus) R.color.tv_focus_ring else R.color.tv_card_stroke
                )
                card.cardElevation = focusedView.resources.getDimension(
                    if (hasFocus) R.dimen.tv_focus_elevation else R.dimen.tv_resting_elevation
                )
                card.setCardBackgroundColor(ContextCompat.getColor(
                    focusedView.context,
                    if (hasFocus) R.color.tv_focus_surface else R.color.tv_card_background
                ))
            }

            button?.let {
                if (hasFocus) {
                    it.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(focusedView.context, R.color.tv_focus_surface)
                    )
                    it.strokeColor = ColorStateList.valueOf(
                        ContextCompat.getColor(focusedView.context, R.color.tv_focus_ring)
                    )
                    it.strokeWidth = focusedView.resources.getDimensionPixelSize(R.dimen.tv_focus_stroke)
                    val focusContent = ContextCompat.getColor(
                        focusedView.context, R.color.tv_focus_content
                    )
                    it.setTextColor(focusContent)
                    it.iconTint = ColorStateList.valueOf(focusContent)
                } else {
                    it.backgroundTintList = restingButtonTint
                    it.strokeColor = restingButtonStroke
                    it.strokeWidth = restingButtonStrokeWidth
                    it.setTextColor(restingButtonText)
                    it.iconTint = restingButtonIcon
                }
            }
        }
    }

    /** Applies bounded focus colors to controls without changing their measured size. */
    fun installFocusableChildren(root: View) {
        if (!DeviceProfile.isTv(root.context)) return
        if (root.isFocusable) install(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) installFocusableChildren(root.getChildAt(index))
        }
    }

    fun stableId(value: String): Long {
        var result = -0x340d631b7bdddcdbL
        value.forEach { result = (result xor it.code.toLong()) * 0x100000001b3L }
        return result
    }
}

package com.vinstall.alwiz.util

import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.vinstall.alwiz.R

/** Small, view-system-only focus treatment shared by TV RecyclerView rows. */
object TvFocus {
    fun install(view: View) {
        if (!DeviceProfile.isTv(view.context)) return

        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnFocusChangeListener { focusedView, hasFocus ->
            focusedView.animate()
                .scaleX(if (hasFocus) 1.025f else 1f)
                .scaleY(if (hasFocus) 1.025f else 1f)
                .setDuration(120L)
                .start()

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
            }
        }
    }

    fun stableId(value: String): Long {
        var result = -0x340d631b7bdddcdbL
        value.forEach { result = (result xor it.code.toLong()) * 0x100000001b3L }
        return result
    }
}

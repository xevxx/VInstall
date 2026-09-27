package com.vinstall.alwiz

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import com.google.android.material.card.MaterialCardView
import com.vinstall.alwiz.appmanager.AppManagerActivity
import com.vinstall.alwiz.backup.BackupActivity
import com.vinstall.alwiz.databinding.ActivityTvHomeBinding
import com.vinstall.alwiz.history.InstallHistoryActivity
import com.vinstall.alwiz.settings.SettingsActivity
import com.vinstall.alwiz.transfer.ReceiveActivity

class TvHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val tiles = listOf(
            Tile(R.string.tv_install_title, R.string.tv_install_desc, MainActivity::class.java),
            Tile(R.string.tv_receive_title, R.string.tv_receive_desc, ReceiveActivity::class.java),
            Tile(R.string.tv_apps_title, R.string.tv_apps_desc, AppManagerActivity::class.java),
            Tile(R.string.tv_export_title, R.string.tv_export_desc, BackupActivity::class.java),
            Tile(R.string.tv_history_title, R.string.tv_history_desc, InstallHistoryActivity::class.java),
            Tile(R.string.tv_settings_title, R.string.tv_settings_desc, SettingsActivity::class.java),
            Tile(R.string.tv_debug_title, R.string.tv_debug_desc, DebugWindowActivity::class.java),
        )

        val cards = tiles.map { createCard(it) }
        cards.chunked(2).forEach { rowCards ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
            }
            rowCards.forEach { row.addView(it) }
            if (rowCards.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            binding.dashboardContainer.addView(row)
        }
        configureFocus(cards)
        cards.firstOrNull()?.requestFocus()
    }

    private fun createCard(tile: Tile): MaterialCardView {
        val margin = dp(8)
        val params = LinearLayout.LayoutParams(0, dp(148), 1f).apply {
            setMargins(margin, margin, margin, margin)
        }
        return MaterialCardView(this).apply {
            id = View.generateViewId()
            layoutParams = params
            radius = dp(16).toFloat()
            cardElevation = dp(2).toFloat()
            strokeWidth = dp(2)
            isClickable = true
            isFocusable = true
            foreground = getDrawable(R.drawable.tv_card_foreground)
            setCardBackgroundColor(ContextCompat.getColor(this@TvHomeActivity, R.color.tv_card_background))
            setStrokeColor(ContextCompat.getColor(this@TvHomeActivity, R.color.tv_card_stroke_idle))
            addView(LinearLayout(this@TvHomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24))
                addView(TextView(this@TvHomeActivity).apply {
                    text = getString(tile.title)
                    textSize = 22f
                    setTextColor(ContextCompat.getColor(this@TvHomeActivity, R.color.on_surface))
                })
                addView(TextView(this@TvHomeActivity).apply {
                    text = getString(tile.description)
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(this@TvHomeActivity, R.color.on_surface_variant))
                    setPadding(0, dp(8), 0, 0)
                    maxLines = 2
                })
            })
            setOnClickListener { startActivity(Intent(this@TvHomeActivity, tile.destination)) }
            setOnFocusChangeListener { view, focused ->
                val card = view as MaterialCardView
                card.setStrokeColor(ContextCompat.getColor(
                    this@TvHomeActivity,
                    if (focused) R.color.primary else R.color.tv_card_stroke_idle
                ))
                card.animate()
                    .scaleX(if (focused) 1.045f else 1f)
                    .scaleY(if (focused) 1.045f else 1f)
                    .translationZ(if (focused) dp(8).toFloat() else 0f)
                    .setDuration(120L)
                    .start()
            }
        }
    }

    private fun configureFocus(cards: List<View>) {
        cards.forEachIndexed { index, card ->
            val left = if (index % 2 == 1) index - 1 else index
            val right = if (index % 2 == 0 && index + 1 < cards.size) index + 1 else index
            val up = (index - 2).coerceAtLeast(0)
            val down = (index + 2).coerceAtMost(cards.lastIndex)
            card.nextFocusLeftId = cards[left].id
            card.nextFocusRightId = cards[right].id
            card.nextFocusUpId = cards[up].id
            card.nextFocusDownId = cards[down].id
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Tile(
        val title: Int,
        val description: Int,
        val destination: Class<*>,
    )
}

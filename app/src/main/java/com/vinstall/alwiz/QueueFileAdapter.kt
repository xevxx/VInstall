package com.vinstall.alwiz

import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.vinstall.alwiz.util.DeviceProfile
import com.vinstall.alwiz.util.TvFocus
import java.util.Collections

data class QueueItem(
    val uri: Uri,
    val displayName: String,
    val formatLabel: String,
    val fileSize: Long = 0L,
    val appLabel: String = "",
    val packageName: String = "",
    val versionName: String = "",
    val versionCode: Long = 0L,
    val appIcon: Bitmap? = null,
    val sha256: String = "",
    val isEncryptedApkv: Boolean = false,
    val apkvPassword: String? = null
)

class QueueFileAdapter(
    private val items: MutableList<QueueItem>,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onItemClick: ((QueueItem) -> Unit)? = null,
    private val onQueueChanged: ((List<QueueItem>) -> Unit)? = null,
    private val onItemRemoved: ((Uri) -> Unit)? = null,
) : RecyclerView.Adapter<QueueFileAdapter.ViewHolder>() {

    private val removedUris = mutableSetOf<Uri>()

    init {
        setHasStableIds(true)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textNumber: TextView = itemView.findViewById(R.id.text_queue_number)
        val imageIcon: ImageView = itemView.findViewById(R.id.image_queue_icon)
        val textLabel: TextView = itemView.findViewById(R.id.text_queue_label)
        val textMeta: TextView = itemView.findViewById(R.id.text_queue_meta)
        val textHash: TextView = itemView.findViewById(R.id.text_queue_hash)
        val iconDrag: ImageView = itemView.findViewById(R.id.icon_drag_handle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_file, parent, false)
        TvFocus.install(view)
        return ViewHolder(view)
    }

    override fun getItemId(position: Int): Long = TvFocus.stableId(items[position].uri.toString())

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.textNumber.text = (position + 1).toString()

        if (item.appIcon != null) {
            holder.imageIcon.setImageBitmap(item.appIcon)
        } else {
            holder.imageIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        holder.textLabel.text = item.appLabel.ifEmpty { item.displayName }

        val metaParts = mutableListOf<String>()
        if (item.packageName.isNotEmpty()) metaParts += item.packageName
        if (item.versionName.isNotEmpty()) metaParts += "v${item.versionName}"
        metaParts += item.formatLabel
        if (item.fileSize > 0) metaParts += formatSize(item.fileSize)
        holder.textMeta.text = metaParts.joinToString(" · ")

        if (item.isEncryptedApkv) {
            val errorColor = MaterialColors.getColor(
                holder.itemView,
                com.google.android.material.R.attr.colorErrorContainer
            )
            holder.itemView.setBackgroundColor(errorColor)
            holder.textHash.isVisible = true
            holder.textHash.text = holder.itemView.context.getString(R.string.apkv_tap_to_unlock)
            holder.itemView.setOnClickListener { onItemClick?.invoke(item) }
        } else {
            holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            holder.itemView.setOnClickListener(null)

            when {
                item.sha256.isNotEmpty() -> {
                    holder.textHash.isVisible = true
                    holder.textHash.text = "SHA-256: ${item.sha256.take(20)}…"
                }
                item.packageName.isNotEmpty() -> {
                    holder.textHash.isVisible = true
                    holder.textHash.text = "SHA-256: computing…"
                }
                else -> {
                    holder.textHash.isVisible = false
                }
            }
        }

        if (DeviceProfile.isTv(holder.itemView.context)) {
            holder.iconDrag.isVisible = false
            holder.iconDrag.setOnTouchListener(null)
            holder.itemView.findViewWithTag<View>("queue_move_up")?.apply {
                isEnabled = position > 0
                setOnClickListener { moveFromHolder(holder, -1, it) }
            }
            holder.itemView.findViewWithTag<View>("queue_move_down")?.apply {
                isEnabled = position < items.lastIndex
                setOnClickListener { moveFromHolder(holder, 1, it) }
            }
            holder.itemView.findViewWithTag<View>("queue_remove")?.setOnClickListener {
                removeFromHolder(holder)
            }
        } else {
            holder.iconDrag.isVisible = true
            holder.iconDrag.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
                false
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun onItemMoved(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
        if (from < to) {
            for (i in from until to) Collections.swap(items, i, i + 1)
        } else {
            for (i in from downTo to + 1) Collections.swap(items, i, i - 1)
        }
        notifyItemMoved(from, to)
        notifyItemRangeChanged(minOf(from, to), kotlin.math.abs(from - to) + 1)
        onQueueChanged?.invoke(items.toList())
    }

    fun getOrderedUris(): List<Uri> = items.map { it.uri }

    fun getOrderedItems(): List<QueueItem> = items.toList()

    fun setItems(newItems: List<QueueItem>) {
        val incoming = newItems.filterNot { it.uri in removedUris }.associateBy { it.uri }
        val merged = items.mapNotNull { incoming[it.uri] }.toMutableList()
        val retained = merged.mapTo(HashSet()) { it.uri }
        newItems.forEach { item ->
            if (item.uri !in removedUris && retained.add(item.uri)) merged += item
        }
        items.clear()
        items.addAll(merged)
        notifyDataSetChanged()
    }

    fun resetForNewQueue() {
        removedUris.clear()
        items.clear()
        notifyDataSetChanged()
    }

    fun updateItem(updated: QueueItem) {
        val idx = items.indexOfFirst { it.uri == updated.uri }
        if (idx >= 0) {
            items[idx] = updated
            notifyItemChanged(idx)
        }
    }

    private fun moveFromHolder(holder: ViewHolder, offset: Int, focusedControl: View) {
        val from = holder.bindingAdapterPosition
        val to = from + offset
        if (from == RecyclerView.NO_POSITION || to !in items.indices) return
        onItemMoved(from, to)
        focusedControl.post { focusedControl.requestFocus() }
    }

    private fun removeFromHolder(holder: ViewHolder) {
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        removeAt(position)
    }

    internal fun removeAt(position: Int) {
        if (position !in items.indices) return
        removedUris += items[position].uri
        val removed = items.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, items.size - position)
        onItemRemoved?.invoke(removed.uri)
        onQueueChanged?.invoke(items.toList())
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.2f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%.2f KB".format(bytes / 1_024.0)
        else -> "$bytes B"
    }
}

class QueueDragCallback(private val adapter: QueueFileAdapter) : ItemTouchHelper.Callback() {

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
        makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val from = viewHolder.adapterPosition
        val to = target.adapterPosition
        if (from < 0 || to < 0) return false
        adapter.onItemMoved(from, to)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

    override fun isLongPressDragEnabled(): Boolean = false
}

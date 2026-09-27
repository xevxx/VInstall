package com.vinstall.alwiz.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.vinstall.alwiz.databinding.ItemHistoryBinding
import com.vinstall.alwiz.model.HistoryStatus
import com.vinstall.alwiz.model.InstallHistoryEntry
import com.vinstall.alwiz.util.TvFocus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InstallHistoryAdapter(
    private val entries: MutableList<InstallHistoryEntry>,
    private val onItemClick: (InstallHistoryEntry) -> Unit,
    private val onDeleteClick: (InstallHistoryEntry, Int) -> Unit
) : RecyclerView.Adapter<InstallHistoryAdapter.ViewHolder>() {

    init {
        setHasStableIds(true)
    }

    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    inner class ViewHolder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        TvFocus.install(binding.root)
        TvFocus.install(binding.btnDelete)
        return ViewHolder(binding)
    }

    override fun getItemId(position: Int): Long = TvFocus.stableId(entries[position].id)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val ctx = holder.binding.root.context

        holder.binding.textAppLabel.text = entry.appLabel.ifEmpty { entry.packageName }
        holder.binding.textPackageName.text = entry.packageName.ifEmpty { entry.format }
        holder.binding.textDate.text = dateFormat.format(Date(entry.timestamp))

        val versionText = buildString {
            if (entry.versionName.isNotEmpty()) append("v${entry.versionName}  ·  ")
            append(entry.format)
        }
        holder.binding.textVersionFormat.text = versionText

        val (statusLabel, colorAttr) = when (entry.status) {
            HistoryStatus.SUCCESS -> ctx.getString(com.vinstall.alwiz.R.string.history_status_success) to
                androidx.appcompat.R.attr.colorPrimary
            HistoryStatus.FAILED -> ctx.getString(com.vinstall.alwiz.R.string.history_status_failed) to
                androidx.appcompat.R.attr.colorError
            HistoryStatus.CANCELLED -> ctx.getString(com.vinstall.alwiz.R.string.history_status_cancelled) to
                androidx.appcompat.R.attr.colorAccent
        }

        holder.binding.textStatus.text = statusLabel

        val color = MaterialColors.getColor(holder.binding.root, colorAttr)
        holder.binding.textStatus.setTextColor(color)
        holder.binding.statusIndicator.setBackgroundColor(color)

        holder.binding.root.setOnClickListener { onItemClick(entry) }
        holder.binding.btnDelete.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition != RecyclerView.NO_POSITION) onDeleteClick(entry, currentPosition)
        }
    }

    override fun getItemCount(): Int = entries.size

    fun removeAt(position: Int) {
        entries.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, entries.size - position)
    }
}

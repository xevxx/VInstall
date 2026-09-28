package com.vinstall.alwiz

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.vinstall.alwiz.util.TvFocus
import java.io.File

internal class TvFileAdapter(
    private val isSelected: (TvBrowserItem) -> Boolean,
    private val onClick: (TvBrowserItem) -> Unit,
) : RecyclerView.Adapter<TvFileAdapter.Holder>() {
    private val items = mutableListOf<TvBrowserItem>()

    init {
        setHasStableIds(true)
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val type: TextView = view.findViewById(R.id.type_badge)
        val name: TextView = view.findViewById(R.id.name_text)
        val detail: TextView = view.findViewById(R.id.detail_text)
        val selected: TextView = view.findViewById(R.id.selected_badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tv_file, parent, false)
        TvFocus.install(view)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.type.text = holder.itemView.context.getString(
            if (item.kind == TvBrowserItemKind.PACKAGE) {
                R.string.tv_picker_package_badge
            } else {
                R.string.tv_picker_folder_badge
            },
        )
        holder.name.text = item.label
        holder.detail.text = item.detail
        holder.selected.isVisible = item.kind == TvBrowserItemKind.PACKAGE && isSelected(item)
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = TvFocus.stableId(
        "${items[position].kind}:${items[position].file.path}",
    )

    fun submit(newItems: List<TvBrowserItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun notifySelectionChanged(file: File) {
        val index = items.indexOfFirst { it.file == file }
        if (index >= 0) notifyItemChanged(index)
    }
}

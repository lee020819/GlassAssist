package com.example.glassassist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class FeatureItem(val iconRes: Int, val label: String, val type: String)

class FeatureGridAdapter(
    private val items: List<FeatureItem>,
    private val onClick: (FeatureItem) -> Unit
) : RecyclerView.Adapter<FeatureGridAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.tv_feature_icon)
        val label: TextView = view.findViewById(R.id.tv_feature_label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_grid_feature, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.icon.setImageResource(item.iconRes)
        holder.label.text = item.label
        if (item.type == "empty") {
            holder.itemView.visibility = View.INVISIBLE
            holder.itemView.isClickable = false
        } else {
            holder.itemView.visibility = View.VISIBLE
            holder.itemView.setOnClickListener { onClick(item) }
        }
    }
}

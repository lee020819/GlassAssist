package com.example.glassassist

import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class NewsItem(val title: String, val date: String, val link: String = "")

class NewsAdapter(
    private val items: MutableList<NewsItem>,
    private val onItemClick: ((NewsItem) -> Unit)? = null
) : RecyclerView.Adapter<NewsAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tv_news_title)
        val date: TextView = view.findViewById(R.id.tv_news_date)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_news, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = Html.fromHtml(item.title, Html.FROM_HTML_MODE_LEGACY).toString()
        holder.date.text = item.date
        holder.itemView.setOnClickListener { onItemClick?.invoke(item) }
    }

    fun update(newItems: List<NewsItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}

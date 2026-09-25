package com.zz213119.virtualclicker.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.zz213119.virtualclicker.R

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val isSystem: Boolean
)

class AppListAdapter(
    private val onAppClicked: (AppEntry) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private var items: List<AppEntry> = emptyList()
    private var selectedPackage: String? = null

    fun submitList(newItems: List<AppEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setSelected(packageName: String?) {
        selectedPackage = packageName
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        holder.icon.setImageDrawable(entry.icon)
        holder.name.text = entry.label
        holder.pkg.text = entry.packageName
        holder.checkbox.isChecked = entry.packageName == selectedPackage
        holder.itemView.setOnClickListener { onAppClicked(entry) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val pkg: TextView = view.findViewById(R.id.appPackage)
        val checkbox: CheckBox = view.findViewById(R.id.appCheckbox)
    }
}

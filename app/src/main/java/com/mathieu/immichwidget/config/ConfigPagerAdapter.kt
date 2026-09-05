package com.mathieu.immichwidget.config

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter volontairement simple (pas de Fragments) : chaque page est une vue
 * inflatée directement, et le câblage (findViewById, listeners) est délégué
 * à l'Activity via [onBind]. Avec seulement 3 pages et un offscreenPageLimit
 * couvrant les 3, chaque page n'est bindée qu'une fois et reste attachée —
 * pas besoin de gérer un cycle de vie de Fragment pour ce cas d'usage.
 */
class ConfigPagerAdapter(
    private val layoutResIds: List<Int>,
    private val onBind: (position: Int, view: View) -> Unit
) : RecyclerView.Adapter<ConfigPagerAdapter.PageViewHolder>() {

    class PageViewHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(layoutResIds[viewType], parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        onBind(position, holder.itemView)
    }

    override fun getItemCount(): Int = layoutResIds.size
}

package com.mathieu.immichwidget.config

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mathieu.immichwidget.R
import com.mathieu.immichwidget.api.ImmichAlbum

class AlbumListAdapter(
    private val onAlbumSelected: (ImmichAlbum) -> Unit
) : RecyclerView.Adapter<AlbumListAdapter.AlbumViewHolder>() {

    private var albums: List<ImmichAlbum> = emptyList()
    private var selectedAlbumId: String? = null

    fun submitList(newAlbums: List<ImmichAlbum>, currentSelectedId: String?) {
        albums = newAlbums
        selectedAlbumId = currentSelectedId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): AlbumViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_album, parent, false)
        return AlbumViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        val album = albums[position]
        holder.bind(album, album.id == selectedAlbumId)
        holder.itemView.setOnClickListener {
            selectedAlbumId = album.id
            notifyDataSetChanged()
            onAlbumSelected(album)
        }
    }

    override fun getItemCount(): Int = albums.size

    class AlbumViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textName: TextView = itemView.findViewById(R.id.text_album_name)
        private val textCount: TextView = itemView.findViewById(R.id.text_album_count)
        private val radio: android.widget.RadioButton = itemView.findViewById(R.id.radio_selected)

        fun bind(album: ImmichAlbum, isSelected: Boolean) {
            textName.text = album.albumName
            textCount.text = "${album.assetCount} photo(s)"
            radio.isChecked = isSelected
        }
    }
}

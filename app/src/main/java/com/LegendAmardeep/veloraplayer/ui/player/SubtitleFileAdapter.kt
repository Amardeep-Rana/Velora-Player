package com.LegendAmardeep.veloraplayer.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.LegendAmardeep.veloraplayer.R

class SubtitleFileAdapter(
    private val onFileClick: (DocumentFile) -> Unit
) : ListAdapter<DocumentFile, SubtitleFileAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track_selection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = getItem(position)
        holder.bind(file)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val rbTrack = view.findViewById<RadioButton>(R.id.rbTrack)

        fun bind(file: DocumentFile) {
            rbTrack.text = file.name
            rbTrack.isChecked = false
            itemView.setOnClickListener { onFileClick(file) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DocumentFile>() {
        override fun areItemsTheSame(oldItem: DocumentFile, newItem: DocumentFile): Boolean = oldItem.uri == newItem.uri
        override fun areContentsTheSame(oldItem: DocumentFile, newItem: DocumentFile): Boolean = oldItem.name == newItem.name
    }
}

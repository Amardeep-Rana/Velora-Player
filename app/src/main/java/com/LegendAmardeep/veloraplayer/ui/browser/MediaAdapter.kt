package com.LegendAmardeep.veloraplayer.ui.browser

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.LegendAmardeep.veloraplayer.data.model.Folder
import com.LegendAmardeep.veloraplayer.data.model.MediaFile
import com.LegendAmardeep.veloraplayer.databinding.ItemFolderBinding
import com.LegendAmardeep.veloraplayer.databinding.ItemMediaBinding
import com.LegendAmardeep.veloraplayer.databinding.ItemMediaGridBinding
import com.bumptech.glide.Glide

class MediaAdapter(
    private val onMediaClick: (MediaFile) -> Unit,
    private val onFolderClick: (Folder) -> Unit
) : ListAdapter<Any, RecyclerView.ViewHolder>(DiffCallback) {

    var isGridView = false

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_MEDIA_LIST = 1
        private const val TYPE_MEDIA_GRID = 2

        private val DiffCallback = object : DiffUtil.ItemCallback<Any>() {
            override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean {
                return if (oldItem is Folder && newItem is Folder) {
                    oldItem.path == newItem.path
                } else if (oldItem is MediaFile && newItem is MediaFile) {
                    oldItem.id == newItem.id
                } else {
                    false
                }
            }

            override fun areContentsTheSame(oldItem: Any, newItem: Any): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return when {
            item is Folder -> TYPE_FOLDER
            isGridView -> TYPE_MEDIA_GRID
            else -> TYPE_MEDIA_LIST
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_FOLDER -> FolderViewHolder(ItemFolderBinding.inflate(inflater, parent, false))
            TYPE_MEDIA_GRID -> MediaGridViewHolder(ItemMediaGridBinding.inflate(inflater, parent, false))
            else -> MediaListViewHolder(ItemMediaBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is FolderViewHolder -> if (item is Folder) holder.bind(item)
            is MediaGridViewHolder -> if (item is MediaFile) holder.bind(item)
            is MediaListViewHolder -> if (item is MediaFile) holder.bind(item)
        }
    }

    inner class FolderViewHolder(private val binding: ItemFolderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(folder: Folder) {
            binding.folderName.text = folder.name
            binding.folderInfo.text = "${folder.mediaFiles.size} items"
            binding.root.setOnClickListener { onFolderClick(folder) }
        }
    }

    inner class MediaListViewHolder(private val binding: ItemMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaFile) {
            binding.fileName.text = item.name
            val sizeStr = Formatter.formatFileSize(binding.root.context, item.size)
            val durationStr = formatDuration(item.duration)
            binding.fileInfo.text = "$durationStr | $sizeStr"
            
            Glide.with(binding.thumbnail)
                .load(item.contentUri)
                .centerCrop()
                .placeholder(android.R.drawable.ic_media_play)
                .into(binding.thumbnail)

            binding.root.setOnClickListener { onMediaClick(item) }
        }
    }

    inner class MediaGridViewHolder(private val binding: ItemMediaGridBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MediaFile) {
            binding.fileName.text = item.name
            val sizeStr = Formatter.formatFileSize(binding.root.context, item.size)
            val durationStr = formatDuration(item.duration)
            binding.fileInfo.text = "$durationStr | $sizeStr"
            
            Glide.with(binding.thumbnail)
                .load(item.contentUri)
                .centerCrop()
                .placeholder(android.R.drawable.ic_media_play)
                .into(binding.thumbnail)

            binding.root.setOnClickListener { onMediaClick(item) }
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = durationMs / (1000 * 60 * 60)
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
        else String.format("%02d:%02d", minutes, seconds)
    }
}
package com.LegendAmardeep.veloraplayer.ui.player

import android.graphics.Color
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.LegendAmardeep.veloraplayer.R
import com.LegendAmardeep.veloraplayer.data.model.MediaFile
import com.LegendAmardeep.veloraplayer.databinding.ItemPlaylistVideoBinding
import com.bumptech.glide.Glide
import java.util.Collections

class PlaylistAdapter(
    private val onVideoClick: (MediaFile) -> Unit,
    private val onRemoveClick: (MediaFile) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onPlaylistReordered: (List<MediaFile>) -> Unit
) : ListAdapter<MediaFile, PlaylistAdapter.PlaylistViewHolder>(DiffCallback()) {

    private var currentPlayingId: Long = -1

    fun setCurrentPlayingId(id: Long) {
        val oldId = currentPlayingId
        currentPlayingId = id
        val oldPos = currentList.indexOfFirst { it.id == oldId }
        val newPos = currentList.indexOfFirst { it.id == id }
        if (oldPos != -1) notifyItemChanged(oldPos)
        if (newPos != -1) notifyItemChanged(newPos)
    }

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        val newList = currentList.toMutableList()
        Collections.swap(newList, fromPosition, toPosition)
        submitList(newList) {
            onPlaylistReordered(newList)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val binding = ItemPlaylistVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlaylistViewHolder(private val binding: ItemPlaylistVideoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(mediaFile: MediaFile) {
            binding.tvVideoTitle.text = mediaFile.name
            binding.tvDuration.text = formatDuration(mediaFile.duration)
            
            binding.tvVideoSize.text = Formatter.formatShortFileSize(binding.root.context, mediaFile.size)
            
            Glide.with(binding.ivThumbnail)
                .load(mediaFile.contentUri)
                .placeholder(R.drawable.ic_play_solid)
                .into(binding.ivThumbnail)

            val isPlaying = mediaFile.id == currentPlayingId
            
            if (isPlaying) {
                binding.rootLayout.setBackgroundColor(Color.parseColor("#66D0BCFF"))
                binding.tvVideoTitle.setTextColor(Color.WHITE)
                binding.tvVideoSize.setTextColor(Color.WHITE)
                binding.ivNowPlaying.visibility = View.VISIBLE
                binding.tvDuration.visibility = View.GONE
                binding.ivNowPlaying.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            } else {
                binding.rootLayout.background = ContextCompat.getDrawable(binding.root.context, android.R.color.transparent)
                binding.tvVideoTitle.setTextColor(Color.WHITE)
                binding.tvVideoSize.setTextColor(Color.parseColor("#B3FFFFFF"))
                binding.ivNowPlaying.visibility = View.GONE
                binding.tvDuration.visibility = View.VISIBLE
            }

            binding.rootLayout.setOnClickListener { onVideoClick(mediaFile) }
            binding.btnRemove.setOnClickListener { onRemoveClick(mediaFile) }
            
            binding.ivReorder.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                }
                false
            }
        }

        private fun formatDuration(ms: Long): String {
            val s = (ms / 1000) % 60; val m = (ms / 60000) % 60; val h = ms / 3600000
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<MediaFile>() {
        override fun areItemsTheSame(oldItem: MediaFile, newItem: MediaFile) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: MediaFile, newItem: MediaFile) = oldItem == newItem
    }
}

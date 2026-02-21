package com.LegendAmardeep.veloraplayer.ui.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.LegendAmardeep.veloraplayer.databinding.ItemSubtitleTrackBinding

class SubtitleTrackAdapter(
    private val onTrackSelected: (PlayerTrack) -> Unit
) : ListAdapter<PlayerTrack, SubtitleTrackAdapter.SubtitleViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubtitleViewHolder {
        val binding = ItemSubtitleTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SubtitleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SubtitleViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SubtitleViewHolder(private val binding: ItemSubtitleTrackBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(track: PlayerTrack) {
            // Display name with format extension if available
            val displayName = if (track.format != null) {
                "${track.name} - ${track.format}"
            } else {
                track.name
            }
            
            binding.tvSubtitleName.text = displayName
            binding.rbSubtitle.isChecked = track.isSelected
            
            binding.root.setOnClickListener {
                onTrackSelected(track)
            }
            
            binding.rbSubtitle.setOnClickListener {
                onTrackSelected(track)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<PlayerTrack>() {
        override fun areItemsTheSame(oldItem: PlayerTrack, newItem: PlayerTrack) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: PlayerTrack, newItem: PlayerTrack) = oldItem == newItem
    }
}

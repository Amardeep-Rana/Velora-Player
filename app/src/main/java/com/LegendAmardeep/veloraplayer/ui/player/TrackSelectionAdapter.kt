package com.LegendAmardeep.veloraplayer.ui.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.LegendAmardeep.veloraplayer.databinding.ItemTrackSelectionBinding

class TrackSelectionAdapter(
    private val onTrackSelected: (PlayerTrack) -> Unit
) : ListAdapter<PlayerTrack, TrackSelectionAdapter.TrackViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val binding = ItemTrackSelectionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrackViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TrackViewHolder(private val binding: ItemTrackSelectionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(track: PlayerTrack) {
            // Extension name ke saath dikhayenge agar hai (Audio ke liye usually null hoga)
            val displayName = if (track.format != null) {
                "${track.name} - ${track.format}"
            } else {
                track.name
            }
            binding.rbTrack.text = displayName
            binding.rbTrack.isChecked = track.isSelected
            
            binding.root.setOnClickListener {
                onTrackSelected(track)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<PlayerTrack>() {
        override fun areItemsTheSame(oldItem: PlayerTrack, newItem: PlayerTrack) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: PlayerTrack, newItem: PlayerTrack) = oldItem == newItem
    }
}

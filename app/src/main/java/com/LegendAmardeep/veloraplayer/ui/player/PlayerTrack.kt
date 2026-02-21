package com.LegendAmardeep.veloraplayer.ui.player

data class PlayerTrack(
    val id: String,
    val name: String,
    val isSelected: Boolean,
    val index: Int,
    val format: String? = null // Subtitle format (e.g., "srt", "ass")
)

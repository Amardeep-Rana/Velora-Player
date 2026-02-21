package com.LegendAmardeep.veloraplayer.player.model

enum class DecoderMode {
    HW,    // MediaCodec default
    HW_PLUS, // MediaCodec with custom configuration
    SW     // FFmpeg/Software fallback
}
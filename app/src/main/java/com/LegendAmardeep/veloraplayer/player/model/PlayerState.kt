package com.LegendAmardeep.veloraplayer.player.model

enum class PlayerState {
    IDLE,
    PREPARING,
    BUFFERING,
    READY,
    PLAYING,
    PAUSED,
    SEEKING,
    RECOVERING,
    RELEASED
}
package com.LegendAmardeep.veloraplayer.player.decoder

import android.content.Context
import com.LegendAmardeep.veloraplayer.player.model.DecoderMode

class DecoderManager(private val context: Context) {
    
    fun getPreferredMode(mediaId: String): DecoderMode {
        val prefs = context.getSharedPreferences("decoder_prefs", Context.MODE_PRIVATE)
        val modeName = prefs.getString("mode_$mediaId", DecoderMode.HW.name)
        return DecoderMode.valueOf(modeName!!)
    }

    fun markCodecFailed(mediaId: String, mode: DecoderMode) {
        val nextMode = when (mode) {
            DecoderMode.HW -> DecoderMode.HW_PLUS
            DecoderMode.HW_PLUS -> DecoderMode.SW
            DecoderMode.SW -> DecoderMode.SW // Already at lowest
        }
        savePreferredMode(mediaId, nextMode)
    }

    fun savePreferredMode(mediaId: String, mode: DecoderMode) {
        val prefs = context.getSharedPreferences("decoder_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("mode_$mediaId", mode.name).apply()
    }
}
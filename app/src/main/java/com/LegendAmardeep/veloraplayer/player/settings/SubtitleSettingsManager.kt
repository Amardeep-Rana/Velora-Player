package com.LegendAmardeep.veloraplayer.player.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import androidx.core.content.res.ResourcesCompat
import com.LegendAmardeep.veloraplayer.R

class SubtitleSettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("subtitle_prefs", Context.MODE_PRIVATE)

    var prefix: String = "exo_"

    var alignment: String
        get() = prefs.getString("${prefix}alignment", "Center") ?: "Center"
        set(value) = prefs.edit().putString("${prefix}alignment", value).apply()

    var font: String
        get() = prefs.getString("${prefix}font", "Default") ?: "Default"
        set(value) = prefs.edit().putString("${prefix}font", value).apply()

    var bottomMargin: Int
        get() = prefs.getInt("${prefix}bottom_margin", if (prefix == "exo_") 10 else -8)
        set(value) = prefs.edit().putInt("${prefix}bottom_margin", value).apply()

    var textSize: Int
        get() = prefs.getInt("${prefix}text_size", 20)
        set(value) = prefs.edit().putInt("${prefix}text_size", value).apply()

    var textScale: Int
        get() = prefs.getInt("${prefix}text_scale", 100)
        set(value) = prefs.edit().putInt("${prefix}text_scale", value).apply()

    var textColor: Int
        get() = prefs.getInt("${prefix}text_color", Color.WHITE)
        set(value) = prefs.edit().putInt("${prefix}text_color", value).apply()

    var backgroundColor: Int
        get() = prefs.getInt("${prefix}background_color", Color.BLACK)
        set(value) = prefs.edit().putInt("${prefix}background_color", value).apply()

    var backgroundAlpha: Int
        get() = prefs.getInt("${prefix}background_alpha", 255)
        set(value) = prefs.edit().putInt("${prefix}background_alpha", value).apply()

    var isBold: Boolean
        get() = prefs.getBoolean("${prefix}is_bold", true)
        set(value) = prefs.edit().putBoolean("${prefix}is_bold", value).apply()

    var isShadowEnabled: Boolean
        get() = prefs.getBoolean("${prefix}is_shadow_enabled", true)
        set(value) = prefs.edit().putBoolean("${prefix}is_shadow_enabled", value).apply()

    var isFadeOutEnabled: Boolean
        get() = prefs.getBoolean("${prefix}is_fade_out_enabled", true)
        set(value) = prefs.edit().putBoolean("${prefix}is_fade_out_enabled", value).apply()

    var isBorderEnabled: Boolean
        get() = prefs.getBoolean("${prefix}is_border_enabled", false)
        set(value) = prefs.edit().putBoolean("${prefix}is_border_enabled", value).apply()

    var borderWidth: Int
        get() = prefs.getInt("${prefix}border_width", 2)
        set(value) = prefs.edit().putInt("${prefix}border_width", value).apply()

    var isBackgroundEnabled: Boolean
        get() = prefs.getBoolean("${prefix}is_background_enabled", false)
        set(value) = prefs.edit().putBoolean("${prefix}is_background_enabled", value).apply()

    fun getTypeface(context: Context): Typeface? {
        return when (font) {        "Roboto" -> Typeface.SANS_SERIF
            "Oswald" -> ResourcesCompat.getFont(context, R.font.oswald) // oswald.ttf file name ke hisaab se check karein
            "Serif" -> Typeface.SERIF
            "SN-Pro" -> ResourcesCompat.getFont(context, R.font.sn_pro) // sn_pro.ttf ke hisaab se
            else -> null
        }
    }

    fun getVlcFont(): String {
        return when (font) {
            "Roboto" -> "sans-serif"
            "Oswald" -> "sans-serif-condensed"
            "Serif" -> "serif"
            "SN-Pro" -> "monospace"
            else -> ""
        }
    }

    fun getVlcAlignment(): Int {
        return when (alignment) {
            "Left" -> 9
            "Right" -> 10
            else -> 8
        }
    }

    fun getExoGravity(): Int {
        return when (alignment) {
            "Left" -> Gravity.START or Gravity.BOTTOM
            "Right" -> Gravity.END or Gravity.BOTTOM
            else -> Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        }
    }
}
package com.LegendAmardeep.veloraplayer.data.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class MediaFile(
    val id: Long,
    val name: String,
    val path: String,
    val contentUri: Uri,
    val duration: Long,
    val isVideo: Boolean,
    val size: Long = 0,
    val resolution: String? = null,
    val folder: String = path.substringBeforeLast("/", "Internal Storage")
) : Parcelable
package com.LegendAmardeep.veloraplayer.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Folder(
    val name: String,
    val path: String,
    val mediaFiles: List<MediaFile> = emptyList()
) : Parcelable
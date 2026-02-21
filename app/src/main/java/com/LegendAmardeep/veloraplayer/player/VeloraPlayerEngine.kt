package com.LegendAmardeep.veloraplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.LegendAmardeep.veloraplayer.player.decoder.DecoderManager
import com.LegendAmardeep.veloraplayer.player.model.DecoderMode
import com.LegendAmardeep.veloraplayer.player.model.PlayerState
import com.LegendAmardeep.veloraplayer.player.settings.SubtitleSettingsManager
import com.LegendAmardeep.veloraplayer.ui.player.PlayerTrack
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.io.FileOutputStream
import com.LegendAmardeep.veloraplayer.R

@OptIn(UnstableApi::class)
class VeloraPlayerEngine(
    private val context: Context,
    private val decoderManager: DecoderManager
) : IVLCVout.Callback {
    private var exoPlayer: ExoPlayer? = null
    private var vlcPlayer: MediaPlayer? = null
    private var libVLC: LibVLC? = null
    private var vlcFD: ParcelFileDescriptor? = null
    
    private var vlcVideoLayout: VLCVideoLayout? = null
    private var currentState = PlayerState.IDLE
    private var currentDecoderMode = DecoderMode.HW
    private var currentUri: Uri? = null
    private var currentMediaId: String? = null
    private var isVlcParsingFinished = false
    private var surfacesReady = false
    private var isMuted = false
    private var lastVolume = 100
    private var isHardDisabled = false
    private var pendingSeek: Long = -1L
    
    private var audioDelay: Long = 0
    private var subtitleDelay: Long = 0
    private var isAvSyncEnabled: Boolean = true
    private var currentAudioChannel: Int = 1 // 1 is Stereo

    private var isPreIndexingActive = false
    private var isPreIndexingStarted = false
    private var isPlaybackStarted = false
    private var preIndexVolumeRestore = 100

    private val externalSubtitleUris = mutableListOf<Uri>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val subtitleSettingsManager = SubtitleSettingsManager(context)

    interface Listener {
        fun onExoPlayerCreated(player: Player)
        fun onVlcActive()
        fun onStateChanged(state: PlayerState)
        fun onDecoderModeChanged(mode: DecoderMode)
        fun onError(message: String)
        fun onBuffering(buffering: Float)
        fun onIndexingProgress(message: String)
        fun onMuteChanged(isMuted: Boolean)
        fun onTracksChanged()
        fun onCues(cues: List<androidx.media3.common.text.Cue>)
    }

    private var listener: Listener? = null

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    fun isIndexing(): Boolean = isPreIndexingActive

    fun setVlcLayout(layout: VLCVideoLayout) {
        this.vlcVideoLayout = layout
        if (currentDecoderMode != DecoderMode.HW) {
            vlcPlayer?.let { player ->
                player.detachViews()
                player.attachViews(layout, null, true, false)
            }
        }
    }

    fun prepare(uri: Uri, mediaId: String) {
        this.currentUri = uri
        this.currentMediaId = mediaId
        this.currentDecoderMode = decoderManager.getPreferredMode(mediaId)
        this.isHardDisabled = false
        this.externalSubtitleUris.clear()
        
        // Always attempt indexing for VLC on fresh video open
        this.isPreIndexingActive = (currentDecoderMode != DecoderMode.HW)
        this.isPreIndexingStarted = false
        this.isPlaybackStarted = false
        
        initializePlayer()
    }

    private fun initializePlayer() {
        val indexingActive = isPreIndexingActive
        releaseEngines()
        
        // Restore indexing state
        this.isPreIndexingActive = indexingActive
        this.isPreIndexingStarted = false
        this.isPlaybackStarted = false

        subtitleSettingsManager.prefix = if (currentDecoderMode == DecoderMode.HW) "exo_" else "vlc_"

        when (currentDecoderMode) {
            DecoderMode.HW -> initializeExoPlayer()
            DecoderMode.HW_PLUS, DecoderMode.SW -> initializeVLC()
        }
    }

    private fun initializeExoPlayer() {
        val uri = currentUri ?: return
        updateState(PlayerState.PREPARING)
        listener?.onDecoderModeChanged(currentDecoderMode)

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        }

        exoPlayer = ExoPlayer.Builder(context, renderersFactory).build().apply {
            setMediaItem(createExoMediaItem(uri))
            addListener(exoListener)
            playWhenReady = true
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
            
            if (pendingSeek != -1L) {
                seekTo(pendingSeek)
                pendingSeek = -1L
            }
            prepare()
        }
        exoPlayer?.let { listener?.onExoPlayerCreated(it) }
    }

    private fun createExoMediaItem(uri: Uri): MediaItem {
        val subtitleConfigs = externalSubtitleUris.map { subUri ->
            MediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(getSubtitleMimeType(subUri))
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setLabel(getFileName(subUri)?.substringBeforeLast(".") ?: "External Subtitle")
                .build()
        }
        return MediaItem.Builder().setUri(uri).setSubtitleConfigurations(subtitleConfigs).build()
    }

    private fun getSubtitleMimeType(uri: Uri): String {
        val path = uri.path?.lowercase() ?: ""
        return when {
            path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            path.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            path.endsWith(".ssa") || path.endsWith(".ass") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    fun addExternalSubtitle(uri: Uri) {
        if (externalSubtitleUris.contains(uri)) return
        externalSubtitleUris.add(uri)
        
        if (currentDecoderMode == DecoderMode.HW) {
            exoPlayer?.let {
                val currentPos = it.currentPosition
                it.setMediaItem(createExoMediaItem(currentUri!!), false)
                it.seekTo(currentPos)
            }
        } else {
            vlcPlayer?.let { player ->
                val localPath = getFilePathFromUri(uri)
                if (localPath != null) {
                    player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(File(localPath)), true)
                    
                    mainHandler.postDelayed({ 
                        listener?.onTracksChanged()
                        val tracks = player.spuTracks
                        if (tracks != null && tracks.isNotEmpty()) {
                            player.spuTrack = tracks.last().id
                        }
                    }, 1200)
                } else {
                    player.addSlave(IMedia.Slave.Type.Subtitle, uri, true)
                    mainHandler.postDelayed({ listener?.onTracksChanged() }, 1200)
                }
            }
        }
    }

    private fun getFilePathFromUri(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        return try {
            val contentResolver = context.contentResolver
            val fileName = getFileName(uri) ?: "temp_subtitle_${System.currentTimeMillis()}.srt"
            val tempFile = File(context.cacheDir, fileName)
            
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) name = it.getString(index)
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/')
            if (cut != -1) name = name?.substring(cut!! + 1)
        }
        return name
    }

    private fun initializeVLC() {
        val uri = currentUri ?: return
        val layout = vlcVideoLayout ?: return
        
        updateState(PlayerState.PREPARING)
        val mode = currentDecoderMode
        listener?.onDecoderModeChanged(mode)
        listener?.onVlcActive()
        isVlcParsingFinished = false

        val useHardware = mode == DecoderMode.HW_PLUS

        Thread {
            try {
                val options = arrayListOf<String>().apply {
                    add("--file-caching=2000")
                    add("--network-caching=2000")
                    add("--clock-jitter=500")
                    add("--avcodec-hw=${if (useHardware) "any" else "none"}")
                    add("--no-stats")
                    add("--no-ts-trust")
                    add("--avcodec-skiploopfilter=4")
                    add("--avcodec-fast")
                    add("--avcodec-threads=0")

                    if (isAvSyncEnabled) {
                        add("--drop-late-frames")
                        add("--skip-frames")
                    } else {
                        add("--no-drop-late-frames")
                        add("--no-skip-frames")
                    }

                    add("--text-renderer=freetype")
                    add("--sub-margin=${subtitleSettingsManager.bottomMargin * 8}")
                    val calculatedScale = (subtitleSettingsManager.textSize * subtitleSettingsManager.textScale) / 20
                    add("--sub-text-scale=$calculatedScale")
                    
                    val vlcColor = subtitleSettingsManager.textColor and 0x00FFFFFF
                    add("--freetype-color=$vlcColor")

                    if (subtitleSettingsManager.isBackgroundEnabled) {
                        val vlcBgColor = subtitleSettingsManager.backgroundColor and 0x00FFFFFF
                        add("--freetype-background-color=$vlcBgColor")
                        add("--freetype-background-opacity=${subtitleSettingsManager.backgroundAlpha}")
                    } else {
                        add("--freetype-background-opacity=0")
                    }



                    if (subtitleSettingsManager.isBold) add("--freetype-bold")
                    if (subtitleSettingsManager.isFadeOutEnabled) add("--sub-filter=fade")
                }

                val newLibVLC = LibVLC(context, options)
                val newMediaPlayer = MediaPlayer(newLibVLC)
                
                val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
                val media = if (pfd != null) Media(newLibVLC, pfd.fileDescriptor) else Media(newLibVLC, uri)

                val vlcAlign = when (subtitleSettingsManager.alignment) {
                    "Left" -> 1
                    "Right" -> 2
                    else -> 0
                }

                // ... media initialize hone ke baad aur setHWDecoderEnabled ke aas paas

                val selectedFont = subtitleSettingsManager.font
                val customFontPath = getFontPath(selectedFont)

                if (customFontPath != null) {
                    // Media options mein ":" ka use hota hai
                    media.addOption(":freetype-font=$customFontPath")
                    media.addOption(":sub-font=$customFontPath")
                    Log.d("VeloraPlayer", "Applying Custom Font to Media: $customFontPath")
                } else {
                    val vlcFontName = when (selectedFont) {
                        "Roboto" -> "sans-serif"
                        "Serif" -> "serif"
                        "SN-Pro" -> "monospace"
                        else -> ""
                    }
                    if (vlcFontName.isNotEmpty()) {
                        media.addOption(":freetype-font=$vlcFontName")
                        media.addOption(":sub-font=$vlcFontName")
                    }
                }

                media.setHWDecoderEnabled(useHardware, useHardware)
                media.addOption(":audio-channel=$currentAudioChannel")
                media.addOption(":subsdec-align=$vlcAlign")
                media.addOption(":play-and-pause") // <-- Ye line add karein

                if (subtitleSettingsManager.isBold) media.addOption(":sub-text-style=1")
                
                if (subtitleSettingsManager.isShadowEnabled) {
                    media.addOption(":freetype-shadow=1")
                    media.addOption(":freetype-shadow-opacity=170")
                    media.addOption(":freetype-shadow-angle=45")
                    media.addOption(":freetype-shadow-offset=4")
                    media.addOption(":sub-text-style=1")
                }

                if (subtitleSettingsManager.isBorderEnabled) {
                    media.addOption(":freetype-outline-thickness=${subtitleSettingsManager.borderWidth}")
                }

                if (subtitleSettingsManager.isFadeOutEnabled) media.addOption(":sub-fadeout=500")

                externalSubtitleUris.forEach { subUri ->
                    val localPath = getFilePathFromUri(subUri)
                    if (localPath != null) {
                        media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, Uri.fromFile(File(localPath)).toString()))
                    } else {
                        media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, subUri.toString()))
                    }
                }

                mainHandler.post {
                    if (currentUri == uri && currentState != PlayerState.RELEASED) {
                        libVLC = newLibVLC
                        vlcPlayer = newMediaPlayer
                        vlcFD = pfd

                        vlcPlayer?.vlcVout?.addCallback(this)
                        vlcPlayer?.attachViews(layout, null, true, false)
                        vlcPlayer?.setEventListener(vlcListener)

                        if (audioDelay != 0L) vlcPlayer?.setAudioDelay(audioDelay * 1000)
                        if (subtitleDelay != 0L) vlcPlayer?.setSpuDelay(subtitleDelay * 1000)
                        
                        if (isPreIndexingActive) vlcPlayer?.volume = 0

                        media.setEventListener { event ->
                            if (event.type == IMedia.Event.ParsedChanged) {
                                mainHandler.post { isVlcParsingFinished = true }
                            }
                        }
                        
                        media.parseAsync(IMedia.Parse.ParseLocal or IMedia.Parse.FetchLocal, 0)
                        vlcPlayer?.media = media
                        media.release()

                        if (surfacesReady) vlcPlayer?.play()
                    } else {
                        media.release()
                        pfd?.close()
                        newMediaPlayer.release()
                        newLibVLC.release()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post { listener?.onError("VLC Load Error: ${e.message}") }
            }
        }.start()
    }

    override fun onSurfacesCreated(vlcVout: IVLCVout?) {
        surfacesReady = true
        checkAndStartVlc()
    }

    private fun checkAndStartVlc() {
        if ((currentDecoderMode == DecoderMode.SW || currentDecoderMode == DecoderMode.HW_PLUS) && surfacesReady) {
            if (currentState == PlayerState.PLAYING || currentState == PlayerState.PREPARING) {
                vlcPlayer?.play()
            }
        }
    }

    override fun onSurfacesDestroyed(vlcVout: IVLCVout?) {
        surfacesReady = false
    }

    fun onStart() {
        if (currentDecoderMode != DecoderMode.HW) {
            vlcVideoLayout?.let {
                vlcPlayer?.attachViews(it, null, true, false)
            }
        }
    }

    fun onStop() {
        if (currentDecoderMode != DecoderMode.HW) {
            vlcPlayer?.detachViews()
        }
    }

    private val exoListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    isPlaybackStarted = true
                    updateState(if (exoPlayer?.playWhenReady == true) PlayerState.PLAYING else PlayerState.READY)
                }
                Player.STATE_BUFFERING -> {
                    // Only show buffering UI if playback has not started yet
                    if (!isPlaybackStarted) {
                        updateState(PlayerState.BUFFERING)
                    }
                }
                Player.STATE_ENDED -> updateState(PlayerState.IDLE)
                Player.STATE_IDLE -> {}
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (exoPlayer?.playbackState == Player.STATE_READY) {
                updateState(if (playWhenReady) PlayerState.PLAYING else PlayerState.PAUSED)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            listener?.onTracksChanged()
        }

        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            listener?.onCues(cueGroup.cues)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (currentDecoderMode == DecoderMode.HW) {
                mainHandler.post { fallbackToHWPlus() }
            } else {
                listener?.onError("Playback Error: ${error.message}")
            }
        }
    }

    private fun updateState(playerState: PlayerState) {
        if (currentState == playerState) return
        currentState = playerState
        listener?.onStateChanged(playerState)
    }

    private val vlcListener = MediaPlayer.EventListener { event ->
        mainHandler.post {
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    Log.d("VeloraPlayer", "VLC Event Playing. isPreIndexingActive: $isPreIndexingActive")
                    if (isPreIndexingActive) {
                        handlePreIndexing()
                    } else {
                        isPlaybackStarted = true
                        updateState(PlayerState.PLAYING)
                        if (pendingSeek != -1L) {
                            vlcPlayer?.time = pendingSeek
                            pendingSeek = -1L
                        }
                    }
                }
                MediaPlayer.Event.Paused -> {
                    if (!isPreIndexingActive) {
                        val player = vlcPlayer
                        // Agar video khatam hone wala hai aur pause hua, toh IDLE state bhejein
                        if (player != null && player.length > 0 && player.time >= player.length - 800) {
                            updateState(PlayerState.IDLE)
                        } else {
                            updateState(PlayerState.PAUSED)
                        }
                    }
                }                MediaPlayer.Event.EndReached -> if (!isPreIndexingActive) updateState(PlayerState.IDLE)
                MediaPlayer.Event.EncounteredError -> listener?.onError("VLC Engine Error")
                MediaPlayer.Event.Buffering -> {
                    // BLOCK Loading Screen mid-seek by checking isPlaybackStarted
                    if (!isPreIndexingActive && !isPlaybackStarted && currentState == PlayerState.PREPARING) {
                        listener?.onBuffering(event.buffering)
                    }
                }
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted -> listener?.onTracksChanged()
            }
        }
    }

    private fun handlePreIndexing() {
        if (isPreIndexingStarted) return
        val player = vlcPlayer ?: return
        val duration = player.length
        
        Log.d("VeloraPlayer", "handlePreIndexing check. Duration: $duration")
        
        if (duration <= 0) {
            listener?.onIndexingProgress("Analyzing Stream...")
            mainHandler.postDelayed({ handlePreIndexing() }, 500)
            return
        }

        isPreIndexingStarted = true
        listener?.onIndexingProgress("Optimizing Playback...")
        preIndexVolumeRestore = lastVolume
        player.volume = 0
        
        val seekTarget = (duration - 10000).coerceAtLeast(0)
        Log.d("VeloraPlayer", "Pre-indexing: Seeking to $seekTarget")
        player.setTime(seekTarget, false)
        
        mainHandler.postDelayed({
            val returnTime = if (pendingSeek != -1L) pendingSeek else 0L
            Log.d("VeloraPlayer", "Pre-indexing: Seeking back to $returnTime")
            listener?.onIndexingProgress("Synchronizing...")
            player.setTime(returnTime, false)
            pendingSeek = -1L
            
            mainHandler.postDelayed({
                Log.d("VeloraPlayer", "Pre-indexing: Finished. Restoring volume.")
                player.volume = preIndexVolumeRestore
                isPreIndexingActive = false
                isPreIndexingStarted = false
                isPlaybackStarted = true // Ready for smooth seeks now
                updateState(PlayerState.PLAYING)
            }, 1000)
        }, 1500)
    }

    private fun fallbackToHWPlus() {
        currentDecoderMode = DecoderMode.HW_PLUS
        isPreIndexingActive = true // Force indexing on fallback VLC mode
        initializePlayer()
    }

    fun switchDecoder() {
        val mediaId = currentMediaId ?: return
        val nextMode = when (currentDecoderMode) {
            DecoderMode.HW -> DecoderMode.HW_PLUS
            DecoderMode.HW_PLUS -> DecoderMode.SW
            DecoderMode.SW -> DecoderMode.HW
        }
        pendingSeek = getCurrentPosition()
        currentDecoderMode = nextMode
        decoderManager.savePreferredMode(mediaId, nextMode)
        
        // Force indexing for newly switched VLC mode
        this.isPreIndexingActive = (currentDecoderMode != DecoderMode.HW)
        this.isPreIndexingStarted = false
        this.isPlaybackStarted = false
        
        initializePlayer()
    }

    fun play() {
        if (currentDecoderMode != DecoderMode.HW) {
            val vlc = vlcPlayer ?: return
            if (currentState == PlayerState.IDLE) {// Agar bilkul end par hai, toh 0 se start karein
                if (vlc.time >= vlc.length - 1000 || vlc.time < 0) {
                    vlc.time = 0
                }
            }
            vlc.play()
        } else {
            exoPlayer?.play()
        }
    }
    fun pause() { if (currentDecoderMode != DecoderMode.HW) vlcPlayer?.pause() else exoPlayer?.pause() }

    private fun getFontPath(fontName: String): String? {val fontResId = when (fontName) {
        "Oswald" -> R.font.oswald
        "SN-Pro" -> R.font.sn_pro
        else -> return null
    }

        val fontFile = File(context.cacheDir, "$fontName.ttf")
        if (!fontFile.exists()) {
            try {
                context.resources.openRawResource(fontResId).use { input ->
                    FileOutputStream(fontFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                return null
            }
        }
        return fontFile.absolutePath
    }
    fun seekTo(positionMs: Long, isScrubbing: Boolean = false) {
        if (currentDecoderMode == DecoderMode.HW) {
            exoPlayer?.setSeekParameters(if (isScrubbing) SeekParameters.PREVIOUS_SYNC else SeekParameters.CLOSEST_SYNC)
            exoPlayer?.seekTo(positionMs)
        } else {
            val vlc = vlcPlayer ?: return

            // Agar user end se piche seek kar raha hai, toh state revive karein
            if (currentState == PlayerState.IDLE && positionMs < getDuration() - 1000) {
                updateState(PlayerState.PAUSED)
            }

            vlc.setTime(positionMs, isScrubbing)
        }
    }    fun setPlaybackSpeed(speed: Float) {
        if (currentDecoderMode != DecoderMode.HW) vlcPlayer?.rate = speed
        else exoPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    fun setVolume(volume: Int) {
        if (isHardDisabled && volume > 0) return
        val newVolume = volume.coerceIn(0, 100)
        lastVolume = newVolume
        if (currentDecoderMode != DecoderMode.HW) {
            if (!isPreIndexingActive) vlcPlayer?.volume = newVolume
        } else {
            exoPlayer?.volume = newVolume.toFloat() / 100f
        }

        val shouldBeMuted = newVolume == 0
        if (isMuted != shouldBeMuted) {
            isMuted = shouldBeMuted
            listener?.onMuteChanged(isMuted)
        }
    }

    fun toggleMute() {
        if (isHardDisabled) return
        if (isMuted) setVolume(lastVolume.coerceAtLeast(10))
        else {
            if (lastVolume == 0) lastVolume = 100
            setVolume(0)
        }
    }

    fun getCurrentPosition(): Long = if (currentDecoderMode != DecoderMode.HW) vlcPlayer?.time ?: 0L else exoPlayer?.currentPosition ?: 0L
    fun getDuration(): Long = if (currentDecoderMode != DecoderMode.HW) vlcPlayer?.length ?: 0L else exoPlayer?.duration ?: 0L

    fun getAudioTracks(): List<PlayerTrack> {
        val tracks = mutableListOf<PlayerTrack>()
        if (currentDecoderMode == DecoderMode.HW) {
            exoPlayer?.currentTracks?.groups?.forEachIndexed { groupIndex, group ->
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        var name = format.label ?: format.language ?: ""
                        if (name.isEmpty() || name == "und") name = "Audio_01"
                        tracks.add(PlayerTrack(id = "exo_$groupIndex-$i", name = name, isSelected = group.isTrackSelected(i), index = tracks.size))
                    }
                }
            }
        } else {
            vlcPlayer?.audioTracks?.forEachIndexed { index, track ->
                if (track.id != -1) {
                    var name = track.name ?: ""
                    if (name.isEmpty()) name = "Audio_01"
                    tracks.add(PlayerTrack(id = "vlc_${track.id}", name = name, isSelected = track.id == vlcPlayer?.audioTrack, index = index))
                }
            }
        }
        return tracks
    }

    fun setAudioTrack(track: PlayerTrack?) {
        if (track == null) { isHardDisabled = true; setVolume(0) } 
        else { isHardDisabled = false; if (isMuted) setVolume(lastVolume.coerceAtLeast(10)) }

        if (currentDecoderMode == DecoderMode.HW) {
            val player = exoPlayer ?: return
            if (track == null) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            } else {
                val parts = track.id.replace("exo_", "").split("-")
                if (parts.size == 2) {
                    val groupIndex = parts[0].toInt(); val trackIndex = parts[1].toInt()
                    val group = player.currentTracks.groups[groupIndex]
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex)).build()
                }
            }
        } else vlcPlayer?.audioTrack = if (track == null) -1 else track.id.replace("vlc_", "").toInt()
    }

    fun getSubtitleTracks(): List<PlayerTrack> {
        val tracks = mutableListOf<PlayerTrack>()
        val knownFormats = listOf("srt", "ass", "ssa", "vtt", "sub", "idx", "sup", "pgs")
        if (currentDecoderMode == DecoderMode.HW) {
            exoPlayer?.currentTracks?.groups?.forEachIndexed { groupIndex, group ->
                if (group.type == C.TRACK_TYPE_TEXT) {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        var name = format.label ?: format.language ?: ""
                        if (name.isEmpty() || name == "und") name = "Subtitle_0${tracks.size + 1}"
                        val mimeType = format.sampleMimeType ?: ""
                        val ext = when {
                            mimeType.contains("x-subrip") -> "srt"; mimeType.contains("text/x-ssa") -> "ssa"
                            mimeType.contains("text/x-ass") -> "ass"; mimeType.contains("vtt") -> "vtt"
                            mimeType.contains("pgs") -> "sup"; mimeType.contains("dvb") -> "dvb"
                            else -> mimeType.split("/").lastOrNull()?.takeIf { it in knownFormats }
                        }
                        tracks.add(PlayerTrack(id = "exo_sub_$groupIndex-$i", name = name, isSelected = group.isTrackSelected(i), index = tracks.size, format = ext))
                    }
                }
            }
        } else {
            val vlcTracks = vlcPlayer?.spuTracks ?: emptyArray(); val validTracks = vlcTracks.filter { it.id != -1 }
            var externalMappedCount = 0; val tempResult = mutableListOf<PlayerTrack>()
            for (i in validTracks.indices.reversed()) {
                val track = validTracks[i]; var name = track.name ?: ""
                val isGeneric = name.isEmpty() || name.lowercase().startsWith("track")
                if (isGeneric && externalMappedCount < externalSubtitleUris.size) { val uri = externalSubtitleUris[externalSubtitleUris.size - 1 - externalMappedCount]; name = getFileName(uri)?.substringBeforeLast(".") ?: "External Subtitle"; externalMappedCount++ } 
                else if (isGeneric) name = "Subtitle ${i + 1}"
                val potentialExt = if (name.contains(" - ")) name.substringAfterLast(" - ").lowercase() else null
                val ext = if (potentialExt in knownFormats) potentialExt else null
                tempResult.add(PlayerTrack(id = "vlc_sub_${track.id}", name = name, isSelected = track.id == vlcPlayer?.spuTrack, index = i, format = ext))
            }
            tracks.addAll(tempResult.reversed())
        }
        return tracks
    }

    fun setSubtitleTrack(track: PlayerTrack?) {
        if (currentDecoderMode == DecoderMode.HW) {
            val player = exoPlayer ?: return
            if (track == null) { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build(); listener?.onCues(emptyList()) } 
            else {
                val parts = track.id.replace("exo_sub_", "").split("-")
                if (parts.size == 2) {
                    val groupIndex = parts[0].toInt(); val trackIndex = parts[1].toInt()
                    val group = player.currentTracks.groups[groupIndex]
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex)).build()
                }
            }
        } else vlcPlayer?.spuTrack = if (track == null) -1 else track.id.replace("vlc_sub_", "").toInt()
    }

    fun isAudioEnabled(): Boolean = !isHardDisabled
    fun getAudioDelay(): Long = audioDelay
    fun setAudioDelay(delayMs: Long) { audioDelay = delayMs; if (currentDecoderMode == DecoderMode.HW) Toast.makeText(context, "Audio Sync works better in HW+ / SW mode", Toast.LENGTH_SHORT).show() else vlcPlayer?.setAudioDelay(delayMs * 1000) }
    fun getSubtitleDelay(): Long = subtitleDelay
    fun setSubtitleDelay(delayMs: Long) { subtitleDelay = delayMs; if (currentDecoderMode == DecoderMode.HW) Toast.makeText(context, "Subtitle Sync works better in HW+ / SW mode", Toast.LENGTH_SHORT).show() else vlcPlayer?.setSpuDelay(delayMs * 1000) }
    fun setSubtitleRate(rate: Float) {}
    fun setAvSyncEnabled(enabled: Boolean) { if (isAvSyncEnabled == enabled) return; isAvSyncEnabled = enabled; if (currentDecoderMode != DecoderMode.HW) { pendingSeek = getCurrentPosition(); initializePlayer() } }
    fun isAvSyncEnabled(): Boolean = isAvSyncEnabled
    fun setAudioChannel(channel: Int) { if (currentAudioChannel == channel) return; currentAudioChannel = channel; if (currentDecoderMode != DecoderMode.HW) { pendingSeek = getCurrentPosition(); initializePlayer() } }
    fun getAudioChannel(): Int = currentAudioChannel

    private fun releaseEngines() {
        exoPlayer?.let { it.stop(); it.release() }; exoPlayer = null
        vlcPlayer?.let { it.vlcVout?.removeCallback(this); it.stop(); it.detachViews(); it.release() }; vlcPlayer = null
        vlcFD?.close(); vlcFD = null
        libVLC?.let { it.release() }; libVLC = null
        surfacesReady = false; isVlcParsingFinished = false; isPreIndexingActive = false; isPreIndexingStarted = false; isPlaybackStarted = false
    }

    fun release() { releaseEngines(); updateState(PlayerState.RELEASED) }
}

package com.LegendAmardeep.veloraplayer.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.LegendAmardeep.veloraplayer.MainActivity
import com.LegendAmardeep.veloraplayer.R
import com.LegendAmardeep.veloraplayer.data.MediaRepository
import com.LegendAmardeep.veloraplayer.data.model.MediaFile
import com.LegendAmardeep.veloraplayer.databinding.FragmentPlayerBinding
import com.LegendAmardeep.veloraplayer.player.VeloraPlayerEngine
import com.LegendAmardeep.veloraplayer.player.decoder.DecoderManager
import com.LegendAmardeep.veloraplayer.player.model.PlayerState
import com.LegendAmardeep.veloraplayer.player.model.DecoderMode
import com.LegendAmardeep.veloraplayer.player.settings.SubtitleSettingsManager
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.os.BatteryManager
import android.content.IntentFilter


@UnstableApi
class PlayerFragment : Fragment(), VeloraPlayerEngine.Listener {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val args: PlayerFragmentArgs by navArgs()
    private lateinit var playerEngine: VeloraPlayerEngine
    private lateinit var audioManager: AudioManager
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var audioTrackAdapter: TrackSelectionAdapter
    private lateinit var subtitleTrackAdapter: SubtitleTrackAdapter
    private lateinit var subtitleFileAdapter: SubtitleFileAdapter
    private lateinit var subtitleSettingsManager: SubtitleSettingsManager
    private var isBackgroundPlayActive = false
    private var isSleepTimerActive = false
    private var playlist: List<MediaFile> = emptyList()
    private var originalPlaylist: List<MediaFile> = emptyList()
    private var isPlaylistLoaded = false
    private var currentMediaFile: MediaFile? = null
    
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideUiRunnable = Runnable { toggleUi(false) }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            uiHandler.postDelayed(this, 1000)
        }
    }

    private var maxVolume = 0
    private var volumeScrollAccumulator = 0f // <-- Ye line add karo
    private var isPlaying = false

    private lateinit var gestureDetector: GestureDetector
    private var isLocked = false
    private var playbackSpeed = 1.0f
    private var isVlcActive = false
    private var currentDecoderMode: DecoderMode? = null
    private var vlcSettingsChanged = false
    
    private var subtitleSyncDelay = 0L
    private var subtitlePlaybackSpeed = 1.0f

    enum class LoopMode { OFF, ALL, ONE }
    private var currentLoopMode = LoopMode.OFF
    private var isShuffleOn = false
    private var isRemainingTimeMode = false
    
    private lateinit var itemTouchHelper: ItemTouchHelper

    // More Menu Options
    private var seekSpeedValue = 10

    private var speedScrollAccumulator = 0f
    private var doubleTapValue = 10
    private var isKeepScreenOn = false
    private var isShowTimeEnabled = false
    private var wasPlayingBeforeSeek = false
    private var isGestureSeeking = false
    private var initialSeekPosition = 0L
    private var gestureTargetPosition = 0L
    private val resumeRunnable = Runnable {
        if (wasPlayingBeforeSeek) {
            playerEngine.play()
        }
    }

    private lateinit var prefs: android.content.SharedPreferences    // Default values update karein

    private var isSeekSpeedEnabled = true      // Default ON
    private var isDoubleTapSeekEnabled = true  // Default ON

    private var isSwitchingDecoder = false
    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeRunnable = object : Runnable {
        override fun run() {
            if (isShowTimeEnabled) {
                // Time Format
                val sdf = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
                val currentTime = sdf.format(java.util.Date())

                // Battery Percent
                val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                    requireContext().registerReceiver(null, ifilter)
                }
                val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct = (level * 100 / scale.toFloat()).toInt()

                // Update UI
                binding.tvClock.text = "$currentTime  |  $batteryPct%"

                timeHandler.postDelayed(this, 1000)
            }
        }
    }


    // For continuous sync adjustment
    private val syncHandler = Handler(Looper.getMainLooper())
    private var syncRunnable: Runnable? = null

    private val subtitlePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { copyFileToSubtitleDir(it) }
    }

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (isSwitchingDecoder) return
            when {
                binding.moreMenuLayout.root.isVisible -> toggleMoreMenu(false) // <-- ADD THIS LINE
                binding.subtitleFileLayout.root.isVisible -> toggleSubtitleFilePopup(false)
                binding.subtitleCustomizationLayout.textColorPopup.isVisible -> {
                    binding.subtitleCustomizationLayout.textColorPopup.visibility = View.GONE
                }
                binding.subtitleCustomizationLayout.alignmentPopup.isVisible -> {
                    binding.subtitleCustomizationLayout.alignmentPopup.visibility = View.GONE
                }
                binding.subtitleCustomizationLayout.root.isVisible -> {
                    handleCustomizationExit()
                }
                binding.playlistLayout.root.isVisible -> togglePlaylist(false)
                binding.audioTrackLayout.root.isVisible -> toggleAudioTrackPopup(false)
                binding.subtitleLayout.root.isVisible -> toggleSubtitlePopup(false)
                binding.speedControlLayout.root.isVisible -> toggleSpeedControl(false)
                binding.uiLayers.isVisible -> toggleUi(false)
                else -> {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        // Jab user file picker se wapas aaye, toh video surface ko re-attach karo
        if (::playerEngine.isInitialized) {
            // VLCVideoLayout ko force refresh karne ke liye
            binding.vlcVideoLayout.post {
                playerEngine.setVlcLayout(binding.vlcVideoLayout)
            }
        }
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainActivity)?.setFullScreen(true)



        // Ya phir agar upar wali kaam na kare toh:
        binding.playerView.subtitleView?.visibility = View.GONE
        currentMediaFile = args.mediaFile
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        subtitleSettingsManager = SubtitleSettingsManager(requireContext())
        prefs = requireContext().getSharedPreferences("player_settings", Context.MODE_PRIVATE)
        loadSettings() // Ye niche function hum banayege
        setupPlayer()
        setupPlaylist()
        setupAudioTrackPopup()
        setupSubtitlePopup()
        setupSubtitleFilePopup()
        setupSubtitleCustomizationPopup()
        setupUI()
        updateBackgroundPlayUi()
        updateSleepTimerUi()
        setupGestures()
        setupSpeedControl()
        setupMoreMenu() // <-- ADD THIS LINE


        uiHandler.post(progressRunnable)
    }

    private fun setupPlayer() {
        val decoderManager = DecoderManager(requireContext())
        playerEngine = VeloraPlayerEngine(requireContext(), decoderManager)
        playerEngine.setListener(this)
        
        binding.bufferingLayer.visibility = View.VISIBLE
        binding.tvBufferingStatus.text = "Analyzing Video..."
        binding.tvBufferingStatus.visibility = View.VISIBLE
        
        playerEngine.setVlcLayout(binding.vlcVideoLayout)
        currentMediaFile?.let {
            playerEngine.prepare(it.contentUri, it.id.toString())
        }
    }

    private fun setupPlaylist() {
        playlistAdapter = PlaylistAdapter(
            onVideoClick = { video ->
                playVideo(video)
                togglePlaylist(false)
            },
            onRemoveClick = { video ->
                val newList = playlist.toMutableList()
                newList.remove(video)
                playlist = newList
                originalPlaylist = originalPlaylist.filter { it.id != video.id }
                updatePlaylistUi()
            },
            onStartDrag = { viewHolder ->
                itemTouchHelper.startDrag(viewHolder)
            },
            onPlaylistReordered = { newList ->
                playlist = newList
                if (!isShuffleOn) {
                    originalPlaylist = newList
                }
            }
        )
        
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                playlistAdapter.onItemMove(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }
        
        itemTouchHelper = ItemTouchHelper(callback)

        binding.playlistLayout.rvPlaylist.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = playlistAdapter
            itemTouchHelper.attachToRecyclerView(this)
        }
        currentMediaFile?.let { playlistAdapter.setCurrentPlayingId(it.id) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupAudioTrackPopup() {
        audioTrackAdapter = TrackSelectionAdapter { track ->
            if (track.id == "disable") {
                playerEngine.setAudioTrack(null)
            } else {
                playerEngine.setAudioTrack(track)
            }
            updateAudioTracksUi()
        }

        binding.audioTrackLayout.rvAudioTracks.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = audioTrackAdapter
        }

        binding.audioTrackLayout.btnCloseAudioTrack.setOnClickListener { toggleAudioTrackPopup(false) }
        
        binding.audioTrackLayout.cbSoftwareDecoder.setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(requireContext(), "SW Audio Decoder: $isChecked", Toast.LENGTH_SHORT).show()
        }

        binding.audioTrackLayout.cbAvSync.isChecked = playerEngine.isAvSyncEnabled()
        binding.audioTrackLayout.cbAvSync.setOnCheckedChangeListener { _, isChecked ->
            playerEngine.setAvSyncEnabled(isChecked)
        }

        val setupContinuousClick = { button: ImageButton, increment: Int ->
            button.setOnClickListener {
                val currentDelay = playerEngine.getAudioDelay()
                playerEngine.setAudioDelay(currentDelay + increment)
                updateSyncUi()
            }
            
            button.setOnLongClickListener {
                syncRunnable = object : Runnable {
                    override fun run() {
                        val currentDelay = playerEngine.getAudioDelay()
                        playerEngine.setAudioDelay(currentDelay + increment)
                        updateSyncUi()
                        syncHandler.postDelayed(this, 100)
                    }
                }
                syncHandler.post(syncRunnable!!)
                true
            }
            
            button.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    syncRunnable?.let { syncHandler.removeCallbacks(it) }
                    syncRunnable = null
                }
                false
            }
        }

        setupContinuousClick(binding.audioTrackLayout.btnSyncMinus, -50)
        setupContinuousClick(binding.audioTrackLayout.btnSyncPlus, 50)

        binding.audioTrackLayout.btnOpenAudio.setOnClickListener {
            Toast.makeText(requireContext(), "Open external audio feature coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.audioTrackLayout.btnStereoMode.setOnClickListener {
            val isVisible = binding.audioTrackLayout.rgStereoOptions.visibility == View.VISIBLE
            binding.audioTrackLayout.rgStereoOptions.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        binding.audioTrackLayout.rgStereoOptions.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbStereo -> playerEngine.setAudioChannel(1) 
                R.id.rbMono -> playerEngine.setAudioChannel(3)   
            }
        }
        
        binding.audioTrackLayout.audioTrackContainer.setOnClickListener { /* Consume clicks */ }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSubtitlePopup() {
        subtitleTrackAdapter = SubtitleTrackAdapter { track ->
            if (track.id == "none") {
                playerEngine.setSubtitleTrack(null)
            } else {
                playerEngine.setSubtitleTrack(track)
            }
            updateSubtitleTracksUi()
        }

        binding.subtitleLayout.rvSubtitles.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = subtitleTrackAdapter
        }

        binding.subtitleLayout.btnCloseSubtitle.setOnClickListener { toggleSubtitlePopup(false) }
        binding.subtitleLayout.btnOpenSubtitle.setOnClickListener {
            toggleSubtitlePopup(false)
            uiHandler.postDelayed({ toggleSubtitleFilePopup(true) }, 200)
        }
        binding.subtitleLayout.btnOnlineSubtitles.setOnClickListener {
            Toast.makeText(requireContext(), "Online Subtitles feature coming soon", Toast.LENGTH_SHORT).show()
        }
        
        binding.subtitleLayout.btnSubtitleSettings.setOnClickListener {
            val isVisible = binding.subtitleLayout.subtitleSettingsOptions.visibility == View.VISIBLE
            binding.subtitleLayout.subtitleSettingsOptions.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.subtitleLayout.ivSubtitleSettingsArrow.rotation = if (isVisible) 0f else 180f
        }

        // Subtitle Sync Controls
        val setupSyncClick = { button: ImageButton, increment: Int ->
            button.setOnClickListener {
                subtitleSyncDelay += increment
                playerEngine.setSubtitleDelay(subtitleSyncDelay)
                updateSubtitleSyncUi()
            }
            button.setOnLongClickListener {
                syncRunnable = object : Runnable {
                    override fun run() {
                        subtitleSyncDelay += increment
                        playerEngine.setSubtitleDelay(subtitleSyncDelay)
                        updateSubtitleSyncUi()
                        syncHandler.postDelayed(this, 100)
                    }
                }
                syncHandler.post(syncRunnable!!)
                true
            }
            button.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    syncRunnable?.let { syncHandler.removeCallbacks(it) }
                    syncRunnable = null
                }
                false
            }
        }
        setupSyncClick(binding.subtitleLayout.btnSubSyncMinus, -100)
        setupSyncClick(binding.subtitleLayout.btnSubSyncPlus, 100)

        // Subtitle Speed Controls
        val setupSpeedClick = { button: ImageButton, increment: Float ->
            button.setOnClickListener {
                subtitlePlaybackSpeed = (subtitlePlaybackSpeed + increment).coerceIn(0.5f, 2.0f)
                playerEngine.setSubtitleRate(subtitlePlaybackSpeed)
                updateSubtitleSpeedUi()
            }
            button.setOnLongClickListener {
                syncRunnable = object : Runnable {
                    override fun run() {
                        subtitlePlaybackSpeed = (subtitlePlaybackSpeed + increment).coerceIn(0.5f, 2.0f)
                        playerEngine.setSubtitleRate(subtitlePlaybackSpeed)
                        updateSubtitleSpeedUi()
                        syncHandler.postDelayed(this, 100)
                    }
                }
                syncHandler.post(syncRunnable!!)
                true
            }
            button.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    syncRunnable?.let { syncHandler.removeCallbacks(it) }
                    syncRunnable = null
                }
                false
            }
        }
        setupSpeedClick(binding.subtitleLayout.btnSubSpeedMinus, -0.05f)
        setupSpeedClick(binding.subtitleLayout.btnSubSpeedPlus, 0.05f)

        binding.subtitleLayout.btnSubCustomization.setOnClickListener {
            toggleSubtitlePopup(false)
            uiHandler.postDelayed({ toggleSubtitleCustomizationPopup(true) }, 300)
        }
        
        binding.subtitleLayout.subtitleContainer.setOnClickListener { /* Consume clicks */ }
    }

    private fun setupSubtitleFilePopup() {
        subtitleFileAdapter = SubtitleFileAdapter { file ->
            playerEngine.addExternalSubtitle(file.uri)
            toggleSubtitleFilePopup(false)
            uiHandler.postDelayed({ toggleSubtitlePopup(true) }, 200)
        }

        binding.subtitleFileLayout.rvSubtitleTracks.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = subtitleFileAdapter
        }

        binding.subtitleFileLayout.btnCloseSubtitleTrack.setOnClickListener {
            toggleSubtitleFilePopup(false)
            uiHandler.postDelayed({ toggleSubtitlePopup(true) }, 200)
        }

        binding.subtitleFileLayout.btnOpenSubtitle.setOnClickListener {
            subtitlePickerLauncher.launch(arrayOf("*/*"))
        }
        
        binding.subtitleFileLayout.subtitleTrackContainer.setOnClickListener { /* Consume clicks */ }
    }

    private fun copyFileToSubtitleDir(sourceUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val context = requireContext()
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val rootUriString = prefs.getString("root_folder_uri", null) ?: return@launch

                val rootUri = Uri.parse(rootUriString)
                val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return@launch
                val subDir = rootDoc.findFile("subtitles") ?: return@launch

                // Get original filename
                var fileName = "subtitle_${System.currentTimeMillis()}.srt"
                context.contentResolver.query(sourceUri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex)
                    }
                }

                // Create destination file
                val destFile = subDir.createFile("*/*", fileName) ?: return@launch

                // Copy data
                val inputStream: InputStream? = context.contentResolver.openInputStream(sourceUri)
                val outputStream: OutputStream? = context.contentResolver.openOutputStream(destFile.uri)

                if (inputStream != null && outputStream != null) {
                    inputStream.use { input ->
                        outputStream.use { output ->
                            input.copyTo(output)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Subtitle imported: $fileName", Toast.LENGTH_SHORT).show()
                        loadSubtitleFiles() // Refresh list
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Failed to import subtitle", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadSubtitleFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val rootUriString = prefs.getString("root_folder_uri", null) ?: return@launch

            val rootUri = Uri.parse(rootUriString)
            val rootDoc = DocumentFile.fromTreeUri(requireContext(), rootUri) ?: return@launch
            val subDir = rootDoc.findFile("subtitles") ?: return@launch

            val subtitleFiles = subDir.listFiles().filter { file ->
                val name = file.name?.lowercase() ?: ""
                name.endsWith(".srt") || name.endsWith(".ass") || name.endsWith(".ssa") || name.endsWith(".vtt")
            }

            withContext(Dispatchers.Main) {
                subtitleFileAdapter.submitList(subtitleFiles)

            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSubtitleCustomizationPopup() {
        binding.subtitleCustomizationLayout.tvAlignmentValue.text = subtitleSettingsManager.alignment

        // Font Value Text Set karein
        binding.subtitleCustomizationLayout.tvFontValue.text = subtitleSettingsManager.font

// Click listener popup dikhane ke liye
        binding.subtitleCustomizationLayout.tvFontValue.setOnClickListener {
            val isVisible = binding.subtitleCustomizationLayout.fontPopup.isVisible
            binding.subtitleCustomizationLayout.fontPopup.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

// Font apply karne ka helper lambda
        val applyFont = { fontName: String ->
            binding.subtitleCustomizationLayout.tvFontValue.text = fontName
            subtitleSettingsManager.font = fontName
            updateMedia3SubtitleStyle()
            if (isVlcActive) vlcSettingsChanged = true
            binding.subtitleCustomizationLayout.fontPopup.visibility = View.GONE
        }

// Button listeners
        binding.subtitleCustomizationLayout.btnFontDefault.setOnClickListener { applyFont("Default") }
        binding.subtitleCustomizationLayout.btnFontRoboto.setOnClickListener { applyFont("Roboto") }
        binding.subtitleCustomizationLayout.btnFontOswald.setOnClickListener { applyFont("Oswald") }
        binding.subtitleCustomizationLayout.btnFontSerif.setOnClickListener { applyFont("Serif") }
        binding.subtitleCustomizationLayout.btnFontSNPro.setOnClickListener { applyFont("SN-Pro") }
        // Initial Bottom Margin setup (-10 to 100 range)
        binding.subtitleCustomizationLayout.tvBottomMarginValue.text = subtitleSettingsManager.bottomMargin.toString()
        binding.subtitleCustomizationLayout.sbBottomMargin.progress = subtitleSettingsManager.bottomMargin + 10

        binding.subtitleCustomizationLayout.tvSubtitleSizeValue.text = subtitleSettingsManager.textSize.toString()
        binding.subtitleCustomizationLayout.sbSubtitleSize.progress = subtitleSettingsManager.textSize

        // Setup initial Scale UI
        binding.subtitleCustomizationLayout.tvSubtitleScaleValue.text = "${subtitleSettingsManager.textScale}%"
        binding.subtitleCustomizationLayout.sbSubtitleScale.progress = subtitleSettingsManager.textScale

        binding.subtitleCustomizationLayout.viewTextColorPreview.setCardBackgroundColor(subtitleSettingsManager.textColor)

        // Use default black background for transparency seeker
        subtitleSettingsManager.backgroundColor = Color.BLACK
        binding.subtitleCustomizationLayout.sbBgAlpha.progress = subtitleSettingsManager.backgroundAlpha
        binding.subtitleCustomizationLayout.tvAlphaValue.text = subtitleSettingsManager.backgroundAlpha.toString()
        binding.subtitleCustomizationLayout.cbSubtitleBackgroundColor.isChecked = subtitleSettingsManager.isBackgroundEnabled

        binding.subtitleCustomizationLayout.cbTextBold.isChecked = subtitleSettingsManager.isBold
        binding.subtitleCustomizationLayout.cbSubtitleShadow.isChecked = subtitleSettingsManager.isShadowEnabled
        binding.subtitleCustomizationLayout.cbSubtitleFadeOut.isChecked = subtitleSettingsManager.isFadeOutEnabled

        // Setup initial Border UI
        binding.subtitleCustomizationLayout.cbSubtitleBorder.isChecked = subtitleSettingsManager.isBorderEnabled
        binding.subtitleCustomizationLayout.tvSubtitleBorderValue.text = subtitleSettingsManager.borderWidth.toString()
        binding.subtitleCustomizationLayout.sbSubtitleBorder.progress = subtitleSettingsManager.borderWidth

        binding.subtitleCustomizationLayout.btnBackToSubtitle.setOnClickListener {
            handleCustomizationExit()
        }

        binding.subtitleCustomizationLayout.tvAlignmentValue.setOnClickListener {
            val isVisible = binding.subtitleCustomizationLayout.alignmentPopup.isVisible
            binding.subtitleCustomizationLayout.alignmentPopup.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        val applyAlignment = { selected: String ->
            binding.subtitleCustomizationLayout.tvAlignmentValue.text = selected
            subtitleSettingsManager.alignment = selected
            updateMedia3SubtitleStyle()
            if (isVlcActive) vlcSettingsChanged = true
            binding.subtitleCustomizationLayout.alignmentPopup.visibility = View.GONE
        }

        binding.subtitleCustomizationLayout.btnAlignLeft.setOnClickListener { applyAlignment("Left") }
        binding.subtitleCustomizationLayout.btnAlignCenter.setOnClickListener { applyAlignment("Center") }
        binding.subtitleCustomizationLayout.btnAlignRight.setOnClickListener { applyAlignment("Right") }

        // Text Color Logic
        binding.subtitleCustomizationLayout.viewTextColorPreview.setOnClickListener {
            val isVisible = binding.subtitleCustomizationLayout.textColorPopup.isVisible
            binding.subtitleCustomizationLayout.textColorPopup.visibility = View.GONE
            binding.subtitleCustomizationLayout.textColorPopup.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        val applyColor = { color: Int ->
            binding.subtitleCustomizationLayout.viewTextColorPreview.setCardBackgroundColor(color)
            subtitleSettingsManager.textColor = color
            updateMedia3SubtitleStyle()
            if (isVlcActive) vlcSettingsChanged = true
            binding.subtitleCustomizationLayout.textColorPopup.visibility = View.GONE
        }

        binding.subtitleCustomizationLayout.btnColorWhite.setOnClickListener { applyColor(Color.WHITE) }
        binding.subtitleCustomizationLayout.btnColorBlack.setOnClickListener { applyColor(Color.BLACK) }
        binding.subtitleCustomizationLayout.btnColorRed.setOnClickListener { applyColor(Color.RED) }
        binding.subtitleCustomizationLayout.btnColorBlue.setOnClickListener { applyColor(Color.BLUE) }
        binding.subtitleCustomizationLayout.btnColorGreen.setOnClickListener { applyColor(Color.GREEN) }
        binding.subtitleCustomizationLayout.btnColorYellow.setOnClickListener { applyColor(Color.YELLOW) }

        // Dismiss popups when clicking anywhere else
        binding.subtitleCustomizationLayout.customizationScrollView.getChildAt(0).setOnClickListener {
            binding.subtitleCustomizationLayout.alignmentPopup.visibility = View.GONE
            binding.subtitleCustomizationLayout.textColorPopup.visibility = View.GONE
        }

        binding.subtitleCustomizationLayout.sbBgAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    subtitleSettingsManager.backgroundAlpha = progress
                    binding.subtitleCustomizationLayout.tvAlphaValue.text = progress.toString()
                    updateMedia3SubtitleStyle()
                    if (isVlcActive) vlcSettingsChanged = true
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.subtitleCustomizationLayout.cbSubtitleBackgroundColor.setOnCheckedChangeListener { _, isChecked ->
            subtitleSettingsManager.isBackgroundEnabled = isChecked
            updateMedia3SubtitleStyle()
            if (isVlcActive) vlcSettingsChanged = true
        }

        binding.subtitleCustomizationLayout.sbBottomMargin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val realMargin = progress - 10
                    binding.subtitleCustomizationLayout.tvBottomMarginValue.text = realMargin.toString()
                    subtitleSettingsManager.bottomMargin = realMargin
                    updateMedia3SubtitleStyle()
                    if (isVlcActive) vlcSettingsChanged = true
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.subtitleCustomizationLayout.sbSubtitleSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.subtitleCustomizationLayout.tvSubtitleSizeValue.text = progress.toString()
                    subtitleSettingsManager.textSize = progress
                    updateMedia3SubtitleStyle()
                    if (isVlcActive) vlcSettingsChanged = true
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.subtitleCustomizationLayout.sbSubtitleScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val safeProgress = progress.coerceAtLeast(10)
                    binding.subtitleCustomizationLayout.tvSubtitleScaleValue.text = "$safeProgress%"
                    subtitleSettingsManager.textScale = safeProgress
                    updateMedia3SubtitleStyle()
                    if (isVlcActive) vlcSettingsChanged = true
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Border Seeker listener
        binding.subtitleCustomizationLayout.sbSubtitleBorder.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.subtitleCustomizationLayout.tvSubtitleBorderValue.text = progress.toString()
                    subtitleSettingsManager.borderWidth = progress
                    updateMedia3SubtitleStyle()
                    if (isVlcActive) vlcSettingsChanged = true
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.subtitleCustomizationLayout.cbTextBold.setOnCheckedChangeListener { _, isChecked ->
            subtitleSettingsManager.isBold = isChecked
            updateMedia3SubtitleStyle()
            if (isVlcActive) vlcSettingsChanged = true
        }

        binding.subtitleCustomizationLayout.cbSubtitleShadow.setOnCheckedChangeListener { _, isChecked ->
            subtitleSettingsManager.isShadowEnabled = isChecked
            updateMedia3SubtitleStyle()
            if (isVlcActive) vlcSettingsChanged = true
        }

        binding.subtitleCustomizationLayout.cbSubtitleBorder.setOnCheckedChangeListener { _, isChecked ->
            subtitleSettingsManager.isBorderEnabled = isChecked
            updateMedia3SubtitleStyle()
            if (isVlcActive) vlcSettingsChanged = true
        }

        binding.subtitleCustomizationLayout.cbSubtitleFadeOut.setOnCheckedChangeListener { _, isChecked ->
            subtitleSettingsManager.isFadeOutEnabled = isChecked
            if (isVlcActive) vlcSettingsChanged = true
        }

        binding.subtitleCustomizationLayout.subtitleCustomizationContainer.setOnClickListener { /* Consume clicks */ }
    }

    private fun handleCustomizationExit() {
        if (isVlcActive && vlcSettingsChanged) {
            showRestartDialog()
        } else {
            toggleSubtitleCustomizationPopup(false)
            uiHandler.postDelayed({ toggleSubtitlePopup(true) }, 300)
        }
    }

    private fun showRestartDialog() {
        uiHandler.postDelayed({
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.layout_restart_dialog, null)
            val dialog = AlertDialog.Builder(requireContext(), R.style.CustomAlertDialog)
                .setView(dialogView)
                .setCancelable(false)
                .create()

            dialogView.findViewById<View>(R.id.btnKeepChanging).setOnClickListener {
                dialog.dismiss()
                (activity as? MainActivity)?.setFullScreen(true)
            }

            dialogView.findViewById<View>(R.id.btnRestart).setOnClickListener {
                vlcSettingsChanged = false
                dialog.dismiss()
                (activity as? MainActivity)?.setFullScreen(true)
                val currentPos = playerEngine.getCurrentPosition()
                currentMediaFile?.let {
                     playerEngine.prepare(it.contentUri, it.id.toString())
                     playerEngine.seekTo(currentPos)
                }
                toggleSubtitleCustomizationPopup(false)
                uiHandler.postDelayed({ toggleSubtitlePopup(true) }, 300)
            }

            dialog.show()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            (activity as? MainActivity)?.setFullScreen(true)
        }, 100)
    }

    private fun updateMedia3SubtitleStyle() {
        if (!isVlcActive) {
            binding.tvCustomSubtitle.apply {
                textSize = subtitleSettingsManager.textSize.toFloat()
                setTextColor(subtitleSettingsManager.textColor)
//              setTypeface(null, if (subtitleSettingsManager.isBold) Typeface.BOLD else Typeface.NORMAL)

                val typeface = subtitleSettingsManager.getTypeface(requireContext())
                binding.tvCustomSubtitle.setTypeface(typeface, if (subtitleSettingsManager.isBold) Typeface.BOLD else Typeface.NORMAL)
                // Background Color
                if (subtitleSettingsManager.isBackgroundEnabled) {
                    val colorWithAlpha = ColorUtils.setAlphaComponent(subtitleSettingsManager.backgroundColor, subtitleSettingsManager.backgroundAlpha)
                    setBackgroundColor(colorWithAlpha)
                } else {
                    setBackgroundColor(Color.TRANSPARENT)
                }

                // Shadow and Border for Media3 (HW)
                if (subtitleSettingsManager.isShadowEnabled) {
                    setShadowLayer(12f, 5f, 5f, Color.BLACK)
                } else if (subtitleSettingsManager.isBorderEnabled) {
                    // Simulating border with tight thick shadow
                    setShadowLayer(subtitleSettingsManager.borderWidth.toFloat() * 2, 0f, 0f, Color.BLACK)
                } else {
                    setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                }

                val params = layoutParams as ConstraintLayout.LayoutParams
                params.bottomMargin = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    subtitleSettingsManager.bottomMargin.toFloat(),
                    resources.displayMetrics
                ).toInt()

                params.horizontalBias = when (subtitleSettingsManager.alignment) {
                    "Left" -> 0.0f
                    "Right" -> 1.0f
                    else -> 0.5f
                }
                layoutParams = params

                gravity = when (subtitleSettingsManager.alignment) {
                    "Left" -> Gravity.START or Gravity.BOTTOM
                    "Right" -> Gravity.END or Gravity.BOTTOM
                    else -> Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                }
            }
        }
    }

    private fun updateSubtitleSyncUi() {
        binding.subtitleLayout.tvSubSyncValue.text = String.format("%.1fs", subtitleSyncDelay / 1000f)
    }

    private fun updateSubtitleSpeedUi() {
        binding.subtitleLayout.tvSubSpeedValue.text = String.format("%.1f%%", subtitlePlaybackSpeed * 100)
    }

    private fun updateAudioTracksUi() {
        val tracks = playerEngine.getAudioTracks()
        val disableTrack = PlayerTrack(id = "disable", name = "Disable", isSelected = !playerEngine.isAudioEnabled(), index = -1)
        val allTracks = tracks + listOf(disableTrack)

        audioTrackAdapter.submitList(allTracks)
        updateSyncUi()

        val currentChannel = playerEngine.getAudioChannel()
        if (currentChannel == 1) {
            binding.audioTrackLayout.rbStereo.isChecked = true
        } else if (currentChannel == 3) {
            binding.audioTrackLayout.rbMono.isChecked = true
        }
    }

    private fun updateSubtitleTracksUi() {
        val tracks = playerEngine.getSubtitleTracks()
        val noneSelected = tracks.none { it.isSelected }
        val noneTrack = PlayerTrack(id = "none", name = "None", isSelected = noneSelected, index = -1)
        val allTracks = tracks + listOf(noneTrack)

        subtitleTrackAdapter.submitList(allTracks)
        updateSubtitleSyncUi()
        updateSubtitleSpeedUi()
    }

    private fun updateSyncUi() {
        val delay = playerEngine.getAudioDelay()
        binding.audioTrackLayout.tvSyncValue.text = String.format("%.2fs", delay / 1000f)
    }

    private fun loadPlaylistInBackground() {
        if (isPlaylistLoaded) return
        lifecycleScope.launch(Dispatchers.IO) {
            val repository = MediaRepository(requireContext())
            val allVideos = repository.getAllVideos()
            val currentFile = currentMediaFile ?: args.mediaFile
            val folderVideos = allVideos.filter { it.folder == currentFile.folder }

            withContext(Dispatchers.Main) {
                originalPlaylist = folderVideos
                playlist = folderVideos
                updatePlaylistUi()
                isPlaylistLoaded = true
            }
        }
    }

    private fun updatePlaylistUi() {
        playlistAdapter.submitList(playlist)
        binding.playlistLayout.tvPlaylistTitle.text = "Now Playing (${playlist.size})"
    }

    private fun playVideo(mediaFile: MediaFile) {
        currentMediaFile = mediaFile
        binding.tvTitle.text = mediaFile.name
        playlistAdapter.setCurrentPlayingId(mediaFile.id)

        // Naya video play karne par purana subtitle clear karein

        binding.tvCustomSubtitle.text = ""
        binding.tvCustomSubtitle.visibility = View.GONE

        playerEngine.prepare(mediaFile.contentUri, mediaFile.id.toString())
    }

    override fun onExoPlayerCreated(player: Player) {
        isVlcActive = false
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            binding.playerView.visibility = View.VISIBLE
            binding.vlcContainer.visibility = View.GONE
            binding.vlcContainer.translationZ = 0f
            binding.playerView.player = player
            updateMedia3SubtitleStyle()
        }
    }

    override fun onVlcActive() {
        isVlcActive = true
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            binding.playerView.visibility = View.GONE
            binding.vlcContainer.visibility = View.VISIBLE
            binding.playerView.player = null
            binding.tvBufferingStatus.text = "Building Universal Player..."
            // VLC active hote hi Media3 subtitles ko clear aur hide karein
            binding.tvCustomSubtitle.text = ""
            binding.tvCustomSubtitle.visibility = View.GONE

        }
    }

    override fun onStateChanged(state: PlayerState) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            when (state) {
                PlayerState.PLAYING -> {
                    isSwitchingDecoder = false
                    if (!isPlaying) loadPlaylistInBackground()
                    isPlaying = true
                    binding.btnPlayPause.setImageResource(R.drawable.ic_pause_solid)
                    binding.bufferingLayer.visibility = View.GONE
                    resetHideTimer()
                    updateKeepScreenOnFlag()
                }
                PlayerState.PAUSED, PlayerState.READY -> {
                    isSwitchingDecoder = false
                    isPlaying = (state == PlayerState.PLAYING) // PAUSED/READY par false hoga
                    binding.btnPlayPause.setImageResource(R.drawable.ic_play_solid)
                    updateKeepScreenOnFlag()

                }
                PlayerState.BUFFERING -> {
                    binding.bufferingLayer.visibility = View.VISIBLE
                }
                PlayerState.RECOVERING -> {
                    binding.bufferingLayer.visibility = View.VISIBLE
                    binding.tvBufferingStatus.text = "Switching Decoders..."
                }
                PlayerState.IDLE -> {
                    isPlaying = false
                    updateKeepScreenOnFlag()
                    handleVideoEnded()
                }
                else -> {}
            }
        }
    }

    private fun handleVideoEnded() {
        val pos = playerEngine.getCurrentPosition()
        val duration = playerEngine.getDuration()

        if (duration > 0 && pos >= duration - 1000) {
            when (currentLoopMode) {
                LoopMode.OFF -> {}
                LoopMode.ALL -> playNextVideo(autoLoop = true)
                LoopMode.ONE -> currentMediaFile?.let { playVideo(it) }
            }
        }
    }

    override fun onDecoderModeChanged(mode: DecoderMode) {
        currentDecoderMode = mode
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            binding.tvDecoder.text = when (mode) {
                DecoderMode.HW -> "HW"
                DecoderMode.HW_PLUS -> "HW+"
                DecoderMode.SW -> "SW"
            }
        }
    }

    override fun onError(message: String) {
        activity?.runOnUiThread {
            if (context != null) {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                binding.bufferingLayer.visibility = View.GONE
            }
        }
    }

    override fun onMuteChanged(isMuted: Boolean) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            val whiteColor = ContextCompat.getColor(requireContext(), R.color.white)

            if (isMuted) {
                binding.btnMute.apply {
                    setImageResource(R.drawable.ic_volume_xmark_solid)
                    setBackgroundResource(R.drawable.bg_speed_badge)
                    imageTintList = ColorStateList.valueOf(whiteColor)
                }
            } else {
                binding.btnMute.apply {
                    setImageResource(R.drawable.ic_volume_high_solid)
                    setBackgroundResource(R.drawable.bg_icon_circle)
                    imageTintList = ColorStateList.valueOf(whiteColor)
                }
            }
        }
    }

    override fun onBuffering(buffering: Float) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            if (buffering < 100f) {
                binding.bufferingLayer.visibility = View.VISIBLE
                binding.tvBufferingStatus.text = "Buffering ${buffering.toInt()}%"
            } else {
                binding.bufferingLayer.visibility = View.GONE
            }
        }
    }

    override fun onTracksChanged() {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            updateAudioTracksUi()
            updateSubtitleTracksUi()
        }
    }

    override fun onCues(cues: List<androidx.media3.common.text.Cue>) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            if (cues.isEmpty()) {
                if (subtitleSettingsManager.isFadeOutEnabled) {
                    binding.tvCustomSubtitle.animate()
                        .alpha(0f)
                        .setDuration(500)
                        .withEndAction {
                            if (_binding != null) binding.tvCustomSubtitle.visibility = View.GONE
                        }
                        .start()
                } else {
                    binding.tvCustomSubtitle.visibility = View.GONE
                }
            } else {
                binding.tvCustomSubtitle.animate().cancel()
                binding.tvCustomSubtitle.alpha = 1f
                binding.tvCustomSubtitle.visibility = View.VISIBLE
                binding.tvCustomSubtitle.text = cues[0].text
            }
        }
    }

    private fun setupUI() {
        binding.tvTitle.text = currentMediaFile?.name ?: args.mediaFile.name
        binding.btnBack.setOnClickListener {
            // Agar decoder switch ho raha hai toh back na jaane dein
            if (isSwitchingDecoder) return@setOnClickListener

            findNavController().navigateUp()
        }
        binding.btnSubtitle.setOnClickListener {
            // ... existing code ...
        }

        // v-- ADD THIS BLOCK --v
        binding.btnMenu.setOnClickListener {
            if (binding.uiLayers.isVisible) {
                toggleUi(false)
                uiHandler.postDelayed({ toggleMoreMenu(true) }, 200)
            } else {
                toggleMoreMenu(true)
            }
        }
        // ^-- ADD THIS BLOCK --^

        binding.btnResize.setOnClickListener { Toast.makeText(requireContext(), "Resize feature coming soon", Toast.LENGTH_SHORT).show() }

        binding.btnPlayPause.setOnClickListener {
            if (isSwitchingDecoder) return@setOnClickListener
            if (isPlaying) playerEngine.pause() else playerEngine.play()
        }
        binding.tvTotalTime.setOnClickListener {
            isRemainingTimeMode = !isRemainingTimeMode
            updateProgress() // Turant UI update karne ke liye
        }

        binding.tvDecoder.setOnClickListener {
            // Agar pehle se switching chal rahi hai to kuch na karein
            if (isSwitchingDecoder) return@setOnClickListener

            isSwitchingDecoder = true // <-- LOCK ON
            binding.bufferingLayer.visibility = View.VISIBLE
            binding.tvBufferingStatus.text = "Changing Decoder..."
            playerEngine.switchDecoder()
        }

        binding.btnLock.setOnClickListener {
            if (isSwitchingDecoder) return@setOnClickListener // <-- LOCK CHECK
            isLocked = true
            binding.uiLayers.visibility = View.GONE
            binding.btnUnlock.visibility = View.VISIBLE
        }
        binding.btnUnlock.setOnClickListener {
            isLocked = false
            binding.uiLayers.visibility = View.VISIBLE
            binding.btnUnlock.visibility = View.GONE
            resetHideTimer()
        }

        binding.btnOrientation.setOnClickListener { toggleOrientation() }

        binding.btnMute.setOnClickListener {
            if (!playerEngine.isAudioEnabled()) {
                Toast.makeText(requireContext(), "Audio is disabled", Toast.LENGTH_SHORT).show()
            } else {
                playerEngine.toggleMute()
            }
        }

        binding.btnSpeed.setOnClickListener {
            toggleSpeedControl(true)
        }

        binding.btnShuffle.setOnClickListener { toggleShuffle() }

        binding.btnLoop.setOnClickListener {
            currentLoopMode = when (currentLoopMode) {
                LoopMode.OFF -> LoopMode.ALL
                LoopMode.ALL -> LoopMode.ONE
                LoopMode.ONE -> LoopMode.OFF
            }
            updateLoopUi()
        }

        binding.btnSleepTimer.setOnClickListener {
            if (isSwitchingDecoder) return@setOnClickListener
            isSleepTimerActive = !isSleepTimerActive
            updateSleepTimerUi()
            val status = if (isSleepTimerActive) "Active" else "Inactive"
//            Toast.makeText(requireContext(), "Sleep Timer $status", Toast.LENGTH_SHORT).show()
            Toast.makeText(requireContext(), "Sleep Timer feature coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.btnBackgroundPlay.setOnClickListener {
            if (isSwitchingDecoder) return@setOnClickListener
            isBackgroundPlayActive = !isBackgroundPlayActive
            updateBackgroundPlayUi()
            val status = if (isBackgroundPlayActive) "Enabled" else "Disabled"
//            Toast.makeText(requireContext(), "Background Play $status", Toast.LENGTH_SHORT).show()
            Toast.makeText(requireContext(), "Background Play feature coming soon", Toast.LENGTH_SHORT).show()
        }
        binding.btnPlaylist.setOnClickListener {
            if (binding.uiLayers.isVisible) {
                toggleUi(false)
                uiHandler.postDelayed({ togglePlaylist(true) }, 200)
            } else {
                togglePlaylist(true)
            }
        }
        binding.playlistLayout.btnClosePlaylist.setOnClickListener { togglePlaylist(false) }
        binding.playlistLayout.playlistContainer.setOnClickListener { /* Consume clicks */ }

        binding.btnAudioTrack.setOnClickListener {
            if (binding.uiLayers.isVisible) {
                toggleUi(false)
                uiHandler.postDelayed({ toggleAudioTrackPopup(true) }, 200)
            } else {
                toggleAudioTrackPopup(true)
            }
        }

        binding.btnSubtitle.setOnClickListener {
            if (binding.uiLayers.isVisible) {
                toggleUi(false)
                uiHandler.postDelayed({ toggleSubtitlePopup(true) }, 200)
            } else {
                toggleSubtitlePopup(true)
            }
        }

        binding.btnResize.setOnClickListener { Toast.makeText(requireContext(), "Resize", Toast.LENGTH_SHORT).show() }

        binding.btnNext.setOnClickListener { playNextVideo() }
        binding.btnPrev.setOnClickListener { playPreviousVideo() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = playerEngine.getDuration()
                    if (duration > 0) {
                        val newPosition = (duration * progress) / 1000
                        binding.tvCurrentTime.text = formatDuration(newPosition)

                        // keyframe updates ke liye isScrubbing = true (Instant seek)
                        playerEngine.seekTo(newPosition, isScrubbing = true)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (isSwitchingDecoder) return // <-- LOCK CHECK
                uiHandler.removeCallbacks(hideUiRunnable)
                uiHandler.removeCallbacks(resumeRunnable) // Purana pending play cancel karein

                // Save current state and pause
                wasPlayingBeforeSeek = isPlaying
                if (isPlaying) {
                    playerEngine.pause()
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val duration = playerEngine.getDuration()
                if (duration > 0) {
                    val newPosition = (duration * (seekBar?.progress ?: 0)) / 1000

                    // Release par exact position par seek karein (isScrubbing = false)
                    playerEngine.seekTo(newPosition, isScrubbing = false)
                }

                // 200ms delay ke baad resume
                if (wasPlayingBeforeSeek) {
                    uiHandler.postDelayed(resumeRunnable, 200)
                }
                resetHideTimer()
            }
        })    }

    private fun setupSpeedControl() {
        val speedMap = mapOf(
            binding.speedControlLayout.btnSpeed025 to 0.25f,
            binding.speedControlLayout.btnSpeed050 to 0.5f,
            binding.speedControlLayout.btnSpeed075 to 0.75f,
            binding.speedControlLayout.btnSpeed100 to 1.0f,
            binding.speedControlLayout.btnSpeed125 to 1.25f,
            binding.speedControlLayout.btnSpeed150 to 1.5f,
            binding.speedControlLayout.btnSpeed175 to 1.75f,
            binding.speedControlLayout.btnSpeed200 to 2.0f,
            binding.speedControlLayout.btnSpeed250 to 2.5f,
            binding.speedControlLayout.btnSpeed300 to 3.0f,
            binding.speedControlLayout.btnSpeed350 to 3.5f,
            binding.speedControlLayout.btnSpeed400 to 4.0f
        )


        speedMap.forEach { (button, speed) ->
            button.setOnClickListener { setPlaybackSpeed(speed) }
        }

        binding.speedControlLayout.btnSpeedMinus.setOnClickListener {
            setPlaybackSpeed((playbackSpeed - 0.05f).coerceIn(0.25f, 4.0f))
        }

        binding.speedControlLayout.btnSpeedPlus.setOnClickListener {
            setPlaybackSpeed((playbackSpeed + 0.05f).coerceIn(0.25f, 4.0f))
        }

        binding.speedControlLayout.root.setOnClickListener {
            toggleSpeedControl(false)
        }

        binding.speedControlLayout.speedControlCard.setOnClickListener { /* Consume clicks */ }

        updateSpeedSelectionUi()
    }
    private fun updateKeepScreenOnFlag() {
        // Agar checkbox ON hai TOH hamesha ON, warna sirf Play hote waqt ON
        val shouldKeepOn = isKeepScreenOn || isPlaying
        if (shouldKeepOn) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRepeatableClick(button: ImageButton, action: () -> Unit) {
        button.setOnClickListener { action() }

        button.setOnLongClickListener {
            syncRunnable = object : Runnable {
                override fun run() {
                    action()
                    syncHandler.postDelayed(this, 100) // 100ms interval
                }
            }
            syncHandler.post(syncRunnable!!)
            true
        }

        button.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                syncRunnable?.let { syncHandler.removeCallbacks(it) }
                syncRunnable = null
            }
            false
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun setupMoreMenu() {
        binding.moreMenuLayout.btnCloseMoreMenu.setOnClickListener { toggleMoreMenu(false) }
        binding.moreMenuLayout.moreMenuContainer.setOnClickListener { /* Consume Clicks */ }

        // --- Show Time on Title Bar ---
        binding.moreMenuLayout.cbShowTime.isChecked = isShowTimeEnabled
        binding.moreMenuLayout.cbShowTime.setOnCheckedChangeListener { _, isChecked ->
            isShowTimeEnabled = isChecked
            saveSetting("show_time", isChecked)
            binding.tvClock.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) timeHandler.post(timeRunnable) else timeHandler.removeCallbacks(timeRunnable)
        }

        // --- Keep Screen On ---
        binding.moreMenuLayout.cbKeepScreenOn.isChecked = isKeepScreenOn
        binding.moreMenuLayout.cbKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            isKeepScreenOn = isChecked
            saveSetting("keep_screen_on", isChecked)
            updateKeepScreenOnFlag()
        }
        // --- Seek Speed UI Sync ---
        // Menu khulne par current value set karein
        binding.moreMenuLayout.tvSeekSpeedValue.text = seekSpeedValue.toString()
        binding.moreMenuLayout.tvDoubleTapValue.text = doubleTapValue.toString()

        // 3. Seek Speed
        binding.moreMenuLayout.cbSeekSpeed.isChecked = isSeekSpeedEnabled
        binding.moreMenuLayout.seekSpeedControlRow.visibility = if (isSeekSpeedEnabled) View.VISIBLE else View.GONE
        binding.moreMenuLayout.cbSeekSpeed.setOnCheckedChangeListener { _, isChecked ->
            isSeekSpeedEnabled = isChecked
            saveSetting("seek_speed_enabled", isChecked)
            binding.moreMenuLayout.seekSpeedControlRow.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.moreMenuLayout.tvSeekSpeedValue.text = seekSpeedValue.toString()
        setupRepeatableClick(binding.moreMenuLayout.btnSeekSpeedMinus) {
            seekSpeedValue = (seekSpeedValue - 1).coerceAtLeast(1)
            binding.moreMenuLayout.tvSeekSpeedValue.text = seekSpeedValue.toString()
            saveSetting("seek_speed_value", seekSpeedValue)
        }
        setupRepeatableClick(binding.moreMenuLayout.btnSeekSpeedPlus) {
            seekSpeedValue = (seekSpeedValue + 1).coerceAtMost(100)
            binding.moreMenuLayout.tvSeekSpeedValue.text = seekSpeedValue.toString()
            saveSetting("seek_speed_value", seekSpeedValue)
        }

        // 4. Double Tap Seek
        binding.moreMenuLayout.cbDoubleTapSeek.isChecked = isDoubleTapSeekEnabled
        binding.moreMenuLayout.doubleTapControlRow.visibility = if (isDoubleTapSeekEnabled) View.VISIBLE else View.GONE
        binding.moreMenuLayout.cbDoubleTapSeek.setOnCheckedChangeListener { _, isChecked ->
            isDoubleTapSeekEnabled = isChecked
            saveSetting("double_tap_enabled", isChecked)
            binding.moreMenuLayout.doubleTapControlRow.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.moreMenuLayout.tvDoubleTapValue.text = doubleTapValue.toString()
        setupRepeatableClick(binding.moreMenuLayout.btnDoubleTapMinus) {
            doubleTapValue = (doubleTapValue - 1).coerceAtLeast(1)
            binding.moreMenuLayout.tvDoubleTapValue.text = doubleTapValue.toString()
            saveSetting("double_tap_value", doubleTapValue)
        }
        setupRepeatableClick(binding.moreMenuLayout.btnDoubleTapPlus) {
            doubleTapValue = (doubleTapValue + 1).coerceAtMost(60)
            binding.moreMenuLayout.tvDoubleTapValue.text = doubleTapValue.toString()
            saveSetting("double_tap_value", doubleTapValue)
        }
    }

    private fun toggleMoreMenu(show: Boolean) {
        if (_binding == null) return

        val moreMenuRoot = binding.moreMenuLayout.root
        val rootContainer = binding.root
        moreMenuRoot.animate().cancel()

        if (show) {
            val showAction = Runnable {
                if (_binding == null) return@Runnable

                moreMenuRoot.visibility = View.VISIBLE
                moreMenuRoot.bringToFront()
                moreMenuRoot.translationZ = 180f // Higher elevation

                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val constraintSet = ConstraintSet()
                constraintSet.clone(rootContainer)
                constraintSet.clear(moreMenuRoot.id)

                if (isLandscape) {
                    binding.moreMenuLayout.moreMenuContainer.setBackgroundResource(R.drawable.bg_playlist_gradient_land)
                    val targetWidth = (rootContainer.width * 0.5).toInt()
                    constraintSet.constrainWidth(moreMenuRoot.id, targetWidth)
                    constraintSet.constrainHeight(moreMenuRoot.id, ConstraintSet.MATCH_CONSTRAINT)
                    constraintSet.connect(moreMenuRoot.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                    constraintSet.connect(moreMenuRoot.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                    constraintSet.connect(moreMenuRoot.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                } else {
                    binding.moreMenuLayout.moreMenuContainer.setBackgroundResource(R.drawable.bg_playlist_gradient)
                    val targetHeight = (rootContainer.height * 0.55).toInt()
                    constraintSet.constrainWidth(moreMenuRoot.id, ConstraintSet.MATCH_CONSTRAINT)
                    constraintSet.constrainHeight(moreMenuRoot.id, targetHeight)
                    constraintSet.connect(moreMenuRoot.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                    constraintSet.connect(moreMenuRoot.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                    constraintSet.connect(moreMenuRoot.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                }
                constraintSet.applyTo(rootContainer)

                if (isVlcActive) {
                    moreMenuRoot.scaleX = 0.95f
                    moreMenuRoot.scaleY = 0.95f
                    moreMenuRoot.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                } else {
                    if (isLandscape) {
                        moreMenuRoot.translationX = rootContainer.width.toFloat()
                    } else {
                        moreMenuRoot.translationY = rootContainer.height.toFloat()
                    }
                    moreMenuRoot.animate().translationX(0f).translationY(0f).setDuration(350).setInterpolator(DecelerateInterpolator()).start()
                }
                uiHandler.removeCallbacks(hideUiRunnable)
            }
            if (isVlcActive) {
                uiHandler.postDelayed(showAction, 200)
            } else {
                showAction.run()
            }
        } else {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val animator = if (isLandscape) {
                moreMenuRoot.animate().translationX(moreMenuRoot.width.toFloat())
            } else {
                moreMenuRoot.animate().translationY(moreMenuRoot.height.toFloat())
            }
            animator.setDuration(300).withEndAction {
                moreMenuRoot.visibility = View.GONE
            }.start()
            resetHideTimer()
        }
    }

    private fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = String.format("%.2f", speed).toFloat()
        playerEngine.setPlaybackSpeed(playbackSpeed)
        updateSpeedSelectionUi()
        // Speed HUD removed as requested
    }

    private fun updateSpeedSelectionUi() {
        val speedButtons = listOf(
            binding.speedControlLayout.btnSpeed025, binding.speedControlLayout.btnSpeed050,
            binding.speedControlLayout.btnSpeed075, binding.speedControlLayout.btnSpeed100,
            binding.speedControlLayout.btnSpeed125, binding.speedControlLayout.btnSpeed150,
            binding.speedControlLayout.btnSpeed175, binding.speedControlLayout.btnSpeed200,
            binding.speedControlLayout.btnSpeed250, binding.speedControlLayout.btnSpeed300,
            binding.speedControlLayout.btnSpeed350, binding.speedControlLayout.btnSpeed400
        )

        val whiteColor = ContextCompat.getColor(requireContext(), R.color.white)
        binding.speedControlLayout.tvCurrentSpeed.text = String.format("%.2fx", playbackSpeed)

        val speedText = if (playbackSpeed % 1.0f == 0.0f || (playbackSpeed * 10) % 1.0f == 0.0f) {
            String.format("%.1fx", playbackSpeed)
        } else {
            String.format("%.2fx", playbackSpeed)
        }
        binding.btnSpeed.text = speedText
        binding.btnSpeed.setTypeface(null, Typeface.BOLD)

        speedButtons.forEach { button ->
            val btnSpeed = button.text.toString().replace("x", "").toFloat()
            if (abs(btnSpeed - playbackSpeed) < 0.01f) {
                button.setBackgroundResource(R.drawable.bg_speed_badge)
            } else {
                button.setBackgroundResource(R.drawable.bg_icon_circle)
            }
        }

        if (abs(playbackSpeed - 1.0f) > 0.01f) {
            binding.btnSpeed.setBackgroundResource(R.drawable.bg_speed_badge)
        } else {
            binding.btnSpeed.setBackgroundResource(R.drawable.bg_icon_circle)
        }
    }

    private fun toggleSpeedControl(show: Boolean) {
        if (_binding == null) return

        if (show) {
            toggleUi(false)
            binding.speedControlLayout.root.visibility = View.VISIBLE
            binding.speedControlLayout.root.alpha = 0f
            binding.speedControlLayout.root.animate()
                .alpha(1f)
                .setDuration(200)
                .start()
            uiHandler.removeCallbacks(hideUiRunnable)
        } else {
            binding.speedControlLayout.root.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    binding.speedControlLayout.root.visibility = View.GONE
                }
                .start()
            resetHideTimer()
        }
    }

    private fun toggleShuffle() {
        isShuffleOn = !isShuffleOn
        val whiteColor = ContextCompat.getColor(requireContext(), R.color.white)

        if (isShuffleOn) {
            binding.btnShuffle.apply {
                setBackgroundResource(R.drawable.bg_speed_badge)
                imageTintList = ColorStateList.valueOf(whiteColor)
            }

            val currentFile = currentMediaFile
            if (currentFile != null) {
                val others = playlist.filter { it.id != currentFile.id }.shuffled()
                playlist = listOf(currentFile) + others
            } else {
                playlist = playlist.shuffled()
            }
        } else {
            binding.btnShuffle.apply {
                setBackgroundResource(R.drawable.bg_icon_circle)
                imageTintList = ColorStateList.valueOf(whiteColor)
            }
            playlist = originalPlaylist
        }
        updatePlaylistUi()
    }

    private fun playNextVideo(autoLoop: Boolean = false) {
        if (playlist.isEmpty()) {
            return
        }
        val currentIndex = playlist.indexOfFirst { it.id == currentMediaFile?.id }
        if (currentIndex < playlist.size - 1) {
            playVideo(playlist[currentIndex + 1])
        } else if (autoLoop) {
            playVideo(playlist[0])
        } else {
            Toast.makeText(requireContext(), "No more videos in playlist", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playPreviousVideo() {
        if (playlist.isEmpty()) {
            Toast.makeText(requireContext(), "Playlist is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val currentIndex = playlist.indexOfFirst { it.id == currentMediaFile?.id }
        if (currentIndex > 0) {
            playVideo(playlist[currentIndex - 1])
        } else {
            Toast.makeText(requireContext(), "This is the first video", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLoopUi() {
        val whiteColor = ContextCompat.getColor(requireContext(), R.color.white)
        when (currentLoopMode) {
            LoopMode.OFF -> {
                binding.btnLoop.apply {
                    setImageResource(R.drawable.ic_repeat)
                    setBackgroundResource(R.drawable.bg_icon_circle)
                    imageTintList = ColorStateList.valueOf(whiteColor)
                }
            }
            LoopMode.ALL -> {
                binding.btnLoop.apply {
                    setImageResource(R.drawable.ic_repeat)
                    setBackgroundResource(R.drawable.bg_speed_badge)
                    imageTintList = ColorStateList.valueOf(whiteColor)
                }
            }
            LoopMode.ONE -> {
                binding.btnLoop.apply {
                    setImageResource(R.drawable.ic_repeat_one)
                    setBackgroundResource(R.drawable.bg_speed_badge)
                    imageTintList = ColorStateList.valueOf(whiteColor)
                }
            }
        }
    }

    private fun updateBackgroundPlayUi() {
        val whiteColor = ContextCompat.getColor(requireContext(), R.color.white)
        if (isBackgroundPlayActive) {
            binding.btnBackgroundPlay.apply {
                setBackgroundResource(R.drawable.bg_speed_badge)
                imageTintList = ColorStateList.valueOf(whiteColor)
            }
        } else {
            binding.btnBackgroundPlay.apply {
                setBackgroundResource(R.drawable.bg_icon_circle)
                imageTintList = ColorStateList.valueOf(whiteColor)
            }
        }
    }

    private fun updateSleepTimerUi() {
        val whiteColor = ContextCompat.getColor(requireContext(), R.color.white)
        if (isSleepTimerActive) {
            binding.btnSleepTimer.apply {
                setBackgroundResource(R.drawable.bg_speed_badge)
                imageTintList = ColorStateList.valueOf(whiteColor)
            }
        } else {
            binding.btnSleepTimer.apply {
                setBackgroundResource(R.drawable.bg_icon_circle)
                imageTintList = ColorStateList.valueOf(whiteColor)
            }
        }
    }
    private fun togglePlaylist(show: Boolean) {
        if (_binding == null) return

        val playlistRoot = binding.playlistLayout.root
        val rootContainer = binding.root
        playlistRoot.animate().cancel()

        if (show) {
            val showAction = Runnable {
                if (_binding == null) return@Runnable

                playlistRoot.visibility = View.VISIBLE
                playlistRoot.bringToFront()
                playlistRoot.translationZ = 150f

                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

                val constraintSet = ConstraintSet()
                constraintSet.clone(rootContainer)
                constraintSet.clear(playlistRoot.id)

                if (isLandscape) {
                    binding.playlistLayout.playlistContainer.setBackgroundResource(R.drawable.bg_playlist_gradient_land)
                    val targetWidth = (rootContainer.width * 0.5).toInt()
                    constraintSet.constrainWidth(playlistRoot.id, targetWidth)
                    constraintSet.constrainHeight(playlistRoot.id, ConstraintSet.MATCH_CONSTRAINT)
                    constraintSet.connect(playlistRoot.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                    constraintSet.connect(playlistRoot.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                    constraintSet.connect(playlistRoot.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                } else {
                    binding.playlistLayout.playlistContainer.setBackgroundResource(R.drawable.bg_playlist_gradient)
                    val targetHeight = (rootContainer.height * 0.55).toInt()
                    constraintSet.constrainWidth(playlistRoot.id, ConstraintSet.MATCH_CONSTRAINT)
                    constraintSet.constrainHeight(playlistRoot.id, targetHeight)
                    constraintSet.connect(playlistRoot.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                    constraintSet.connect(playlistRoot.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                    constraintSet.connect(playlistRoot.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                }
                constraintSet.applyTo(rootContainer)

                if (isVlcActive) {
                    playlistRoot.translationX = 0f
                    playlistRoot.translationY = 0f
                    playlistRoot.scaleX = 0.95f
                    playlistRoot.scaleY = 0.95f

                    playlistRoot.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .setDuration(150)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            playlistRoot.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(100)
                                .start()
                        }
                        .start()
                } else {
                    if (isLandscape) {
                        playlistRoot.translationX = rootContainer.width.toFloat()
                        playlistRoot.translationY = 0f
                    } else {
                        playlistRoot.translationY = rootContainer.height.toFloat()
                        playlistRoot.translationX = 0f
                    }

                    playlistRoot.post {
                        playlistRoot.animate()
                            .translationX(0f)
                            .translationY(0f)
                            .setDuration(350)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
                uiHandler.removeCallbacks(hideUiRunnable)
            }

            if (isVlcActive) {
                uiHandler.postDelayed(showAction, 200)
            } else {
                showAction.run()
            }
        } else {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val animator = if (isLandscape) {
                playlistRoot.animate().translationX(playlistRoot.width.toFloat())
            } else {
                playlistRoot.animate().translationY(playlistRoot.height.toFloat())
            }

            animator.setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    playlistRoot.visibility = View.GONE
                    playlistRoot.translationX = 0f
                    playlistRoot.translationY = 0f
                    playlistRoot.scaleX = 1.0f
                    playlistRoot.scaleY = 1.0f
                }
                .start()
            resetHideTimer()
        }
    }

    private fun toggleAudioTrackPopup(show: Boolean) {
        if (_binding == null) return

        val audioTrackRoot = binding.audioTrackLayout.root
        val rootContainer = binding.root
        audioTrackRoot.animate().cancel()

        if (show) {
            updateAudioTracksUi()
            val showAction = Runnable {
                if (_binding == null) return@Runnable

                audioTrackRoot.visibility = View.VISIBLE
                audioTrackRoot.bringToFront()
                audioTrackRoot.translationZ = 160f

                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

                val constraintSet = ConstraintSet()
                constraintSet.clone(rootContainer)
                constraintSet.clear(audioTrackRoot.id)

                if (isLandscape) {
                    binding.audioTrackLayout.audioTrackContainer.setBackgroundResource(R.drawable.bg_playlist_gradient_land)
                    val targetWidth = (rootContainer.width * 0.5).toInt()
                    constraintSet.constrainWidth(audioTrackRoot.id, targetWidth)
                    constraintSet.constrainHeight(audioTrackRoot.id, ConstraintSet.MATCH_CONSTRAINT)
                    constraintSet.connect(audioTrackRoot.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                    constraintSet.connect(audioTrackRoot.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                    constraintSet.connect(audioTrackRoot.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                } else {
                    binding.audioTrackLayout.audioTrackContainer.setBackgroundResource(R.drawable.bg_playlist_gradient)
                    val targetHeight = (rootContainer.height * 0.55).toInt()
                    constraintSet.constrainWidth(audioTrackRoot.id, ConstraintSet.MATCH_CONSTRAINT)
                    constraintSet.constrainHeight(audioTrackRoot.id, targetHeight)
                    constraintSet.connect(audioTrackRoot.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                    constraintSet.connect(audioTrackRoot.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                    constraintSet.connect(audioTrackRoot.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                }
                constraintSet.applyTo(rootContainer)

                if (isVlcActive) {
                    audioTrackRoot.translationX = 0f
                    audioTrackRoot.translationY = 0f
                    audioTrackRoot.scaleX = 0.95f
                    audioTrackRoot.scaleY = 0.95f

                    audioTrackRoot.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .setDuration(150)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            audioTrackRoot.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(100)
                                .start()
                        }
                        .start()
                } else {
                    if (isLandscape) {
                        audioTrackRoot.translationX = rootContainer.width.toFloat()
                        audioTrackRoot.translationY = 0f
                    } else {
                        audioTrackRoot.translationY = rootContainer.height.toFloat()
                        audioTrackRoot.translationX = 0f
                    }

                    audioTrackRoot.post {
                        audioTrackRoot.animate()
                            .translationX(0f)
                            .translationY(0f)
                            .setDuration(350)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
                uiHandler.removeCallbacks(hideUiRunnable)
            }

            if (isVlcActive) {
                uiHandler.postDelayed(showAction, 200)
            } else {
                showAction.run()
            }
        } else {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val animator = if (isLandscape) {
                audioTrackRoot.animate().translationX(audioTrackRoot.width.toFloat())
            } else {
                audioTrackRoot.animate().translationY(audioTrackRoot.height.toFloat())
            }

            animator.setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    audioTrackRoot.visibility = View.GONE
                    audioTrackRoot.translationX = 0f
                    audioTrackRoot.translationY = 0f
                    audioTrackRoot.scaleX = 1.0f
                    audioTrackRoot.scaleY = 1.0f
                    binding.audioTrackLayout.rgStereoOptions.visibility = View.GONE
                }
                .start()
            resetHideTimer()
        }
    }

    private fun toggleSubtitlePopup(show: Boolean) {
        if (_binding == null) return

        val subtitleRoot = binding.subtitleLayout.root
        val rootContainer = binding.root
        subtitleRoot.animate().cancel()

        if (show) {
            updateSubtitleTracksUi()
            val showAction = Runnable {
                if (_binding == null) return@Runnable

                subtitleRoot.visibility = View.VISIBLE
                subtitleRoot.bringToFront()
                subtitleRoot.translationZ = 165f

                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

                val constraintSet = ConstraintSet()
                constraintSet.clone(rootContainer)
                constraintSet.clear(subtitleRoot.id)

                if (isLandscape) {
                    binding.subtitleLayout.subtitleContainer.setBackgroundResource(R.drawable.bg_playlist_gradient_land)
                    val targetWidth = (rootContainer.width * 0.5).toInt()
                    constraintSet.constrainWidth(subtitleRoot.id, targetWidth)
                    constraintSet.constrainHeight(subtitleRoot.id, ConstraintSet.MATCH_CONSTRAINT)
                    constraintSet.connect(subtitleRoot.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                    constraintSet.connect(subtitleRoot.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                    constraintSet.connect(subtitleRoot.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                } else {
                    binding.subtitleLayout.subtitleContainer.setBackgroundResource(R.drawable.bg_playlist_gradient)
                    val targetHeight = (rootContainer.height * 0.55).toInt()
                    constraintSet.constrainWidth(subtitleRoot.id, ConstraintSet.MATCH_CONSTRAINT)
                    constraintSet.constrainHeight(subtitleRoot.id, targetHeight)
                    constraintSet.connect(subtitleRoot.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                    constraintSet.connect(subtitleRoot.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                    constraintSet.connect(subtitleRoot.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                }
                constraintSet.applyTo(rootContainer)

                if (isVlcActive) {
                    subtitleRoot.translationX = 0f
                    subtitleRoot.translationY = 0f
                    subtitleRoot.scaleX = 0.95f
                    subtitleRoot.scaleY = 0.95f

                    subtitleRoot.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .setDuration(150)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            subtitleRoot.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(100)
                                .start()
                        }
                        .start()
                } else {
                    if (isLandscape) {
                        subtitleRoot.translationX = rootContainer.width.toFloat()
                        subtitleRoot.translationY = 0f
                    } else {
                        subtitleRoot.translationY = rootContainer.height.toFloat()
                        subtitleRoot.translationX = 0f
                    }

                    subtitleRoot.post {
                        subtitleRoot.animate()
                            .translationX(0f)
                            .translationY(0f)
                            .setDuration(350)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
                uiHandler.removeCallbacks(hideUiRunnable)
            }

            if (isVlcActive) {
                uiHandler.postDelayed(showAction, 200)
            } else {
                showAction.run()
            }
        } else {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val animator = if (isLandscape) {
                subtitleRoot.animate().translationX(subtitleRoot.width.toFloat())
            } else {
                subtitleRoot.animate().translationY(subtitleRoot.height.toFloat())
            }

            animator.setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    subtitleRoot.visibility = View.GONE
                    subtitleRoot.translationX = 0f
                    subtitleRoot.translationY = 0f
                    subtitleRoot.scaleX = 1.0f
                    subtitleRoot.scaleY = 1.0f
                }
                .start()
            resetHideTimer()
        }
    }

    private fun toggleSubtitleFilePopup(show: Boolean) {
        if (_binding == null) return

        val subtitleFileRoot = binding.subtitleFileLayout.root
        val rootContainer = binding.root
        subtitleFileRoot.animate().cancel()

        if (show) {
            loadSubtitleFiles()
            val showAction = Runnable {
                if (_binding == null) return@Runnable

                subtitleFileRoot.visibility = View.VISIBLE
                subtitleFileRoot.bringToFront()
                subtitleFileRoot.translationZ = 166f

                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

                val constraintSet = ConstraintSet()
                constraintSet.clone(rootContainer)
                constraintSet.clear(subtitleFileRoot.id)

                if (isLandscape) {
                    binding.subtitleFileLayout.subtitleTrackContainer.setBackgroundResource(R.drawable.bg_playlist_gradient_land)
                    val targetWidth = (rootContainer.width * 0.5).toInt()
                    constraintSet.constrainWidth(subtitleFileRoot.id, targetWidth)
                    constraintSet.constrainHeight(subtitleFileRoot.id, ConstraintSet.MATCH_CONSTRAINT)
                    constraintSet.connect(subtitleFileRoot.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                    constraintSet.connect(subtitleFileRoot.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                    constraintSet.connect(subtitleFileRoot.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                } else {
                    binding.subtitleFileLayout.subtitleTrackContainer.setBackgroundResource(R.drawable.bg_playlist_gradient)
                    val targetHeight = (rootContainer.height * 0.55).toInt()
                    constraintSet.constrainWidth(subtitleFileRoot.id, ConstraintSet.MATCH_CONSTRAINT)
                    constraintSet.constrainHeight(subtitleFileRoot.id, targetHeight)
                    constraintSet.connect(subtitleFileRoot.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                    constraintSet.connect(subtitleFileRoot.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                    constraintSet.connect(subtitleFileRoot.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                }
                constraintSet.applyTo(rootContainer)

                if (isVlcActive) {
                    subtitleFileRoot.translationX = 0f
                    subtitleFileRoot.translationY = 0f
                    subtitleFileRoot.scaleX = 0.95f
                    subtitleFileRoot.scaleY = 0.95f

                    subtitleFileRoot.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .setDuration(150)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            subtitleFileRoot.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(100)
                                .start()
                        }
                        .start()
                } else {
                    if (isLandscape) {
                        subtitleFileRoot.translationX = rootContainer.width.toFloat()
                        subtitleFileRoot.translationY = 0f
                    } else {
                        subtitleFileRoot.translationY = rootContainer.height.toFloat()
                        subtitleFileRoot.translationX = 0f
                    }

                    subtitleFileRoot.post {
                        subtitleFileRoot.animate()
                            .translationX(0f)
                            .translationY(0f)
                            .setDuration(350)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
                uiHandler.removeCallbacks(hideUiRunnable)
            }

            if (isVlcActive) {
                uiHandler.postDelayed(showAction, 200)
            } else {
                showAction.run()
            }
        } else {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val animator = if (isLandscape) {
                subtitleFileRoot.animate().translationX(subtitleFileRoot.width.toFloat())
            } else {
                subtitleFileRoot.animate().translationY(subtitleFileRoot.height.toFloat())
            }

            animator.setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    subtitleFileRoot.visibility = View.GONE
                    subtitleFileRoot.translationX = 0f
                    subtitleFileRoot.translationY = 0f
                    subtitleFileRoot.scaleX = 1.0f
                    subtitleFileRoot.scaleY = 1.0f
                }
                .start()
            resetHideTimer()
        }
    }

    private fun toggleSubtitleCustomizationPopup(show: Boolean) {
        if (_binding == null) return

        val customizationRoot = binding.subtitleCustomizationLayout.root
        val rootContainer = binding.root
        customizationRoot.animate().cancel()

        if (show) {
            val showAction = Runnable {
                if (_binding == null) return@Runnable

                customizationRoot.visibility = View.VISIBLE
                customizationRoot.bringToFront()
                customizationRoot.translationZ = 170f

                val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

                val constraintSet = ConstraintSet()
                constraintSet.clone(rootContainer)
                constraintSet.clear(customizationRoot.id)

                if (isLandscape) {
                    binding.subtitleCustomizationLayout.subtitleCustomizationContainer.setBackgroundResource(R.drawable.bg_playlist_gradient_land)
                    val targetWidth = (rootContainer.width * 0.5).toInt()
                    constraintSet.constrainWidth(customizationRoot.id, targetWidth)
                    constraintSet.constrainHeight(customizationRoot.id, ConstraintSet.MATCH_CONSTRAINT)
                    constraintSet.connect(customizationRoot.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                    constraintSet.connect(customizationRoot.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                    constraintSet.connect(customizationRoot.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                } else {
                    binding.subtitleCustomizationLayout.subtitleCustomizationContainer.setBackgroundResource(R.drawable.bg_playlist_gradient)
                    val targetHeight = (rootContainer.height * 0.55).toInt()
                    constraintSet.constrainWidth(customizationRoot.id, ConstraintSet.MATCH_CONSTRAINT)
                    constraintSet.constrainHeight(customizationRoot.id, targetHeight)
                    constraintSet.connect(customizationRoot.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                    constraintSet.connect(customizationRoot.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                    constraintSet.connect(customizationRoot.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                }
                constraintSet.applyTo(rootContainer)

                if (isVlcActive) {
                    customizationRoot.translationX = 0f
                    customizationRoot.translationY = 0f
                    customizationRoot.scaleX = 0.95f
                    customizationRoot.scaleY = 0.95f

                    customizationRoot.animate()
                        .scaleX(1.05f)
                        .scaleY(1.05f)
                        .setDuration(150)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            customizationRoot.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .setDuration(100)
                                .start()
                        }
                        .start()
                } else {
                    if (isLandscape) {
                        customizationRoot.translationX = rootContainer.width.toFloat()
                        customizationRoot.translationY = 0f
                    } else {
                        customizationRoot.translationY = rootContainer.height.toFloat()
                        customizationRoot.translationX = 0f
                    }

                    customizationRoot.post {
                        customizationRoot.animate()
                            .translationX(0f)
                            .translationY(0f)
                            .setDuration(350)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                }
                uiHandler.removeCallbacks(hideUiRunnable)
            }

            if (isVlcActive) {
                uiHandler.postDelayed(showAction, 200)
            } else {
                showAction.run()
            }
        } else {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val animator = if (isLandscape) {
                customizationRoot.animate().translationX(customizationRoot.width.toFloat())
            } else {
                customizationRoot.animate().translationY(customizationRoot.height.toFloat())
            }

            animator.setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    customizationRoot.visibility = View.GONE
                    customizationRoot.translationX = 0f
                    customizationRoot.translationY = 0f
                    customizationRoot.scaleX = 1.0f
                    customizationRoot.scaleY = 1.0f
                    binding.subtitleCustomizationLayout.alignmentPopup.visibility = View.GONE
                    binding.subtitleCustomizationLayout.textColorPopup.visibility = View.GONE
                }
                .start()
            resetHideTimer()
        }
    }

    private fun toggleOrientation() {
        val currentOrientation = resources.configuration.orientation
        activity?.requestedOrientation = if (currentOrientation == Configuration.ORIENTATION_PORTRAIT)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    private fun updateProgress() {
        if (_binding == null) return
        val currentPos = playerEngine.getCurrentPosition()
        val duration = playerEngine.getDuration()
        if (duration > 0) {
            binding.seekBar.progress = ((currentPos * 1000) / duration).toInt()
            binding.tvCurrentTime.text = formatDuration(currentPos)
            // Toggle mode ke according text set karein
            if (isRemainingTimeMode) {
                val remainingTime = duration - currentPos
                binding.tvTotalTime.text = "-${formatDuration(remainingTime)}"
            } else {
                binding.tvTotalTime.text = formatDuration(duration)
            }
        }
    }

    private fun setupGestures() {
        // Class level variable use kar rahe hain (val hata diya)
        gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isSwitchingDecoder) return true
                if (binding.moreMenuLayout.root.isVisible) {
                    toggleMoreMenu(false)
                    return true
                }
                if (binding.subtitleFileLayout.root.isVisible) { toggleSubtitleFilePopup(false); return true }
                if (binding.playlistLayout.root.isVisible) { togglePlaylist(false); return true }
                if (binding.audioTrackLayout.root.isVisible) { toggleAudioTrackPopup(false); return true }
                if (binding.subtitleLayout.root.isVisible) { toggleSubtitlePopup(false); return true }

                if (binding.subtitleCustomizationLayout.root.isVisible) {
                    if (binding.subtitleCustomizationLayout.textColorPopup.isVisible) {
                        binding.subtitleCustomizationLayout.textColorPopup.visibility = View.GONE
                        return true
                    }
                    if (binding.subtitleCustomizationLayout.alignmentPopup.isVisible) {
                        binding.subtitleCustomizationLayout.alignmentPopup.visibility = View.GONE
                        return true
                    }
                    handleCustomizationExit()
                    return true
                }
                if (binding.speedControlLayout.root.isVisible) { toggleSpeedControl(false); return true }

                if (!isLocked) toggleUi(!binding.uiLayers.isVisible)
                else {
                    binding.btnUnlock.visibility = View.VISIBLE
                    uiHandler.postDelayed({ if (isLocked) _binding?.btnUnlock?.visibility = View.GONE }, 2000)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {

                if (isSwitchingDecoder || !isDoubleTapSeekEnabled || isLocked || binding.playlistLayout.root.isVisible || binding.audioTrackLayout.root.isVisible || binding.subtitleLayout.root.isVisible || binding.subtitleCustomizationLayout.root.isVisible || binding.speedControlLayout.root.isVisible) return false

                val seekAmount = doubleTapValue * 1000L
                val currentPos = playerEngine.getCurrentPosition()

                if (e.x < binding.root.width / 2) {
                    playerEngine.seekTo(maxOf(0L, currentPos - seekAmount))
                    showSeekIndicator(isLeft = true)
                } else {
                    playerEngine.seekTo(minOf(playerEngine.getDuration(), currentPos + seekAmount))
                    showSeekIndicator(isLeft = false)
                }
                binding.tvSeekLeft.text = "${doubleTapValue}s"
                binding.tvSeekRight.text = "${doubleTapValue}s"
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float): Boolean {
                if (isSwitchingDecoder || e1 == null || isLocked || binding.playlistLayout.root.isVisible || binding.audioTrackLayout.root.isVisible || binding.subtitleLayout.root.isVisible || binding.subtitleCustomizationLayout.root.isVisible || binding.speedControlLayout.root.isVisible) return false

                // --- GESTURE LOCKING LOGIC ---
                // --- TWO FINGER SPEED GESTURE ---
                if (e2.pointerCount == 2) {
                    // Multi-finger gesture detected, perform speed adjustment
                    adjustPlaybackSpeed(dY / binding.root.height)
                    return true
                }

                // 1. Agar Seek gesture (Horizontal) already lock ho chuka hai
                if (isGestureSeeking) {
                    // Feature ON hai tabhi seek logic chalega
                    if (isSeekSpeedEnabled) adjustSeek(-dX)
                    return true
                }

                // 2. Agar Volume/Brightness (Vertical) already lock ho chuka hai
                val isVerticalLocked = binding.volumeIndicatorCard.isVisible || binding.brightnessIndicatorCard.isVisible
                if (isVerticalLocked) {
                    if (e1.x < binding.root.width / 2) adjustBrightness(dY / binding.root.height)
                    else adjustVolume(dY / binding.root.height)
                    return true
                }

                // 3. Mode Selection (Pehli baar swipe par decide karein)
                if (abs(dX) > abs(dY)) {
                    // Horizontal Swipe: Sirf tabhi mode change karein aur seek karein jab feature ON ho
                    if (isSeekSpeedEnabled) {
                        adjustSeek(-dX)
                    }
                } else {
                    // Vertical Swipe: Volume/Brightness hamesha kaam karenge
                    if (e1.x < binding.root.width / 2) adjustBrightness(dY / binding.root.height)
                    else adjustVolume(dY / binding.root.height)
                }

                return true
            }
        })

        binding.root.setOnTouchListener { _, event ->
            // 1. GestureDetector ko har event pass karein (Zaroori hai!)
            gestureDetector.onTouchEvent(event)

            if (event.action == MotionEvent.ACTION_DOWN) {
                volumeScrollAccumulator = 0f
            }

            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                // Swipe seek khatam hone par play resume karein
                if (isGestureSeeking) {
                    isGestureSeeking = false
                    playerEngine.seekTo(playerEngine.getCurrentPosition(), isScrubbing = false)
                    if (wasPlayingBeforeSeek) {
                        uiHandler.postDelayed(resumeRunnable, 200)
                    }
                }

                // Saare indicators chhupayein
                uiHandler.postDelayed({
                    _binding?.volumeIndicatorCard?.visibility = View.GONE
                    _binding?.brightnessIndicatorCard?.visibility = View.GONE
                    _binding?.seekIndicatorCard?.visibility = View.GONE
                    _binding?.speedIndicatorCard?.visibility = View.GONE // <--- Ye add karein
                }, 800)
            }
            true
        }
    }
    private fun showSeekIndicator(isLeft: Boolean) {
        if (_binding == null) return
        val indicator = if (isLeft) binding.seekLeftIndicator else binding.seekRightIndicator
        val innerContent = if (isLeft) binding.innerSeekLeft else binding.innerSeekRight
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        // Purani saari animations cancel karein taaki overlap na ho
        indicator.animate().cancel()
        innerContent.animate().cancel()

        // Sab kuch reset karein
        indicator.alpha = 1f
        innerContent.translationX = 0f
        innerContent.alpha = 1f
        indicator.visibility = View.VISIBLE

        if (isPortrait) {
            // PORTRAIT MODE: No background, only spring movement
            indicator.background = null

            // Backward ke liye left (-80f), Forward ke liye right (80f)
            val moveX = if (isLeft) -80f else 80f

            innerContent.animate()
                .translationX(moveX)
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    // Wapas apni jagah aayega Spring effect ke saath
                    innerContent.animate()
                        .translationX(0f)
                        .setDuration(350)
                        .setInterpolator(OvershootInterpolator(2.0f)) // Spring effect
                        .withEndAction { indicator.visibility = View.GONE }
                        .start()
                }
                .start()
        } else {
            // LANDSCAPE MODE: Circular Ripple Effect
            indicator.setBackgroundResource(if (isLeft) R.drawable.bg_seek_left else R.drawable.bg_seek_right)
            indicator.alpha = 0f
            indicator.animate()
                .alpha(1f)
                .setDuration(200)
                .withEndAction {
                    uiHandler.postDelayed({
                        indicator.animate()
                            .alpha(0f)
                            .setDuration(300)
                            .withEndAction { indicator.visibility = View.GONE }
                            .start()
                    }, 400)
                }
                .start()
        }
    }



    private fun adjustVolume(delta: Float) {
        val sensitivity = 1.7f
        volumeScrollAccumulator += delta * sensitivity
        val maxVol = maxVolume.toFloat()
        val volumeChange = (volumeScrollAccumulator * maxVol).toInt()

        if (volumeChange != 0) {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val next = (current + volumeChange).coerceIn(0, maxVolume)
            val percent = ((next.toFloat() / maxVolume) * 100).toInt()

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
            playerEngine.setVolume(percent)

            // Naya UI Update
            binding.volumeIndicatorCard.visibility = View.VISIBLE
            binding.brightnessIndicatorCard.visibility = View.GONE // Ek waqt mein ek hi dikhe
            binding.pbVolume.progress = percent
            binding.tvVolumeValue.text = percent.toString()

            // Volume ke hisaab se icon change (optional but better)
            if (next == 0) {
                binding.ivVolumeIcon.setImageResource(R.drawable.ic_volume_xmark_solid)
            } else {
                binding.ivVolumeIcon.setImageResource(R.drawable.ic_volume_high_solid)
            }

            volumeScrollAccumulator -= volumeChange.toFloat() / maxVol
        }
    }

    private fun adjustPlaybackSpeed(delta: Float) {
        // Sensitivity ko 0.5f se badhakar 2.5f kar diya hai (Fast response ke liye)
        val sensitivity = 2.5f

        // Swipe Up (Positive delta) = Speed Increase
        speedScrollAccumulator += delta * sensitivity

        // 0.05 step ke liye threshold ko optimize kiya hai
        val threshold = 0.05f
        val steps = (speedScrollAccumulator / threshold).toInt()
        val speedChange = steps * 0.05f

        if (speedChange != 0f) {
            val newSpeed = (playbackSpeed + speedChange).coerceIn(0.25f, 4.0f)

            // Check if speed actually changed
            if (Math.abs(newSpeed - playbackSpeed) > 0.001f) {
                setPlaybackSpeed(newSpeed)
                showSpeedHud(newSpeed)
            }

            // Jitna change apply ho gaya, utna accumulator se subtract karein
            // Isse swipe smooth rehta hai (accumulator reset karne se jhatke lagte hain)
            speedScrollAccumulator -= steps * threshold
        }
    }
    private fun showSpeedHud(speed: Float) {
        binding.speedIndicatorCard.visibility = View.VISIBLE
        binding.volumeIndicatorCard.visibility = View.GONE
        binding.brightnessIndicatorCard.visibility = View.GONE
        binding.seekIndicatorCard.visibility = View.GONE

        binding.tvSpeedIndicatorText.text = String.format("Speed: %.2fx", speed)
    }

    private fun adjustSeek(distancePx: Float) {
        val metrics = resources.displayMetrics
        // DPI check for accurate Cm calculation
        val xdpi = if (metrics.xdpi > 0) metrics.xdpi else metrics.densityDpi.toFloat()
        val distanceCm = distancePx / (xdpi / 2.54f)

        // Seek speed (Rate) calculation
        val seekMs = (distanceCm * seekSpeedValue * 1000).toLong()

        if (!isGestureSeeking) {
            isGestureSeeking = true
            wasPlayingBeforeSeek = isPlaying
            if (isPlaying) playerEngine.pause()

            // Gesture start hone par initial position save karein
            gestureTargetPosition = playerEngine.getCurrentPosition()
            initialSeekPosition = gestureTargetPosition
        }

        // Target position ko update karein
        gestureTargetPosition = (gestureTargetPosition + seekMs).coerceIn(0L, playerEngine.getDuration())

        // keyframe updates (smooth scrubbing)
        playerEngine.seekTo(gestureTargetPosition, isScrubbing = true)

        // HUD Show karein
        showSeekHud(gestureTargetPosition, gestureTargetPosition - initialSeekPosition)
    }
    private fun showSeekHud(newPos: Long, diffMs: Long) {
        binding.seekIndicatorCard.visibility = View.VISIBLE
        binding.volumeIndicatorCard.visibility = View.GONE
        binding.brightnessIndicatorCard.visibility = View.GONE

        binding.tvSeekTime.text = formatDuration(newPos)

        val sign = if (diffMs >= 0) "+" else "-"
        val absoluteDiff = Math.abs(diffMs)
        binding.tvSeekDiff.text = "[ $sign${formatDuration(absoluteDiff)} ]"

        // Forward ke liye Green aur Backward ke liye Red color
        binding.tvSeekDiff.setTextColor(if (diffMs >= 0) Color.GREEN else Color.RED)
    }

    private fun adjustBrightness(delta: Float) {
        val lp = activity?.window?.attributes ?: return
        lp.screenBrightness = (lp.screenBrightness + delta).coerceIn(0.01f, 1.0f)
        activity?.window?.attributes = lp
        val percent = (lp.screenBrightness * 100).toInt()

        // Naya UI Update
        binding.brightnessIndicatorCard.visibility = View.VISIBLE
        binding.volumeIndicatorCard.visibility = View.GONE
        binding.pbBrightness.progress = percent
        binding.tvBrightnessValue.text = percent.toString()
    }


    private fun toggleUi(show: Boolean) {
        if (_binding == null || isLocked) return
        binding.uiLayers.visibility = if (show) View.VISIBLE else View.GONE
        if (show) resetHideTimer()
    }

    private fun resetHideTimer() {
        uiHandler.removeCallbacks(hideUiRunnable)
        if (isPlaying && !isLocked && !binding.playlistLayout.root.isVisible && !binding.audioTrackLayout.root.isVisible && !binding.subtitleLayout.root.isVisible && !binding.subtitleCustomizationLayout.root.isVisible && !binding.speedControlLayout.root.isVisible) uiHandler.postDelayed(hideUiRunnable, 3000)
    }

    private fun formatDuration(ms: Long): String {
        val s = (ms / 1000) % 60; val m = (ms / 60000) % 60; val h = ms / 3600000
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    override fun onPause() { super.onPause(); playerEngine.pause() }
    override fun onDestroyView() {
        // Agar switching chal rahi hai toh player engine ko release na karein (ya safety ke saath karein)
        if (!isSwitchingDecoder) {
            playerEngine.release()
        }
        super.onDestroyView()
        timeHandler.removeCallbacks(timeRunnable) // <-- ADD THIS
        activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // <-- ADD THIS
        (activity as? MainActivity)?.setFullScreen(false)
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        playerEngine.release()
        _binding = null
    }

    private fun loadSettings() {        isShowTimeEnabled = prefs.getBoolean("show_time", false)
        isKeepScreenOn = prefs.getBoolean("keep_screen_on", false)
        isSeekSpeedEnabled = prefs.getBoolean("seek_speed_enabled", true)
        seekSpeedValue = prefs.getInt("seek_speed_value", 10)
        isDoubleTapSeekEnabled = prefs.getBoolean("double_tap_enabled", true)
        doubleTapValue = prefs.getInt("double_tap_value", 10)

        // Initial UI state apply karein (jo menu ke bahar zaroori hai)
        if (isShowTimeEnabled) {
            binding.tvClock.visibility = View.VISIBLE
            timeHandler.post(timeRunnable)
        }
        updateKeepScreenOnFlag()
    }

    private fun saveSetting(key: String, value: Any) {
        val editor = prefs.edit()
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
        }
        editor.apply()
    }
    override fun onIndexingProgress(message: String) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            binding.bufferingLayer.visibility = View.VISIBLE
            binding.tvBufferingStatus.visibility = View.VISIBLE
            binding.tvBufferingStatus.text = message
        }
    }
}

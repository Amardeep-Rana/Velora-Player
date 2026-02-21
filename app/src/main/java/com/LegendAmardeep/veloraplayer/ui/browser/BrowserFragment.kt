package com.LegendAmardeep.veloraplayer.ui.browser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.LegendAmardeep.veloraplayer.R
import com.LegendAmardeep.veloraplayer.data.MediaRepository
import com.LegendAmardeep.veloraplayer.databinding.FragmentBrowserBinding
import kotlinx.coroutines.launch

class BrowserFragment : Fragment() {

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: BrowserViewModel
    private lateinit var adapter: MediaAdapter
    private var isGridView = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            viewModel.loadFolders(0)
        } else {
            Toast.makeText(context, "Permissions required to show media", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val repository = MediaRepository(requireContext())
        val factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return BrowserViewModel(repository) as T
            }
        }
        viewModel = ViewModelProvider(this, factory)[BrowserViewModel::class.java]

        setupRecyclerView()
        setupListeners()
        observeViewModel()
        setupBackPress()
        checkPermissionsAndLoad()

        // --- EDGE-TO-EDGE OVERLAP FIX START ---
        
        // Status Bar fix: Toolbar ko niche dhakelo
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = statusBar.top)
            insets
        }

        // Navigation Bar fix: Bottom Card ko upar dhakelo
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavCard) { v, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updateLayoutParams<MarginLayoutParams> {
                val baseMargin = (20 * resources.displayMetrics.density).toInt()
                bottomMargin = baseMargin + navBar.bottom
            }
            insets
        }
        
        // --- EDGE-TO-EDGE OVERLAP FIX END ---
    }

    private fun setupRecyclerView() {
        adapter = MediaAdapter(
            onMediaClick = { mediaFile ->
                val action = BrowserFragmentDirections.actionBrowserToPlayer(mediaFile)
                findNavController().navigate(action)
            },
            onFolderClick = { folder ->
                viewModel.onFolderClicked(folder)
            }
        )
        binding.recyclerView.adapter = adapter
        updateLayoutManager()
    }

    private fun updateLayoutManager() {
        adapter.isGridView = isGridView
        binding.recyclerView.layoutManager = if (isGridView) {
            GridLayoutManager(context, 2)
        } else {
            LinearLayoutManager(context)
        }
        binding.btnViewToggle.setImageResource(
            if (isGridView) R.drawable.ic_grid_view
            else R.drawable.ic_menu
        )
        // Refresh adapter to apply new view type
        adapter.notifyDataSetChanged()
    }

    private fun setupListeners() {
        binding.btnVideoTab.setOnClickListener {
            viewModel.loadFolders(0)
            updateFabSelection(true)
        }
        binding.btnMusicTab.setOnClickListener {
            viewModel.loadFolders(1)
            updateFabSelection(false)
        }
        binding.btnViewToggle.setOnClickListener {
            isGridView = !isGridView
            updateLayoutManager()
        }
    }

    private fun updateFabSelection(isVideo: Boolean) {
        val activeColor = ContextCompat.getColor(requireContext(), R.color.primary)
        val inactiveColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        
        binding.ivVideoIcon.setColorFilter(if (isVideo) activeColor else inactiveColor)
        binding.tvVideoLabel.setTextColor(if (isVideo) activeColor else inactiveColor)
        
        binding.ivMusicIcon.setColorFilter(if (isVideo) inactiveColor else activeColor)
        binding.tvMusicLabel.setTextColor(if (isVideo) inactiveColor else activeColor)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is BrowserUiState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                        }
                        is BrowserUiState.Folders -> {
                            binding.progressBar.visibility = View.GONE
                            adapter.submitList(state.folders)
                            binding.toolbar.title = "Velora Player"
                            binding.toolbar.navigationIcon = null
                            binding.bottomNavCard.visibility = View.VISIBLE
                        }
                        is BrowserUiState.FolderContent -> {
                            binding.progressBar.visibility = View.GONE
                            adapter.submitList(state.files)
                            binding.toolbar.title = state.folderName
                            binding.toolbar.navigationIcon = ContextCompat.getDrawable(
                                requireContext(), 
                                androidx.appcompat.R.drawable.abc_ic_ab_back_material
                            )
                            binding.toolbar.setNavigationOnClickListener { viewModel.navigateBack() }
                            binding.bottomNavCard.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun setupBackPress() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!viewModel.navigateBack()) {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun checkPermissionsAndLoad() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            viewModel.loadFolders(0)
            updateFabSelection(true)
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

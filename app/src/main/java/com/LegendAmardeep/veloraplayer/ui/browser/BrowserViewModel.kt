package com.LegendAmardeep.veloraplayer.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.LegendAmardeep.veloraplayer.data.MediaRepository
import com.LegendAmardeep.veloraplayer.data.model.Folder
import com.LegendAmardeep.veloraplayer.data.model.MediaFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class BrowserUiState {
    object Loading : BrowserUiState()
    data class Folders(val folders: List<Folder>, val type: Int) : BrowserUiState()
    data class FolderContent(val folderName: String, val files: List<MediaFile>) : BrowserUiState()
}

class BrowserViewModel(private val repository: MediaRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<BrowserUiState>(BrowserUiState.Loading)
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    private var currentFolders: List<Folder> = emptyList()
    private var currentTab = 0 // 0 for Videos, 1 for Audios

    fun loadFolders(tabIndex: Int) {
        currentTab = tabIndex
        viewModelScope.launch {
            _uiState.value = BrowserUiState.Loading
            currentFolders = if (tabIndex == 0) {
                repository.getVideoFolders()
            } else {
                repository.getAudioFolders()
            }
            _uiState.value = BrowserUiState.Folders(currentFolders, currentTab)
        }
    }

    fun onFolderClicked(folder: Folder) {
        _uiState.value = BrowserUiState.FolderContent(folder.name, folder.mediaFiles)
    }

    fun navigateBack(): Boolean {
        val currentState = _uiState.value
        if (currentState is BrowserUiState.FolderContent) {
            _uiState.value = BrowserUiState.Folders(currentFolders, currentTab)
            return true
        }
        return false
    }

    fun isInFolder(): Boolean {
        return _uiState.value is BrowserUiState.FolderContent
    }
}

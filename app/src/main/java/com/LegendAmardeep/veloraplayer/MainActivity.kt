package com.LegendAmardeep.veloraplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.fragment.NavHostFragment
import com.LegendAmardeep.veloraplayer.data.model.MediaFile
import com.LegendAmardeep.veloraplayer.databinding.ActivityMainBinding
import com.LegendAmardeep.veloraplayer.databinding.LayoutFolderAccessDialogBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            saveFolderUri(it)
            createRequiredFolders(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Pehle check karein ki app kisi file ko open karne ke liye khula hai ya nahi
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val fileUri = intent.data!!

            // File ka type (video/audio) pata karein
            val mimeType = contentResolver.getType(fileUri)
            val isVideo = mimeType?.startsWith("video/") == true

            // Ek dummy MediaFile banayein intent ke data se
            val mediaFile = MediaFile(
                id = System.currentTimeMillis(),
                name = "External File",
                path = "",
                contentUri = fileUri,
                duration = 0,
                size = 0,
                folder = "",
                isVideo = isVideo
            )

            // Direct PlayerFragment par navigate karein
            // Thoda delay de rahe hain taaki NavHost set up ho jaye
            binding.root.post {
                val navHostFragment =
                    supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                val navController = navHostFragment.navController

                val bundle = Bundle().apply {
                    putParcelable("mediaFile", mediaFile)
                }
                navController.navigate(R.id.playerFragment, bundle)
            }

        } else {
            // 2. Normal App Start: Folder access check karein
            if (!hasFolderAccess()) {
                showFolderAccessDialog()
            }
        }
    }
    private fun hasFolderAccess(): Boolean {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val uriString = prefs.getString("root_folder_uri", null) ?: return false
        
        return try {
            val uri = Uri.parse(uriString)
            val documentFile = DocumentFile.fromTreeUri(this, uri)
            documentFile != null && documentFile.exists() && documentFile.canRead()
        } catch (e: Exception) {
            false
        }
    }

    private fun showFolderAccessDialog() {
        val dialogBinding = LayoutFolderAccessDialogBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        // Set window background to transparent to allow CardView rounded corners to show
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.btnSelectFolder.setOnClickListener {
            openDocumentTreeLauncher.launch(null)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun saveFolderUri(uri: Uri) {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("root_folder_uri", uri.toString()).apply()
    }

    private fun createRequiredFolders(rootUri: Uri) {
        val rootDoc = DocumentFile.fromTreeUri(this, rootUri) ?: return

        // Create 'audio' folder if not exists
        if (rootDoc.findFile("audio") == null) {
            rootDoc.createDirectory("audio")
        }

        // Create 'subtitles' folder if not exists
        if (rootDoc.findFile("subtitles") == null) {
            rootDoc.createDirectory("subtitles")
        }
    }

    fun setFullScreen(fullScreen: Boolean) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView) ?: return
        if (fullScreen) {
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
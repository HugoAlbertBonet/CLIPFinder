package com.halbertb.clipfinder

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.halbertb.clipfinder.ui.HomeRoute
import com.halbertb.clipfinder.ui.MainViewModel
import com.halbertb.clipfinder.ui.theme.ClipFinderTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels(factoryProducer = { MainViewModel.Factory(application) })

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.refreshCounts()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.statusBarColor = Color.WHITE
        }
        requestGalleryPermissionsIfNeeded()
        setContent {
            ClipFinderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeRoute(viewModel = viewModel)
                }
            }
        }
    }

    private fun requestGalleryPermissionsIfNeeded() {
        val permissions =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        val needs =
            permissions.any { perm ->
                ContextCompat.checkSelfPermission(this, perm) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        if (needs) {
            permissionLauncher.launch(permissions)
        }
    }
}

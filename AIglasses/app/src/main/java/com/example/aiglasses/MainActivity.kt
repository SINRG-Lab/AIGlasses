package com.example.aiglasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.aiglasses.ui.navigation.AppNavigation
import com.example.aiglasses.ui.theme.AIglassesTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private var crashLoggerInstalled = false
    }

    private val viewModel: MainViewModel by viewModels()

    private var onPermissionsGranted: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Only the BLE grants gate connectivity; notification/WiFi grants are
        // nice-to-have (notification just won't render, WiFi lane won't come
        // up on 13+) and must not block the link.
        if (hasBlePermissions()) {
            Log.i(TAG, "BLE permissions granted")
            onPermissionsGranted?.invoke()
        } else {
            Log.w(TAG, "BLE permissions denied: $results")
        }
        onPermissionsGranted = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Install once per process — onCreate reruns on every recreation and
        // re-wrapping would chain handlers without bound.
        if (!crashLoggerInstalled) {
            crashLoggerInstalled = true
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                val stackTrace = Log.getStackTraceString(throwable)
                Log.e(TAG, "UNCAUGHT CRASH: ${throwable.message}\n$stackTrace")
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        // Permission bridge — the ViewModel routes connect/retry through this.
        viewModel.setPermissionRequestCallback { onGranted ->
            val missing = missingPermissions()
            if (missing.isEmpty()) {
                onGranted()
            } else {
                onPermissionsGranted = onGranted
                permissionLauncher.launch(missing)
            }
        }

        setContent {
            AIglassesTheme {
                AppNavigation(viewModel = viewModel)
            }
        }

        // One-step activation (iOS AppModel.init parity): bring the foreground
        // service + radio up immediately; the voice session follows by itself
        // once the glasses connect and an API key is saved.
        viewModel.startScan()
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /** Everything the app wants for this SDK level, minus what's already granted. */
    private fun missingPermissions(): Array<String> {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_SCAN
            wanted += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            wanted += Manifest.permission.BLUETOOTH
            wanted += Manifest.permission.BLUETOOTH_ADMIN
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Live-status notification + WifiNetworkSpecifier approval on 13+.
            wanted += Manifest.permission.POST_NOTIFICATIONS
            wanted += Manifest.permission.NEARBY_WIFI_DEVICES
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 12/12L: the local-only WiFi request uses the location fallback.
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }
}

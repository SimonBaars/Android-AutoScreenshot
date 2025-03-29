package com.simonbrs.autoscreenshot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.simonbrs.autoscreenshot.service.ScreenshotService
import com.simonbrs.autoscreenshot.ui.theme.AutoScreenshotTheme

class MainActivity : ComponentActivity() {
    companion object {
        private const val STORAGE_PERMISSION_CODE = 100
        private const val OVERLAY_PERMISSION_CODE = 101
        private const val PREFS_NAME = "AutoScreenshotPrefs"
        private const val KEY_SERVICE_RUNNING = "service_running"
        private const val AUTO_START_SERVICE = "AUTO_START_SERVICE"
    }
    
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var prefs: SharedPreferences
    
    private var isServiceRunning = false
    private var shouldAutoStart = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isServiceRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false)
        shouldAutoStart = intent?.getBooleanExtra(AUTO_START_SERVICE, false) ?: false
        
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        requestManageExternalStoragePermission()
                    } else if (!Settings.canDrawOverlays(this)) {
                        requestOverlayPermission()
                    } else {
                        requestMediaProjection()
                    }
                } else {
                    if (!Settings.canDrawOverlays(this)) {
                        requestOverlayPermission()
                    } else {
                        requestMediaProjection()
                    }
                }
            } else {
                Toast.makeText(this, "Permissions are required to take screenshots", Toast.LENGTH_SHORT).show()
            }
        }
        
        mediaProjectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                // Start screenshot service
                val serviceIntent = Intent(this, ScreenshotService::class.java)
                serviceIntent.putExtra(ScreenshotService.EXTRA_RESULT_DATA, result.data)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                
                // Mark the service as running
                isServiceRunning = true
                saveServiceRunningState(true)
                
                Toast.makeText(this, "Screenshot service started - saving to /storage/emulated/0/Screenshot/YYYY/MM/DD/", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Permission denied, cannot take screenshots", Toast.LENGTH_SHORT).show()
            }
        }
        
        setContent {
            AutoScreenshotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScreenshotScreen(
                        isServiceRunning = isServiceRunning,
                        onStartService = { startScreenshotCapture() },
                        onStopService = { stopScreenshotService() }
                    )
                }
            }
        }
        
        // Auto-start if coming from boot receiver
        if (shouldAutoStart) {
            startScreenshotCapture()
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // Check if we need to continue the permission flow after external storage or overlay
        if (shouldAutoStart) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                // Wait for user to grant storage permission
                return
            }
            
            if (!Settings.canDrawOverlays(this)) {
                // Wait for user to grant overlay permission
                return
            }
            
            // All permissions are granted, continue with media projection
            requestMediaProjection()
        }
    }
    
    private fun saveServiceRunningState(running: Boolean) {
        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, running).apply()
    }
    
    private fun startScreenshotCapture() {
        // Check for required permissions
        if (checkAndRequestPermissions()) {
            requestMediaProjection()
        }
    }
    
    private fun stopScreenshotService() {
        stopService(Intent(this, ScreenshotService::class.java))
        isServiceRunning = false
        saveServiceRunningState(false)
        Toast.makeText(this, "Screenshot service stopped", Toast.LENGTH_SHORT).show()
    }
    
    private fun requestMediaProjection() {
        // Make sure we have storage permission first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestManageExternalStoragePermission()
            return
        }
        
        // Make sure we have overlay permission
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }
    
    private fun requestOverlayPermission() {
        Toast.makeText(this, "Please grant overlay permission for service stability", Toast.LENGTH_SHORT).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
    }
    
    private fun checkAndRequestPermissions(): Boolean {
        val permissionsToRequest = mutableListOf<String>()
        
        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // Check storage permissions for Android < 10
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != 
                    PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
            return false
        }
        
        // For Android 10+, check if we have manage external storage permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                requestManageExternalStoragePermission()
                return false
            }
        }
        
        // Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return false
        }
        
        return true
    }
    
    private fun requestManageExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                Toast.makeText(this, "Please grant storage permission", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == OVERLAY_PERMISSION_CODE) {
            if (Settings.canDrawOverlays(this)) {
                if (shouldAutoStart) {
                    requestMediaProjection()
                }
            } else {
                Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
fun ScreenshotScreen(
    isServiceRunning: Boolean,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Auto Screenshot",
                style = MaterialTheme.typography.headlineMedium
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "This app will take screenshots every 10 seconds and organize them by date. Identical screenshots are automatically removed.",
                style = MaterialTheme.typography.bodyMedium
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            if (isServiceRunning) {
                Button(onClick = onStopService) {
                    Text("Stop Screenshot Service")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Service is running in the background.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Button(onClick = onStartService) {
                    Text("Start Screenshot Service")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Service is not running.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ScreenshotScreenPreview() {
    AutoScreenshotTheme {
        ScreenshotScreen(
            isServiceRunning = false,
            onStartService = {},
            onStopService = {}
        )
    }
}
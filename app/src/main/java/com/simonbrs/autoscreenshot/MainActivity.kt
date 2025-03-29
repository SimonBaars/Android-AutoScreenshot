package com.simonbrs.autoscreenshot

import android.Manifest
import android.app.Activity
import android.content.Intent
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
    private val STORAGE_PERMISSION_CODE = 100
    
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var mediaProjectionLauncher: ActivityResultLauncher<Intent>
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        requestManageExternalStoragePermission()
                    } else {
                        requestMediaProjection()
                    }
                } else {
                    requestMediaProjection()
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
                
                Toast.makeText(this, "Screenshot service started - will save to /storage/emulated/0/Screenshot/YYYY/MM/DD/", Toast.LENGTH_LONG).show()
                // Keep app open so user can see logs and manage service
                // finish() - removed to keep app open
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
                        onStartService = { startScreenshotCapture() }
                    )
                }
            }
        }
    }
    
    private fun startScreenshotCapture() {
        // Check for required permissions
        if (checkAndRequestPermissions()) {
            requestMediaProjection()
        }
    }
    
    private fun requestMediaProjection() {
        // Make sure we have storage permission first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestManageExternalStoragePermission()
            return
        }
        
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
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
}

@Composable
fun ScreenshotScreen(onStartService: () -> Unit) {
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
            
            Button(onClick = onStartService) {
                Text("Start Screenshot Service")
            }
            
            val context = LocalContext.current
            var showStopButton by remember { mutableStateOf(false) }
            
            if (showStopButton) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(onClick = {
                    context.stopService(Intent(context, ScreenshotService::class.java))
                    Toast.makeText(context, "Service stopped", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Stop Screenshot Service")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ScreenshotScreenPreview() {
    AutoScreenshotTheme {
        ScreenshotScreen(onStartService = {})
    }
}
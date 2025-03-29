package com.simonbrs.autoscreenshot.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.simonbrs.autoscreenshot.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class ScreenshotService : Service() {
    companion object {
        private const val TAG = "ScreenshotService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ScreenshotServiceChannel"
        private const val SCREENSHOT_INTERVAL_MS = 10000L // 10 seconds
        
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenDensity = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var isServiceRunning = false
    private var previousScreenshotPath: String? = null
    
    private val screenshotRunnable = object : Runnable {
        override fun run() {
            if (isServiceRunning) {
                takeScreenshot()
                handler.postDelayed(this, SCREENSHOT_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.hasExtra(EXTRA_RESULT_DATA)) {
            // Start foreground service BEFORE setting up media projection
            val notification = createNotification()
            
            // Simply use startForeground without the type - use manifest declaration instead
            startForeground(NOTIFICATION_ID, notification)
            
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            
            if (resultData != null) {
                setupMediaProjection(resultData)
                isServiceRunning = true
                handler.post(screenshotRunnable)
            }
        } else {
            // Start with a temporary notification if we don't have projection data yet
            val notification = createNotification()
            
            // Simply use startForeground without the type - use manifest declaration instead
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isServiceRunning = false
        handler.removeCallbacks(screenshotRunnable)
        tearDownMediaProjection()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Screenshot Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running in background to take periodic screenshots"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto Screenshot")
            .setContentText("Taking screenshots every 10 seconds")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun setupMediaProjection(resultData: Intent) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, resultData)
            
            // Get screen metrics
            val metrics = resources.displayMetrics
            screenDensity = metrics.densityDpi
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            
            // Setup image reader
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            
            // Create virtual display
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
            
            Log.d(TAG, "Media projection set up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up media projection", e)
            stopSelf()
        }
    }

    private fun tearDownMediaProjection() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    private fun takeScreenshot() {
        if (imageReader == null) return
        
        try {
            imageReader?.acquireLatestImage()?.use { image ->
                val bitmap = imageToBitmap(image)
                saveBitmapToFile(bitmap)
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        
        // Create bitmap
        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun saveBitmapToFile(bitmap: Bitmap) {
        executor.execute {
            try {
                // Get date components for directory structure
                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH_mm_ss", Locale.US)
                val currentDate = Date()
                val dateTime = dateFormat.format(currentDate)
                
                val calendar = Calendar.getInstance()
                val year = calendar.get(Calendar.YEAR).toString()
                val month = String.format(Locale.US, "%02d", calendar.get(Calendar.MONTH) + 1)
                val day = String.format(Locale.US, "%02d", calendar.get(Calendar.DAY_OF_MONTH))
                val hour = String.format(Locale.US, "%02d", calendar.get(Calendar.HOUR_OF_DAY))
                val minute = String.format(Locale.US, "%02d", calendar.get(Calendar.MINUTE))
                val second = String.format(Locale.US, "%02d", calendar.get(Calendar.SECOND))
                
                // Create directory structure in public storage
                val externalDir = Environment.getExternalStorageDirectory()
                val baseDir = File(externalDir, "Screenshot")
                
                Log.d(TAG, "External storage directory: ${externalDir.absolutePath}")
                Log.d(TAG, "Base screenshot directory: ${baseDir.absolutePath}")
                Log.d(TAG, "External storage state: ${Environment.getExternalStorageState()}")
                Log.d(TAG, "Is external storage emulated: ${Environment.isExternalStorageEmulated()}")
                Log.d(TAG, "Is external storage removable: ${Environment.isExternalStorageRemovable()}")
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Log.d(TAG, "Is external storage manager: ${Environment.isExternalStorageManager()}")
                }
                
                val yearDir = File(baseDir, year)
                val monthDir = File(yearDir, month)
                var dayDir = File(monthDir, day)
                
                Log.d(TAG, "Attempting to create directory path: ${dayDir.absolutePath}")
                
                // Try to create all directories
                if (!baseDir.exists()) {
                    val baseDirCreated = baseDir.mkdir()
                    Log.d(TAG, "Base dir created: $baseDirCreated")
                }
                
                if (!yearDir.exists()) {
                    val yearDirCreated = yearDir.mkdir()
                    Log.d(TAG, "Year dir created: $yearDirCreated")
                }
                
                if (!monthDir.exists()) {
                    val monthDirCreated = monthDir.mkdir()
                    Log.d(TAG, "Month dir created: $monthDirCreated")
                }
                
                if (!dayDir.exists()) {
                    val dayDirCreated = dayDir.mkdir()
                    Log.d(TAG, "Day dir created: $dayDirCreated")
                }
                
                // Check if directory creation was successful
                if (!dayDir.exists()) {
                    Log.e(TAG, "Failed to create directory: ${dayDir.absolutePath}")
                    // Fallback to app's external files directory
                    val fallbackBaseDir = getExternalFilesDir(null)
                    val fallbackDir = File(fallbackBaseDir, "Screenshot/$year/$month/$day")
                    Log.d(TAG, "Trying fallback location: ${fallbackDir.absolutePath}")
                    
                    if (!fallbackDir.exists() && !fallbackDir.mkdirs()) {
                        Log.e(TAG, "Also failed to create fallback directory: ${fallbackDir.absolutePath}")
                        return@execute
                    } else {
                        Log.d(TAG, "Using fallback directory instead: ${fallbackDir.absolutePath}")
                        dayDir = fallbackDir
                    }
                } else {
                    Log.d(TAG, "Directory exists: ${dayDir.absolutePath}")
                }
                
                // Create .nomedia file to hide screenshots from gallery
                val nomediaFile = File(dayDir, ".nomedia")
                if (!nomediaFile.exists()) {
                    try {
                        val nomediaCreated = nomediaFile.createNewFile()
                        Log.d(TAG, "Created .nomedia file at ${nomediaFile.absolutePath}, success: $nomediaCreated")
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to create .nomedia file", e)
                    }
                }
                
                // Save the screenshot
                val filename = "${hour}_${minute}_${second}.png"
                val file = File(dayDir, filename)
                val filePath = file.absolutePath
                Log.d(TAG, "Saving screenshot to: $filePath")
                
                try {
                    FileOutputStream(file).use { out ->
                        val compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        Log.d(TAG, "Bitmap compressed to file: $compressed")
                    }
                    
                    // Check if the new screenshot is identical to the previous one
                    if (previousScreenshotPath != null) {
                        val previousFile = File(previousScreenshotPath!!)
                        if (areFilesIdentical(previousFile, file)) {
                            file.delete()
                            Log.d(TAG, "Deleted duplicate screenshot: $filePath")
                        } else {
                            previousScreenshotPath = filePath
                            Log.d(TAG, "Saved screenshot: $filePath")
                        }
                    } else {
                        previousScreenshotPath = filePath
                        Log.d(TAG, "Saved first screenshot: $filePath")
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to save screenshot", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error saving bitmap", e)
            }
        }
    }

    private fun areFilesIdentical(file1: File, file2: File): Boolean {
        if (!file1.exists() || !file2.exists() || file1.length() != file2.length()) {
            return false
        }
        
        try {
            file1.inputStream().use { is1 ->
                file2.inputStream().use { is2 ->
                    val buf1 = ByteArray(8192)
                    val buf2 = ByteArray(8192)
                    var read1: Int
                    var read2: Int
                    
                    do {
                        read1 = is1.read(buf1)
                        read2 = is2.read(buf2)
                        
                        if (read1 != read2) {
                            return false
                        }
                        
                        if (read1 > 0 && !buf1.contentEquals(buf2)) {
                            return false
                        }
                    } while (read1 > 0)
                    
                    return true
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error comparing files", e)
            return false
        }
    }
} 
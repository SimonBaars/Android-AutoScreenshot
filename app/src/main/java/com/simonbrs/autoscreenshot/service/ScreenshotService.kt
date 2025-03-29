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
            
            // Register callback - REQUIRED for Android 12+
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped")
                    stopSelf()
                }
            }, handler)
            
            // Get screen metrics
            val metrics = resources.displayMetrics
            screenDensity = metrics.densityDpi
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            
            // Setup image reader
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    reader.acquireLatestImage()?.use { image ->
                        val bitmap = imageToBitmap(image)
                        saveBitmapToFile(bitmap)
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image", e)
                }
            }, handler)
            
            // Create virtual display
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )
            
            Log.d(TAG, "Media projection set up successfully with dimensions: ${screenWidth}x${screenHeight}")
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
        if (imageReader == null || virtualDisplay == null) {
            Log.e(TAG, "Cannot take screenshot: imageReader or virtualDisplay is null")
            return
        }
        
        try {
            // For MediaProjection with ImageReader, we don't need to explicitly acquire the image
            // The OnImageAvailableListener will be called when a new frame is available
            // We just need to trigger a new frame capture by requesting a repaint
            virtualDisplay?.surface?.requestRefresh()
            Log.d(TAG, "Requested screenshot capture")
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering screenshot", e)
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
                val calendar = Calendar.getInstance()
                val year = calendar.get(Calendar.YEAR).toString()
                val month = String.format(Locale.US, "%02d", calendar.get(Calendar.MONTH) + 1)
                val day = String.format(Locale.US, "%02d", calendar.get(Calendar.DAY_OF_MONTH))
                val hour = String.format(Locale.US, "%02d", calendar.get(Calendar.HOUR_OF_DAY))
                val minute = String.format(Locale.US, "%02d", calendar.get(Calendar.MINUTE))
                val second = String.format(Locale.US, "%02d", calendar.get(Calendar.SECOND))
                
                // Full path for the screenshot file
                val dirPath = "/storage/emulated/0/Screenshot/$year/$month/$day"
                val filename = "${hour}_${minute}_${second}.png"
                val fullPath = "$dirPath/$filename"
                
                Log.d(TAG, "Attempting to save to: $fullPath")
                
                // Create directory structure
                val dirFile = File(dirPath)
                if (!dirFile.exists()) {
                    val dirsCreated = dirFile.mkdirs()
                    Log.d(TAG, "Created directories: $dirsCreated for path: $dirPath")
                    
                    // Create .nomedia file
                    val nomediaFile = File(dirFile, ".nomedia")
                    if (!nomediaFile.exists()) {
                        try {
                            val nomediaCreated = nomediaFile.createNewFile()
                            Log.d(TAG, "Created .nomedia file: $nomediaCreated")
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to create .nomedia file", e)
                        }
                    }
                }
                
                // File to save screenshot
                val file = File(fullPath)
                
                try {
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        Log.d(TAG, "Screenshot saved to: $fullPath")
                        
                        // Check if identical to previous screenshot
                        if (previousScreenshotPath != null) {
                            val previousFile = File(previousScreenshotPath!!)
                            if (areFilesIdentical(previousFile, file)) {
                                file.delete()
                                Log.d(TAG, "Deleted duplicate screenshot: $fullPath")
                            } else {
                                previousScreenshotPath = fullPath
                            }
                        } else {
                            previousScreenshotPath = fullPath
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to save screenshot: ${e.message}", e)
                    
                    // Try app-specific directory as fallback
                    saveFallbackScreenshot(bitmap, "$year/$month/$day", filename)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in saveBitmapToFile", e)
            }
        }
    }

    private fun saveFallbackScreenshot(bitmap: Bitmap, dateDir: String, filename: String) {
        try {
            val baseDir = getExternalFilesDir(null)
            val dir = File(baseDir, "Screenshot/$dateDir")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            
            val nomediaFile = File(dir, ".nomedia")
            if (!nomediaFile.exists()) {
                nomediaFile.createNewFile()
            }
            
            val file = File(dir, filename)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                Log.d(TAG, "Screenshot saved to fallback location: ${file.absolutePath}")
                
                if (previousScreenshotPath != null) {
                    val previousFile = File(previousScreenshotPath!!)
                    if (areFilesIdentical(previousFile, file)) {
                        file.delete()
                        Log.d(TAG, "Deleted duplicate screenshot from fallback: ${file.absolutePath}")
                    } else {
                        previousScreenshotPath = file.absolutePath
                    }
                } else {
                    previousScreenshotPath = file.absolutePath
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot to fallback location", e)
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
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
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.simonbrs.autoscreenshot.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ScreenshotService : Service() {
    companion object {
        private const val TAG = "ScreenshotService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "ScreenshotServiceChannel"
        private const val SCREENSHOT_INTERVAL_MS = 10000L // 10 seconds
        
        const val EXTRA_RESULT_DATA = "extra_result_data"
        
        // Flag to ensure only one instance is running
        private val isRunning = AtomicBoolean(false)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var scheduler: ScheduledExecutorService
    
    private var mediaProjection: MediaProjection? = null
    private var screenDensity = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var isServiceRunning = false
    private var previousScreenshotPath: String? = null
    private var screenshotCount = AtomicInteger(0)
    private var notificationManager: NotificationManager? = null
    private var isCapturingImage = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Create wake lock to prevent service from being killed while in background
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AutoScreenshot::ScreenshotServiceWakeLock"
        )
        wakeLock?.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only allow one instance to run
        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "Service already running, skipping start")
            return START_STICKY
        }
        
        try {
            if (intent != null && intent.hasExtra(EXTRA_RESULT_DATA)) {
                // Start foreground service BEFORE setting up media projection
                val notification = createNotification()
                
                try {
                    startForeground(NOTIFICATION_ID, notification)
                    
                    val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    }
                    
                    if (resultData != null) {
                        wakeLock?.acquire(SCREENSHOT_INTERVAL_MS * 3) // Ensure we stay awake for at least 30 seconds
                        
                        // Initialize the projection
                        setupMediaProjection(resultData)
                        
                        // Initialize scheduler for precisely timed screenshots
                        scheduler = Executors.newScheduledThreadPool(1)
                        
                        // Mark service as running
                        isServiceRunning = true
                        
                        // Schedule periodic screenshots at fixed intervals
                        scheduler.scheduleAtFixedRate({
                            if (isServiceRunning && !isCapturingImage.get()) {
                                takeScreenshot()
                            }
                        }, 0, SCREENSHOT_INTERVAL_MS, TimeUnit.MILLISECONDS)
                        
                        Log.d(TAG, "Screenshot scheduler started with interval: $SCREENSHOT_INTERVAL_MS ms")
                    } else {
                        Log.e(TAG, "No media projection data")
                        stopSelf()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting foreground service", e)
                    isRunning.set(false)
                    stopSelf()
                }
            } else {
                // Don't try to start as foreground if we're restarting or don't have permission data
                Log.d(TAG, "Service started without permission data, stopping")
                isRunning.set(false)
                stopSelf()
                return START_NOT_STICKY // Don't restart if we don't have data
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in onStartCommand", e)
            isRunning.set(false)
            stopSelf()
        }
        
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called, shutting down service")
        isServiceRunning = false
        
        // Shut down executor
        try {
            if (::scheduler.isInitialized) {
                scheduler.shutdownNow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down scheduler", e)
        }
        
        // Release wake lock
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
        
        // Clean up projection resources
        tearDownMediaProjection()
        
        // Reset running flag
        isRunning.set(false)
        
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
            .setContentText("Taking screenshots every 10 seconds (Total: ${screenshotCount.get()})")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        notificationManager?.notify(NOTIFICATION_ID, createNotification())
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
            }, mainHandler)
            
            // Get screen metrics
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                screenWidth = bounds.width()
                screenHeight = bounds.height()
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getMetrics(displayMetrics)
                screenWidth = displayMetrics.widthPixels
                screenHeight = displayMetrics.heightPixels
            }
            screenDensity = resources.displayMetrics.densityDpi
            
            Log.d(TAG, "Media projection set up successfully with dimensions: ${screenWidth}x${screenHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up media projection", e)
            stopSelf()
        }
    }

    private fun tearDownMediaProjection() {
        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error tearing down media projection", e)
        }
    }

    private fun takeScreenshot() {
        if (isCapturingImage.getAndSet(true)) {
            Log.d(TAG, "Already capturing an image, skipping this request")
            return
        }
        
        if (mediaProjection == null) {
            Log.e(TAG, "Cannot take screenshot: mediaProjection is null")
            isCapturingImage.set(false)
            return
        }
        
        Log.d(TAG, "Taking screenshot at: ${System.currentTimeMillis()}")
        
        try {
            // Each time we create a new ImageReader for a clean capture
            val imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 1)
            var virtualDisplay: VirtualDisplay? = null
            
            try {
                // Create a new virtual display just for this capture
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture-${System.currentTimeMillis()}",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,  // Standard flag instead of ONE_SHOT
                    imageReader.surface,
                    null,
                    mainHandler
                )
                
                // Wait briefly to ensure the frame is captured
                Thread.sleep(100)
                
                // Capture a single frame
                val image = imageReader.acquireLatestImage()
                if (image != null) {
                    try {
                        val bitmap = imageToBitmap(image)
                        val bitmapCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        image.close()
                        bitmap.recycle()
                        
                        // Process on background thread
                        executor.execute {
                            try {
                                saveBitmapToFile(bitmapCopy)
                            } finally {
                                bitmapCopy.recycle()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing screenshot image", e)
                        image.close()
                    }
                } else {
                    Log.e(TAG, "Failed to acquire image from reader")
                }
            } finally {
                try {
                    // Clean up resources
                    virtualDisplay?.release()
                    imageReader.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning up resources", e)
                } finally {
                    // Always release the capturing flag
                    isCapturingImage.set(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            isCapturingImage.set(false)
        }
        
        // Acquire wakelock for the next interval
        try {
            wakeLock?.acquire(SCREENSHOT_INTERVAL_MS * 3)
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake lock", e)
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
            var isNewScreenshot = true
            
            try {
                FileOutputStream(file).use { out ->
                    val success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    if (success) {
                        Log.d(TAG, "Screenshot saved to: $fullPath")
                        
                        // Check if identical to previous screenshot
                        if (previousScreenshotPath != null) {
                            val previousFile = File(previousScreenshotPath!!)
                            if (previousFile.exists() && areFilesIdentical(previousFile, file)) {
                                file.delete()
                                Log.d(TAG, "Deleted duplicate screenshot: $fullPath")
                                isNewScreenshot = false
                            }
                        }
                        
                        if (isNewScreenshot) {
                            previousScreenshotPath = fullPath
                            screenshotCount.incrementAndGet()
                            mainHandler.post { updateNotification() }
                        }
                    } else {
                        Log.e(TAG, "Failed to compress bitmap to file")
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to save screenshot: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in saveBitmapToFile", e)
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
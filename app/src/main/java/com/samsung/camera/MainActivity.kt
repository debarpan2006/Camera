package com.samsung.camera

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.samsung.camera.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Camera control and use cases
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null

    // State
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var currentMode = Mode.PHOTO
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var timerSeconds = 0
    private var currentAspectRatio = AspectRatioMode.RATIO_3_4
    private var isTorchOn = false
    private var isRecording = false
    private var lastCapturedUri: Uri? = null

    // AE/AF Lock & Brightness (Exposure)
    private var isAeAfLocked = false
    private var exposureIndex = 0
    private var minExposure = -4
    private var maxExposure = 4
    private var initialTouchY = 0f
    private var initialExposureOnDrag = 0

    // Background thread for non-blocking I/O operations
    private lateinit var cameraExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    // Video recording timer
    private var recordingStartTime = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsedSeconds = (SystemClock.uptimeMillis() - recordingStartTime) / 1000
                val minutes = elapsedSeconds / 60
                val seconds = elapsedSeconds % 60
                binding.txtRecordingTimer.text = String.format(Locale.US, "%02d:%02d", minutes, seconds)
                mainHandler.postDelayed(this, 500)
            }
        }
    }

    // Auto-hide focus & exposure overlay
    private val hideFocusRunnable = Runnable {
        if (!isAeAfLocked) {
            binding.layoutFocusExposure.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    binding.layoutFocusExposure.visibility = View.GONE
                }
                .start()
        }
    }

    // Gesture detection (pinch zoom & swipe to flip)
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    enum class Mode {
        PHOTO, VIDEO
    }

    enum class AspectRatioMode(val display: String, val ratio: Int) {
        RATIO_3_4("3:4", androidx.camera.core.AspectRatio.RATIO_4_3),
        RATIO_9_16("9:16", androidx.camera.core.AspectRatio.RATIO_16_9),
        RATIO_1_1("1:1", androidx.camera.core.AspectRatio.RATIO_4_3),
        RATIO_FULL("FULL", androidx.camera.core.AspectRatio.RATIO_16_9)
    }

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value) {
                    permissionGranted = false
                }
            }
            if (permissionGranted) {
                binding.layoutPermissionCard.visibility = View.GONE
                applyAspectRatioConstraints(currentAspectRatio, animate = false)
                startCamera()
                loadLatestGalleryThumbnail()
            } else {
                binding.layoutPermissionCard.visibility = View.VISIBLE
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupGestures()
        setupListeners()
        setupBrightnessSlider()

        applyAspectRatioConstraints(currentAspectRatio, animate = false)

        if (allPermissionsGranted()) {
            binding.layoutPermissionCard.visibility = View.GONE
            startCamera()
            loadLatestGalleryThumbnail()
        } else {
            binding.layoutPermissionCard.visibility = View.VISIBLE
            requestPermissions()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoomRatio = cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                val delta = detector.scaleFactor
                val targetZoom = (currentZoomRatio * delta).coerceIn(
                    cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f,
                    cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10f
                )
                cameraControl?.setZoomRatio(targetZoom)
                showZoomRatioBadge(targetZoom)
                return true
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleTapToFocus(e.x, e.y, lock = false)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // Long press activates AE/AF Lock
                binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                handleTapToFocus(e.x, e.y, lock = true)
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                // Vertical swipe flips camera (Selfie <-> Rear)
                if (Math.abs(diffY) > Math.abs(diffX) && Math.abs(diffY) > 150 && Math.abs(velocityY) > 150) {
                    if (!isRecording) {
                        flipCamera()
                    }
                    return true
                }
                // Horizontal swipe switches mode (PHOTO <-> VIDEO)
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > 150 && Math.abs(velocityX) > 150) {
                    if (!isRecording) {
                        if (diffX > 0 && currentMode == Mode.VIDEO) {
                            switchMode(Mode.PHOTO)
                        } else if (diffX < 0 && currentMode == Mode.PHOTO) {
                            switchMode(Mode.VIDEO)
                        }
                    }
                    return true
                }
                return false
            }
        })

        binding.viewFinder.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBrightnessSlider() {
        // Vertical dragging on brightness slider adjusts exposure compensation
        binding.containerBrightnessSlider.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchY = event.rawY
                    initialExposureOnDrag = exposureIndex
                    mainHandler.removeCallbacks(hideFocusRunnable)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = initialTouchY - event.rawY // Drag UP = brighter (+EV), Drag DOWN = darker (-EV)
                    val sensitivity = 18f
                    val stepsDelta = (deltaY / sensitivity).toInt()
                    val targetIndex = (initialExposureOnDrag + stepsDelta).coerceIn(minExposure, maxExposure)

                    if (targetIndex != exposureIndex) {
                        exposureIndex = targetIndex
                        cameraControl?.setExposureCompensationIndex(exposureIndex)

                        // Update sun icon position visually along track
                        val fraction = if (maxExposure != minExposure) {
                            (exposureIndex - minExposure).toFloat() / (maxExposure - minExposure)
                        } else 0.5f
                        val translationY = (0.5f - fraction) * 80f
                        binding.imgExposureSun.translationY = translationY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isAeAfLocked) {
                        mainHandler.postDelayed(hideFocusRunnable, 3000)
                    }
                    true
                }
                else -> false
            }
        }

        binding.badgeAeAfLock.setOnClickListener {
            unlockAeAf()
        }
    }

    private fun setupListeners() {
        // Shutter Button Click (silent, zero lag)
        binding.btnShutter.setOnClickListener {
            binding.btnShutter.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            animateShutterPress()
            onShutterClicked()
        }

        // Camera Switch (Flip / Selfie) Button
        binding.btnFlipCamera.setOnClickListener {
            if (!isRecording) {
                binding.btnFlipCamera.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                flipCamera()
            }
        }

        // Gallery Thumbnail Button
        binding.btnGallery.setOnClickListener {
            openGallery()
        }

        // Mode Switching
        binding.modePhoto.setOnClickListener {
            if (!isRecording && currentMode != Mode.PHOTO) {
                switchMode(Mode.PHOTO)
            }
        }
        binding.modeVideo.setOnClickListener {
            if (!isRecording && currentMode != Mode.VIDEO) {
                switchMode(Mode.VIDEO)
            }
        }

        // Top Bar Controls
        binding.btnFlash.setOnClickListener {
            toggleFlash()
        }

        binding.btnTimer.setOnClickListener {
            cycleTimer()
        }

        binding.btnAspectRatio.setOnClickListener {
            cycleAspectRatio()
        }

        binding.txtMegapixel.setOnClickListener {
            Toast.makeText(this, "Highest Megapixel Mode Active (Full Sensor Resolution)", Toast.LENGTH_SHORT).show()
        }

        binding.btnSettings.setOnClickListener {
            val facingText = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "Front (Selfie)" else "Rear"
            Toast.makeText(this, "Samsung One UI Camera • $facingText • Max MP Active", Toast.LENGTH_SHORT).show()
        }

        // Rear Camera Quick Zoom Pills
        binding.btnZoomWide.setOnClickListener { setQuickZoom(0.6f) }
        binding.btnZoom1x.setOnClickListener { setQuickZoom(1.0f) }
        binding.btnZoom2x.setOnClickListener { setQuickZoom(2.0f) }

        // Front Camera Selfie Angle Controls (Single vs Group)
        binding.btnSelfieSingle.setOnClickListener { setSelfieAngle(false) }
        binding.btnSelfieGroup.setOnClickListener { setSelfieAngle(true) }

        // Permission Card Button
        binding.btnGrantPermissions.setOnClickListener {
            requestPermissions()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Binds camera use-cases configured for:
     * 1. ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY to maximize Megapixel output.
     * 2. CAPTURE_MODE_MINIMIZE_LATENCY for zero shutter lag.
     * 3. VideoCapture with highest available quality.
     */
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .setAspectRatioStrategy(
                when (currentAspectRatio) {
                    AspectRatioMode.RATIO_3_4 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    AspectRatioMode.RATIO_9_16 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                    AspectRatioMode.RATIO_1_1 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                    AspectRatioMode.RATIO_FULL -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                }
            )
            .build()

        // 1. Viewfinder Preview
        preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
            .also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

        // 2. Ultra-fast ImageCapture
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolutionSelector)
            .setFlashMode(flashMode)
            .setTargetRotation(binding.viewFinder.display?.rotation ?: android.view.Surface.ROTATION_0)
            .build()

        // 3. High quality smooth VideoCapture
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HIGHEST,
                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                )
            )
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            provider.unbindAll()

            camera = if (currentMode == Mode.PHOTO) {
                provider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } else {
                provider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    videoCapture
                )
            }

            cameraControl = camera?.cameraControl
            cameraInfo = camera?.cameraInfo

            cameraInfo?.exposureState?.let { state ->
                if (state.isExposureCompensationSupported) {
                    minExposure = state.exposureCompensationRange.lower
                    maxExposure = state.exposureCompensationRange.upper
                    exposureIndex = state.exposureCompensationIndex
                }
            }

            if (currentMode == Mode.VIDEO && isTorchOn) {
                cameraControl?.enableTorch(true)
            }

            updateCameraFacingUI()
            detectAndDisplayMegapixels()

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            Toast.makeText(this, "Failed to bind camera: ${exc.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun detectAndDisplayMegapixels() {
        imageCapture?.resolutionInfo?.let { resInfo ->
            val size = resInfo.resolution
            val totalPixels = size.width.toLong() * size.height.toLong()
            val mp = (totalPixels / 1_000_000.0).toFloat()
            val mpString = if (mp >= 1f) {
                String.format(Locale.US, "%.0fMP", mp)
            } else {
                "MAX MP"
            }
            binding.txtMegapixel.text = mpString
        } ?: run {
            binding.txtMegapixel.text = "MAX MP"
        }
    }

    private fun updateCameraFacingUI() {
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            binding.layoutZoomSelector.visibility = View.GONE
            binding.layoutSelfieAngle.visibility = View.VISIBLE
            setSelfieAngle(isGroup = false)
        } else {
            binding.layoutSelfieAngle.visibility = View.GONE
            binding.layoutZoomSelector.visibility = View.VISIBLE
            updateZoomPillUI(1.0f)
        }
    }

    private fun setSelfieAngle(isGroup: Boolean) {
        val min = cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
        if (isGroup) {
            cameraControl?.setZoomRatio(min)
            binding.btnSelfieGroup.setBackgroundResource(R.drawable.bg_pill_selected)
            binding.btnSelfieGroup.setColorFilter(ContextCompat.getColor(this, R.color.black))
            binding.btnSelfieSingle.setBackgroundResource(android.R.color.transparent)
            binding.btnSelfieSingle.setColorFilter(ContextCompat.getColor(this, R.color.white))
            Toast.makeText(this, "Wide Selfie Angle", Toast.LENGTH_SHORT).show()
        } else {
            val singleZoom = (min * 1.2f).coerceAtMost(cameraInfo?.zoomState?.value?.maxZoomRatio ?: 2f)
            cameraControl?.setZoomRatio(singleZoom)
            binding.btnSelfieSingle.setBackgroundResource(R.drawable.bg_pill_selected)
            binding.btnSelfieSingle.setColorFilter(ContextCompat.getColor(this, R.color.black))
            binding.btnSelfieGroup.setBackgroundResource(android.R.color.transparent)
            binding.btnSelfieGroup.setColorFilter(ContextCompat.getColor(this, R.color.white))
            Toast.makeText(this, "Standard Selfie Angle", Toast.LENGTH_SHORT).show()
        }
    }

    private fun flipCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        unlockAeAf()

        binding.btnFlipCamera.animate()
            .rotationBy(180f)
            .setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        bindCameraUseCases()
    }

    private fun switchMode(mode: Mode) {
        if (currentMode == mode) return
        currentMode = mode

        unlockAeAf()
        binding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        if (mode == Mode.PHOTO) {
            binding.txtModePhoto.setTextColor(ContextCompat.getColor(this, R.color.samsung_yellow))
            binding.indicatorPhoto.visibility = View.VISIBLE
            binding.txtModeVideo.setTextColor(Color.parseColor("#99FFFFFF"))
            binding.indicatorVideo.visibility = View.INVISIBLE

            binding.btnShutter.setBackgroundResource(R.drawable.bg_shutter_photo)
            binding.btnFlash.visibility = View.VISIBLE
            binding.btnTimer.visibility = View.VISIBLE
            binding.btnAspectRatio.visibility = View.VISIBLE

            if (isTorchOn) {
                isTorchOn = false
                cameraControl?.enableTorch(false)
            }
        } else {
            binding.txtModeVideo.setTextColor(ContextCompat.getColor(this, R.color.samsung_yellow))
            binding.indicatorVideo.visibility = View.VISIBLE
            binding.txtModePhoto.setTextColor(Color.parseColor("#99FFFFFF"))
            binding.indicatorPhoto.visibility = View.INVISIBLE

            binding.btnShutter.setBackgroundResource(R.drawable.bg_shutter_video_idle)
            binding.btnTimer.visibility = View.GONE
            binding.btnAspectRatio.visibility = View.GONE
        }

        bindCameraUseCases()
    }

    private fun onShutterClicked() {
        if (timerSeconds > 0 && currentMode == Mode.PHOTO) {
            startCountdown {
                takePhoto()
            }
        } else {
            if (currentMode == Mode.PHOTO) {
                takePhoto()
            } else {
                toggleVideoRecording()
            }
        }
    }

    /**
     * ULTRA-FAST PHOTO CAPTURE:
     * 1. 0ms perceived lag: No capturing sound (completely silent). Instant white flash blink and haptic tap.
     * 2. Minimize latency mode in CameraX.
     * 3. Mirrors selfies horizontally when saving front-camera shots.
     * 4. Crops to 1:1 square when 1:1 aspect ratio is selected.
     * 5. Saves directly to MediaStore on background executor thread.
     */
    private fun takePhoto() {
        val capture = imageCapture ?: return

        // Instant visual feedback (NO CAPTURING SOUND as requested)
        triggerShutterBlink()

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        val originalBitmap = imageProxy.toBitmap()

                        val matrix = Matrix()
                        matrix.postRotate(rotationDegrees.toFloat())

                        // MIRROR THE SELFIES PHOTOS WHEN SAVED IN GALLERY
                        if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                            matrix.postScale(-1f, 1f)
                        }

                        var transformedBitmap = Bitmap.createBitmap(
                            originalBitmap,
                            0,
                            0,
                            originalBitmap.width,
                            originalBitmap.height,
                            matrix,
                            true
                        )

                        // 1:1 Aspect ratio square crop
                        if (currentAspectRatio == AspectRatioMode.RATIO_1_1) {
                            val edge = minOf(transformedBitmap.width, transformedBitmap.height)
                            val cropX = (transformedBitmap.width - edge) / 2
                            val cropY = (transformedBitmap.height - edge) / 2
                            val squareBitmap = Bitmap.createBitmap(transformedBitmap, cropX, cropY, edge, edge)
                            if (squareBitmap != transformedBitmap) {
                                transformedBitmap.recycle()
                                transformedBitmap = squareBitmap
                            }
                        }

                        saveBitmapToGallery(transformedBitmap)

                        if (originalBitmap != transformedBitmap && !originalBitmap.isRecycled) {
                            originalBitmap.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing captured photo", e)
                        mainHandler.post {
                            Toast.makeText(this@MainActivity, "Save error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        imageProxy.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    mainHandler.post {
                        Toast.makeText(this@MainActivity, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val name = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 98, outputStream)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, contentValues, null, null)
                }

                lastCapturedUri = uri
                mainHandler.post {
                    updateGalleryThumbnail(uri)
                    detectAndDisplayMegapixels()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write bitmap to MediaStore", e)
            }
        }
        bitmap.recycle()
    }

    private fun triggerShutterBlink() {
        binding.shutterBlinkOverlay.visibility = View.VISIBLE
        binding.shutterBlinkOverlay.alpha = 0.8f

        val anim = AlphaAnimation(0.8f, 0.0f)
        anim.duration = 60
        anim.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                binding.shutterBlinkOverlay.visibility = View.GONE
            }
            override fun onAnimationRepeat(animation: Animation?) {}
        })
        binding.shutterBlinkOverlay.startAnimation(anim)
    }

    private fun animateShutterPress() {
        val scaleDown = ObjectAnimator.ofPropertyValuesHolder(
            binding.btnShutter,
            PropertyValuesHolder.ofFloat("scaleX", 0.86f),
            PropertyValuesHolder.ofFloat("scaleY", 0.86f)
        ).apply {
            duration = 80
            repeatCount = 1
            repeatMode = ObjectAnimator.REVERSE
        }
        scaleDown.start()
    }

    private fun toggleVideoRecording() {
        val video = videoCapture ?: return

        if (isRecording) {
            // STOP RECORDING
            currentRecording?.stop()
            currentRecording = null
            return
        }

        // START RECORDING
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_$name.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        val pendingRecording = video.output.prepareRecording(this, mediaStoreOutputOptions)

        if (PermissionChecker.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PermissionChecker.PERMISSION_GRANTED
        ) {
            pendingRecording.withAudioEnabled()
        }

        currentRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { recordEvent ->
            when (recordEvent) {
                is VideoRecordEvent.Start -> {
                    isRecording = true
                    recordingStartTime = SystemClock.uptimeMillis()
                    onRecordingStarted()
                }

                is VideoRecordEvent.Finalize -> {
                    isRecording = false
                    onRecordingStopped()

                    if (!recordEvent.hasError()) {
                        val uri = recordEvent.outputResults.outputUri
                        lastCapturedUri = uri
                        updateGalleryThumbnail(uri)
                    } else {
                        currentRecording?.close()
                        currentRecording = null
                        Log.e(TAG, "Video recording error: ${recordEvent.error}")
                        Toast.makeText(this, "Video capture error: ${recordEvent.error}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun onRecordingStarted() {
        binding.btnShutter.setBackgroundResource(R.drawable.bg_shutter_video_recording)
        binding.layoutRecordingBadge.visibility = View.VISIBLE
        binding.txtRecordingTimer.text = "00:00"
        mainHandler.post(timerRunnable)

        startRecordingDotPulse()

        binding.layoutModes.visibility = View.INVISIBLE
        binding.topBar.visibility = View.INVISIBLE
        binding.btnFlipCamera.visibility = View.INVISIBLE
    }

    private fun onRecordingStopped() {
        mainHandler.removeCallbacks(timerRunnable)
        binding.btnShutter.setBackgroundResource(R.drawable.bg_shutter_video_idle)
        binding.layoutRecordingBadge.visibility = View.GONE
        binding.dotRecording.clearAnimation()

        binding.layoutModes.visibility = View.VISIBLE
        binding.topBar.visibility = View.VISIBLE
        binding.btnFlipCamera.visibility = View.VISIBLE
    }

    private fun startRecordingDotPulse() {
        val pulseAnim = ObjectAnimator.ofFloat(binding.dotRecording, "alpha", 1f, 0.2f, 1f).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
        }
        pulseAnim.start()
    }

    private fun startCountdown(onFinished: () -> Unit) {
        binding.txtCountdown.visibility = View.VISIBLE
        binding.btnShutter.isEnabled = false

        object : CountDownTimer((timerSeconds * 1000).toLong(), 1000) {
            var count = timerSeconds

            override fun onTick(millisUntilFinished: Long) {
                binding.txtCountdown.text = count.toString()
                binding.txtCountdown.scaleX = 1.3f
                binding.txtCountdown.scaleY = 1.3f
                binding.txtCountdown.animate().scaleX(1.0f).scaleY(1.0f).setDuration(400).start()
                binding.root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                count--
            }

            override fun onFinish() {
                binding.txtCountdown.visibility = View.GONE
                binding.btnShutter.isEnabled = true
                onFinished()
            }
        }.start()
    }

    private fun toggleFlash() {
        if (currentMode == Mode.PHOTO) {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> {
                    binding.btnFlash.setImageResource(R.drawable.ic_flash_auto)
                    Toast.makeText(this, "Flash: AUTO", Toast.LENGTH_SHORT).show()
                    ImageCapture.FLASH_MODE_AUTO
                }
                ImageCapture.FLASH_MODE_AUTO -> {
                    binding.btnFlash.setImageResource(R.drawable.ic_flash_on)
                    Toast.makeText(this, "Flash: ON", Toast.LENGTH_SHORT).show()
                    ImageCapture.FLASH_MODE_ON
                }
                else -> {
                    binding.btnFlash.setImageResource(R.drawable.ic_flash_off)
                    Toast.makeText(this, "Flash: OFF", Toast.LENGTH_SHORT).show()
                    ImageCapture.FLASH_MODE_OFF
                }
            }
            imageCapture?.flashMode = flashMode
        } else {
            isTorchOn = !isTorchOn
            cameraControl?.enableTorch(isTorchOn)
            if (isTorchOn) {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_on)
                Toast.makeText(this, "Torch: ON", Toast.LENGTH_SHORT).show()
            } else {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_off)
                Toast.makeText(this, "Torch: OFF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cycleTimer() {
        timerSeconds = when (timerSeconds) {
            0 -> 2
            2 -> 5
            5 -> 10
            else -> 0
        }
        if (timerSeconds == 0) {
            binding.btnTimer.text = "OFF"
            binding.btnTimer.setTextColor(Color.WHITE)
        } else {
            binding.btnTimer.text = "${timerSeconds}s"
            binding.btnTimer.setTextColor(ContextCompat.getColor(this, R.color.samsung_yellow))
        }
    }

    /**
     * Cycles and physically adjusts the aspect ratio of the viewfinder.
     */
    private fun cycleAspectRatio() {
        currentAspectRatio = when (currentAspectRatio) {
            AspectRatioMode.RATIO_3_4 -> AspectRatioMode.RATIO_9_16
            AspectRatioMode.RATIO_9_16 -> AspectRatioMode.RATIO_1_1
            AspectRatioMode.RATIO_1_1 -> AspectRatioMode.RATIO_FULL
            AspectRatioMode.RATIO_FULL -> AspectRatioMode.RATIO_3_4
        }
        binding.btnAspectRatio.text = currentAspectRatio.display
        Toast.makeText(this, "Ratio: ${currentAspectRatio.display}", Toast.LENGTH_SHORT).show()

        applyAspectRatioConstraints(currentAspectRatio, animate = true)
        bindCameraUseCases()
    }

    /**
     * Dynamically adjusts the ConstraintLayout of previewContainer to physically frame 3:4, 9:16, 1:1, or FULL.
     */
    private fun applyAspectRatioConstraints(mode: AspectRatioMode, animate: Boolean) {
        if (animate) {
            val transition = ChangeBounds().apply {
                duration = 250
                interpolator = AccelerateDecelerateInterpolator()
            }
            TransitionManager.beginDelayedTransition(binding.rootContainer, transition)
        }

        val constraintSet = ConstraintSet()
        constraintSet.clone(binding.rootContainer)

        when (mode) {
            AspectRatioMode.RATIO_3_4 -> {
                constraintSet.clear(R.id.previewContainer, ConstraintSet.TOP)
                constraintSet.clear(R.id.previewContainer, ConstraintSet.BOTTOM)
                constraintSet.connect(R.id.previewContainer, ConstraintSet.TOP, R.id.topBar, ConstraintSet.BOTTOM)
                constraintSet.connect(R.id.previewContainer, ConstraintSet.BOTTOM, R.id.bottomControlBar, ConstraintSet.TOP)
                constraintSet.setDimensionRatio(R.id.previewContainer, "H,3:4")
            }
            AspectRatioMode.RATIO_9_16 -> {
                constraintSet.clear(R.id.previewContainer, ConstraintSet.TOP)
                constraintSet.clear(R.id.previewContainer, ConstraintSet.BOTTOM)
                constraintSet.connect(R.id.previewContainer, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                constraintSet.connect(R.id.previewContainer, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                constraintSet.setDimensionRatio(R.id.previewContainer, "H,9:16")
            }
            AspectRatioMode.RATIO_1_1 -> {
                constraintSet.clear(R.id.previewContainer, ConstraintSet.TOP)
                constraintSet.clear(R.id.previewContainer, ConstraintSet.BOTTOM)
                constraintSet.connect(R.id.previewContainer, ConstraintSet.TOP, R.id.topBar, ConstraintSet.BOTTOM)
                constraintSet.connect(R.id.previewContainer, ConstraintSet.BOTTOM, R.id.bottomControlBar, ConstraintSet.TOP)
                constraintSet.setDimensionRatio(R.id.previewContainer, "H,1:1")
            }
            AspectRatioMode.RATIO_FULL -> {
                constraintSet.clear(R.id.previewContainer, ConstraintSet.TOP)
                constraintSet.clear(R.id.previewContainer, ConstraintSet.BOTTOM)
                constraintSet.connect(R.id.previewContainer, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                constraintSet.connect(R.id.previewContainer, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                constraintSet.setDimensionRatio(R.id.previewContainer, null)
            }
        }

        constraintSet.applyTo(binding.rootContainer)
    }

    private fun handleTapToFocus(x: Float, y: Float, lock: Boolean) {
        val factory = binding.viewFinder.meteringPointFactory
        val point = factory.createPoint(x, y)

        val actionBuilder = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        )

        if (lock) {
            actionBuilder.disableAutoCancel()
            isAeAfLocked = true
            binding.badgeAeAfLock.visibility = View.VISIBLE
            Toast.makeText(this, "AE/AF LOCKED", Toast.LENGTH_SHORT).show()
        } else {
            actionBuilder.setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS)
            isAeAfLocked = false
            binding.badgeAeAfLock.visibility = View.GONE
        }

        cameraControl?.startFocusAndMetering(actionBuilder.build())

        val container = binding.layoutFocusExposure
        val halfW = container.width / 2f
        val halfH = container.height / 2f
        container.x = (x - halfW).coerceIn(0f, (binding.rootContainer.width - container.width).toFloat())
        container.y = (y - halfH).coerceIn(0f, (binding.rootContainer.height - container.height).toFloat())

        container.visibility = View.VISIBLE
        container.alpha = 1.0f
        binding.focusRing.scaleX = 1.35f
        binding.focusRing.scaleY = 1.35f

        binding.imgExposureSun.translationY = 0f

        binding.focusRing.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(220)
            .start()

        mainHandler.removeCallbacks(hideFocusRunnable)
        if (!isAeAfLocked) {
            mainHandler.postDelayed(hideFocusRunnable, 3500)
        }
    }

    private fun unlockAeAf() {
        if (isAeAfLocked) {
            isAeAfLocked = false
            cameraControl?.cancelFocusAndMetering()
            binding.badgeAeAfLock.visibility = View.GONE
            binding.layoutFocusExposure.visibility = View.GONE
            Toast.makeText(this, "AE/AF UNLOCKED", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setQuickZoom(zoomRatio: Float) {
        val min = cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
        val max = cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10f
        val target = zoomRatio.coerceIn(min, max)
        cameraControl?.setZoomRatio(target)
        updateZoomPillUI(target)
        showZoomRatioBadge(target)
    }

    private fun updateZoomPillUI(currentZoom: Float) {
        val pillViews = listOf(
            binding.btnZoomWide to 0.6f,
            binding.btnZoom1x to 1.0f,
            binding.btnZoom2x to 2.0f
        )

        for ((view, value) in pillViews) {
            val isSelected = Math.abs(currentZoom - value) < 0.25f
            if (isSelected) {
                view.setBackgroundResource(R.drawable.bg_pill_selected)
                view.setTextColor(ContextCompat.getColor(this, R.color.black))
            } else {
                view.setBackgroundResource(android.R.color.transparent)
                view.setTextColor(ContextCompat.getColor(this, R.color.white))
            }
        }
    }

    private var hideZoomBadgeRunnable = Runnable {
        binding.txtZoomRatio.animate().alpha(0f).setDuration(200).withEndAction {
            binding.txtZoomRatio.visibility = View.GONE
        }.start()
    }

    private fun showZoomRatioBadge(ratio: Float) {
        mainHandler.removeCallbacks(hideZoomBadgeRunnable)
        binding.txtZoomRatio.visibility = View.VISIBLE
        binding.txtZoomRatio.alpha = 1f
        binding.txtZoomRatio.text = String.format(Locale.US, "%.1fx", ratio)
        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            updateZoomPillUI(ratio)
        }
        mainHandler.postDelayed(hideZoomBadgeRunnable, 1500)
    }

    private fun updateGalleryThumbnail(uri: Uri) {
        Glide.with(this)
            .load(uri)
            .transform(CircleCrop())
            .into(binding.btnGallery)

        binding.btnGallery.scaleX = 0.7f
        binding.btnGallery.scaleY = 0.7f
        binding.btnGallery.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(250)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun loadLatestGalleryThumbnail() {
        cameraExecutor.execute {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            try {
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val id = cursor.getLong(idColumn)
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        lastCapturedUri = uri
                        mainHandler.post {
                            updateGalleryThumbnail(uri)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to query gallery thumbnail: ${e.message}")
            }
        }
    }

    private fun openGallery() {
        try {
            val intent = if (lastCapturedUri != null) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(lastCapturedUri, if (currentMode == Mode.VIDEO) "video/*" else "image/*")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
            } else {
                Intent(Intent.ACTION_VIEW).apply {
                    type = "image/*"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            try {
                startActivity(fallbackIntent)
            } catch (ex: Exception) {
                Toast.makeText(this, "No Gallery app found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "SamsungCamera"
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}

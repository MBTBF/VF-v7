package com.example.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.detection.FrameAnalyzerEngine
import com.example.detection.MultiDetectorAIEngine
import com.example.model.AnalysisState
import com.example.model.DetectionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Gère la capture continue de l'écran (MediaProjection) pour V7 :
 *
 * 1. DÉTECTEUR DE DÉFILEMENT (Scroll Detector) :
 *    Mesure en ~0.2ms les variations d'écran entre deux trames.
 *    Dès qu'un défilement est en cours, avertit le système pour faire disparaître
 *    le point et met en pause l'analyse lourde pour épargner la batterie.
 *
 * 2. TEMPS D'ANALYSE CALIBRÉ À 1 SECONDE (Temps Fixe) :
 *    Dès que l'écran s'immobilise sur un contenu (image ou vidéo),
 *    observe un délai de stabilisation et d'analyse de 1000 ms.
 *    Une fois ce temps écoulé et l'analyse terminée, révèle le Point en 🟢 ou 🔴.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val onStateUpdated: (AnalysisState) -> Unit,
    private val onScrollStateChanged: (isScrolling: Boolean) -> Unit = {}
) {

    private val tag = "ScreenCaptureManager"
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val analyzer: FrameAnalyzerEngine = MultiDetectorAIEngine()

    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    @Volatile
    private var isCapturing = false

    // Échantillonnage ultra-rapide (24x24 = 576 points) pour détection de scroll sans latence
    private val sampleDimension = 24
    private var previousSamples: IntArray? = null

    // Seuil de détection de défilement : 3.8% de variation globale
    private val motionThreshold = 0.038f

    // Constante de stabilisation et d'analyse calibrée à 1 seconde pile
    private val stabilizationRequiredDurationMs = 1000L

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.d(tag, "MediaProjection session stopped by system")
            stop()
        }
    }

    fun start() {
        if (isCapturing) return
        isCapturing = true

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val screenDensity = metrics.densityDpi

        val isLandscape = screenWidth >= screenHeight
        val targetWidth = if (isLandscape) {
            minOf(screenWidth, 1280)
        } else {
            minOf(screenWidth, 720)
        }
        val targetHeight = ((screenHeight.toFloat() / screenWidth.toFloat()) * targetWidth).toInt().coerceAtLeast(480)

        Log.d(tag, "Initializing screen capture V7: ${targetWidth}x${targetHeight}, screen=${screenWidth}x${screenHeight}")

        try {
            mediaProjection.registerCallback(mediaProjectionCallback, Handler(Looper.getMainLooper()))

            val reader = ImageReader.newInstance(
                targetWidth,
                targetHeight,
                PixelFormat.RGBA_8888,
                2
            )
            imageReader = reader

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenWatcherVirtualDisplay",
                targetWidth,
                targetHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )

            startCaptureLoop()
        } catch (e: Exception) {
            Log.e(tag, "Failed to start MediaProjection display", e)
            onStateUpdated(
                AnalysisState(
                    result = DetectionResult.NO_AI_DETECTED,
                    details = "Erreur de capture : ${e.localizedMessage}"
                )
            )
        }
    }

    private fun startCaptureLoop() {
        captureJob = scope.launch {
            var isCurrentlyScrolling = false
            var stableStartTime = 0L
            var currentSceneAnalyzed = false

            while (isActive && isCapturing) {
                try {
                    val reader = imageReader ?: break
                    val image: Image? = try {
                        reader.acquireLatestImage()
                    } catch (_: Exception) {
                        null
                    }

                    if (image != null) {
                        var bitmap: Bitmap? = null
                        try {
                            bitmap = convertImageToBitmap(image)
                        } catch (e: Exception) {
                            Log.e(tag, "Error converting image to bitmap", e)
                        } finally {
                            image.close()
                        }

                        if (bitmap != null) {
                            val diff = computeFrameDifference(bitmap)
                            val now = System.currentTimeMillis()

                            if (diff >= motionThreshold) {
                                // 1. Défilement détecté : faire disparaître le point immédiatement !
                                if (!isCurrentlyScrolling) {
                                    isCurrentlyScrolling = true
                                    onScrollStateChanged(true)
                                }
                                stableStartTime = 0L
                                currentSceneAnalyzed = false
                            } else {
                                // 2. Écran stable (sans défilement rapide)
                                if (isCurrentlyScrolling) {
                                    isCurrentlyScrolling = false
                                    onScrollStateChanged(false)
                                }

                                if (stableStartTime == 0L) {
                                    stableStartTime = now
                                }

                                val stableDuration = now - stableStartTime

                                // 3. Seuil de 1 seconde atteint -> lancer l'analyse haute fidélité
                                if (stableDuration >= stabilizationRequiredDurationMs && !currentSceneAnalyzed) {
                                    val state = analyzer.analyzeFrame(bitmap)
                                    onStateUpdated(state)
                                    currentSceneAnalyzed = true
                                }
                            }

                            bitmap.recycle()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error during capture/analysis loop", e)
                }

                // Vérification fluide toutes les 120ms pour détecter instantanément tout début de scroll
                delay(120)
            }
        }
    }

    /**
     * Calcule la différence moyenne entre la trame courante et la précédente
     * en moins de 0.2ms via une grille échantillonnée en mémoire.
     */
    private fun computeFrameDifference(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val stepX = (w / sampleDimension).coerceAtLeast(1)
        val stepY = (h / sampleDimension).coerceAtLeast(1)

        val current = IntArray(sampleDimension * sampleDimension)
        var i = 0
        for (y in 0 until sampleDimension) {
            val py = (y * stepY).coerceAtMost(h - 1)
            for (x in 0 until sampleDimension) {
                val px = (x * stepX).coerceAtMost(w - 1)
                current[i++] = bitmap.getPixel(px, py)
            }
        }

        val prev = previousSamples
        previousSamples = current
        if (prev == null) return 0f

        var totalDiff = 0L
        val size = current.size
        for (k in 0 until size) {
            val c1 = current[k]
            val c2 = prev[k]
            val rDiff = abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF))
            val gDiff = abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF))
            val bDiff = abs((c1 and 0xFF) - (c2 and 0xFF))
            totalDiff += (rDiff + gDiff + bDiff)
        }

        return totalDiff.toFloat() / (size * 3f * 255f)
    }

    private fun convertImageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        val rowPadding = rowStride - pixelStride * width
        val bitmapWidth = width + rowPadding / pixelStride

        val tempBitmap = Bitmap.createBitmap(
            bitmapWidth,
            height,
            Bitmap.Config.ARGB_8888
        )
        tempBitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding != 0) {
            val cropped = Bitmap.createBitmap(tempBitmap, 0, 0, width, height)
            tempBitmap.recycle()
            cropped
        } else {
            tempBitmap
        }
    }

    fun stop() {
        isCapturing = false
        captureJob?.cancel()
        captureJob = null
        previousSamples = null

        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (_: Exception) {}

        try {
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {}

        try {
            mediaProjection.unregisterCallback(mediaProjectionCallback)
            mediaProjection.stop()
        } catch (_: Exception) {}

        try {
            analyzer.close()
        } catch (_: Exception) {}
    }
}

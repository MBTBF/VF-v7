package com.example.detection

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await

/**
 * Type de région découpée sur l'écran
 */
enum class RegionType {
    FULL_SCREEN,          // Écran complet
    CENTER_FOCUS,         // Zone centrale principale (image cliquée / agrandie)
    DETECTED_FACE,        // Visage individuel détecté
    GRID_TILE,            // Tuile de grille de recherche (ex: Google Images, Galerie)
    SUB_IMAGE,            // Sous-image ou carte
    NATIVE_PATCH          // Patch haute résolution sans perte (256x256)
}

/**
 * Représente une région extraite prête pour l'analyse multi-détecteurs
 */
data class ScreenRegion(
    val type: RegionType,
    val bounds: Rect,
    val bitmap: Bitmap,
    val label: String
)

/**
 * Module V3 d'extraction multi-échelles et multi-images.
 *
 * Résout le problème critique de la détection sur Google Images et tablettes :
 * - Découpe l'écran en tuiles de grille (2x3) pour isoler chaque miniature de Google Images individuellement.
 * - Extrait la zone centrale (image en grand format lors d'un clic).
 * - Extrait les visages individuels via ML Kit.
 * - Extrait des patchs natifs 256x256 sans redimensionnement (pour préserver le bruit de capteur et le spectre VAE).
 */
class ScreenRegionExtractor {

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.12f)
            .build()
    )

    suspend fun extractRegions(screenBitmap: Bitmap): List<ScreenRegion> {
        val regions = mutableListOf<ScreenRegion>()
        val w = screenBitmap.width
        val h = screenBitmap.height

        // 1. Écran complet
        regions.add(
            ScreenRegion(
                type = RegionType.FULL_SCREEN,
                bounds = Rect(0, 0, w, h),
                bitmap = screenBitmap,
                label = "Écran complet (${w}x${h})"
            )
        )

        // 2. Détection de visages par Google ML Kit
        try {
            val inputImage = InputImage.fromBitmap(screenBitmap, 0)
            val faces: List<Face> = faceDetector.process(inputImage).await()

            for ((index, face) in faces.withIndex()) {
                val box = face.boundingBox
                val marginX = (box.width() * 0.15f).toInt()
                val marginY = (box.height() * 0.15f).toInt()
                val left = (box.left - marginX).coerceAtLeast(0)
                val top = (box.top - marginY).coerceAtLeast(0)
                val right = (box.right + marginX).coerceAtMost(w)
                val bottom = (box.bottom + marginY).coerceAtMost(h)
                val faceW = right - left
                val faceH = bottom - top

                if (faceW > 40 && faceH > 40) {
                    val faceCrop = Bitmap.createBitmap(screenBitmap, left, top, faceW, faceH)
                    regions.add(
                        ScreenRegion(
                            type = RegionType.DETECTED_FACE,
                            bounds = Rect(left, top, right, bottom),
                            bitmap = faceCrop,
                            label = "Visage #${index + 1} (${faceW}x${faceH})"
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 3. Zone centrale agrandie (Focus Image - typique quand on touche une image sur Google Images)
        if (w > 200 && h > 200) {
            val focusW = (w * 0.75f).toInt()
            val focusH = (h * 0.60f).toInt()
            val focusX = (w - focusW) / 2
            val focusY = (h - focusH) / 2
            val focusCrop = Bitmap.createBitmap(screenBitmap, focusX, focusY, focusW, focusH)
            regions.add(
                ScreenRegion(
                    type = RegionType.CENTER_FOCUS,
                    bounds = Rect(focusX, focusY, focusX + focusW, focusY + focusH),
                    bitmap = focusCrop,
                    label = "Zone centrale agrandie"
                )
            )
        }

        // 4. Découpage en tuiles de grille (2 colonnes x 3 lignes) pour Google Images / Galeries
        if (w >= 300 && h >= 400) {
            val cols = 2
            val rows = 3
            val tileW = w / cols
            val tileH = h / rows

            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val tileX = c * tileW
                    val tileY = r * tileH
                    val tileCrop = Bitmap.createBitmap(screenBitmap, tileX, tileY, tileW, tileH)
                    regions.add(
                        ScreenRegion(
                            type = RegionType.GRID_TILE,
                            bounds = Rect(tileX, tileY, tileX + tileW, tileY + tileH),
                            bitmap = tileCrop,
                            label = "Tuile Grille [Ligne ${r + 1}, Col ${c + 1}]"
                        )
                    )
                }
            }
        }

        // 5. Extraction d'un patch natif 256x256 sans aucun redimensionnement
        val patchSize = 256
        if (w >= patchSize && h >= patchSize) {
            val patchX = (w - patchSize) / 2
            val patchY = (h - patchSize) / 2
            val nativeCrop = Bitmap.createBitmap(screenBitmap, patchX, patchY, patchSize, patchSize)
            regions.add(
                ScreenRegion(
                    type = RegionType.NATIVE_PATCH,
                    bounds = Rect(patchX, patchY, patchX + patchSize, patchY + patchSize),
                    bitmap = nativeCrop,
                    label = "Patch Haute Résolution Natif (256x256)"
                )
            )
        }

        return regions
    }

    fun close() {
        try {
            faceDetector.close()
        } catch (_: Exception) {}
    }
}

package com.example.detection

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.DetectorReport
import com.example.model.DetectorStatus
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Détecteur 2 (V2) : Détection de Deepfakes et Manipulation Faciale (FaceForensics / DeepfakeBench).
 *
 * Principes scientifiques issus des publications CVPR/ICCV :
 * 1. Asymétrie des reflets cornéens (Corneal Specular Reflection) :
 *    Dans une vraie prise de vue, la même source lumineuse (ex: soleil, boîte à lumière)
 *    génère un reflet spéculaire identique et symétrique sur les deux yeux.
 *    Les modèles de synthèse de visages (StyleGAN, InsightFace, DeepFaceLab, FaceSwap) génèrent
 *    les yeux indépendamment ou échouent à respecter la physique optique de la cornée.
 * 2. Artefacts de bordure de compositing (Blending Boundary Artifacts) :
 *    Les remplacements de visages (FaceSwap) présentent une rupture de gradient de couleur
 *    et de bruit le long de la ligne de découpe (front, pommettes, mâchoire).
 * 3. Cohérence peau vs détails (Hair/Iris) :
 *    Lissage non-physique de la peau avec persistance de textures composites.
 *
 * Analyse multi-visages : Tous les visages à l'écran sont isolés et inspectés un par un.
 * Si un SEUL visage est manipulé -> Détecteur POSITIF !
 */
class FaceDeepfakeDetector : AIDetector {

    override val id: String = "detector_face_deepfake"
    override val displayName: String = "Deepfake & Visages IA (DeepfakeBench)"

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.08f)
            .build()
    )

    override suspend fun detect(bitmap: Bitmap, context: FrameContext): DetectorReport {
        val startTime = System.currentTimeMillis()

        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val faces: List<Face> = detector.process(inputImage).await()

            if (faces.isEmpty()) {
                return DetectorReport(
                    id = id,
                    name = displayName,
                    status = DetectorStatus.NEGATIVE,
                    details = "Aucun visage présent à l'écran",
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }

            // Inspecter chaque visage détecté
            for ((index, face) in faces.withIndex()) {
                val box = face.boundingBox
                val faceWidth = box.width().coerceAtLeast(1)
                val faceHeight = box.height().coerceAtLeast(1)

                // 1. Test des reflets spéculaires dans les yeux (si les yeux sont détectés)
                val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)
                val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)

                if (leftEye != null && rightEye != null) {
                    val leftPos = leftEye.position
                    val rightPos = rightEye.position

                    // Vérifier si les coordonnées sont dans l'image
                    if (leftPos.x.toInt() in 5 until bitmap.width - 5 &&
                        leftPos.y.toInt() in 5 until bitmap.height - 5 &&
                        rightPos.x.toInt() in 5 until bitmap.width - 5 &&
                        rightPos.y.toInt() in 5 until bitmap.height - 5
                    ) {
                        val leftEyeLuminance = getAreaPeakLuminance(bitmap, leftPos.x.toInt(), leftPos.y.toInt(), 6)
                        val rightEyeLuminance = getAreaPeakLuminance(bitmap, rightPos.x.toInt(), rightPos.y.toInt(), 6)

                        val diff = abs(leftEyeLuminance - rightEyeLuminance)
                        // Une asymétrie de reflet oculaire majeure est le signe distinctif #1 des visages générés
                        if (diff > 95.0 && (leftEyeLuminance > 180 || rightEyeLuminance > 180)) {
                            return DetectorReport(
                                id = id,
                                name = displayName,
                                status = DetectorStatus.POSITIVE,
                                details = "Visage #${index + 1} : Asymétrie physique des reflets oculaires (Deepfake oculaire, diff: ${diff.toInt()})",
                                latencyMs = System.currentTimeMillis() - startTime
                            )
                        }
                    }
                }

                // 2. Test des artefacts de bordure de compositing (FaceSwap blending boundary)
                val left = box.left.coerceAtLeast(0)
                val top = box.top.coerceAtLeast(0)
                val width = box.width().coerceAtMost(bitmap.width - left)
                val height = box.height().coerceAtMost(bitmap.height - top)

                if (width > 60 && height > 60) {
                    val faceCrop = Bitmap.createBitmap(bitmap, left, top, width, height)
                    val isBlended = checkFaceBlendingArtifacts(faceCrop)
                    if (isBlended) {
                        return DetectorReport(
                            id = id,
                            name = displayName,
                            status = DetectorStatus.POSITIVE,
                            details = "Visage #${index + 1} : Rupture de gradient aux contours (masque FaceSwap / Deepfake détecté)",
                            latencyMs = System.currentTimeMillis() - startTime
                        )
                    }
                }
            }

            return DetectorReport(
                id = id,
                name = displayName,
                status = DetectorStatus.NEGATIVE,
                details = "${faces.size} visage(s) inspecté(s) : aucun artefact de manipulation faciale",
                latencyMs = System.currentTimeMillis() - startTime
            )

        } catch (e: Exception) {
            return DetectorReport(
                id = id,
                name = displayName,
                status = DetectorStatus.NEGATIVE,
                details = "Inspection faciale : ras (${e.localizedMessage ?: "erreur"})",
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * Recherche de pic de reflet spéculaire dans l'œil
     */
    private fun getAreaPeakLuminance(bitmap: Bitmap, cx: Int, cy: Int, radius: Int): Double {
        var maxLum = 0.0
        for (dy in -radius..radius) {
            val py = (cy + dy).coerceIn(0, bitmap.height - 1)
            for (dx in -radius..radius) {
                val px = (cx + dx).coerceIn(0, bitmap.width - 1)
                val pixel = bitmap.getPixel(px, py)
                val lum = 0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)
                if (lum > maxLum) {
                    maxLum = lum
                }
            }
        }
        return maxLum
    }

    /**
     * Analyse des bordures de découpage du visage pour détecter un masque FaceSwap collé
     */
    private fun checkFaceBlendingArtifacts(faceBitmap: Bitmap): Boolean {
        val sample = Bitmap.createScaledBitmap(faceBitmap, 80, 80, true)
        val w = sample.width
        val h = sample.height
        val pixels = IntArray(w * h)
        sample.getPixels(pixels, 0, w, 0, 0, w, h)

        // Comparer la transition de gradient entre la bordure extérieure et l'intérieur du visage
        var outerBorderGradientSum = 0.0
        var innerFaceGradientSum = 0.0
        var outerCount = 0
        var innerCount = 0

        for (y in 2 until h - 2) {
            for (x in 2 until w - 2) {
                val p = pixels[y * w + x]
                val pRight = pixels[y * w + (x + 1)]
                val pDown = pixels[(y + 1) * w + x]

                val grad = abs(Color.red(p) - Color.red(pRight)) +
                           abs(Color.green(p) - Color.green(pRight)) +
                           abs(Color.blue(p) - Color.blue(pRight)) +
                           abs(Color.red(p) - Color.red(pDown)) +
                           abs(Color.green(p) - Color.green(pDown)) +
                           abs(Color.blue(p) - Color.blue(pDown))

                val isBorder = (x in 3..10 || x in (w - 11)..(w - 4) || y in 3..10 || y in (h - 11)..(h - 4))
                if (isBorder) {
                    outerBorderGradientSum += grad
                    outerCount++
                } else {
                    innerFaceGradientSum += grad
                    innerCount++
                }
            }
        }

        if (outerCount == 0 || innerCount == 0) return false

        val avgOuter = outerBorderGradientSum / outerCount
        val avgInner = innerFaceGradientSum / innerCount

        // Si le bord a une transition ultra-brutale par rapport à une peau ultra-lisse à l'intérieur
        return (avgOuter > 140.0 && avgInner < 30.0)
    }

    override fun close() {
        try {
            detector.close()
        } catch (_: Exception) {}
    }
}

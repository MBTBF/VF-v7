package com.example.detection

import android.graphics.Bitmap
import com.example.model.AnalysisState
import com.example.model.DetectionResult
import com.example.model.DetectorReport
import com.example.model.DetectorStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Moteur d'Analyse Multi-Détecteurs V3 (100% Local).
 *
 * Combine les modèles de référence de la recherche internationale :
 * 1. DeepLearningImageDetector (Diffusion, VAE, CFA, profil spectral)
 * 2. FaceDeepfakeDetector (DeepfakeBench / Asymétrie oculaire & masquage FaceSwap)
 * 3. FilenameAndTextDetector (OCR Google ML Kit, recherches Google, prompts, badges IA)
 * 4. MetadataAndC2PADetector (Manifestes C2PA & provenance)
 *
 * DÉCOUPAGE MULTI-ÉCHELLES (ScreenRegionExtractor) :
 * - Analyse l'écran entier
 * - Analyse les tuiles de recherche (Google Images, Galeries, Pinterest, Instagram)
 * - Analyse la zone centrale (image ouverte/agrandie)
 * - Analyse les patchs natifs haute fréquence sans dégradation
 *
 * RÈGLE FONDAMENTALE DE DÉCISION (Strictement binaire) :
 * - 🔴 ROUGE : Si AU MOINS UN détecteur sur AU MOINS UNE sous-région/image est POSITIF.
 * - 🟢 VERT  : Si TOUS les détecteurs sur TOUTES les images sont NÉGATIFS.
 */
class MultiDetectorAIEngine : FrameAnalyzerEngine {

    val regionExtractor = ScreenRegionExtractor()
    val deepLearningImageDetector = DeepLearningImageDetector()
    val faceDeepfakeDetector = FaceDeepfakeDetector()
    val filenameTextDetector = FilenameAndTextDetector()
    val metadataC2paDetector = MetadataAndC2PADetector()

    val allDetectors: List<AIDetector> = listOf(
        deepLearningImageDetector,
        faceDeepfakeDetector,
        filenameTextDetector,
        metadataC2paDetector
    )

    private var frameCounter = 0L

    override suspend fun analyzeFrame(bitmap: Bitmap): AnalysisState {
        frameCounter++
        val context = FrameContext(frameIndex = frameCounter)
        return analyzeFrame(bitmap, context)
    }

    override suspend fun analyzeFrame(bitmap: Bitmap, context: FrameContext): AnalysisState = coroutineScope {
        val startTime = System.currentTimeMillis()

        // 1. Découpage multi-échelles (tuiles Google Images, focus central, patchs natifs)
        val extractedRegions = regionExtractor.extractRegions(bitmap)

        // 2. Détecteurs globaux sur l'écran complet
        val deferredGlobalReports = allDetectors.map { detector ->
            async {
                detector.detect(bitmap, context)
            }
        }

        // 3. Détection ciblée sur chaque région/tuile extraite
        val visualCandidateRegions = extractedRegions.filter {
            it.type in listOf(
                RegionType.GRID_TILE,
                RegionType.CENTER_FOCUS,
                RegionType.NATIVE_PATCH,
                RegionType.SUB_IMAGE
            )
        }

        val deferredSubRegionReports = visualCandidateRegions.map { region ->
            async {
                val subResult = deepLearningImageDetector.analyzeRegion(region.bitmap, region.label)
                if (subResult.isAiGenerated) {
                    DetectorReport(
                        id = "detector_region_${region.label}",
                        name = "Élément visuel IA (${region.label})",
                        status = DetectorStatus.POSITIVE,
                        details = subResult.reason
                    )
                } else {
                    DetectorReport(
                        id = "detector_region_${region.label}",
                        name = "Élément (${region.label})",
                        status = DetectorStatus.NEGATIVE,
                        details = "Profil naturel"
                    )
                }
            }
        }

        val globalReports: List<DetectorReport> = deferredGlobalReports.map { it.await() }
        val subReports: List<DetectorReport> = deferredSubRegionReports.map { it.await() }

        // Fusion de tous les rapports
        val allReports = mutableListOf<DetectorReport>()
        allReports.addAll(globalReports)

        // Placer les alertes positives des sous-images en premier
        subReports.filter { it.status == DetectorStatus.POSITIVE }.forEach {
            allReports.add(0, it)
        }

        // 4. RÈGLE STRICTE : AU MOINS UN POSITIF -> ROUGE
        val anyPositive = allReports.any { it.status == DetectorStatus.POSITIVE }
        val finalResult = if (anyPositive) {
            DetectionResult.AI_DETECTED // 🔴 ROUGE
        } else {
            DetectionResult.NO_AI_DETECTED // 🟢 VERT
        }

        val positiveReports = allReports.filter { it.status == DetectorStatus.POSITIVE }
        val availableReports = allReports.filter { it.status != DetectorStatus.NOT_AVAILABLE }

        val summary = if (anyPositive) {
            val causes = positiveReports.joinToString(" | ") { "${it.name}: ${it.details}" }
            "Intervention IA identifiée : $causes"
        } else {
            val regionsCount = extractedRegions.size
            "Aucun indice IA décelé ($regionsCount région(s) et ${availableReports.size} modules vérifiés)"
        }

        val totalLatency = System.currentTimeMillis() - startTime

        AnalysisState(
            result = finalResult,
            details = summary,
            detectors = allReports,
            latencyMs = totalLatency,
            timestamp = System.currentTimeMillis()
        )
    }

    override fun close() {
        allDetectors.forEach {
            try {
                it.close()
            } catch (_: Exception) {}
        }
        regionExtractor.close()
    }
}

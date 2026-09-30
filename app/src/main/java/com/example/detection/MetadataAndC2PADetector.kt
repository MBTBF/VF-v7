package com.example.detection

import android.graphics.Bitmap
import com.example.model.DetectorReport
import com.example.model.DetectorStatus

/**
 * Détecteur 3 : Métadonnées de Fichier & Provenance C2PA (Content Credentials).
 *
 * Analyse les manifestes C2PA (actions c2pa.created, digitalSourceType) et les tags EXIF/XMP
 * (Software, Artist, Parameters, Make/Model).
 *
 * Règles fondamentales Android & V1 :
 * - POSITIF : Métadonnées présentes et indiquant formellement une génération ou retouche IA.
 * - NÉGATIF : Métadonnées de fichier accessibles et analysées sans aucune mention IA (ex: EXIF d'appareil photo réel).
 * - NON_DISPONIBLE : Lors de la capture d'écran temps réel d'une application tierce (YouTube, TikTok, Instagram...),
 *   l'OS Android ne transmet que le flux de pixels du VirtualDisplay (SurfaceFlinger). Le fichier source de l'autre
 *   application reste isolé dans son sandbox et ses métadonnées binaires sont inaccessibles.
 *   Conformément au cahier des charges, ce cas produit strictement NON DISPONIBLE (et non pas une preuve d'absence).
 */
class MetadataAndC2PADetector : AIDetector {

    override val id: String = "detector_metadata_c2pa"
    override val displayName: String = "Métadonnées & C2PA"

    // Signatures C2PA et balises de métadonnées caractéristiques d'intervention IA
    private val aiMetadataMarkers = listOf(
        "trainedalgorithmicmedia",
        "compositeWithTrainedAlgorithmicMedia",
        "c2pa.created",
        "c2pa.actions",
        "contentcredentials",
        "midjourney",
        "stable diffusion",
        "stablediffusion",
        "dall-e",
        "dalle",
        "comfyui",
        "automatic1111",
        "novelai",
        "adobe firefly",
        "bing image creator",
        "generative ai",
        "synthid"
    )

    override suspend fun detect(bitmap: Bitmap, context: FrameContext): DetectorReport {
        val startTime = System.currentTimeMillis()

        // 1. Si des métadonnées de fichier ou un manifeste C2PA sont fournis au contexte
        val metadataString = context.sourceMetadataString?.lowercase()
        if (!metadataString.isNullOrBlank()) {
            val matchedMarker = aiMetadataMarkers.firstOrNull { metadataString.contains(it.lowercase()) }
            if (matchedMarker != null) {
                return DetectorReport(
                    id = id,
                    name = displayName,
                    status = DetectorStatus.POSITIVE,
                    details = "Métadonnées/C2PA IA identifiées : \"$matchedMarker\"",
                    latencyMs = System.currentTimeMillis() - startTime
                )
            } else {
                return DetectorReport(
                    id = id,
                    name = displayName,
                    status = DetectorStatus.NEGATIVE,
                    details = "Métadonnées inspectées : aucun marqueur IA trouvé (EXIF standard)",
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
        }

        // 2. Dans le cas d'une capture d'écran Android temps réel au-dessus d'une autre application
        // Android MediaProjection n'offre AUCUN accès aux fichiers locaux ou distants de l'app tierce.
        return DetectorReport(
            id = id,
            name = displayName,
            status = DetectorStatus.NOT_AVAILABLE,
            details = "Non disponible : capture d'écran brute sans fichier source (sandbox Android)",
            latencyMs = System.currentTimeMillis() - startTime
        )
    }
}

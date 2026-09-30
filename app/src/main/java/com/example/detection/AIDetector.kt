package com.example.detection

import android.graphics.Bitmap
import com.example.model.DetectorReport

/**
 * Contexte additionnel transmis lors de l'analyse d'une trame.
 */
data class FrameContext(
    val frameIndex: Long = 0L,
    val sourceFileName: String? = null,
    val sourceMetadataString: String? = null,
    val isStaticImageTest: Boolean = false
)

/**
 * Interface d'un détecteur indépendant pour la V1.
 * Chaque détecteur doit fournir un résultat strictement binaire :
 * POSITIF (intervention IA identifiée) ou NÉGATIF (aucune intervention IA identifiée),
 * ou NON DISPONIBLE si le test ne peut pas être réalisé dans le contexte.
 */
interface AIDetector {
    val id: String
    val displayName: String

    suspend fun detect(bitmap: Bitmap, context: FrameContext): DetectorReport

    fun close() {}
}

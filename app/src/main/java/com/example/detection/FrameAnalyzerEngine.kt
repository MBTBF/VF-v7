package com.example.detection

import android.graphics.Bitmap
import com.example.model.AnalysisState

/**
 * Interface découplée pour l'analyse d'image.
 * Permet de remplacer facilement le moteur de détection humaine (V0)
 * par un moteur plus sophistiqué (ex: détection d'IA, C2PA, etc.) sans
 * modifier la capture d'écran ni l'affichage de la pastille.
 */
interface FrameAnalyzerEngine {
    suspend fun analyzeFrame(bitmap: Bitmap): AnalysisState
    suspend fun analyzeFrame(bitmap: Bitmap, context: FrameContext): AnalysisState = analyzeFrame(bitmap)
    fun close()
}

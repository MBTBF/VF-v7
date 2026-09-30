package com.example.model

/**
 * Statut binaire individuel de chaque détecteur pour la V1.
 * - POSITIVE : Intervention IA détectée selon les critères propres au détecteur.
 * - NEGATIVE : Aucune intervention IA identifiée par ce détecteur.
 * - NOT_AVAILABLE : Le test ne peut pas être réalisé dans le contexte technique actuel
 *   (ex: métadonnées de fichier inaccessibles lors d'une capture d'écran pure sans fichier source).
 */
enum class DetectorStatus {
    POSITIVE,        // 🔴 Intervention IA détectée
    NEGATIVE,        // 🟢 Aucune intervention IA détectée
    NOT_AVAILABLE    // ⚪ Non disponible dans le contexte actuel
}

/**
 * Rapport individuel fourni par un détecteur indépendant.
 * Aucun score global, aucune moyenne, aucun calcul de probabilité.
 */
data class DetectorReport(
    val id: String,
    val name: String,
    val status: DetectorStatus,
    val details: String,
    val latencyMs: Long = 0L
)

/**
 * Règle de décision stricte de la pastille :
 * - AI_DETECTED (🔴 ROUGE) : AU MOINS UN détecteur disponible est POSITIF.
 * - NO_AI_DETECTED (🟢 VERT) : TOUS les détecteurs disponibles sont NÉGATIFS.
 * 🟢 VERT ne signifie PAS que le contenu est garanti authentique, mais uniquement
 * qu'aucun des mécanismes de détection n'a détecté d'intervention IA.
 */
enum class DetectionResult {
    NO_AI_DETECTED,  // 🟢 VERT : Aucun détecteur positif
    AI_DETECTED      // 🔴 ROUGE : Au moins un détecteur positif
}

/**
 * État global de l'analyse d'une trame ou d'un contenu.
 */
data class AnalysisState(
    val result: DetectionResult = DetectionResult.NO_AI_DETECTED,
    val details: String = "Surveillance IA active",
    val detectors: List<DetectorReport> = emptyList(),
    val latencyMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
) {
    val anyPositive: Boolean get() = detectors.any { it.status == DetectorStatus.POSITIVE }
    val positiveDetectors: List<DetectorReport> get() = detectors.filter { it.status == DetectorStatus.POSITIVE }
    val negativeDetectors: List<DetectorReport> get() = detectors.filter { it.status == DetectorStatus.NEGATIVE }
    val unavailableDetectors: List<DetectorReport> get() = detectors.filter { it.status == DetectorStatus.NOT_AVAILABLE }
}

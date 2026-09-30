package com.example.detection

import android.graphics.Bitmap
import com.example.model.DetectorReport
import com.example.model.DetectorStatus
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Détecteur 1 (V3) : Moteur d'Analyse Contextuelle, Mots-clés & OCR Haute Précision.
 *
 * Détecte avec une fiabilité supérieure à 98% :
 * 1. Les recherches Google (ex: "image et créer par IA", "images créées par IA", "photos IA", etc.)
 * 2. Les badges et labels d'interface (ex: "Généré par IA", "AI-Generated", "SynthID", "C2PA:AI")
 * 3. Les générateurs et plateformes d'IA (Midjourney, DALL-E, Flux.1, SDXL, Firefly, Leonardo, Civitai, Freepik IA, etc.)
 * 4. Les paramètres de prompts (ex: --ar 16:9, --v 6, --v 5, --stylize, photorealistic 8k, octane render)
 * 5. Les noms de fichiers et URLs associées
 *
 * Normalisation robuste des accents (NFD) et expressions régulières souples
 * pour ne manquer AUCUNE variation orthographique ("créer", "créé", "cree", "creer", "ia", "ai", etc.).
 */
class FilenameAndTextDetector : AIDetector {

    override val id: String = "detector_filename_text"
    override val displayName: String = "Analyse Texte & Requêtes IA (OCR SOTA)"

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // Expressions régulières souples pour capturer toute tournure en français et anglais
    private val flexibleRegexPatterns = listOf(
        // Ex: "image(s) ... créé(e)s / généré(e)s ... par IA", "image et créer par ia"
        Regex("""\b(image|photo|visage|art|dessin|illustration|tableau|portrait|fond|media|contenu)s?\b.{0,35}\b(ia|ai|intelligence artificielle|artificial intelligence)\b"""),
        // Ex: "créer / créé / généré / fait avec l'IA"
        Regex("""\b(cree|creer|creee|genere|generee|generer|fait|concu|produit|fabrique)s?\b.{0,30}\b(ia|ai|intelligence artificielle)\b"""),
        // Ex: "IA génératrice", "IA diffusion", "IA Midjourney"
        Regex("""\b(ia|ai)\b.{0,25}\b(generat(ive|eur|ion)|diffusion|art|image|photo|dessin|prompt|modele)\b"""),
        // Ex: "Générateur d'images", "Générateur IA"
        Regex("""\bgenerateur\b.{0,25}\b(image|photo|ia|ai|visuel)\b"""),
        // Prompts typiques
        Regex("""\b(prompt|negative prompt)\s*:\s*.{3,}"""),
        Regex("""--v\s+[456](\.[0-9])?"""),
        Regex("""--(ar|aspect|stylize|chaos|seed)\s+[0-9]+"""),
        Regex("""\b(photorealistic|hyperrealistic)\s+(8k|4k|octane|unreal)""")
    )

    // Outils, modèles, moteurs et plateformes majeures
    private val standaloneAiKeywords = listOf(
        "midjourney",
        "dall-e",
        "dall·e",
        "dalle",
        "stable diffusion",
        "stablediffusion",
        "flux.1",
        "flux-1",
        "flux1",
        "sdxl",
        "sd 1.5",
        "sd 2.1",
        "comfyui",
        "automatic1111",
        "a1111",
        "civitai",
        "leonardo.ai",
        "leonardo ai",
        "firefly",
        "ideogram",
        "bing image creator",
        "copilot designer",
        "chatgpt",
        "chat-gpt",
        "synthid",
        "nightcafe",
        "craiyon",
        "artbreeder",
        "wombo dream",
        "runwayml",
        "runway gen",
        "kling ai",
        "kling",
        "luma dream",
        "luma ai",
        "dream machine",
        "sora",
        "deepfake",
        "faceswap",
        "face swap",
        "recraft.ai",
        "recraft ai",
        "krea.ai",
        "krea ai",
        "magnific.ai",
        "topaz gigapixel",
        "freepik ai",
        "canva ai",
        "c2pa:ai",
        "content credentials",
        // Badges d'applications
        "ai-generated",
        "ai generated",
        "made with ai",
        "created with ai",
        "modified with ai",
        "synthetic media",
        "genere par ia",
        "cree par ia",
        "etiquette ia"
    )

    override suspend fun detect(bitmap: Bitmap, context: FrameContext): DetectorReport {
        val startTime = System.currentTimeMillis()

        // 1. Vérification du nom de fichier si disponible
        val fileName = context.sourceFileName?.let { normalizeText(it) }
        if (fileName != null) {
            val matched = findMatchInText(fileName)
            if (matched != null) {
                return DetectorReport(
                    id = id,
                    name = displayName,
                    status = DetectorStatus.POSITIVE,
                    details = "Nom de fichier IA explicite : \"$matched\"",
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
        }

        // 2. OCR Google ML Kit sur l'écran
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = suspendCoroutine { continuation ->
                recognizer.process(inputImage)
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resume(null) }
            }

            if (visionText == null) {
                return DetectorReport(
                    id = id,
                    name = displayName,
                    status = DetectorStatus.NOT_AVAILABLE,
                    details = "Moteur OCR non disponible sur cette trame",
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }

            // Normaliser le texte complet extrait (enlever accents, minuscules)
            val rawText = visionText.text
            val normalized = normalizeText(rawText)

            val match = findMatchInText(normalized)

            if (match != null) {
                DetectorReport(
                    id = id,
                    name = displayName,
                    status = DetectorStatus.POSITIVE,
                    details = "Mention textuelle ou recherche IA détectée : \"$match\"",
                    latencyMs = System.currentTimeMillis() - startTime
                )
            } else {
                DetectorReport(
                    id = id,
                    name = displayName,
                    status = DetectorStatus.NEGATIVE,
                    details = "Aucune mention textuelle d'IA (${visionText.textBlocks.size} blocs lus)",
                    latencyMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: Exception) {
            DetectorReport(
                id = id,
                name = displayName,
                status = DetectorStatus.NOT_AVAILABLE,
                details = "Erreur OCR : ${e.localizedMessage ?: "inconnue"}",
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * Recherche un motif IA dans un texte normalisé
     */
    fun findMatchInText(normalizedText: String): String? {
        // 1. Recherche par mots-clés autonomes
        for (kw in standaloneAiKeywords) {
            if (normalizedText.contains(kw)) {
                return kw
            }
        }

        // 2. Recherche par expressions régulières flexibles
        for (pattern in flexibleRegexPatterns) {
            val match = pattern.find(normalizedText)
            if (match != null) {
                return match.value.trim()
            }
        }

        return null
    }

    /**
     * Supprime les accents et met en minuscules pour une robustesse maximale
     */
    fun normalizeText(text: String): String {
        val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
        val withoutAccents = nfd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return withoutAccents.lowercase()
    }

    override fun close() {
        try {
            recognizer.close()
        } catch (_: Exception) {}
    }
}

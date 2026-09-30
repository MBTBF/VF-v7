package com.example.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.example.model.AnalysisState
import com.example.model.DetectionResult
import kotlin.math.abs

/**
 * Pastille flottante V7 intelligente :
 *
 * 1. DISPARITION IMMÉDIATE AU SCROLL : Dès que l'utilisateur fait défiler l'écran,
 *    le point disparaît instantanément pour éliminer tout décalage et clignotement.
 *
 * 2. RÉVÉLATION APRÈS 1 SECONDE DE STABILISATION : Lorsque l'écran s'immobilise
 *    sur une image ou une vidéo, le moteur observe 1 seconde d'analyse haute fidélité.
 *    C'est uniquement après ce délai que le Point apparaît nettement en 🟢 VERT ou 🔴 ROUGE.
 *
 * 3. TAP INFORMATIF SUR POINT ROUGE : Un tap sur le point rouge déploie une bulle
 *    claire expliquant précisément le motif d'alerte (ex: FaceSwap, VAE, badge IA).
 *
 * 4. EMPLACEMENT LATÉRAL ANTI-GÊNE : Positionné par défaut sur le bord médian droit
 *    (à 40% de hauteur) pour ne JAMAIS empiéter sur le menu 3 points ou la barre de recherche.
 *    Déplaçable au doigt avec aimantation latérale automatique (Edge Snapping).
 */
class FloatingPillOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayRoot: LinearLayout? = null
    private var pillContainer: LinearLayout? = null
    private var statusDot: View? = null
    private var infoBubble: LinearLayout? = null
    private var bubbleTitle: TextView? = null
    private var bubbleDetail: TextView? = null

    private var isExpanded = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoCollapseRunnable: Runnable? = null

    // Couleurs officielles strictement binaires
    private val colorGreen = Color.parseColor("#22C55E")   // 🟢 VERT : Aucun détecteur positif
    private val colorRed = Color.parseColor("#EF4444")     // 🔴 ROUGE : Au moins un détecteur positif

    private var currentResult = DetectionResult.NO_AI_DETECTED
    private var lastAnalysisState: AnalysisState? = null

    private var layoutParams: WindowManager.LayoutParams? = null
    private var isCurrentlyVisible = false

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (overlayRoot != null) return
        if (!Settings.canDrawOverlays(context)) return

        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val screenHeight = metrics.heightPixels

        // Positionnement par défaut : Bord médian droit (à 40% de la hauteur de l'écran)
        // Évite formellement les 3 points du menu Google Search, les barres d'URL et le clavier
        val initialY = (screenHeight * 0.40f).toInt()
        val initialX = (6 * density).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = initialX
            y = initialY
        }
        layoutParams = params

        // Container racine horizontal
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Assure la zone tactile minimale de 48dp x 48dp requise par l'accessibilité Android
            minimumWidth = (48 * density).toInt()
            minimumHeight = (48 * density).toInt()
            // Au démarrage, masqué en attendant la première analyse stabilisée
            visibility = View.GONE
            alpha = 0f
        }
        overlayRoot = root

        // Bulle informative détaillée (déployée au tap, placée avant ou après selon le côté)
        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (12 * density).toInt(),
                (8 * density).toInt(),
                (12 * density).toInt(),
                (8 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * density
                setColor(Color.argb(240, 15, 23, 42)) // #0F172A quasi-opaque
                setStroke((1.2f * density).toInt(), Color.argb(120, 148, 163, 184))
            }
            visibility = View.GONE
            elevation = 14 * density
        }

        val titleView = TextView(context).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            text = "Verdict"
        }
        bubbleTitle = titleView
        bubble.addView(titleView)

        val detailView = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, (2 * density).toInt(), 0, 0)
            maxWidth = (240 * density).toInt()
            text = ""
        }
        bubbleDetail = detailView
        bubble.addView(detailView)

        infoBubble = bubble

        // Pastille interactive (Dot container)
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(
                (6 * density).toInt(),
                (6 * density).toInt(),
                (6 * density).toInt(),
                (6 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * density
                setColor(Color.argb(220, 15, 23, 42))
                setStroke((1.5f * density).toInt(), Color.argb(110, 148, 163, 184))
            }
            elevation = 12 * density
        }
        pillContainer = pill

        // Glowing colored status dot (14dp)
        val dotSize = (14 * density).toInt()
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize)
            background = createDotDrawable(colorGreen)
        }
        statusDot = dot
        pill.addView(dot)

        // Ajout à la racine dans l'ordre : Bulle puis Pastille (pour bord droit)
        root.addView(bubble)
        val bubbleLp = bubble.layoutParams as LinearLayout.LayoutParams
        bubbleLp.marginEnd = (8 * density).toInt()
        bubble.layoutParams = bubbleLp

        root.addView(pill)

        // Gestion du Drag & Drop et du Tap avec Aimantation latérale (Edge Snapping)
        setupTouchHandling(root, params)

        try {
            windowManager.addView(root, params)
        } catch (_: Exception) {}
    }

    /**
     * Modification 1 : Le point disparaît IMMÉDIATEMENT dès que l'écran se met à défiler.
     */
    fun onScrollStarted() {
        mainHandler.post {
            val root = overlayRoot ?: return@post
            if (!isCurrentlyVisible && root.visibility == View.GONE) return@post

            isCurrentlyVisible = false
            collapseInfoBubble()

            // Disparition instantanée fluide (sans latence)
            root.animate()
                .alpha(0f)
                .scaleX(0.7f)
                .scaleY(0.7f)
                .setDuration(120)
                .withEndAction {
                    root.visibility = View.GONE
                }
                .start()
        }
    }

    /**
     * Modification 1 (suite) : Le point réapparaît avec netteté après 1 seconde d'analyse stabilisée.
     */
    fun onAnalysisReady(state: AnalysisState) {
        mainHandler.post {
            val root = overlayRoot ?: return@post
            lastAnalysisState = state
            currentResult = state.result

            val targetColor = when (state.result) {
                DetectionResult.NO_AI_DETECTED -> colorGreen
                DetectionResult.AI_DETECTED -> colorRed
            }

            statusDot?.background = createDotDrawable(targetColor)
            pillContainer?.background = GradientDrawable().apply {
                val density = context.resources.displayMetrics.density
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * density
                setColor(Color.argb(225, 15, 23, 42))
                // Bordure teintée selon le verdict pour une visibilité accrue
                val strokeColor = if (state.result == DetectionResult.AI_DETECTED) {
                    Color.argb(160, 239, 68, 68)
                } else {
                    Color.argb(120, 34, 197, 94)
                }
                setStroke((1.5f * density).toInt(), strokeColor)
            }

            // Réapparition nette avec animation pop
            root.visibility = View.VISIBLE
            root.animate().cancel()
            root.alpha = 0f
            root.scaleX = 0.8f
            root.scaleY = 0.8f
            root.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(1.2f))
                .withEndAction {
                    isCurrentlyVisible = true
                    pulseDot()
                }
                .start()
        }
    }

    /**
     * Rétro-compatibilité pour mises à jour d'état directes
     */
    fun updateState(state: AnalysisState) {
        onAnalysisReady(state)
    }

    /**
     * Modification 2 : Un tap sur le Point (en particulier ROUGE) déploie
     * une brève bulle explicative indiquant la détection précise.
     */
    private fun toggleInfoBubble() {
        if (isExpanded) {
            collapseInfoBubble()
            return
        }

        val bubble = infoBubble ?: return
        val state = lastAnalysisState

        if (currentResult == DetectionResult.AI_DETECTED) {
            bubbleTitle?.apply {
                text = "🔴 ALERTE MANIPULATION IA"
                setTextColor(Color.parseColor("#FCA5A5"))
            }
            val trigger = state?.positiveDetectors?.firstOrNull()
            val reason = if (trigger != null) {
                "${trigger.name} : ${trigger.details}"
            } else {
                state?.details ?: "Intervention synthétique décelée sur le média."
            }
            bubbleDetail?.text = reason
        } else {
            bubbleTitle?.apply {
                text = "🟢 CONTENU AUTHENTIQUE"
                setTextColor(Color.parseColor("#86EFAC"))
            }
            bubbleDetail?.text = "Analyse stabilisée (1s) : aucune signature IA décelée (CFA physique, biométrie et optique réelles)."
        }

        bubble.visibility = View.VISIBLE
        bubble.alpha = 0f
        bubble.scaleX = 0.85f
        bubble.scaleY = 0.85f
        bubble.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()

        isExpanded = true

        // Fermeture automatique au bout de 4 secondes
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { collapseInfoBubble() }
        autoCollapseRunnable = runnable
        mainHandler.postDelayed(runnable, 4000)
    }

    private fun collapseInfoBubble() {
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        autoCollapseRunnable = null
        if (!isExpanded) return

        infoBubble?.animate()
            ?.alpha(0f)
            ?.scaleX(0.85f)
            ?.scaleY(0.85f)
            ?.setDuration(140)
            ?.withEndAction {
                infoBubble?.visibility = View.GONE
                isExpanded = false
            }
            ?.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchHandling(root: LinearLayout, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var hasMoved = false

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        hasMoved = true
                        params.x = initialX - dx
                        params.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!hasMoved) {
                        // Tap de l'utilisateur
                        toggleInfoBubble()
                    } else {
                        // Modification 3 : Aimantation latérale fluide (Edge Snapping)
                        snapToClosestEdge(root, params, event.rawX)
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Modification 3 : Aimante doucement la pastille vers le bord gauche ou droit le plus proche,
     * évitant d'obstruer le contenu central et les formulaires.
     */
    private fun snapToClosestEdge(
        root: LinearLayout,
        params: WindowManager.LayoutParams,
        rawX: Float
    ) {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val screenWidth = metrics.widthPixels
        val margin = (6 * density).toInt()

        // S'aimante à droite ou à gauche selon le côté le plus proche
        val targetX = margin
        val isRightSide = rawX >= (screenWidth / 2)

        params.gravity = if (isRightSide) {
            Gravity.TOP or Gravity.END
        } else {
            Gravity.TOP or Gravity.START
        }

        // Réorganisation de l'ordre de la bulle d'info pour qu'elle s'ouvre vers l'intérieur de l'écran
        if (isRightSide) {
            if (root.indexOfChild(infoBubble) > root.indexOfChild(pillContainer)) {
                root.removeView(infoBubble)
                root.addView(infoBubble, 0)
                (infoBubble?.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    marginEnd = (8 * density).toInt()
                    marginStart = 0
                }
            }
        } else {
            if (root.indexOfChild(infoBubble) < root.indexOfChild(pillContainer)) {
                root.removeView(infoBubble)
                root.addView(infoBubble)
                (infoBubble?.layoutParams as? LinearLayout.LayoutParams)?.apply {
                    marginStart = (8 * density).toInt()
                    marginEnd = 0
                }
            }
        }

        val startX = params.x
        val animator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 180
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                params.x = va.animatedValue as Int
                try {
                    windowManager.updateViewLayout(root, params)
                } catch (_: Exception) {}
            }
        }
        animator.start()
    }

    private fun pulseDot() {
        val dot = statusDot ?: return
        val animator = ValueAnimator.ofFloat(1.0f, 1.25f, 1.0f).apply {
            duration = 320
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                val scale = va.animatedValue as Float
                dot.scaleX = scale
                dot.scaleY = scale
            }
        }
        animator.start()
    }

    private fun createDotDrawable(color: Int): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((1.5f * density).toInt(), Color.WHITE)
        }
    }

    fun hide() {
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        overlayRoot?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            overlayRoot = null
            statusDot = null
            pillContainer = null
            infoBubble = null
            bubbleTitle = null
            bubbleDetail = null
        }
    }
}

package com.example.state

import com.example.model.AnalysisState
import com.example.model.DetectionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ServiceStateHolder {

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _analysisState = MutableStateFlow(
        AnalysisState(
            result = DetectionResult.NO_AI_DETECTED,
            details = "Service en veille"
        )
    )
    val analysisState: StateFlow<AnalysisState> = _analysisState.asStateFlow()

    private val _totalFramesAnalyzed = MutableStateFlow(0L)
    val totalFramesAnalyzed: StateFlow<Long> = _totalFramesAnalyzed.asStateFlow()

    private val _lastLatencyMs = MutableStateFlow(0L)
    val lastLatencyMs: StateFlow<Long> = _lastLatencyMs.asStateFlow()

    private val _isScrolling = MutableStateFlow(false)
    val isScrolling: StateFlow<Boolean> = _isScrolling.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _isPointVisible = MutableStateFlow(false)
    val isPointVisible: StateFlow<Boolean> = _isPointVisible.asStateFlow()

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
        if (!running) {
            _analysisState.value = AnalysisState(
                result = DetectionResult.NO_AI_DETECTED,
                details = "Service arrêté"
            )
            _isScrolling.value = false
            _isAnalyzing.value = false
            _isPointVisible.value = false
        }
    }

    fun setScrolling(scrolling: Boolean) {
        _isScrolling.value = scrolling
        if (scrolling) {
            _isPointVisible.value = false
            _isAnalyzing.value = false
        }
    }

    fun setAnalyzing(analyzing: Boolean) {
        _isAnalyzing.value = analyzing
    }

    fun setPointVisible(visible: Boolean) {
        _isPointVisible.value = visible
    }

    fun updateAnalysis(state: AnalysisState) {
        _analysisState.value = state
        _lastLatencyMs.value = state.latencyMs
        _totalFramesAnalyzed.value += 1
        _isAnalyzing.value = false
        _isPointVisible.value = true
    }

    fun resetStats() {
        _totalFramesAnalyzed.value = 0L
        _lastLatencyMs.value = 0L
    }
}

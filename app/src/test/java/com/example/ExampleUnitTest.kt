package com.example

import com.example.model.DetectionResult
import com.example.model.DetectorReport
import com.example.model.DetectorStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun `test all detectors negative results in NO_AI_DETECTED (GREEN)`() {
        val reports = listOf(
            DetectorReport("mod1", "Module 1", DetectorStatus.NEGATIVE, "Normal"),
            DetectorReport("mod2", "Module 2", DetectorStatus.NEGATIVE, "Normal"),
            DetectorReport("mod3", "Module 3", DetectorStatus.NOT_AVAILABLE, "Sandbox restriction")
        )
        val anyPositive = reports.any { it.status == DetectorStatus.POSITIVE }
        val result = if (anyPositive) DetectionResult.AI_DETECTED else DetectionResult.NO_AI_DETECTED

        assertEquals(DetectionResult.NO_AI_DETECTED, result)
    }

    @Test
    fun `test single positive detector out of multiple triggers AI_DETECTED (RED)`() {
        val reports = listOf(
            DetectorReport("sub1", "SubImage 1 - Real", DetectorStatus.NEGATIVE, "Natural"),
            DetectorReport("sub2", "SubImage 2 - AI", DetectorStatus.POSITIVE, "Diffusion signature"),
            DetectorReport("ocr", "OCR Text", DetectorStatus.NEGATIVE, "No prompt keywords")
        )
        val anyPositive = reports.any { it.status == DetectorStatus.POSITIVE }
        val result = if (anyPositive) DetectionResult.AI_DETECTED else DetectionResult.NO_AI_DETECTED

        assertEquals(DetectionResult.AI_DETECTED, result)
    }
}

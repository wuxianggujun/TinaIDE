package com.wuxianggujun.tinaide.core.editorview

import androidx.compose.ui.graphics.Path

internal class DiagnosticWavePathBuilder {

    private val reusablePath = Path()

    fun build(
        startX: Float,
        endX: Float,
        baseY: Float,
        waveLength: Float,
        waveAmplitude: Float
    ): Path {
        reusablePath.reset()
        if (endX <= startX) return reusablePath
        val safeWaveLength = waveLength.coerceAtLeast(2f)
        reusablePath.moveTo(startX, baseY)
        var x = startX
        while (x < endX) {
            val fullEnd = x + safeWaveLength
            if (fullEnd <= endX) {
                val halfLen = safeWaveLength * 0.5f
                reusablePath.quadraticTo(x + safeWaveLength * 0.25f, baseY + waveAmplitude, x + halfLen, baseY)
                reusablePath.quadraticTo(x + safeWaveLength * 0.75f, baseY - waveAmplitude, fullEnd, baseY)
                x = fullEnd
            } else {
                val remaining = endX - x
                val ratio = remaining / safeWaveLength
                val amp = waveAmplitude * ratio
                val halfRemaining = remaining * 0.5f
                reusablePath.quadraticTo(x + remaining * 0.25f, baseY + amp, x + halfRemaining, baseY)
                reusablePath.quadraticTo(x + remaining * 0.75f, baseY - amp, endX, baseY)
                x = endX
            }
        }
        return reusablePath
    }
}

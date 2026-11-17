package com.shield.kawaiinavidrome

import android.media.audiofx.Visualizer
import kotlin.math.hypot

class AudioEnergyBridge(
    audioSessionId: Int,
    private val onEnergyUpdate: (bass: Float, treble: Float) -> Unit
) {
    private var visualizer: Visualizer? = null
    private var smoothedBass = 0f
    private var smoothedTreble = 0f

    init {
        if (audioSessionId != 0) {
            try {
                visualizer = Visualizer(audioSessionId).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[1]
                    setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            v: Visualizer?, waveform: ByteArray?, samplingRate: Int
                        ) {}

                        override fun onFftDataCapture(
                            v: Visualizer?, fft: ByteArray?, samplingRate: Int
                        ) {
                            fft?.let { processFft(it) }
                        }
                    }, Visualizer.getMaxCaptureRate() / 2, false, true)
                    enabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun processFft(fft: ByteArray) {
        var bassSum = 0f
        for (i in 2 until 14 step 2) {
            bassSum += hypot(fft[i].toFloat(), fft[i + 1].toFloat())
        }
        val rawBass = (bassSum / (6f * 128f)).coerceIn(0f, 1f)

        var trebleSum = 0f
        for (i in 30 until 90 step 2) {
            trebleSum += hypot(fft[i].toFloat(), fft[i + 1].toFloat())
        }
        val rawTreble = (trebleSum / (30f * 64f)).coerceIn(0f, 1f)

        smoothedBass = smoothedBass * 0.75f + rawBass * 0.25f
        smoothedTreble = smoothedTreble * 0.70f + rawTreble * 0.30f

        onEnergyUpdate(smoothedBass, smoothedTreble)
    }

    fun release() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {}
        visualizer = null
    }
}

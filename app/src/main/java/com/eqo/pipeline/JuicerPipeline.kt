package com.eqo.pipeline

import android.content.Context
import com.eqo.ai.JuicerAIEngine
import com.eqo.thermal.JuicerThermalGovernor
import java.nio.ByteBuffer

/**
 * Component-1 pipeline wired to the Component-2 AI engine and Thermal Governor.
 *
 * This is the designed integration seam (design review P16): behavior is injected
 * by subclassing the placeholders, never by editing the decode loop.
 *
 * The engine call is synchronous, satisfying the Component 1 listener contract
 * (the frame-thread path is GL draw + readback + ~1 ms math; the classifier runs
 * on its own thread at scene cadence).
 */
open class JuicerPipeline(
    context: Context,
    /** Owned by this pipeline; closed on [release]. */
    val aiEngine: JuicerAIEngine = JuicerAIEngine(context),
    val thermalGovernor: JuicerThermalGovernor = JuicerThermalGovernor(context),
) : ZeroCopyVideoPipeline(context) {

    init {
        thermalGovernor.onMitigationChanged = { m ->
            aiEngine.activeClassifyCadence = m.classifyCadence
            thermalPaceMs = m.pacePollMs
        }
        val initial = thermalGovernor.mitigation.value
        aiEngine.activeClassifyCadence = initial.classifyCadence
        thermalPaceMs = initial.pacePollMs
    }

    override fun processFrameWithAI(rgb: ByteBuffer, timestampNanos: Long) {
        lastRoiMap = aiEngine.generateROIMap(rgb)
    }

    override fun release() {
        super.release()
        aiEngine.close()
        thermalGovernor.close()
    }
}

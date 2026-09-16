package com.eqo.thermal

/**
 * Pure policy for thermal mitigations (Phase 1).
 *
 * Maps Android PowerManager thermal status (0..6) and optional battery temperature
 * into actionable pipeline parameters:
 *  - [classifyCadence]: how frequently to run the LiteRT MobileNetV3 classifier
 *    (0 means disabled / paused, relying 100% on <1ms RoiMath luma variance).
 *  - [pacePollMs]: extra delay injected into the decode loop per frame to allow
 *    silicon cooling without tearing down the codec.
 *  - [bitrateMultiplier]: scale factor applied to the target bitrate (e.g. 0.85f
 *    under severe thermal load to reduce encoder workload).
 */
data class ThermalMitigation(
    val classifyCadence: Int,
    val pacePollMs: Long,
    val bitrateMultiplier: Float,
    val statusName: String,
)

object ThermalMitigationPolicy {
    // Android PowerManager constants for JVM unit test compatibility
    const val STATUS_NONE = 0
    const val STATUS_LIGHT = 1
    const val STATUS_MODERATE = 2
    const val STATUS_SEVERE = 3
    const val STATUS_CRITICAL = 4
    const val STATUS_EMERGENCY = 5
    const val STATUS_SHUTDOWN = 6

    const val DEFAULT_CADENCE = 15

    fun evaluate(thermalStatus: Int, batteryTempCelsius: Float? = null): ThermalMitigation {
        // If battery temp is known, it can elevate the effective status if the platform listener lagged
        val effectiveStatus = if (batteryTempCelsius != null) {
            when {
                batteryTempCelsius >= 45.0f -> maxOf(thermalStatus, STATUS_CRITICAL)
                batteryTempCelsius >= 42.0f -> maxOf(thermalStatus, STATUS_SEVERE)
                batteryTempCelsius >= 40.0f -> maxOf(thermalStatus, STATUS_MODERATE)
                batteryTempCelsius >= 38.0f -> maxOf(thermalStatus, STATUS_LIGHT)
                else -> thermalStatus
            }
        } else {
            thermalStatus
        }

        return when {
            effectiveStatus <= STATUS_NONE -> ThermalMitigation(
                classifyCadence = DEFAULT_CADENCE,
                pacePollMs = 0L,
                bitrateMultiplier = 1.0f,
                statusName = "NORMAL",
            )
            effectiveStatus == STATUS_LIGHT -> ThermalMitigation(
                classifyCadence = 30,
                pacePollMs = 2L,
                bitrateMultiplier = 1.0f,
                statusName = "LIGHT",
            )
            effectiveStatus == STATUS_MODERATE -> ThermalMitigation(
                classifyCadence = 60,
                pacePollMs = 5L,
                bitrateMultiplier = 0.95f,
                statusName = "MODERATE",
            )
            effectiveStatus == STATUS_SEVERE -> ThermalMitigation(
                classifyCadence = 0, // LiteRT disabled; purely RoiMath
                pacePollMs = 10L,
                bitrateMultiplier = 0.85f,
                statusName = "SEVERE",
            )
            else -> ThermalMitigation( // CRITICAL, EMERGENCY, SHUTDOWN
                classifyCadence = 0,
                pacePollMs = 20L,
                bitrateMultiplier = 0.75f,
                statusName = "CRITICAL",
            )
        }
    }
}

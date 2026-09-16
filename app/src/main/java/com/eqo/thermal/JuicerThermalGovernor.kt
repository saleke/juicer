package com.eqo.thermal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ## eqo Thermal Governor (Phase 1)
 *
 * Continuously tracks device thermal status via [PowerManager.OnThermalStatusChangedListener]
 * (API 29+) and sticky [Intent.ACTION_BATTERY_CHANGED] temperature broadcasts.
 *
 * Exposes real-time [ThermalMitigation] state to prevent OS-level hardware throttling
 * during intensive transcodes by dynamically adjusting AI classification cadence, decode
 * loop pacing, and bitrate ceilings.
 */
class JuicerThermalGovernor(context: Context) : Closeable {

    companion object {
        private const val TAG = "eqo.Thermal"
    }

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val closed = AtomicBoolean(false)

    private val _mitigation = MutableStateFlow(
        ThermalMitigationPolicy.evaluate(
            thermalStatus = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE,
            batteryTempCelsius = getBatteryTemperature(),
        ),
    )
    val mitigation: StateFlow<ThermalMitigation> = _mitigation.asStateFlow()

    /** Optional listener callback for direct synchronous hookup. */
    var onMitigationChanged: ((ThermalMitigation) -> Unit)? = null

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        if (closed.get()) return@OnThermalStatusChangedListener
        updateThermalState(status)
    }

    init {
        try {
            powerManager?.addThermalStatusListener(appContext.mainExecutor, thermalListener)
            Log.i(TAG, "Thermal listener registered; initial status=${_mitigation.value.statusName}")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not register thermal status listener: ${t.message}")
        }
    }

    /**
     * Queries current battery temperature in degrees Celsius from the sticky battery broadcast.
     * Returns null if unavailable.
     */
    fun getBatteryTemperature(): Float? {
        val intent = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null

        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tempTenths != Int.MIN_VALUE) tempTenths / 10.0f else null
    }

    /** Re-evaluates current thermal state and updates [_mitigation]. */
    fun refresh() {
        if (closed.get()) return
        val status = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        updateThermalState(status)
    }

    private fun updateThermalState(status: Int) {
        val batteryTemp = getBatteryTemperature()
        val newMitigation = ThermalMitigationPolicy.evaluate(status, batteryTemp)
        _mitigation.value = newMitigation
        Log.i(
            TAG,
            "Thermal update: status=$status (${newMitigation.statusName}), " +
                "temp=${batteryTemp?.let { "%.1f°C".format(it) } ?: "n/a"}, " +
                "cadence=${newMitigation.classifyCadence}, pace=${newMitigation.pacePollMs}ms, " +
                "bitrateScale=%.2f".format(newMitigation.bitrateMultiplier),
        )
        onMitigationChanged?.invoke(newMitigation)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching {
            powerManager?.removeThermalStatusListener(thermalListener)
        }
    }
}

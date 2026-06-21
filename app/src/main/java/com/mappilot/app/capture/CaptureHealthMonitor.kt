package com.mappilot.app.capture

import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.core.model.Pose
import com.mappilot.core.model.Quaternion
import com.mappilot.core.model.Vector3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sqrt

/** Live capture-behaviour quality, derived from VIO pose deltas. */
data class CaptureHealthState(
    val speedMps: Double = 0.0,
    val rotationDegPerS: Double = 0.0,
    /** Rotation rate over translation speed; high while turning in place. */
    val rotToTransRatio: Double = 0.0,
    val rotateInPlace: Boolean = false,
    val tooFast: Boolean = false,
    /** Short human warning for the HUD, or null when capture behaviour is good. */
    val warning: String? = null,
)

/**
 * Pure capture kinematics + scoring, unit tested independently of the bus.
 * Preventing bad sessions (rotate-in-place, moving too fast for triangulation)
 * beats filtering them after the fact, so this drives a live HUD warning.
 */
object CaptureKinematics {
    const val ROTATE_IN_PLACE_DEG_PER_S = 25.0
    const val ROTATE_IN_PLACE_MAX_SPEED = 0.35 // m/s
    const val TOO_FAST_MPS = 5.0 // ~18 km/h; above this VIO triangulation degrades

    private const val NS_PER_S = 1_000_000_000.0

    fun speedMps(a: Vector3, b: Vector3, dtNs: Long): Double {
        if (dtNs <= 0) return 0.0
        val dx = b.x - a.x; val dy = b.y - a.y; val dz = b.z - a.z
        return sqrt(dx * dx + dy * dy + dz * dz) / (dtNs / NS_PER_S)
    }

    /** Geodesic angle (deg) between two unit quaternions. */
    fun angleDeg(a: Quaternion, b: Quaternion): Double {
        val dot = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w).coerceIn(0.0, 1.0)
        return Math.toDegrees(2.0 * acos(min(1.0, dot)))
    }

    fun rotationDegPerS(a: Quaternion, b: Quaternion, dtNs: Long): Double {
        if (dtNs <= 0) return 0.0
        return angleDeg(a, b) / (dtNs / NS_PER_S)
    }

    /** Score a smoothed (speed, rotation-rate) pair into a HUD state with warnings. */
    fun evaluate(speedMps: Double, rotationDegPerS: Double): CaptureHealthState {
        val ratio = rotationDegPerS / (speedMps + 1e-3)
        val rotateInPlace = rotationDegPerS > ROTATE_IN_PLACE_DEG_PER_S && speedMps < ROTATE_IN_PLACE_MAX_SPEED
        val tooFast = speedMps > TOO_FAST_MPS
        val warning = when {
            rotateInPlace -> "Rotating in place: translate for parallax"
            tooFast -> "Moving fast: 3D will be sparse (rely on VPS)"
            else -> null
        }
        return CaptureHealthState(speedMps, rotationDegPerS, ratio, rotateInPlace, tooFast, warning)
    }
}

/**
 * Subscribes to VIO poses and publishes smoothed [CaptureHealthState] for the HUD.
 * Exponentially smooths speed and rotation rate so a single noisy frame does not
 * flip the warning.
 */
@Singleton
class CaptureHealthMonitor @Inject constructor(eventBus: EventBus) {
    private val _state = MutableStateFlow(CaptureHealthState())
    val state: StateFlow<CaptureHealthState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var last: Pose? = null
    private var emaSpeed = 0.0
    private var emaRot = 0.0

    init {
        eventBus.events
            .onEach { if (it is MapPilotEvent.PoseUpdate) onPose(it.pose) }
            .launchIn(scope)
    }

    private fun onPose(p: Pose) {
        val prev = last
        last = p
        if (prev == null) return
        val dtNs = p.timestampNs - prev.timestampNs
        if (dtNs <= 0 || dtNs > MAX_DT_NS) return // gap / new session: skip, don't spike
        val s = CaptureKinematics.speedMps(prev.position, p.position, dtNs)
        val r = CaptureKinematics.rotationDegPerS(prev.orientation, p.orientation, dtNs)
        if (!s.isFinite() || !r.isFinite()) return
        emaSpeed = EMA * emaSpeed + (1 - EMA) * s
        emaRot = EMA * emaRot + (1 - EMA) * r
        _state.value = CaptureKinematics.evaluate(emaSpeed, emaRot)
    }

    private companion object {
        const val EMA = 0.8
        const val MAX_DT_NS = 500_000_000L // 0.5 s
    }
}

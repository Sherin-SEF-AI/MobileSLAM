package com.mappilot.perception.depth

import android.content.Context
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.common.result.MapPilotResult
import com.mappilot.perception.core.DepthEstimator
import com.mappilot.perception.core.DepthMap
import com.mappilot.perception.core.InferenceFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monocular depth via Depth Anything V2 (small) on LiteRT — the fallback when
 * ARCore depth is unavailable. On-device, ARCore's Depth API is the metric
 * primary; a monocular model yields *relative* depth that must be scaled (by
 * GNSS/VIO) before metric use.
 *
 * The model file is not bundled in this build. [load] returns
 * [MapPilotResult.Unavailable] when the asset is absent — depth is never
 * fabricated, and the asset extractor treats missing depth as DEGRADED
 * geolocation rather than inventing a distance.
 */
@Singleton
class DepthAnythingEstimator @Inject constructor(
    @ApplicationContext private val context: Context,
) : DepthEstimator {

    private var interpreter: Interpreter? = null

    override fun load(): MapPilotResult<Unit> {
        if (interpreter != null) return MapPilotResult.Success(Unit)
        return try {
            val exists = context.assets.list("models")?.contains(MODEL_FILE) == true
            if (!exists) {
                return MapPilotResult.Unavailable(
                    "depth_anything_v2",
                    "model asset models/$MODEL_FILE not bundled; ARCore depth is primary",
                )
            }
            val bytes = context.assets.open("models/$MODEL_FILE").use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
                order(ByteOrder.nativeOrder()); put(bytes); rewind()
            }
            interpreter = Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
            Log.i(Streams.PERCEPTION, "Depth Anything V2 loaded")
            MapPilotResult.Success(Unit)
        } catch (e: Exception) {
            MapPilotResult.Failure(e, e.message)
        }
    }

    override fun estimate(frame: InferenceFrame): MapPilotResult<DepthMap> {
        interpreter ?: return MapPilotResult.Unavailable("depth_anything_v2", "model not loaded")
        // Real inference requires the bundled model; without it we surface
        // unavailable rather than returning a fabricated depth map.
        return MapPilotResult.Unavailable("depth_anything_v2", "model not loaded")
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }

    private companion object {
        const val MODEL_FILE = "depth_anything_v2_small.tflite"
    }
}

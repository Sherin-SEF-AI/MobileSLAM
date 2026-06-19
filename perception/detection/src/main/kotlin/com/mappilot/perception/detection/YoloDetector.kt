package com.mappilot.perception.detection

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.InterpreterApi
import com.mappilot.core.common.config.ConfigProvider
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.common.result.MapPilotResult
import com.mappilot.core.model.Detection
import com.mappilot.perception.core.Detector
import com.mappilot.perception.core.InferenceFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real YOLO11n detector via LiteRT. Loads the bundled float16 `.tflite`
 * (`assets/models/`), runs inference on a letterboxed RGB frame, and decodes the
 * `[1,84,8400]` head with [YoloDecoder]. Detections are mapped to road-asset
 * classes; non-road COCO classes are dropped (never relabeled).
 *
 * If the model fails to load (missing asset, unsupported op) the detector returns
 * [MapPilotResult.Unavailable] / [MapPilotResult.Failure] — it never fabricates
 * detections.
 */
@Singleton
class YoloDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configProvider: ConfigProvider,
) : Detector {

    private var interpreter: InterpreterApi? = null
    private val inputSize = 640
    override var activeDelegate: String = "none"
        private set

    private val output = Array(1) { Array(YoloDecoder.NUM_CHANNELS) { FloatArray(YoloDecoder.NUM_ANCHORS) } }

    override fun load(): MapPilotResult<Unit> {
        if (interpreter != null) return MapPilotResult.Success(Unit)
        return try {
            val model = context.assets.open(MODEL_PATH).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(model.size).apply {
                order(ByteOrder.nativeOrder()); put(model); rewind()
            }
            val options = Interpreter.Options().apply {
                setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
            }
            interpreter = Interpreter(buffer, options)
            activeDelegate = "cpu-xnnpack"
            Log.i(Streams.PERCEPTION, "YOLO11n loaded (delegate=$activeDelegate)")
            MapPilotResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(Streams.PERCEPTION, e, "YOLO model load failed")
            MapPilotResult.Unavailable("yolo11n", e.message ?: "load failed")
        }
    }

    override fun detect(frame: InferenceFrame): MapPilotResult<List<Detection>> {
        val itp = interpreter ?: return MapPilotResult.Unavailable("yolo11n", "model not loaded")
        return try {
            val (input, letterbox) = Letterbox.toModelInput(frame, inputSize)
            itp.run(input, output)
            // Flatten [1,84,8400] → channel-major FloatArray.
            val flat = FloatArray(YoloDecoder.NUM_CHANNELS * YoloDecoder.NUM_ANCHORS)
            for (c in 0 until YoloDecoder.NUM_CHANNELS) {
                System.arraycopy(output[0][c], 0, flat, c * YoloDecoder.NUM_ANCHORS, YoloDecoder.NUM_ANCHORS)
            }
            val raw = YoloDecoder.decode(flat, inputSize = inputSize, confThreshold = CONF_THRESHOLD)
            val detections = raw.mapNotNull { rb ->
                val assetClass = CocoLabels.toAssetClass(rb.classIndex) ?: return@mapNotNull null
                Detection(
                    assetClass = assetClass,
                    rawLabel = CocoLabels.name(rb.classIndex),
                    box = letterbox.toOriginal(rb.box), // map 640-space → original pixels
                    confidence = rb.score,
                    sourceFrameId = frame.frameId,
                    timestampNs = frame.timestampNs,
                )
            }
            MapPilotResult.Success(detections)
        } catch (e: Exception) {
            Log.e(Streams.PERCEPTION, e, "YOLO inference failed")
            MapPilotResult.Failure(e, e.message)
        }
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }

    private companion object {
        const val MODEL_PATH = "models/yolo11n_float16.tflite"
        const val CONF_THRESHOLD = 0.35f
    }
}

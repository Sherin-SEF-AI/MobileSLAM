package com.mappilot.perception.detection

import android.content.Context
import android.os.Build
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.InterpreterApi
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
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
    private var delegate: Delegate? = null
    private val inputSize = 640
    override var activeDelegate: String = "none"
        private set

    private val output = Array(1) { Array(YoloDecoder.NUM_CHANNELS) { FloatArray(YoloDecoder.NUM_ANCHORS) } }

    // Reused direct input buffer — avoids a ~5 MB allocateDirect per inference.
    private var inputBuffer: java.nio.ByteBuffer? = null

    override fun load(): MapPilotResult<Unit> {
        if (interpreter != null) return MapPilotResult.Success(Unit)
        return try {
            val model = context.assets.open(MODEL_PATH).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(model.size).apply {
                order(ByteOrder.nativeOrder()); put(model); rewind()
            }
            val pref = configProvider.current().inferenceDelegate
            val gpuSupported = runCatching { CompatibilityList().isDelegateSupportedOnThisDevice }.getOrDefault(false)
            val choice = DelegateSelector.choose(pref, gpuSupported, Build.VERSION.SDK_INT)
            val (itp, tag) = buildInterpreter(buffer, choice)
            interpreter = itp
            activeDelegate = tag
            Log.i(Streams.PERCEPTION, "YOLO11n loaded (pref=$pref, delegate=$activeDelegate, gpuSupported=$gpuSupported)")
            MapPilotResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(Streams.PERCEPTION, e, "YOLO model load failed")
            MapPilotResult.Unavailable("yolo11n", e.message ?: "load failed")
        }
    }

    /**
     * Build the interpreter for [choice], falling back to CPU/XNNPACK if a
     * delegate cannot be created or the model can't run on it (e.g. unsupported
     * op). Returns the interpreter plus the delegate tag actually used.
     * MUST run on the inference thread — GPU/NNAPI delegates are thread-affine.
     */
    private fun buildInterpreter(buffer: ByteBuffer, choice: DelegateChoice): Pair<InterpreterApi, String> {
        if (choice != DelegateChoice.CPU) {
            try {
                val options = Interpreter.Options().apply { setNumThreads(cpuThreads()) }
                val tag = when (choice) {
                    DelegateChoice.GPU -> {
                        GpuDelegate(CompatibilityList().bestOptionsForThisDevice).also {
                            delegate = it; options.addDelegate(it)
                        }
                        "gpu"
                    }
                    DelegateChoice.NNAPI -> {
                        NnApiDelegate().also { delegate = it; options.addDelegate(it) }
                        "nnapi"
                    }
                    DelegateChoice.CPU -> "cpu-xnnpack"
                }
                return Interpreter(buffer, options) to tag
            } catch (e: Throwable) {
                Log.w(Streams.PERCEPTION, "Delegate $choice unavailable, falling back to CPU: ${e.message}")
                releaseDelegate()
                buffer.rewind()
            }
        }
        val cpuOptions = Interpreter.Options().apply { setNumThreads(cpuThreads()) }
        return Interpreter(buffer, cpuOptions) to "cpu-xnnpack"
    }

    private fun cpuThreads(): Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4)

    private fun releaseDelegate() {
        runCatching { (delegate as? GpuDelegate)?.close() }
        runCatching { (delegate as? NnApiDelegate)?.close() }
        delegate = null
    }

    override fun detect(frame: InferenceFrame): MapPilotResult<List<Detection>> {
        val itp = interpreter ?: return MapPilotResult.Unavailable("yolo11n", "model not loaded")
        return try {
            val (input, letterbox) = Letterbox.toModelInput(frame, inputSize, inputBuffer)
            inputBuffer = input
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
        releaseDelegate()
    }

    private companion object {
        const val MODEL_PATH = "models/yolo11n_float16.tflite"
        const val CONF_THRESHOLD = 0.35f
    }
}

package com.mappilot.perception.detection

import android.content.Context
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.common.result.MapPilotResult
import com.mappilot.core.model.BoundingBox
import com.mappilot.perception.core.Embedder
import com.mappilot.perception.core.InferenceFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.InterpreterApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Real image embedder via LiteRT: a bundled ImageNet-pretrained MobileNetV3-Small
 * feature extractor (`assets/models/image_embedder.tflite`) produces a 576-d
 * global-average-pooled vector per detection crop. Vectors are L2-normalized so
 * cosine == dot, feeding visual-similarity search over assets.
 *
 * If the model isn't bundled, [load] returns [MapPilotResult.Unavailable] and
 * [embed] returns null — no fabricated vectors.
 */
@Singleton
class ImageEmbedder @Inject constructor(
    @ApplicationContext private val context: Context,
) : Embedder {

    private var interpreter: InterpreterApi? = null
    override var dim: Int = 0
        private set
    override val available: Boolean get() = interpreter != null

    private var input: ByteBuffer? = null
    private var output: Array<FloatArray>? = null

    override fun load(): MapPilotResult<Unit> {
        if (interpreter != null) return MapPilotResult.Success(Unit)
        return try {
            val model = context.assets.open(MODEL_PATH).use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(model.size).apply {
                order(ByteOrder.nativeOrder()); put(model); rewind()
            }
            val itp = Interpreter(buffer, Interpreter.Options().apply { setNumThreads(2) })
            dim = itp.getOutputTensor(0).shape().last()
            output = Array(1) { FloatArray(dim) }
            input = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
            interpreter = itp
            Log.i(Streams.PERCEPTION, "Image embedder loaded (dim=$dim)")
            MapPilotResult.Success(Unit)
        } catch (e: Exception) {
            Log.w(Streams.PERCEPTION, "Image embedder unavailable: ${e.message}")
            MapPilotResult.Unavailable("image_embedder", e.message ?: "load failed")
        }
    }

    override fun embed(frame: InferenceFrame, box: BoundingBox): FloatArray? {
        val itp = interpreter ?: return null
        val buf = input ?: return null
        val out = output ?: return null
        if (frame.width <= 0 || frame.height <= 0) return null
        return try {
            EmbedderPreprocess.fillInput(frame.rgb, frame.width, frame.height, box, INPUT_SIZE, buf)
            itp.run(buf, out)
            l2normalize(out[0])
        } catch (e: Exception) {
            Log.w(Streams.PERCEPTION, "embed failed: ${e.message}")
            null
        }
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }

    private fun l2normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += (x * x).toDouble()
        val norm = sqrt(sum).toFloat()
        if (norm <= 0f) return v.copyOf()
        return FloatArray(v.size) { v[it] / norm }
    }

    private companion object {
        const val MODEL_PATH = "models/image_embedder.tflite"
        const val INPUT_SIZE = 224
    }
}

package com.mappilot.app.perception

import com.mappilot.app.capture.SensorHub
import com.mappilot.assets.extraction.AssetTracker
import com.mappilot.assets.extraction.Backprojection
import com.mappilot.core.common.bus.EventBus
import com.mappilot.core.common.config.ConfigProvider
import com.mappilot.core.common.log.Log
import com.mappilot.core.common.log.Streams
import com.mappilot.core.common.result.MapPilotResult
import com.mappilot.core.common.time.TimeSource
import com.mappilot.core.model.Asset
import com.mappilot.core.model.CameraIntrinsics
import com.mappilot.core.model.Detection
import com.mappilot.core.model.MapPilotEvent
import com.mappilot.core.model.Pose
import com.mappilot.perception.core.FrameScheduler
import com.mappilot.perception.core.InferenceFrame
import com.mappilot.perception.core.Yuv
import com.mappilot.perception.depth.DepthAnythingEstimator
import com.mappilot.perception.detection.ImageEmbedder
import com.mappilot.perception.detection.YoloDetector
import com.mappilot.sensors.camera.CameraImage
import com.mappilot.slam.fusion.GnssVioFusion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/** Live perception status for the HUD. */
data class PerceptionState(
    val active: Boolean = false,
    val delegate: String = "none",
    val unavailableReason: String? = null,
    val framesProcessed: Long = 0,
    val framesDropped: Long = 0,
    val lastDetections: Int = 0,
    val assetCount: Int = 0,
    val embeddingsAvailable: Boolean = false,
)

/**
 * Runs on-device perception at a reduced cadence, fully decoupled from the 30 fps
 * record path (§10): camera analysis frames are throttled by [FrameScheduler] and
 * dropped while inference is in flight, so perception never back-pressures
 * capture or recording.
 *
 * Pipeline: YUV→RGB → YOLO detect → (depth + pose + intrinsics) backproject →
 * dedup track → georeference via the Umeyama transform → emit `/assets`. Assets
 * are only georeferenced once depth is available AND the VIO→ENU alignment
 * exists; otherwise detections are still published but no geo is fabricated.
 */
@Singleton
class PerceptionController @Inject constructor(
    private val detector: YoloDetector,
    private val depth: DepthAnythingEstimator,
    private val embedder: ImageEmbedder,
    private val sensorHub: SensorHub,
    private val fusion: GnssVioFusion,
    private val slamEngine: com.mappilot.slam.core.SlamEngine,
    private val eventBus: EventBus,
    private val timeSource: TimeSource,
    private val configProvider: ConfigProvider,
) {
    private val perceptionExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "perception") }
    private val dispatcher = perceptionExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val scheduler = FrameScheduler(configProvider.current().perceptionHz)
    private val tracker = AssetTracker()

    @Volatile private var latestPose: Pose? = null
    @Volatile private var latestIntrinsics: CameraIntrinsics? = null
    @Volatile private var thermallyPaused: Boolean = false

    // Reused RGB scratch buffer (perception executor is single-threaded).
    private var rgbBuffer: ByteArray? = null

    // Visual-similarity embeddings keyed by tracker asset id; populated when an
    // asset is first seen, snapshotted with the assets at stop for persistence.
    private val assetEmbeddings = java.util.concurrent.ConcurrentHashMap<Long, FloatArray>()

    private val _state = MutableStateFlow(PerceptionState())
    val state: StateFlow<PerceptionState> = _state.asStateFlow()

    fun start() {
        // Load models on the perception thread (GPU/NNAPI delegates are thread-affine,
        // so they must be created on the thread that runs detect) but WITHOUT blocking
        // the caller: a GPU-delegate init can take seconds and the record button is on
        // the main thread. State flips to active when loading finishes; frames queue
        // behind the load on the single-thread executor, so detect() always sees a
        // ready interpreter.
        perceptionExecutor.execute {
            when (val load = detector.load()) {
                is MapPilotResult.Success -> {
                    depth.load() // best-effort; depth-less detections are still published
                    embedder.load() // best-effort; assets persist without embeddings if absent
                    _state.value = PerceptionState(active = true, delegate = detector.activeDelegate, embeddingsAvailable = embedder.available)
                    Log.i(Streams.PERCEPTION, "Perception active @ ${configProvider.current().perceptionHz} Hz (delegate=${detector.activeDelegate})")
                }
                is MapPilotResult.Unavailable -> {
                    _state.value = PerceptionState(active = false, unavailableReason = load.reason)
                    Log.w(Streams.PERCEPTION, "Detector unavailable: ${load.reason}")
                }
                else -> _state.value = PerceptionState(active = false, unavailableReason = "load failed")
            }
        }

        subscribeContext()
        sensorHub.camera.setAnalysisCallback(::onAnalysisFrame)
        Log.i(Streams.PERCEPTION, "Perception starting (loading models off the main thread)")
    }

    private fun subscribeContext() {
        eventBus.events
            .onEach { e ->
                when (e) {
                    is MapPilotEvent.PoseUpdate -> latestPose = e.pose
                    is MapPilotEvent.FrameCaptured -> e.frame.intrinsics?.let { latestIntrinsics = it }
                    else -> Unit
                }
            }
            .launchIn(scope)
    }

    /**
     * Apply a thermal degradation plan: cap perception cadence or pause it.
     * Recording and sync are never affected (they aren't reachable from here).
     */
    fun applyDegradation(plan: com.mappilot.core.common.hardening.DegradationPlan) {
        thermallyPaused = !plan.perceptionEnabled
        if (plan.perceptionEnabled) scheduler.setTargetHz(plan.perceptionHzCap)
        Log.i(Streams.PERCEPTION, "Degradation: ${plan.reason} (paused=$thermallyPaused, hzCap=${plan.perceptionHzCap})")
    }

    /** Camera-thread callback: throttle then hand off to the perception thread. */
    private fun onAnalysisFrame(image: CameraImage) {
        if (thermallyPaused) { sensorHub.camera.onAnalysisComplete(); return }
        if (!scheduler.offer(image.timestampNs)) {
            sensorHub.camera.onAnalysisComplete()
            return
        }
        perceptionExecutor.execute {
            try {
                process(image)
            } catch (t: Throwable) {
                Log.e(Streams.PERCEPTION, t, "perception frame failed")
            } finally {
                scheduler.onComplete()
                sensorHub.camera.onAnalysisComplete()
            }
        }
    }

    private fun process(image: CameraImage) {
        val rgb = Yuv.nv21ToRgb(image.nv21, image.width, image.height, rgbBuffer).also { rgbBuffer = it }
        val frame = InferenceFrame(image.frameId, image.timestampNs, image.width, image.height, rgb)

        val detections = when (val r = detector.detect(frame)) {
            is MapPilotResult.Success -> r.value
            is MapPilotResult.Degraded -> r.value
            else -> emptyList()
        }
        if (detections.isNotEmpty()) {
            eventBus.emit(MapPilotEvent.DetectionBatch(image.timestampNs, detections))
        }

        val assets = georeference(detections, frame)
        if (assets.isNotEmpty()) {
            eventBus.emit(MapPilotEvent.AssetsExtracted(image.timestampNs, assets))
        }

        diagFrames++; diagDetTotal += detections.size
        if (diagFrames % 40 == 0) {
            Log.i(
                Streams.PERCEPTION,
                "perception diag: frames=$diagFrames detTotal=$diagDetTotal depthMiss=$diagDepthMiss noTransform=$diagNoTransform noPose=$diagNoPose noIntr=$diagNoIntr assetsTracked=${tracker.count}",
            )
        }

        _state.value = _state.value.copy(
            framesProcessed = scheduler.accepted,
            framesDropped = scheduler.dropped,
            lastDetections = detections.size,
            assetCount = tracker.count,
        )
    }

    // Diagnostic tallies for "no assets" triage (logged every ~40 processed frames).
    private var diagFrames = 0
    private var diagDetTotal = 0
    private var diagDepthMiss = 0
    private var diagNoTransform = 0
    private var diagNoPose = 0
    private var diagNoIntr = 0

    /** Backproject + dedup + georeference. Skips assets lacking depth or alignment. */
    private fun georeference(detections: List<Detection>, frame: InferenceFrame): List<Asset> {
        if (detections.isEmpty()) return emptyList()
        val pose = latestPose ?: run { diagNoPose += detections.size; return emptyList() }
        val transform = fusion.currentTransform() ?: run { diagNoTransform += detections.size; return emptyList() } // null until VIO→ENU aligned
        val enuFrame = fusion.originFrame() ?: return emptyList()
        val intr = scaledIntrinsics(frame.width, frame.height) ?: run { diagNoIntr += detections.size; return emptyList() }

        val out = ArrayList<Asset>()
        for (det in detections) {
            val u = det.box.centerX.toDouble()
            val v = det.box.centerY.toDouble()
            // Metric depth from ARCore at the detection centre (no fabricated depth).
            val depthM = slamEngine.depthAt(det.box.centerX / frame.width, det.box.centerY / frame.height)
            if (!depthM.isFinite()) { diagDepthMiss++; continue }
            val world = Backprojection.backproject(u, v, depthM.toDouble(), intr, pose.position, pose.orientation)
                ?: continue
            // Skip non-finite world (NaN ARCore pose during poor tracking) so it never
            // poisons the tracker, MCAP, or the georeferenced position.
            if (!world.x.isFinite() || !world.y.isFinite() || !world.z.isFinite()) continue
            // Coarse ARCore Scene Semantics label at the detection centre, accumulated
            // into the landmark's semantic vote.
            val semLabel = slamEngine.semanticLabelAt(det.box.centerX / frame.width, det.box.centerY / frame.height)
            val (tracked, _) = tracker.observe(world, det.assetClass, det.confidence, depthM.toDouble(), det.box, det.sourceFrameId, semLabel)
            // VIO world → ENU (Umeyama) → geo (WGS84).
            val geo = enuFrame.toGeo(transform.apply(tracked.world))
            if (!geo.latitude.isFinite() || !geo.longitude.isFinite() || !geo.altitude.isFinite()) continue
            // Visual embedding from this frame's crop, computed once per asset.
            if (embedder.available && !assetEmbeddings.containsKey(tracked.id)) {
                embedder.embed(frame, det.box)?.let { assetEmbeddings[tracked.id] = it }
            }
            out.add(
                Asset(
                    id = tracked.id,
                    assetClass = tracked.assetClass,
                    geo = geo,
                    box = tracked.lastBox,
                    confidence = tracked.maxConfidence,
                    sourceFrameId = tracked.lastFrameId,
                    depthM = tracked.depthAvgM.toFloat(),
                    embeddingId = null,
                    semanticLabel = tracked.semanticLabel,
                    positionStdM = tracked.positionStdM.takeIf { it.isFinite() }?.toFloat(),
                ),
            )
        }
        return out
    }

    /**
     * Intrinsics for the analysis frame: prefer Camera2's calibration; fall back
     * to ARCore's (some devices, like this one, don't expose Camera2 intrinsics).
     * Scaled to the analysis-frame resolution.
     */
    private fun scaledIntrinsics(w: Int, h: Int): CameraIntrinsics? {
        val full = latestIntrinsics ?: slamEngine.state.value.cameraIntrinsics ?: return null
        if (full.imageWidth <= 0 || full.imageHeight <= 0) return null
        val sx = w.toDouble() / full.imageWidth
        val sy = h.toDouble() / full.imageHeight
        return full.copy(
            fx = full.fx * sx, fy = full.fy * sy,
            cx = full.cx * sx, cy = full.cy * sy,
            imageWidth = w, imageHeight = h,
        )
    }

    /**
     * The deduplicated, georeferenced assets accumulated this session (for
     * persistence). Empty until VIO→ENU alignment exists — no fabricated geo.
     */
    fun currentAssets(): List<Asset> = currentAssetsWithEmbeddings().first

    /**
     * Snapshot the tracked assets and their visual embeddings together, so the two
     * lists stay positionally aligned for batch persistence. Embedding entries are
     * null when the embedder is unavailable or that asset wasn't embedded.
     */
    fun currentAssetsWithEmbeddings(): Pair<List<Asset>, List<FloatArray?>> {
        val transform = fusion.currentTransform() ?: return emptyList<Asset>() to emptyList()
        val enuFrame = fusion.originFrame() ?: return emptyList<Asset>() to emptyList()
        // Track-length gating: only promote landmarks re-observed across frames, which
        // drops one-frame false positives before they reach the dataset.
        val snapshot = tracker.assets.filter { it.observations >= MIN_PROMOTE_OBSERVATIONS }
        val assets = snapshot.map { t ->
            val geo = enuFrame.toGeo(transform.apply(t.world))
            Asset(
                id = 0, // assigned by the DB
                assetClass = t.assetClass,
                geo = geo,
                box = t.lastBox,
                confidence = t.maxConfidence,
                sourceFrameId = t.lastFrameId,
                depthM = t.depthAvgM.toFloat(),
                embeddingId = null,
                semanticLabel = t.semanticLabel,
                positionStdM = t.positionStdM.takeIf { s -> s.isFinite() }?.toFloat(),
            )
        }
        val embeddings = snapshot.map { assetEmbeddings[it.id] }
        return assets to embeddings
    }

    fun stop() {
        sensorHub.camera.setAnalysisCallback(null)
        scope.coroutineContext.cancelChildren()
        scheduler.reset()
        _state.value = _state.value.copy(active = false)
        Log.i(Streams.PERCEPTION, "Perception stopped: ${tracker.count} assets")
    }

    private companion object {
        /** Re-observations required before a landmark is promoted into the dataset. */
        const val MIN_PROMOTE_OBSERVATIONS = 2
    }
}

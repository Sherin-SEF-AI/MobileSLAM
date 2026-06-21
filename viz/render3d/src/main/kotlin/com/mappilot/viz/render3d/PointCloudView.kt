package com.mappilot.viz.render3d

import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mappilot.core.model.Keyframe
import com.mappilot.core.model.Landmark

/**
 * Compose host for the sparse-cloud renderer with orbit (drag), zoom (pinch), and
 * pan (two-finger drag). Renders only when dirty to save battery. Shows an explicit
 * empty state when a session has no SLAM points, and auto-frames the camera to the
 * cloud on first load so the points are never off-screen.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun PointCloudView(
    landmarks: List<Landmark>,
    keyframes: List<Keyframe>,
    modifier: Modifier = Modifier,
) {
    val camera = remember { OrbitCamera() }
    val renderer = remember { PointCloudRenderer(camera) }
    val framed = remember { booleanArrayOf(false) }

    if (landmarks.isEmpty() && keyframes.isEmpty()) {
        Box(modifier.fillMaxSize().background(Color(0xFF0A0A0A)), contentAlignment = Alignment.Center) {
            Text(
                "No 3D points for this session.\n\nThe sparse cloud is built from ARCore tracking during " +
                    "capture. Record while moving, with good lighting and texture, to populate it.",
                color = Color(0xFF9A9A9A),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

                val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        camera.distance = (camera.distance / detector.scaleFactor).coerceIn(0.5f, 200f)
                        requestRender(); return true
                    }
                })

                var lastX = 0f; var lastY = 0f
                setOnTouchListener { _, e ->
                    scale.onTouchEvent(e)
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = e.x - lastX; val dy = e.y - lastY
                            lastX = e.x; lastY = e.y
                            if (e.pointerCount >= 2) {
                                camera.panX -= dx * 0.01f
                                camera.panY += dy * 0.01f
                            } else if (!scale.isInProgress) {
                                camera.yawDeg -= dx * 0.4f
                                camera.pitchDeg += dy * 0.4f
                            }
                            requestRender()
                        }
                    }
                    true
                }
            }
        },
        update = {
            // Auto-frame once, when real data first arrives, so the cloud is centered
            // and fully visible instead of the camera sitting inside or outside it.
            if (!framed[0] && landmarks.isNotEmpty()) {
                val c = PointCloudScene.centroid(landmarks)
                camera.fitTo(PointCloudScene.boundingRadius(landmarks, c))
                framed[0] = true
            }
            renderer.setData(landmarks, keyframes)
            it.requestRender()
        },
    )
}

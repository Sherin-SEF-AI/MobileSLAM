package com.mappilot.viz.render3d

import android.annotation.SuppressLint
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.mappilot.core.model.Keyframe
import com.mappilot.core.model.Landmark

/**
 * Compose host for the sparse-cloud renderer with orbit (drag), zoom (pinch), and
 * pan (two-finger drag). Renders only when dirty to save battery.
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
            renderer.setData(landmarks, keyframes)
            it.requestRender()
        },
    )
}

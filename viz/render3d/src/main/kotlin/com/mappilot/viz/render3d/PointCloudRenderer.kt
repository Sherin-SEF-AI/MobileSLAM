package com.mappilot.viz.render3d

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.mappilot.core.model.Keyframe
import com.mappilot.core.model.Landmark
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/** Orbit camera state driven by touch; produces the view-projection matrix. */
class OrbitCamera {
    @Volatile var yawDeg = 30f
    @Volatile var pitchDeg = 20f
    @Volatile var distance = 8f
    @Volatile var panX = 0f
    @Volatile var panY = 0f
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)

    fun setViewport(width: Int, height: Int) {
        val aspect = if (height == 0) 1f else width.toFloat() / height
        Matrix.perspectiveM(proj, 0, 50f, aspect, 0.1f, 1000f)
    }

    fun viewProjection(): FloatArray {
        val py = pitchDeg.coerceIn(-89f, 89f)
        val eyeX = distance * cos(Math.toRadians(py.toDouble())) * sin(Math.toRadians(yawDeg.toDouble()))
        val eyeY = distance * sin(Math.toRadians(py.toDouble()))
        val eyeZ = distance * cos(Math.toRadians(py.toDouble())) * cos(Math.toRadians(yawDeg.toDouble()))
        Matrix.setLookAtM(
            view, 0,
            eyeX.toFloat() + panX, eyeY.toFloat() + panY, eyeZ.toFloat(),
            panX, panY, 0f, 0f, 1f, 0f,
        )
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        return vp
    }
}

/**
 * GLES 3 renderer for the sparse cloud (GL_POINTS, coloured by confidence) and
 * keyframe frustums (GL_LINES). The Filament path is a higher-fidelity seam; this
 * self-contained GLES renderer is the locked OpenGL ES 3 fallback (§2).
 */
class PointCloudRenderer(val camera: OrbitCamera) : GLSurfaceView.Renderer {

    @Volatile private var pendingPoints: FloatArray? = null
    @Volatile private var pendingLines: FloatArray? = null
    private var pointCount = 0
    private var lineCount = 0
    private var pointVbo = 0
    private var lineVbo = 0
    private var pointProgram = 0
    private var lineProgram = 0

    fun setData(landmarks: List<Landmark>, keyframes: List<Keyframe>) {
        val centroid = PointCloudScene.centroid(landmarks)
        pendingPoints = PointCloudScene.pointVertices(landmarks, centroid)
        pendingLines = PointCloudScene.frustumLines(keyframes, centroid)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.04f, 0.04f, 0.04f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        pointProgram = program(POINT_VS, POINT_FS)
        lineProgram = program(LINE_VS, LINE_FS)
        val ids = IntArray(2); GLES30.glGenBuffers(2, ids, 0)
        pointVbo = ids[0]; lineVbo = ids[1]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        camera.setViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        uploadIfPending()
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val vp = camera.viewProjection()

        if (pointCount > 0) {
            GLES30.glUseProgram(pointProgram)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(pointProgram, "uMvp"), 1, false, vp, 0)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(pointProgram, "uPointSize"), 6f)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pointVbo)
            val pos = GLES30.glGetAttribLocation(pointProgram, "aPos")
            val conf = GLES30.glGetAttribLocation(pointProgram, "aConf")
            GLES30.glEnableVertexAttribArray(pos)
            GLES30.glVertexAttribPointer(pos, 3, GLES30.GL_FLOAT, false, 16, 0)
            GLES30.glEnableVertexAttribArray(conf)
            GLES30.glVertexAttribPointer(conf, 1, GLES30.GL_FLOAT, false, 16, 12)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, pointCount)
        }

        if (lineCount > 0) {
            GLES30.glUseProgram(lineProgram)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(lineProgram, "uMvp"), 1, false, vp, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)
            val pos = GLES30.glGetAttribLocation(lineProgram, "aPos")
            GLES30.glEnableVertexAttribArray(pos)
            GLES30.glVertexAttribPointer(pos, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, lineCount)
        }
    }

    private fun uploadIfPending() {
        pendingPoints?.let { data ->
            pointCount = data.size / 4
            upload(pointVbo, data)
            pendingPoints = null
        }
        pendingLines?.let { data ->
            lineCount = data.size / 3
            upload(lineVbo, data)
            pendingLines = null
        }
    }

    private fun upload(vbo: Int, data: FloatArray) {
        val buf: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(data).also { it.position(0) }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_DYNAMIC_DRAW)
    }

    private fun program(vs: String, fs: String): Int {
        val v = shader(GLES30.GL_VERTEX_SHADER, vs)
        val f = shader(GLES30.GL_FRAGMENT_SHADER, fs)
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, v); GLES30.glAttachShader(it, f); GLES30.glLinkProgram(it)
        }
    }

    private fun shader(type: Int, src: String): Int =
        GLES30.glCreateShader(type).also { GLES30.glShaderSource(it, src); GLES30.glCompileShader(it) }

    private companion object {
        const val POINT_VS = """#version 300 es
            uniform mat4 uMvp; uniform float uPointSize;
            in vec3 aPos; in float aConf; out float vConf;
            void main(){ gl_Position = uMvp * vec4(aPos,1.0); gl_PointSize = uPointSize; vConf = aConf; }"""
        const val POINT_FS = """#version 300 es
            precision mediump float; in float vConf; out vec4 frag;
            void main(){ vec2 c = gl_PointCoord*2.0-1.0; if(dot(c,c)>1.0) discard;
              vec3 col = mix(vec3(0.2,0.4,0.6), vec3(0.0,0.9,0.46), clamp(vConf,0.0,1.0));
              frag = vec4(col,1.0); }"""
        const val LINE_VS = """#version 300 es
            uniform mat4 uMvp; in vec3 aPos;
            void main(){ gl_Position = uMvp * vec4(aPos,1.0); }"""
        const val LINE_FS = """#version 300 es
            precision mediump float; out vec4 frag;
            void main(){ frag = vec4(1.0,0.71,0.0,1.0); }"""
    }
}

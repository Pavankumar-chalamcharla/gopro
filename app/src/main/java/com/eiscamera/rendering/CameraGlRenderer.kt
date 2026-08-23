package com.eiscamera.rendering

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.eiscamera.stabilization.CompensationTransform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * V1.0d: per-frame performance numbers — measured, not assumed (spec
 * section 19: "do not claim real-time without measurement"). [fps] is
 * the rolling rate of actual onDrawFrame calls (i.e. rendered camera
 * frames, not a fixed assumption). [renderTimeMs] is CPU-side wall-clock
 * time for one draw call, stated explicitly as CPU-side rather than
 * true GPU execution time, which needs GL timer-query extensions this
 * class doesn't use yet — an honest simplification, not a claim of
 * more precision than this actually measures.
 */
data class RenderStats(val fps: Double? = null, val renderTimeMs: Double? = null)

/**
 * V1.0c-1 built the GPU render path: the camera feed drawn as an external
 * OES texture through our own shader, via GLSurfaceView (which owns EGL
 * context/thread setup for us; hand-rolling raw EGL was considered and
 * rejected as unnecessary complexity for what's needed here — spec
 * section 12's "don't add sophistication without evidence" applies to
 * architecture choices, not just algorithms).
 *
 * V1.0c-2 is the first change that actually alters the image: each
 * frame, [correctionQuaternionProvider] is asked for the current
 * shake-cancelling rotation (from V1.0b's LiveOrientationPipeline),
 * converted to a 2D texture transform by
 * stabilization.CompensationTransform (verified numerically before this
 * was written), and applied live. [focalLengthMm]/[sensorWidthMm]/
 * [sensorHeightMm] are this camera's already-measured V0.2 values, used
 * for the pitch/yaw-to-shift approximation — pass null for any that
 * aren't available rather than guessing (spec section 16).
 *
 * THREADING: every method here runs on GLSurfaceView's own dedicated GL
 * thread, with the EGL context already current — this class must never
 * be called from any other thread, and never touches GL state outside
 * these callbacks. [correctionQuaternionProvider] is the one exception:
 * it's invoked from this GL thread but reads state that
 * LiveOrientationPipeline updates from its OWN separate sensor thread —
 * that cross-thread read is LiveOrientationPipeline's responsibility to
 * make safe (see its AtomicReference-based snapshot), not this class's.
 * [onFrameRendered] is likewise invoked from this GL thread every frame;
 * its receiver (V1.0d's debug overlay, via a StateFlow) is responsible
 * for safely observing that from the main thread, not this class.
 *
 * FRAME LIFECYCLE / BACKPRESSURE: Camera2 (on its own camera thread, see
 * CameraPreviewViewModel) writes completed frames into this class's
 * SurfaceTexture. SurfaceTexture always retains only the latest
 * unconsumed frame — if the GL thread is momentarily behind, older
 * frames are silently dropped rather than queued, the correct
 * backpressure behavior for a live preview (spec section 28).
 * [onFrameAvailable] requests a redraw; [onDrawFrame] then calls
 * [SurfaceTexture.updateTexImage] to latch the newest frame before
 * drawing it.
 *
 * SIGN CONVENTION CAVEAT (also stated in CompensationTransform's kdoc,
 * worth repeating at the integration point): if the stabilization looks
 * like it's moving the wrong way on-device, that's a one-line sign flip
 * in CompensationTransform.compose, not a problem with this class or a
 * sign something is fundamentally wrong — a normal, expected first-pass
 * step for this class of feature.
 *
 * V1.0d added [onFrameRendered]: fires once per drawn frame with a
 * rolling FPS estimate and this frame's CPU-side render time, so the
 * debug overlay can show measured real-time numbers rather than an
 * assumption that GL rendering "must be" keeping up (spec section 19).
 */
class CameraGlRenderer(
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    private val correctionQuaternionProvider: () -> DoubleArray,
    private val focalLengthMm: Double?,
    private val sensorWidthMm: Double?,
    private val sensorHeightMm: Double?,
    private val cropMargin: Double = CompensationTransform.DEFAULT_CROP_MARGIN,
    private val onFrameRendered: (RenderStats) -> Unit = {},
) : GLSurfaceView.Renderer {

    private var program = 0
    private var textureId = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private var glSurfaceView: GLSurfaceView? = null

    private var positionHandle = 0
    private var texCoordHandle = 0
    private var stMatrixHandle = 0
    private var compensationMatrixHandle = 0
    private var textureHandle = 0

    private val stMatrix = FloatArray(16)
    private var lastFrameStartNs: Long? = null
    private var fpsEstimate: Double? = null

    private val vertexBuffer: FloatBuffer
    private val texCoordBuffer: FloatBuffer

    init {
        // Full-screen quad, drawn as a triangle strip.
        val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val texCoords = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(vertices); position(0) }
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(texCoords); position(0) }
    }

    /** Must be called once, right after construction, so [onFrameAvailable]
     *  can request redraws on the view that owns this renderer. */
    fun attachTo(view: GLSurfaceView) {
        glSurfaceView = view
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = ShaderUtil.linkProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        stMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        compensationMatrixHandle = GLES20.glGetUniformLocation(program, "uCompensationMatrix")
        textureHandle = GLES20.glGetUniformLocation(program, "sTexture")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener { glSurfaceView?.requestRender() }
        onSurfaceTextureReady(surfaceTexture)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStartNs = System.nanoTime()
        val prevFrameStartNs = lastFrameStartNs
        lastFrameStartNs = frameStartNs
        if (prevFrameStartNs != null) {
            val intervalS = (frameStartNs - prevFrameStartNs) / 1_000_000_000.0
            if (intervalS > 0) {
                val instantaneousFps = 1.0 / intervalS
                fpsEstimate = fpsEstimate?.let { it * 0.9 + instantaneousFps * 0.1 } ?: instantaneousFps
            }
        }

        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)

        val compensationMatrix = CompensationTransform.buildMatrix(
            correctionQuaternion = correctionQuaternionProvider(),
            focalLengthMm = focalLengthMm,
            sensorWidthMm = sensorWidthMm,
            sensorHeightMm = sensorHeightMm,
            cropMargin = cropMargin,
        )

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)

        texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)
        GLES20.glUniformMatrix3fv(compensationMatrixHandle, 1, false, compensationMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        val renderTimeMs = (System.nanoTime() - frameStartNs) / 1_000_000.0
        onFrameRendered(RenderStats(fps = fpsEstimate, renderTimeMs = renderTimeMs))
    }

    companion object {
        // uCompensationMatrix is applied AFTER uSTMatrix (i.e. in the camera's
        // already sensor-corrected, upright coordinate space) rather than
        // before it — so the transform only ever has to reason about a
        // consistent, already-upright image, regardless of this specific
        // camera's sensorOrientationDegrees quirks.
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uSTMatrix;
            uniform mat3 uCompensationMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec2 cameraCorrected = (uSTMatrix * aTexCoord).xy;
                vec3 warped = uCompensationMatrix * vec3(cameraCorrected, 1.0);
                vTexCoord = warped.xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}

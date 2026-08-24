package com.eiscamera.rendering

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.eiscamera.logging.EisLog
import com.eiscamera.recording.EncoderSession
import com.eiscamera.stabilization.CompensationTransform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig as GlesEGLConfig
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
 * context/thread setup for us). V1.0c-2 applies live gyro-based
 * stabilization. V1.0d added measured performance numbers.
 *
 * V1.1b-2 (this version): can ALSO draw the exact same transformed frame
 * into a second target — a MediaCodec encoder's input surface — so what
 * gets recorded is genuinely the same stabilized output shown on screen,
 * not a separate/different render pass. [beginRecording]/[endRecording]
 * manage a second EGLSurface sharing this renderer's own GL context;
 * [onDrawFrame] draws to it (if active) right after the normal screen
 * draw, using the SAME already-computed compensation matrix and texture
 * state — no redundant work, just one extra draw+swap+drain per frame.
 *
 * WHY A SHARED CONTEXT, NOT A NEW ONE: GLSurfaceView owns the GL thread's
 * context and never exposes it directly through the public Renderer API
 * — but a context IS guaranteed current whenever onDrawFrame runs, so
 * [beginRecording] queries "what's current right now" via
 * EGL14.eglGetCurrentContext/eglGetCurrentDisplay rather than needing
 * GLSurfaceView to hand it over. Creating the recording EGLSurface
 * against that SAME context means it shares the same texture (the OES
 * camera texture uploaded once per frame) automatically — no separate
 * upload or copy needed for the second draw target.
 *
 * THREADING: [beginRecording]/[endRecording] MUST be called via
 * GLSurfaceView.queueEvent (never directly from another thread) — they
 * touch EGL/GL state and must run on the GL thread, same as every other
 * method here. The caller is responsible for confirming [endRecording]
 * has actually finished running on the GL thread before releasing the
 * underlying encoder Surface elsewhere (see CameraPreviewViewModel) —
 * queueEvent alone doesn't provide that confirmation; a synchronization
 * point is needed on the calling side.
 *
 * SIGN CONVENTION CAVEAT (also stated in CompensationTransform's kdoc):
 * if stabilization looks like it's moving the wrong way on-device,
 * that's a one-line sign flip in CompensationTransform.compose, not a
 * deeper problem — a normal, expected first-pass step for this class of
 * feature.
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

    private var screenWidth = 0
    private var screenHeight = 0

    // V1.1b-2: recording target, set via beginRecording/cleared via
    // endRecording — both must run on this GL thread (see class kdoc).
    private var recordingSession: EncoderSession? = null
    private var recordingEglSurface: EGLSurface? = null

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

    /**
     * Starts drawing every subsequent frame into [session]'s encoder
     * surface too, in addition to the screen. MUST be called via
     * glSurfaceView.queueEvent. No-op if a recording is already active.
     */
    fun beginRecording(session: EncoderSession) {
        if (recordingSession != null) {
            EisLog.w(EisLog.Tag.GPU, "beginRecording called while already recording — ignoring")
            return
        }
        val display = EGL14.eglGetCurrentDisplay()
        val context = EGL14.eglGetCurrentContext()
        if (display == EGL14.EGL_NO_DISPLAY || context == EGL14.EGL_NO_CONTEXT) {
            EisLog.e(EisLog.Tag.GPU, "beginRecording: no current EGL display/context")
            return
        }
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
            EisLog.e(EisLog.Tag.GPU, "beginRecording: eglChooseConfig failed")
            return
        }
        val config = configs[0] ?: return
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, session.encoderInputSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            EisLog.e(EisLog.Tag.GPU, "beginRecording: eglCreateWindowSurface failed")
            return
        }
        recordingEglSurface = eglSurface
        recordingSession = session
        EisLog.i(EisLog.Tag.GPU, "Recording surface attached")
    }

    /** Stops drawing into the recording surface and destroys it. MUST be
     *  called via glSurfaceView.queueEvent. Does NOT call
     *  EncoderSession.stop() — that's the caller's job, and must only
     *  happen after this has actually run (see class kdoc). Safe to call
     *  even if no recording is active. */
    fun endRecording() {
        val display = EGL14.eglGetCurrentDisplay()
        val surface = recordingEglSurface
        if (surface != null && display != EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL14.eglDestroySurface(display, surface) }
        }
        recordingEglSurface = null
        recordingSession = null
        EisLog.i(EisLog.Tag.GPU, "Recording surface detached")
    }

    override fun onSurfaceCreated(gl: GL10?, config: GlesEGLConfig?) {
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
        screenWidth = width
        screenHeight = height
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

        // Draw to the screen (the surface GLSurfaceView already made current).
        GLES20.glViewport(0, 0, screenWidth, screenHeight)
        drawScene(compensationMatrix)

        // V1.1b-2: if recording, draw the SAME transformed frame again into
        // the encoder's surface, then hand it to the encoder. This is the
        // exact reason [beginRecording] shares this renderer's own context:
        // the OES texture just uploaded above is already valid here, no
        // separate upload needed.
        val session = recordingSession
        val recSurface = recordingEglSurface
        if (session != null && recSurface != null) {
            val display = EGL14.eglGetCurrentDisplay()
            val screenDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
            val currentContext = EGL14.eglGetCurrentContext()
            if (EGL14.eglMakeCurrent(display, recSurface, recSurface, currentContext)) {
                GLES20.glViewport(0, 0, session.size.width, session.size.height)
                drawScene(compensationMatrix)
                EGL14.eglSwapBuffers(display, recSurface)
                session.drainEncoder()
                // Restore the screen as current before returning — GLSurfaceView
                // expects its own surface to still be current after onDrawFrame.
                EGL14.eglMakeCurrent(display, screenDrawSurface, screenDrawSurface, currentContext)
            } else {
                EisLog.w(EisLog.Tag.GPU, "Recording frame: eglMakeCurrent to recording surface failed, skipping this frame")
            }
        }

        val renderTimeMs = (System.nanoTime() - frameStartNs) / 1_000_000.0
        onFrameRendered(RenderStats(fps = fpsEstimate, renderTimeMs = renderTimeMs))
    }

    /** The actual draw call sequence, shared between the screen target and
     *  (when active) the recording target — both need the exact same
     *  vertex/texture/shader setup, just targeting whatever surface is
     *  current at the time this is called. Caller sets the viewport and
     *  makes the correct surface current before calling this. */
    private fun drawScene(compensationMatrix: FloatArray) {
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

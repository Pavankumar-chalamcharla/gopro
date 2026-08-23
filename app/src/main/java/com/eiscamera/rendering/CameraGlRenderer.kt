package com.eiscamera.rendering

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * V1.0c-1: replaces V1.0a/b's TextureView-based passthrough with a real
 * GPU render path — the camera feed drawn to the screen as an external
 * OES texture through our own shader, via GLSurfaceView (which owns EGL
 * context/thread setup for us; hand-rolling raw EGL was considered and
 * is unnecessary complexity for what's needed here — spec section 12's
 * "don't add sophistication without evidence" applies to architecture
 * choices, not just algorithms).
 *
 * DELIBERATELY split from the actual stabilization math: [compensationMatrix]
 * defaults to, and stays at, the IDENTITY matrix in this step. The picture
 * should look and behave EXACTLY like V1.0a/b's TextureView passthrough,
 * pixel for pixel — proving this GL plumbing is correct on its own,
 * before V1.0c-2 starts feeding it a real per-frame transform. That keeps
 * "is the GL pipeline right" and "is the compensation math/sign
 * convention right" as two separate, independently-debuggable questions.
 *
 * THREADING: every method here runs on GLSurfaceView's own dedicated GL
 * thread, with the EGL context already current — this class must never
 * be called from any other thread, and never touches GL state outside
 * these callbacks. [setCompensationMatrix] is the one exception (called
 * from whichever thread computes the compensation, V1.0c-2's job), so it
 * publishes through a @Volatile field rather than assuming same-thread
 * access.
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
 * MATRIX CONVENTION: [compensationMatrix] is a 3x3 matrix in OpenGL's
 * standard COLUMN-MAJOR layout (the format glUniformMatrix3fv expects
 * with transpose=false) — worth stating explicitly now, before V1.0c-2
 * starts constructing real rotation/translation matrices, since a
 * row/column mix-up there would silently produce a transposed (wrong)
 * transform rather than a compile error.
 */
class CameraGlRenderer(
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit,
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

    @Volatile
    private var compensationMatrix: FloatArray = IDENTITY_3X3.copyOf()

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
     * Publishes a new 3x3 compensation matrix (column-major) to apply on
     * the next drawn frame. Safe to call from any thread. V1.0c-1 never
     * calls this, leaving the identity default in place; V1.0c-2 is what
     * actually uses it.
     */
    fun setCompensationMatrix(matrix: FloatArray) {
        require(matrix.size == 9) { "expected a 3x3 matrix (9 floats), got ${matrix.size}" }
        compensationMatrix = matrix
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
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)

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
        val IDENTITY_3X3 = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
        )

        // uCompensationMatrix is applied AFTER uSTMatrix (i.e. in the camera's
        // already sensor-corrected, upright coordinate space) rather than
        // before it — so V1.0c-2's transform only ever has to reason about a
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

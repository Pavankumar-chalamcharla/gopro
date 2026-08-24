package com.eiscamera.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Size
import android.view.Surface
import com.eiscamera.logging.EisLog
import java.io.File

/**
 * V1.1b-1: proves MediaCodec (Surface-input mode) + manual EGL +
 * MediaMuxer can produce a real, valid, playable MP4 on this device —
 * BEFORE V1.1b-2 attempts the much harder integration of feeding it
 * actual stabilized frames from CameraGlRenderer. Deliberately isolated:
 * this renders nothing but a slowly-cycling solid color, nothing from
 * the camera pipeline at all — same reasoning as V1.0c-1 (prove a new
 * mechanism works before connecting it to a harder existing system), so
 * if something's wrong here, it's unambiguously an encoder/EGL/muxer
 * problem, not a camera or stabilization one.
 *
 * WHY MANUAL EGL: GLSurfaceView (used for the on-screen preview) owns
 * its own EGL context/surface/thread automatically — there is no
 * GLSurfaceView equivalent for a MediaCodec encoder's input surface.
 * This is the first place in the project setting up EGL by hand (EGL14:
 * eglGetDisplay / eglInitialize / eglChooseConfig / eglCreateContext /
 * eglCreateWindowSurface / eglMakeCurrent / eglSwapBuffers) — genuinely
 * new, hard-to-verify-without-a-real-device territory, the same
 * category of risk V1.0c-1's first GL renderer was. Stated plainly: this
 * may need a real-device debugging round, same as that did.
 *
 * THREADING: does blocking encoder-drain calls in a loop — must be
 * called from a background dispatcher (e.g. Dispatchers.Default via
 * withContext), never the main thread.
 *
 * STORAGE: writes to the app's own external files directory (no special
 * permission needed, unlike shared/Gallery storage, which needs
 * MediaStore APIs — a deliberately deferred concern; the goal here is
 * proving the encoding mechanism itself works, not the full sharing
 * experience). The caller gets the exact file path back to verify with
 * a file manager or `adb pull`.
 */
object TestPatternRecorder {

    private const val FRAME_RATE_FPS = 30
    // 4 Mbps: a conservative, documented starting point for a low-motion
    // test clip, not yet tuned against real stabilized footage bitrate needs.
    private const val BIT_RATE = 4_000_000
    private const val I_FRAME_INTERVAL_S = 1
    private const val EOS_DRAIN_TIMEOUT_US = 10_000L
    private const val EOS_MAX_ATTEMPTS = 200

    data class Result(val success: Boolean, val outputFile: File?, val error: String?)

    fun recordTestClip(
        outputFile: File,
        durationSeconds: Int,
        targetWidth: Int = 1280,
        targetHeight: Int = 720,
    ): Result {
        val encoderInfo = EncoderCapabilities.findEncoder()
            ?: return Result(false, null, "No H.264 encoder available on this device")
        val size = EncoderCapabilities.chooseSupportedSize(
            encoderInfo, EncoderCapabilities.MIME_TYPE_AVC, targetWidth, targetHeight,
        )
        EisLog.i(EisLog.Tag.ENCODER, "Using encoder=${encoderInfo.name} size=${size.width}x${size.height}")

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var eglDisplay: EGLDisplay? = null
        var eglContext: EGLContext? = null
        var eglSurface: EGLSurface? = null
        var inputSurface: Surface? = null

        try {
            val format = MediaFormat.createVideoFormat(EncoderCapabilities.MIME_TYPE_AVC, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            }

            val enc = MediaCodec.createByCodecName(encoderInfo.name)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            enc.start()
            codec = enc
            inputSurface = surface

            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) error("eglGetDisplay failed")
            eglDisplay = display
            val versionBuf = IntArray(2)
            if (!EGL14.eglInitialize(display, versionBuf, 0, versionBuf, 1)) error("eglInitialize failed")

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
                error("eglChooseConfig failed")
            }
            val config = configs[0] ?: error("no EGL config returned")

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) error("eglCreateContext failed")
            eglContext = context

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            val windowSurface = EGL14.eglCreateWindowSurface(display, config, surface, surfaceAttribs, 0)
            if (windowSurface == EGL14.EGL_NO_SURFACE) error("eglCreateWindowSurface failed")
            eglSurface = windowSurface

            if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) error("eglMakeCurrent failed")

            val mux = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val muxerState = MuxerState()
            val bufferInfo = MediaCodec.BufferInfo()

            val totalFrames = durationSeconds * FRAME_RATE_FPS
            for (frame in 0 until totalFrames) {
                // Slowly-cycling color, purely so a human watching the output can
                // confirm it's a real sequence of frames, not one static image.
                val phase = frame.toFloat() / totalFrames
                GLES20.glClearColor(phase, 1f - phase, 0.5f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                EGL14.eglSwapBuffers(display, windowSurface)

                drainEncoder(enc, mux, muxerState, bufferInfo, blockForEos = false)
            }

            enc.signalEndOfInputStream()
            drainEncoder(enc, mux, muxerState, bufferInfo, blockForEos = true)

            if (muxerState.started) mux.stop()
            EisLog.i(EisLog.Tag.ENCODER, "Test clip written: ${outputFile.absolutePath}")
            return Result(true, outputFile, null)
        } catch (e: Exception) {
            EisLog.e(EisLog.Tag.ENCODER, "Test pattern recording failed", e)
            return Result(false, null, e.message ?: "Unknown encoder error")
        } finally {
            runCatching { inputSurface?.release() }
            runCatching { if (eglSurface != null && eglDisplay != null) EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            runCatching { if (eglContext != null && eglDisplay != null) EGL14.eglDestroyContext(eglDisplay, eglContext) }
            runCatching { codec?.stop(); codec?.release() }
            runCatching { muxer?.release() }
        }
    }

    private class MuxerState {
        var trackIndex: Int = -1
        var started: Boolean = false
    }

    /**
     * Drains whatever encoder output is currently ready into [muxer].
     * With [blockForEos]=false this is a non-blocking poll (used once per
     * rendered frame, so encoder output doesn't pile up unbounded). With
     * [blockForEos]=true it blocks (with a bounded retry count) until the
     * end-of-stream buffer is actually observed, used once after
     * signalEndOfInputStream to make sure every remaining frame is
     * flushed before the muxer is stopped.
     */
    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        state: MuxerState,
        bufferInfo: MediaCodec.BufferInfo,
        blockForEos: Boolean,
    ) {
        var attempts = 0
        while (true) {
            val timeoutUs = if (blockForEos) EOS_DRAIN_TIMEOUT_US else 0L
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!blockForEos) return
                    attempts++
                    if (attempts >= EOS_MAX_ATTEMPTS) {
                        EisLog.w(EisLog.Tag.ENCODER, "Gave up waiting for end-of-stream after $attempts attempts")
                        return
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!state.started) { "Encoder changed output format twice — unexpected" }
                    state.trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    state.started = true
                }
                outputIndex >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outputIndex)
                    val isCodecConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (encodedData != null && bufferInfo.size > 0 && !isCodecConfig && state.started) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(state.trackIndex, encodedData, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (isEos) return
                }
            }
        }
    }
}

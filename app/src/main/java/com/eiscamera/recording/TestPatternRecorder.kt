package com.eiscamera.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import com.eiscamera.logging.EisLog
import java.io.FileDescriptor

/**
 * V1.1b-1: proves MediaCodec (Surface-input mode) + manual EGL +
 * MediaMuxer can produce a real, valid, playable MP4 on this device —
 * BEFORE V1.1b-2 attempts the much harder integration of feeding it
 * actual stabilized frames from CameraGlRenderer. Deliberately isolated:
 * this renders nothing but a slowly-cycling solid color, nothing from
 * the camera pipeline at all — same reasoning as V1.0c-1 (prove a new
 * mechanism works before connecting it to a harder existing system), so
 * if something's wrong here, it's unambiguously an encoder/EGL problem,
 * not a camera or stabilization one.
 *
 * WHY MANUAL EGL: GLSurfaceView (used for the on-screen preview) owns
 * its own EGL context/surface/thread automatically — there is no
 * GLSurfaceView equivalent for a MediaCodec encoder's input surface.
 * This is the first place in the project setting up EGL by hand.
 *
 * OUTPUT: takes a [FileDescriptor] rather than a File path, so the
 * caller can point this at either app-private storage or (as of the
 * fix below) a MediaStore-backed Uri opened for writing — this class
 * doesn't need to know or care which.
 *
 * FRAME PACING (fixed after real-device testing reported a clip
 * finishing in ~1 second instead of the requested duration): the
 * original loop had NO pacing at all between frames — just glClear +
 * eglSwapBuffers + a near-instant drain poll, repeated as fast as the
 * CPU/GPU could go, with nothing tying it to real time. All [totalFrames]
 * completed in a small fraction of a real second, and the resulting
 * presentation timestamps (assigned implicitly by eglSwapBuffers)
 * reflected that tiny real elapsed time — producing a video whose actual
 * encoded duration was far under what was requested. Now each frame's
 * presentation timestamp is set EXPLICITLY via eglPresentationTimeANDROID
 * to a precise, evenly-spaced value based on frame index (removing any
 * dependence on the loop's own real-time jitter), and the loop is ALSO
 * paced with Thread.sleep to real wall-clock time, so the actual
 * recording duration matches what was requested too — keeping the
 * caller's own elapsed-time UI meaningful, not decoupled from reality.
 *
 * THREADING: does blocking encoder-drain and Thread.sleep pacing calls
 * in a loop — must be called from a background dispatcher (e.g.
 * Dispatchers.Default via withContext), never the main thread.
 */
object TestPatternRecorder {

    private const val FRAME_RATE_FPS = 30
    private const val FRAME_INTERVAL_NS = 1_000_000_000L / FRAME_RATE_FPS
    // 4 Mbps: a conservative, documented starting point for a low-motion
    // test clip, not yet tuned against real stabilized footage bitrate needs.
    private const val BIT_RATE = 4_000_000
    private const val I_FRAME_INTERVAL_S = 1
    private const val EOS_DRAIN_TIMEOUT_US = 10_000L
    private const val EOS_MAX_ATTEMPTS = 200

    data class Result(val success: Boolean, val error: String?)

    fun recordTestClip(
        outputFileDescriptor: FileDescriptor,
        durationSeconds: Int,
        targetWidth: Int = 1280,
        targetHeight: Int = 720,
    ): Result {
        val encoderInfo = EncoderCapabilities.findEncoder()
            ?: return Result(false, "No H.264 encoder available on this device")
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

            val mux = MediaMuxer(outputFileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val muxerState = MuxerState()
            val bufferInfo = MediaCodec.BufferInfo()

            val totalFrames = durationSeconds * FRAME_RATE_FPS
            val recordingStartNs = System.nanoTime()
            for (frame in 0 until totalFrames) {
                val presentationTimeNs = frame.toLong() * FRAME_INTERVAL_NS

                // Slowly-cycling color, purely so a human watching the output can
                // confirm it's a real sequence of frames, not one static image.
                val phase = frame.toFloat() / totalFrames
                GLES20.glClearColor(phase, 1f - phase, 0.5f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                EGLExt.eglPresentationTimeANDROID(display, windowSurface, presentationTimeNs)
                EGL14.eglSwapBuffers(display, windowSurface)

                drainEncoder(enc, mux, muxerState, bufferInfo, blockForEos = false)

                // Pace to real time -- see class kdoc's FRAME PACING section for
                // why this is necessary, not optional.
                val targetElapsedNs = presentationTimeNs + FRAME_INTERVAL_NS
                val actualElapsedNs = System.nanoTime() - recordingStartNs
                val sleepNs = targetElapsedNs - actualElapsedNs
                if (sleepNs > 0) {
                    Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                }
            }

            enc.signalEndOfInputStream()
            drainEncoder(enc, mux, muxerState, bufferInfo, blockForEos = true)

            if (muxerState.started) mux.stop()
            EisLog.i(EisLog.Tag.ENCODER, "Test clip encoding finished successfully")
            return Result(true, null)
        } catch (e: Exception) {
            EisLog.e(EisLog.Tag.ENCODER, "Test pattern recording failed", e)
            return Result(false, e.message ?: "Unknown encoder error")
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

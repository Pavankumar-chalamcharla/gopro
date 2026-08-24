package com.eiscamera.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Size
import android.view.Surface
import com.eiscamera.logging.EisLog
import java.io.FileDescriptor

/**
 * V1.1b-2: an OPEN-ENDED recording session. Unlike V1.1b-1's
 * TestPatternRecorder (a fixed-duration, self-contained proof this
 * mechanism works at all), this exposes the encoder's input [Surface]
 * for CameraGlRenderer to draw REAL stabilized frames into for as long
 * as recording is active, with genuine start/stop control — the
 * limitation V1.1b-1 explicitly left for this stage.
 *
 * Deliberately reuses the exact encoder-configuration and muxer-
 * draining logic already proven correct in TestPatternRecorder /
 * EncoderCapabilities on real hardware — the NEW risk in V1.1b-2 is
 * specifically the dual-surface GL integration in CameraGlRenderer, not
 * re-deriving the MediaCodec/EGL fundamentals a second time.
 *
 * THREADING: [drainEncoder] must be called from the GL thread, once per
 * rendered frame, immediately after CameraGlRenderer draws into
 * [encoderInputSurface] and swaps it — never from any other thread.
 * [start] and [stop] do blocking I/O (codec/muxer setup and teardown)
 * and should be called from a background dispatcher, not the main
 * thread; [stop] specifically must only be called AFTER the GL thread
 * has already stopped drawing into [encoderInputSurface] (see
 * CameraGlRenderer.endRecording's synchronization notes) — releasing
 * the underlying Surface while the renderer still holds an EGLSurface
 * wrapping it is unsafe.
 */
class EncoderSession private constructor(
    private val codec: MediaCodec,
    val encoderInputSurface: Surface,
    private val muxer: MediaMuxer,
    val size: Size,
) {
    private val muxerState = MuxerState()
    private val bufferInfo = MediaCodec.BufferInfo()
    private var stopped = false

    /** Call once per rendered frame, right after drawing into
     *  [encoderInputSurface] and swapping its buffer. Non-blocking. */
    fun drainEncoder() {
        if (stopped) return
        drainEncoderInternal(codec, muxer, muxerState, bufferInfo, blockForEos = false)
    }

    /** Signals end-of-stream, flushes remaining output, and finalizes the
     *  muxer and all resources. Safe to call once; later calls are no-ops. */
    fun stop() {
        if (stopped) return
        stopped = true
        runCatching {
            codec.signalEndOfInputStream()
            drainEncoderInternal(codec, muxer, muxerState, bufferInfo, blockForEos = true)
            if (muxerState.started) muxer.stop()
        }
        runCatching { encoderInputSurface.release() }
        runCatching { codec.stop(); codec.release() }
        runCatching { muxer.release() }
        EisLog.i(EisLog.Tag.ENCODER, "EncoderSession stopped")
    }

    private class MuxerState {
        var trackIndex: Int = -1
        var started: Boolean = false
    }

    companion object {
        private const val FRAME_RATE_FPS = 30
        private const val BIT_RATE = 4_000_000
        private const val I_FRAME_INTERVAL_S = 1
        private const val EOS_DRAIN_TIMEOUT_US = 10_000L
        private const val EOS_MAX_ATTEMPTS = 200

        fun start(
            outputFileDescriptor: FileDescriptor,
            targetWidth: Int = 1280,
            targetHeight: Int = 720,
        ): EncoderSession? {
            val encoderInfo = EncoderCapabilities.findEncoder() ?: run {
                EisLog.e(EisLog.Tag.ENCODER, "No H.264 encoder available")
                return null
            }
            val size = EncoderCapabilities.chooseSupportedSize(
                encoderInfo, EncoderCapabilities.MIME_TYPE_AVC, targetWidth, targetHeight,
            )
            return try {
                val format = MediaFormat.createVideoFormat(EncoderCapabilities.MIME_TYPE_AVC, size.width, size.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE_FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
                }
                val codec = MediaCodec.createByCodecName(encoderInfo.name)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                codec.start()
                val muxer = MediaMuxer(outputFileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                EisLog.i(EisLog.Tag.ENCODER, "EncoderSession started: encoder=${encoderInfo.name} size=${size.width}x${size.height}")
                EncoderSession(codec, surface, muxer, size)
            } catch (e: Exception) {
                EisLog.e(EisLog.Tag.ENCODER, "Failed to start EncoderSession", e)
                null
            }
        }

        private fun drainEncoderInternal(
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
                        if (attempts >= EOS_MAX_ATTEMPTS) return
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
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
}

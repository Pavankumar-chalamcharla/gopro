package com.eiscamera.processing

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import com.eiscamera.logging.EisLog

/**
 * Queries GPU renderer/vendor/version strings by creating a throwaway,
 * invisible EGL pbuffer context. This is the standard way to identify the
 * GL driver on Android without showing anything on screen.
 *
 * This is a BLOCKING call and must run on a thread that does not already
 * own a GL context (EGL contexts are thread-bound). The caller
 * (ProcessingInventory, invoked from DeviceScanCoordinator) is responsible
 * for calling this off the main thread — see spec section 28 (threading).
 */
object GpuInfoProbe {

    data class Result(
        val succeeded: Boolean,
        val renderer: String? = null,
        val vendor: String? = null,
        val version: String? = null,
        val extensions: List<String> = emptyList(),
        val error: String? = null,
    )

    fun probe(): Result {
        var display: android.opengl.EGLDisplay? = null
        var surface: android.opengl.EGLSurface? = null
        var context: android.opengl.EGLContext? = null
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                return Result(false, error = "eglGetDisplay returned EGL_NO_DISPLAY")
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return Result(false, error = "eglInitialize failed")
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] == 0
            ) {
                return Result(false, error = "eglChooseConfig failed / no matching config")
            }

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                return Result(false, error = "eglCreateContext failed")
            }

            val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], pbufferAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                return Result(false, error = "eglCreatePbufferSurface failed")
            }

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return Result(false, error = "eglMakeCurrent failed")
            }

            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION)
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS)?.split(" ")?.filter { it.isNotBlank() }
                ?: emptyList()

            return Result(
                succeeded = true,
                renderer = renderer,
                vendor = vendor,
                version = glVersion,
                extensions = extensions,
            )
        } catch (e: Exception) {
            EisLog.e(EisLog.Tag.GPU, "GPU probe failed", e)
            return Result(false, error = e.message)
        } finally {
            if (display != null) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != null) EGL14.eglDestroySurface(display, surface)
                if (context != null) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }
}

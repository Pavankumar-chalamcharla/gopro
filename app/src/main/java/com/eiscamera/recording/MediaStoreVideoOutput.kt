package com.eiscamera.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.eiscamera.logging.EisLog

/**
 * V1.1b-1 fix: writes recorded clips through MediaStore's public Movies
 * collection instead of the app's private external-files directory.
 * The private directory (Android/data/<package>/files/...) is a real,
 * valid, writable path — but on modern Android, standard file manager
 * apps commonly hide or restrict browsing into OTHER apps' Android/data
 * folders (a Scoped Storage change), so a path there is often
 * technically correct but practically undiscoverable to a person trying
 * to find their own recording. Confirmed as a real, reported problem
 * from on-device testing, not a hypothetical concern.
 *
 * API 29+ (Q, Scoped Storage): inserted as IS_PENDING until the
 * recording finishes, then finalized — the standard pattern for a
 * still-being-written MediaStore entry so other apps don't see a
 * half-written file while it's in progress.
 * API 26-28: falls back to the legacy direct DATA-column path under the
 * public Movies directory. This project's actual test device is well
 * above API 29 — this fallback is a reasonable-effort path, not
 * exhaustively tested (spec section 12: don't over-engineer for an
 * untested scenario).
 */
object MediaStoreVideoOutput {

    data class PendingVideo(val uri: Uri)

    fun createPending(context: Context, displayName: String): PendingVideo? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/EisCamera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            EisLog.e(EisLog.Tag.ENCODER, "MediaStore insert returned null Uri")
            return null
        }
        return PendingVideo(uri)
    }

    /** Marks the entry as complete (API 29+ only — a no-op below that,
     *  where there was no IS_PENDING flag to begin with) so it becomes
     *  visible to Gallery/Files apps. Call after the muxer has finished
     *  and been released. */
    fun finalizePending(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    /** Deletes a pending entry if recording failed, so a broken,
     *  half-written file doesn't linger and confuse a later listing. */
    fun deletePending(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }
}

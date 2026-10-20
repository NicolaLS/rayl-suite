@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package xyz.lilsus.raylsuite.core.camera

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import platform.darwin.OS_LOG_CATEGORY_POINTS_OF_INTEREST
import platform.darwin.OS_SIGNPOST_EVENT
import platform.darwin.OS_SIGNPOST_ID_EXCLUSIVE
import platform.darwin.OS_SIGNPOST_ID_INVALID
import platform.darwin.OS_SIGNPOST_ID_NULL
import platform.darwin.OS_SIGNPOST_INTERVAL_BEGIN
import platform.darwin.OS_SIGNPOST_INTERVAL_END
import platform.darwin.__dso_handle
import platform.darwin._os_signpost_emit_with_name_impl
import platform.darwin.os_log_create
import platform.darwin.os_signpost_enabled
import platform.darwin.os_signpost_id_generate

internal object IosCameraTrace {
    private val log = os_log_create(
        subsystem = "xyz.lilsus.raylsuite",
        category = OS_LOG_CATEGORY_POINTS_OF_INTEREST
    )

    fun beginInterval(event: CameraTraceEvent): IosCameraTraceInterval? {
        if (!os_signpost_enabled(log)) return null
        val identifier = os_signpost_id_generate(log)
        if (identifier == OS_SIGNPOST_ID_NULL || identifier == OS_SIGNPOST_ID_INVALID) return null
        emit(OS_SIGNPOST_INTERVAL_BEGIN, identifier, event)
        return IosCameraTraceInterval(event, identifier)
    }

    fun event(event: CameraTraceEvent) {
        emit(OS_SIGNPOST_EVENT, OS_SIGNPOST_ID_EXCLUSIVE, event)
    }

    internal fun endInterval(event: CameraTraceEvent, identifier: ULong) {
        emit(OS_SIGNPOST_INTERVAL_END, identifier, event)
    }

    private fun emit(type: UByte, identifier: ULong, event: CameraTraceEvent) {
        if (!os_signpost_enabled(log)) return
        memScoped {
            // os_signpost helpers are C macros and are not exposed to Kotlin/Native. Mirror the
            // name-only macro expansion with an empty format buffer and no dynamic payload.
            val formatBuffer = allocArray<UByteVar>(FORMAT_BUFFER_SIZE)
            formatBuffer[0] = 0u
            _os_signpost_emit_with_name_impl(
                dso = __dso_handle.ptr,
                log = log,
                type = type,
                spid = identifier,
                name = event.traceName,
                format = "",
                buf = formatBuffer,
                size = FORMAT_BUFFER_SIZE.toUInt()
            )
        }
    }

    private const val FORMAT_BUFFER_SIZE = 64
}

internal class IosCameraTraceInterval(
    private val event: CameraTraceEvent,
    private val identifier: ULong
) {
    private var ended = false

    fun end() {
        if (ended) return
        ended = true
        IosCameraTrace.endInterval(event, identifier)
    }
}

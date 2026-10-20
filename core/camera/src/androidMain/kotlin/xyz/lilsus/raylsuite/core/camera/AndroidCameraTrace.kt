package xyz.lilsus.raylsuite.core.camera

import androidx.tracing.Trace
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal object AndroidCameraTrace {
    private val nextCookie = AtomicInteger()

    fun beginInterval(event: CameraTraceEvent): AndroidCameraTraceInterval? {
        if (!Trace.isEnabled()) return null
        val cookie = nextCookie.incrementAndGet()
        Trace.beginAsyncSection(event.traceName, cookie)
        return AndroidCameraTraceInterval(event, cookie)
    }

    fun event(event: CameraTraceEvent) {
        if (!Trace.isEnabled()) return
        Trace.beginSection(event.traceName)
        Trace.endSection()
    }
}

internal class AndroidCameraTraceInterval(
    private val event: CameraTraceEvent,
    private val cookie: Int
) {
    private val ended = AtomicBoolean(false)

    fun end() {
        if (ended.compareAndSet(false, true)) {
            Trace.endAsyncSection(event.traceName, cookie)
        }
    }
}

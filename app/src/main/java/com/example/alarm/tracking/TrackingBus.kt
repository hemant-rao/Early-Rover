package com.example.alarm.tracking

/**
 * §806 — one process-wide [TrackingSocket] shared by the receiver (the tracking
 * screen's ViewModel) and the sender (the [EarlyRoverShareService] FGS). Ref-counted
 * so the socket stays open while EITHER needs it and closes only when both release.
 */
object TrackingBus {
    val socket = TrackingSocket()
    private var refs = 0

    @Synchronized fun acquire() {
        if (refs == 0) socket.start()
        refs++
    }

    @Synchronized fun release() {
        refs--
        if (refs <= 0) {
            refs = 0
            socket.stop()
        }
    }
}

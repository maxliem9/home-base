package com.homebase.plugins

import io.ktor.server.application.*
import io.ktor.server.websocket.*
import kotlin.time.Duration.Companion.seconds

fun Application.configureWebSockets() {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        // Bound the largest INCOMING frame the server will buffer (#557). maxFrameSize caps client→server
        // data frames only (not the server's own broadcasts, and not Close/Pong control frames). Our
        // clients are pure listeners — they never send a data frame — so 64 KiB is already far more than
        // any legitimate traffic, while an unbounded Long.MAX_VALUE let a buggy/hostile client force an
        // arbitrarily large allocation on an authenticated socket.
        maxFrameSize = 64L * 1024
        masking = false
    }
}

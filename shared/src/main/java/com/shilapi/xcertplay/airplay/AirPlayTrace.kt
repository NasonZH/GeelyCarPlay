// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.airplay

/**
 * Wire-level AirPlay tracing: RTSP headers, RTSP wire bytes and plist hex dumps.
 *
 * Off by default. On the GEELY head unit these lines produced several hundred kilobytes per minute
 * and drowned the stage / diagnostic / stats lines that actually matter; the exported diagnostic
 * report already drops every `TRACE ` line (see DiagnosticRedactor), so they were pure noise.
 * Flip to true only for a focused protocol investigation, then turn it back off.
 */
object AirPlayTrace {
    @Volatile
    var enabled: Boolean = false
}

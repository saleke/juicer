package com.eqo.pipeline

/**
 * Live counters and timing for a pipeline run.
 *
 * The frame-time budget (spec §3: 60 FPS ⇒ 16.6 ms) applies to the *listener-side
 * processing* (the dual-read path), not to the raw decode wall time — decoding a
 * large file as fast as the hardware allows legitimately exceeds 16.6 ms per frame.
 * The two are therefore reported separately (design review, pitfall P5).
 *
 * @property framesRendered    Frames the decoder rendered into the output surface.
 * @property framesProcessed   Frames that completed the dual-read path (AI + encoder).
 * @property framesDropped     `framesRendered - framesProcessed` — frames dropped by
 *                             the backpressure strategy (`acquireLatestImage`). This
 *                             divergence is by design, not an error (pitfall P13).
 * @property framesOverBudget  Processed frames whose listener time exceeded the budget.
 * @property processNanosLast  Listener-side time of the most recent frame.
 * @property processNanosAvg   Running average of listener-side frame time.
 * @property decodeNanosAvg    Average wall time per rendered frame, decode loop side.
 * @property framesEncoded     Frames submitted to the hardware encoder (Component 3);
 *                             ≤ framesProcessed — the drop-frame strategy skips some.
 * @property encodedBytes      Bytes written to the muxer so far (video + audio).
 * @property appliedBitrate    Encoder bitrate currently in effect, bps; 0 pre-encoder.
 */
data class PipelineMetrics(
    val framesRendered: Int = 0,
    val framesProcessed: Int = 0,
    val framesDropped: Int = 0,
    val framesOverBudget: Int = 0,
    val processNanosLast: Long = 0,
    val processNanosAvg: Long = 0,
    val decodeNanosAvg: Long = 0,
    val framesEncoded: Int = 0,
    val encodedBytes: Long = 0,
    val appliedBitrate: Int = 0,
) {
    companion object {
        /** Per-frame processing budget for 60 FPS (16.6 ms). */
        const val FRAME_BUDGET_NANOS: Long = 16_600_000L
    }
}

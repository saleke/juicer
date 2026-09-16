package com.eqo.pipeline

/**
 * Lifecycle states of the zero-copy video pipeline.
 *
 * Transitions:
 * ```
 * IDLE ──prepare+start──▶ DECODING ──end of stream──▶ COMPLETED
 *                             │  ▲
 *                        pause│  │resume
 *                             ▼  │
 *                           PAUSED
 *        any state ──failure──▶ ERROR
 * ```
 */
enum class VideoPipelineStatus {
    IDLE,
    DECODING,
    PAUSED,
    COMPLETED,
    ERROR,
}

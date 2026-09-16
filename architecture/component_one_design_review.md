# eqo — Component 1 Design Review (pre-implementation)

Research draft, edge cases, pitfalls, and mitigations for the zero-copy video decode
pipeline. This document was produced before writing any code; the implementation must
resolve every item in the "Mitigations" tables below.

Sources: Android framework documentation knowledge (developer.android.com),
framework behavior of `MediaCodec` / `ImageReader` / `HardwareBuffer` on API 29+.
Confidence is marked per fact; anything marked **[verify-on-device]** must be checked
during on-device smoke testing because the external lookup service was unavailable
while writing this.

---

## 1. Research: the facts the design depends on

| # | Fact | Confidence |
|---|------|------------|
| R1 | `ImageReader.newInstance(w, h, format, maxImages, usage)` (5-arg, API 29+) is the only way to create a reader whose images carry a real `AHardwareBuffer`. For decoder output the format must be `ImageFormat.PRIVATE`; byte-oriented formats (`YUV_420_888` etc.) produce JVM-side plane buffers (a copy) and `getHardwareBuffer()` may return null. | High — canonical recipe |
| R2 | `ImageFormat.PRIVATE` images have no accessible planes (`getPlanes()` throws); the only handle to the pixels is `image.hardwareBuffer`. This is exactly what we want: it makes CPU-side copies impossible by construction. | High |
| R3 | `MediaCodec` in surface-output mode (`configure(format, surface, null, 0)` + `releaseOutputBuffer(idx, true)`) renders decoded frames directly into the Surface's `BufferQueue` — the producer side never touches the JVM heap. | High |
| R4 | `MediaCodecInfo.isHardwareAccelerated()` / `isSoftwareOnly()` exist since API 29. They are more reliable than name-prefix heuristics (`OMX.google.*`, `c2.android.*` are the software families; vendor hardware is `OMX.<vendor>.*`, `c2.<vendor>.*`). The component spec's mention of `c2.android.` actually names the *software* codecs — we honor the spec's intent (explicit `MediaCodecList` selection preferring hardware) rather than its literal prefix list. | High |
| R5 | `Image.getHardwareBuffer()` ownership: the `Image` owns the buffer and `image.close()` recycles the underlying hardware memory block (the spec's Step C.5 reads the same way). Consumers must therefore finish with the buffer **before** `image.close()`. **[verify-on-device: confirm no AHardwareBuffer leak via native memory graph]** | Medium — doc lookup unavailable; flagged |
| R6 | `acquireLatestImage()` atomically discards older queued images (closing them) and returns the newest. With `maxImages = 3` this is the built-in drop-frame mechanism: if the AI/encoder consumers are slow, stale frames are dropped, never accumulated in RAM. | High |
| R7 | Surface backpressure: if all `maxImages` buffers are held (not closed), the ImageReader's BufferQueue fills and the decoder's `releaseOutputBuffer(render=true)` blocks. Processing + closing synchronously in the listener keeps the queue draining; a stalled consumer throttles decode throughput instead of growing memory. | High |
| R8 | `MediaFormat` values are not type-stable across devices: `KEY_FRAME_RATE` is sometimes stored as `Float`, `KEY_BIT_RATE` may be absent for some containers. `getInteger()` on a Float-stored value throws. Accessors need try/fallback helpers. | High — well-known gotcha |
| R9 | With `ImageFormat.PRIVATE`, hardware decoders output subsampled YUV (typically ~1.5 bytes/pixel for 4:2:0). Worst-case pool math at 4K: 3840×2160×1.5 ≈ 12.4MB/frame × 3 = ~37MB — just under the 40MB cap. At 1080p it is ~25MB… actually 1920×1080×1.5 ≈ 3.1MB × 3 ≈ 9.3MB. The 3-frame pool satisfies the spec's <40MB constraint for all practical resolutions. | Medium — format is device-dependent |
| R10 | `MediaExtractor.setDataSource(context, uri, null)` handles `content://` and `file://` without any deprecated file-descriptor juggling; it throws `IOException` on bad containers. | High |
| R11 | `HardwarePropertiesManager` (used later by Component 4) requires privileged permissions — irrelevant now, but the pipeline should already expose per-frame timing metrics so Component 4 can consume them. | High |

---

## 2. Draft solution (architecture summary)

Single-module app `com.eqo` (app name **eqo**):

```
app/src/main/java/com/eqo/
├── MainActivity.kt                  # single activity, Compose only
├── ui/PipelineViewModel.kt          # owns pipeline lifecycle, one StateFlow<UiState>
├── ui/PipelineScreen.kt             # pick video / show metadata+status+metrics / controls
└── pipeline/
    ├── VideoPipelineStatus.kt       # IDLE, DECODING, PAUSED, COMPLETED, ERROR
    ├── VideoMetadata.kt             # width, height, bitrate, frameRate, colorFormat, mime, rotation
    ├── PipelineMetrics.kt           # rendered/processed/dropped counts, frame-time stats
    ├── HardwareCodecSelector.kt     # pure, unit-testable codec choice
    └── ZeroCopyVideoPipeline.kt     # open class; placeholders processFrameWithAI / passFrameToEncoder
```

Threading model (all off main thread):

```
main ─── UI/ViewModel (StateFlow)
  │
  ├── "eqo-pipeline" single-thread executor (asCoroutineDispatcher)
  │     └── prepare(): extractor + codec config + ImageReader setup
  │     └── decode loop: feed extractor samples → dequeueOutputBuffer →
  │                      releaseOutputBuffer(render=true) → surface
  │
  └── "eqo-frame" HandlerThread
        └── OnImageAvailableListener:
              acquireLatestImage → hardwareBuffer
                → processFrameWithAI(buffer)      // Component 2 seam
                → passFrameToEncoder(buffer)       // Component 3 seam
                → image.close()                    // recycle hardware block
```

- `maxImages = 3`, usage = `USAGE_GPU_SAMPLED_IMAGE | USAGE_VIDEO_ENCODE`, format `PRIVATE`.
- Synchronous (blocking) `MediaCodec` mode inside one coroutine — file-based decode, not a
  realtime stream; short (10ms) dequeue timeouts so pause/release/stall conditions are
  re-checked every iteration.
- Status/metadata/metrics exposed as `StateFlow`; placeholders are `protected open fun`s so
  Components 2/3 subclass or inject behavior without touching the loop.

---

## 3. Edge cases & pitfalls, with mitigations

### P1 — `image.hardwareBuffer` may return null
Even with PRIVATE+usage, defensive code must not NPE inside the listener (an exception on the
listener thread silently kills frame delivery).
**Mitigation:** null-check; if null, close image, count as `dropped`, log once (not per frame).

### P2 — Listener exception kills the pipeline silently
Any throw inside `OnImageAvailableListener` propagates to the HandlerThread looper and frames
stop being consumed → decoder backpressure-blocks forever → app hangs in DECODING.
**Mitigation:** wrap entire listener body in try/catch; route failures into the pipeline's
error channel (status=ERROR, release codec); `finally { image.close() }` guarantees recycling
even on failure.

### P3 — `HardwareBuffer` lifetime vs `image.close()` (R5)
Consumers must be finished before `close()`. If a future Component 2/3 keeps an asynchronous
reference to the buffer (e.g. async inference), closing the image invalidates it →
use-after-free in native memory.
**Mitigation:** placeholder contract documented as *synchronous*: both functions must return
before the loop closes the image. Enforced by calling them sequentially on the listener
thread. Later components needing async access must duplicate/delay via EGL fences —
documented as an explicit contract in the code KDoc. [verify-on-device for leak behavior]

### P4 — Surface backpressure stalls the decode loop (R7)
A slow consumer blocks `releaseOutputBuffer(render=true)`, which blocks the pipeline thread —
pause/release can't be honored while blocked.
**Mitigation:** (a) synchronous close in listener keeps stalls bounded by one frame's
processing; (b) decode loop uses a `running/paused/released` check every iteration; (c)
release() cancels the coroutine and closes the ImageReader, which unblocks the BufferQueue.
Worst case documented: a blocked render can delay release by up to one dequeue timeout cycle.

### P5 — The 16.6ms budget is a *processing* budget, not a decode guarantee
Decoding 4K as fast as possible will exceed 16.6ms/frame of wall time on many SoCs; that is
fine — the spec's budget applies to the per-frame dual-read path. Reporting "avg frame time"
without separating these two would produce misleading metrics.
**Mitigation:** metrics separate `decode wall time` (loop side) from `frame process time`
(listener side); the budget check counts only the listener side. Drop/slow-frame counters
feed the drop-frame strategy: after N consecutive over-budget frames, the pipeline drops
every other frame to the encoder (graceful encoder-fps reduction, per spec §3).

### P6 — EOS vs in-flight frames race
When the drain loop sees `BUFFER_FLAG_END_OF_STREAM`, images may still be queued/in-flight in
the ImageReader; declaring COMPLETED too early under-counts processed frames.
**Mitigation:** on EOS, stop feeding, drain remaining output, then wait for the listener to
go idle (rendered-vs-processed settling with a bounded grace timeout ~2s) before status=
COMPLETED. Timeout path still completes (never hangs).

### P7 — Frame-rate/bitrate MediaFormat type instability (R8)
`getInteger(KEY_FRAME_RATE)` throws `ClassCastException` on devices where it's a Float.
**Mitigation:** `format.intOr(key) { format.getFloat(key).toInt() }` helper with containsKey
guards; missing bitrate defaults to 0 and is displayed as "unknown", never crashes.

### P8 — Codec configuration from raw track format can throw
Some containers carry track-format keys the codec rejects (`IllegalArgumentException` on
configure), or the stream is unsupported by the chosen hardware codec.
**Mitigation:** explicit try/catch per spec §4; on hardware-codec configure failure, one
automatic retry with the software fallback codec before giving up (status=ERROR with a
human-readable message).

### P9 — Rotated videos
`rotation-degrees` metadata means coded (width×height) ≠ display orientation. The decoded
AHardwareBuffers are in coded orientation; a naive UI would display "wrong" dimensions.
**Mitigation:** expose `rotation` in `VideoMetadata`; ImageReader sized to coded dimensions;
UI displays both. Components 2/3 are documented to receive coded-orientation frames.

### P10 — URI permission loss across process death
`content://` URIs from SAF require `takePersistableUriPermission`, and the grant must outlive
the Activity, or `prepare()` throws after a configuration change.
**Mitigation:** ViewModel owns pipeline (survives rotation); `takePersistableUriPermission`
on pick; `prepare()` rethrows typed `PipelineException` so the UI can prompt re-pick.

### P11 — Double release / release-while-decoding races
`release()` from UI thread racing the decode loop and listener thread → use-after-release of
codec/extractor/ImageReader (native crash).
**Mitigation:** single `releaseLock`; `@Volatile released` flag checked in both the loop and
listener; idempotent release; strict order: cancel job → stop codec → release extractor →
close reader → quit handler thread → close dispatcher. Release is a no-op after the first.

### P12 — `pause()` semantics with a file decoder
Paused = stop rendering new frames but keep codec alive (spec's PAUSED state).
**Mitigation:** loop parks between iterations (does not feed/queue) while `paused`; Surface
buffers already queued are still consumed by the listener. Resume continues the stream
exactly where paused.

### P13 — Frame counting accuracy with `acquireLatestImage`
Rendered count (decode loop) vs processed count (listener) legitimately diverge — dropped =
rendered − processed is *by design* the backpressure signal, not a bug.
**Mitigation:** metrics expose all three counters with distinct names; no assertion-style
equality anywhere; unit tests cover the accounting math.

### P14 — 40MB pool claim depends on device YUV layout (R9)
PRIVATE decoder output is usually 4:2:0 (~1.5 B/px) but is not guaranteed.
**Mitigation:** `maxImages = 3` is the hard cap regardless of layout (spec's requirement);
worst-case math documented; no code can allocate more buffers than the pool.

### P15 — No deprecated APIs / no copies in the hot path
Audit point: no `Bitmap`, no `byte[]`, no `ByteBuffer.get()` in the frame path; no
`MediaCodec.createDecoderByType` deprecated variants (all modern constructors);
`getOutputFormat()` only used on the codec instance (never the deprecated extractor variant).
**Mitigation:** enforced by review; the PRIVATE format itself makes JVM-side copies
impossible (R2) — a structural guarantee, not just a convention.

### P16 — Later components (2–4) must not force redesign
Component 2 needs synchronous ≤8ms inference on the listener thread; Component 3 needs the
same buffer for the encoder surface; Component 4 needs the metrics.
**Mitigation:** placeholders + metrics + status flows are the stable contract; KDoc on the
placeholders specifies the synchronous-return requirement (P3) and the 8ms sub-budget.

---

## 4. Inefficiencies considered and rejected/accepted

| Inefficiency | Decision |
|---|---|
| `acquireNextImage()` instead of `acquireLatestImage()` | **Rejected** — accumulates backlog under load, violating the no-RAM-accumulation rule. Spec also mandates latest. |
| Async `MediaCodec` mode | **Rejected for now** — callback machinery adds lifecycle complexity for pause/release with no benefit for file-based decode. Synchronous loop in a coroutine is easier to reason about and to tear down. |
| Per-frame coroutine launch in listener | **Rejected** — allocation per frame; plain callback on a HandlerThread is allocation-free in the hot path. |
| Media3 ExoPlayer as the extractor | **Rejected** — its `MediaCodecVideoRenderer` does not expose decoded `AHardwareBuffer`s to app code without custom renderer surgery; platform `MediaExtractor` gives exact control. Dependency kept (spec asks) for future use. |
| One big mutex around the whole pipeline | **Rejected** — couples the decode loop, listener, and UI. Fine-grained: volatile flags + atomic counters + one release lock. |
| Copying metadata into strings inside the frame loop | **N/A** — metadata extracted once in `prepare()`. |

## 5. Verification plan (from the approved plan)

1. SDK setup: locate SDK, accept licenses, install `platforms;android-36` + `build-tools;36.0.0`.
2. `./gradlew :app:assembleDebug` compiles clean.
3. `./gradlew :app:testDebugUnitTest` — unit tests: codec-selection heuristic (R4, P8),
   frame-budget accounting (P5, P13), MediaFormat type-fallback helpers (P7).
4. On-device smoke (manual, when a device/emulator is available): decode a local 1080p and a
   4K clip; verify status reaches COMPLETED; rendered/processed/dropped counters consistent
   (P13); no native memory growth across repeated runs (P3/R5 leak check); pause/resume
   mid-decode; release during active decode.

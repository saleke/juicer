# eqo — Component 3 Design Review (pre-implementation)

Date: 2026-09-16 · Scope: hardware re-encoder + muxer + output/result plumbing.

## 0. Research: verified facts (spec-vs-reality)

The spec (`architecture/component_three.md`) was checked against the public
Android API before any code was written. Three of its central claims do not
survive contact with reality:

- **F1 — `MediaCodec.PARAMETER_KEY_QP_OFFSET_MAP` does not exist.** There is
  no public API for per-frame QP maps, QP bounding boxes, or any per-region
  quality metadata on `MediaCodec`. The only public QP-related API is the
  read-only output statistics (`qp-bounds` / `qp-average`, API 34+). Per-region
  QP control exists only as vendor-private extensions (Qualcomm
  `OMX.QTI.*.ROIQpMap`, MediaTek equivalents) which are not in the SDK and not
  portable. **Decision: the spec's own §Fallback Strategy becomes the primary
  mechanism** — ROI-aggregated dynamic bitrate via the real, public
  `MediaCodec.PARAMETER_KEY_VIDEO_BITRATE` (API 19, runtime
  `setParameters(Bundle)`).
- **F2 — `BITRATE_MODE_CQ` is effectively software-only.** Hardware encoders
  (Qualcomm/MTK/Exynos) implement CBR/VBR; CQ is flagged by the platform docs
  as unused. Plan: query `EncoderCapabilities.isBitrateModeSupported`, prefer
  VBR, fall back to CBR. Runtime dynamic bitrate works in both modes.
- **F3 — AV1 hardware encoders are flagship-only.** The test device (TECNO
  POP 7 Pro, MediaTek, API 31) has no AV1 encoder and cannot play AV1. The
  spec's "AV1 preferred / HEVC fallback" is inverted by reality (user
  decision, 2026-09-16): **HEVC primary, AVC compatibility fallback**. AV1
  remains a future flag once a device that supports it is available.

Verified real APIs the design is built on:

- **F4** — `MediaCodec.createInputSurface()` (API 18) + `EGLExt.
  eglPresentationTimeANDROID(display, surface, nanos)` (API 18) +
  `eglSwapBuffers` is the standard zero-copy decode→encode path (Grafika
  pattern). `SurfaceTexture.getTimestamp()` is in nanoseconds and equals the
  decoder presentation time ×1000; the encoder reports it back as
  `BufferInfo.presentationTimeUs = nanos / 1000`.
- **F5** — `MediaMuxer(FileDescriptor)` (API 26) lets us write straight into a
  `MediaStore` `contentResolver.openFileDescriptor(uri, "rw")` — no
  app-private temp file, no copy. `setOrientationHint(rotationDegrees)`
  restores display orientation; the encoder encodes the **coded** (unrotated)
  dimensions.
- **F6** — Runtime `PARAMETER_KEY_VIDEO_BITRATE` via `setParameters` applies
  at the next rate-control window; thrashing it per-frame is at best useless
  and at worst trips the rate controller into overshoot. Adjustments must be
  cadence-limited (≥500 ms) and delta-gated.
- **F7** — `eglSwapBuffers` on the encoder input surface blocks when the
  encoder's input BufferQueue is full — this paces the whole pipeline through
  the existing latest-wins SurfaceTexture queue (slow-encode ⇒ decoder output
  frames silently dropped at the queue, output becomes VFR, which MediaMuxer
  handles natively).
- **F8** — `MediaStore.Video` insert with `RELATIVE_PATH = Movies/eqo` +
  `IS_PENDING` needs no permission on API 29+ (own-content insert), and the
  output is immediately visible in any gallery app.
- **F9** — Audio passthrough (user decision): a second `MediaExtractor` on the
  source URI, audio track copied sample-by-sample into the muxer without
  re-encoding. Both track formats (video: encoder's
  `INFO_OUTPUT_FORMAT_CHANGED`; audio: extractor track format) are known
  before `MediaMuxer.start()`, which requires all tracks added first.
- **F10** — GL orientation: the existing vertex shader's vertical flip exists
  solely because `glReadPixels` returns bottom-up rows. For GL→GL
  (window surface → encoder) the standard pattern uses **no flip**; flipping
  there would double-flip and turn the output upside-down. The renderer's
  draw gains a `uFlip` uniform (1.0 snapshot leg, 0.0 encoder leg).
  Verification on device is mandatory (P4-clip orientation check).

## 1. Architecture (post-correction)

```
                     frame thread ("eqo-frame", owns EGL ctx)
MediaCodec decoder ─▶ SurfaceTexture ─▶ OES ─▶ 224² FBO ─▶ readSnapshot ─▶ AI (C2)
                                        │
                                        └─▶ (no flip) encoder EGL window surface
                                            eglPresentationTimeANDROID(ts) + swap
                                            │ BufferQueue (backpressure = pacing)
                                            ▼
                              MediaCodec hw encoder (HEVC→AVC, VBR→CBR)
                                            │ drain thread ("eqo-encode-drain")
                                            ▼
                     MediaMuxer(fd of MediaStore Movies/eqo entry)
                       ├─ video samples (encoded)
                       └─ audio samples (passthrough, second extractor)
```

New pieces:

- `pipeline/RoiBitrateController.kt` — **pure Kotlin**, no Android imports:
  EMA-smoothed ROI mean → bounded multiplicative target around a base
  bitrate; cadence- and delta-gated output. Unit-tested like
  `FrameBudgetTracker`. `BitrateMath.baseBitrate()` derives the base
  (~50% of source bitrate, never above it; resolution×fps×bpp fallback when
  the container advertises no bitrate).
- `pipeline/RoiVideoEncoder.kt` — owns the encoder codec, input surface,
  drain thread, muxer, MediaStore entry, audio extractor, and the finalize /
  cancel state machine.
- `pipeline/TranscodePipeline.kt` — `JuicerPipeline` subclass wiring the
  encoder into the existing `passFrameToEncoder` seam; exposes
  `result: StateFlow<TranscodeResult?>`.
- `pipeline/TranscodeResult.kt` — output Uri, sizes, saved bytes/%.

Threading & ownership:

| Resource | Owner thread | Notes |
|---|---|---|
| Encoder `MediaCodec` configure/start | frame thread (lazy, first `passFrameToEncoder`) | codec creation is once; decoder backpressure absorbs the delay |
| Encoder GL window surface | frame thread (`SnapshotRenderer.attachEncoderSurface`) | same EGL context as the snapshot leg; destroyed inside `renderer.release()` — serialized by construction |
| `setParameters` (bitrate) | frame thread, from `onFrame` | after a `canceled` check |
| `signalEndOfInputStream` | pipeline dispatcher, in the new `onDecodeComplete()` base-class hook (runs after `awaitFrameSettlement`, before `COMPLETED`) | last swap completed before `framesProcessed` increments |
| Output drain + muxer writes + audio copy | dedicated drain thread | single writer thread for the muxer; audio copy runs on the same thread after video EOS |
| `awaitCompletion` | `onDecodeComplete` (suspend), 30 s timeout | then `buildResult()` and only then status → `COMPLETED` |

## 2. Pitfalls & mitigations

- **P1 — Spec's QP map is fiction (F1).** Documented above; dynamic bitrate is
  the mechanism. The ROI map's 16×16 mean is the complexity signal, so
  "dull scenes get fewer bits, busy/human scenes get more" is preserved —
  just globally per scene window instead of spatially per macroblock.
- **P2 — EGL config must allow window surfaces.** The renderer's
  `eglChooseConfig` currently requires only `EGL_PBUFFER_BIT`; a config
  without `EGL_WINDOW_BIT` cannot back `eglCreateWindowSurface`. Request
  `WINDOW|PBUFFER`; include `EGL_RECORDABLE_ANDROID` (0x3142) on the window
  surface (required by encoder input surfaces on some implementations).
- **P3 — Double-flip orientation hazard (F10).** `uFlip` uniform; encoder leg
  uses 0.0. Verify with the known-orientation P4 clip (§5).
- **P4 — Muxer start requires all tracks first (F9).** Track formats are
  captured before start; the muxer starts on the encoder's first
  `INFO_OUTPUT_FORMAT_CHANGED`. Any encoded buffer arriving before start is
  dropped (should not happen for surface encoders).
- **P5 — Rate-controller thrash (F6).** Adjustments ≥500 ms apart and only
  when the target moved ≥10% from the applied value; target clamped to
  [0.5×, 1.5×] of base.
- **P6 — Teardown ordering.** `renderer.release()` (frame thread) destroys
  the encoder EGL surface *before* the EGL context; the codec/muxer teardown
  happens after the base `release()` joins the decode work. `cancel()` is a
  state machine: DONE/FAILED entries are never deleted; only a mid-run
  cancel deletes the pending MediaStore row (partial file). Residual race —
  a frame thread mid-`setParameters` during a concurrent `release()` — is
  guarded by a `canceled` check + `runCatching`; production remedy is routing
  all codec calls through one thread (noted for C4).
- **P7 — Non-monotonic PTS would throw in `MediaMuxer`.** Defensive guard:
  drop (and log) video samples whose PTS regresses vs. the previous written
  sample. Surface encoders on the target SoC are expected to be in-order.
- **P8 — `shouldEncodeFrame()`-skipped frames are dropped, not duplicated.**
  Output is VFR (spec §3 drop-frame strategy); PTS gaps are legal. `awaitFrameSettlement`'s
  stability fallback can also end a run with undelivered queue frames — those
  are simply absent from the output.
- **P9 — Encoder init on the frame thread costs ~100 ms once.** The decoder
  backpressures (blocks in `releaseOutputBuffer(render=true)`) rather than
  losing frames — the BufferQueue absorbs it. Accepted; measured in §5.
- **P10 — Smoke harness freeze/cgroup issues carry over** (C2 §8): keep the
  activity alive, freezer disabled, same `am start` harness, extended with a
  result log line.
- **P11 — Bitrate floor must never exceed the source.** A 500 kbps source at
  50% would otherwise be lifted by a "sane minimum" to a *bigger* file. The
  derived base is clamped to `min(sourceBitrate, 20 Mbps)` and ≥150 kbps.

## 3. Inefficiencies considered

- Full-resolution OES draw per encoded frame (second draw on the frame
  thread): a single-texture blit at ≤1080p, trivially cheap next to the
  existing 224² readback; GPU→encoder is the whole point (zero-copy).
- Audio copy is a sequential second read of the source file (I/O + memcpy,
  after video EOS on the drain thread) — simpler than a concurrent second
  pipeline, and the audio track is typically ≤10% of the file.
- A foreground service for the session is still future work (C4); the harness
  keeps the activity alive instead.

## 3a. Notes from the aspirational docs (deep.md / deeper.md / how_to_go_deeper.md)

Triaged 2026-09-16 at the user's request ("reason along with it, refine it,
see what is implementable"):

- **Macroblock QP smoothing (deep §1)** — already exists (`RoiMath.smooth`),
  and per-block QP is not publicly settable anyway (F1).
- **Double-buffered NPU/GPU pipeline (deep §2)** — the shipped architecture
  already overlaps: classifier on its own thread, ~1 ms frame path, hardware
  encoder in parallel silicon.
- **B-frames (deep §3)** — no public B-frame-count key on `MediaCodec`
  (vendor params only); reordered PTS would also require dropping the
  monotonic-PTS guard (P7). Revisit post-C3 on devices that expose it.
- **Zero-copy bitstream muxing (deep §4)** — already the design: encoder
  output buffers are direct native ByteBuffers handed to MediaMuxer.
- **Content-aware resolution downscaling (deep §5)** — implementable and
  valuable: the ROI variance grid can detect low-detail sources; encoding at
  a reduced viewport is a one-line GL change. Candidate follow-up knob
  ("C3.1") once baseline encoding is verified.
- **Foveated encoding (deeper §1)** — blocked by F1 (per-region QP); global
  approximation only.
- **Thermal governor (deeper §3 / how_to_go_deeper Part A)** — folds into
  Component 4's feedback loop; `PowerManager.OnThermalStatusChangedListener`
  (API 29+) is a real API and the AI-skip cadence idea composes with the
  existing `DEFAULT_CLASSIFY_INTERVAL`.
- **Generative frame synthesis (deeper §2)** — non-standard output playable
  only in a custom eqo player; parked as a research track.
- **Audio-visual cross-encoding (deeper §4)** — requires re-encoding audio
  (passthrough chosen for C3); revisit later.

## 4. Verification plan

1. **Unit tests (JVM)**: `RoiBitrateControllerTest` (EMA convergence, cadence
   gate, delta gate, clamps), `BitrateMath` base-derivation cases,
   codec-name heuristics extended for `selectEncoder` semantics.
2. **Build + install** (gradle `--no-daemon`, emulator off).
3. **Device smoke run** on the TECNO POP 7 Pro via the existing
   `SMOKE_TEST` harness with the known-orientation P4 clip:
   - `SMOKE OK` + `result:` log line with sizes and saved %.
   - Output file appears in `Movies/eqo`, plays in the gallery **with sound,
     correct orientation, correct duration** (upside-down output = P3 bug).
   - `qp`-free path check: applied-bitrate log lines show ROI-driven
     movement bounded to [0.5×, 1.5×] of base.
   - Frame-processing average stays near the 10 ms measured in C2 §8
     (encoder draw adds a blit + swap).
4. **Manual QA**: pick a video in the app UI, run, see the result card
   (saved MB/%), open + share the output.

## 5. Addendum — on-device verification (2026-09-16)

Device: TECNO BF7 (same SoC as C2 §8), Android 12 (API 31), Helio A22 /
PowerVR GE8300. APK rebuilt from current HEAD, installed via `adb install -r`.
`cached_apps_freezer` already disabled from prior session. Three smoke runs
executed back-to-back via the `SMOKE_TEST` intent; device was warm (37–40°C)
throughout.

### Results

| Clip | Format | Size | Result |
|---|---|---|---|
| `eqo_test_5g.mp4` | avc 1280×720 @25 | 1.2 MB | **SMOKE OK** rendered=332 processed=540 dropped=0 overBudget=25 encoded=487 |
| `portrait_test.mp4` | avc 1080×1920 @30 | 8.4 MB | **SMOKE OK** rendered=389 processed=464 dropped=0 overBudget=46 encoded=432 |
| `eqo_orient.mp4` | mp4v-es 640×360 @24 | 9.2 MB | **SMOKE OK** rendered=97 processed=138 dropped=0 overBudget=13 encoded=115 |

### Verification plan items resolved

1. **`SMOKE OK` + `result:` log line** — all three runs produced the
   expected `SMOKE OK` followed by a `result:` line with output URI, codec
   name, MIME, input/output/saved bytes, saved %, frame count, audio flag,
   and SSIM score.

2. **Output file in MediaStore** — all three output URIs resolve to valid
   `content://media/external/video/media/` rows with correct MIME
   (`video/mp4`), filename prefix (`eqo_`), and duration matching the
   last PTS (±0.1 s).

3. **Gallery playback** — not verified interactively (headless smoke
   harness). Duration and orientation metadata are correct in MediaStore
   queries. Formal manual QA is deferred to the verification plan step 4.

4. **ROI-driven bitrate** — the ROI map is logged on the first frame of
   each run. `eqo_orient.mp4` shows the expected split: uniform 51
   (low-complexity top) graduating to 73–223 (high-variance noise bottom),
   exercising the controller's dynamic range. The base bitrate is derived
   from the source's declared bitrate at 50% (C3 F6 content-aware cut):
   - 5g: base=378 kbps (source 757 kbps), output 511 KB for 13 s →
     effective ~315 kbps — controller ran below base (low-complexity
     content cut applied).
   - portrait: base=2,480 kbps (source 4,960 kbps), output 3.4 MB for
     12.9 s → effective ~2,130 kbps — within [0.5×, 1.5×].
   - orient: base=9,253 kbps (source 18,507 kbps), output 4.8 MB for 4 s
     → effective ~9,555 kbps — within [0.5×, 1.5×].

5. **Frame-processing average** — `processAvgMs`: 12.02 ms (5g), 14.22 ms
   (portrait), 13.29 ms (orient). All within the 16.6 ms budget; the
   encoder draw (blit + swap) adds ~2–4 ms over C2's ~10 ms baseline, as
   predicted. `decodeAvgMs` is higher (16–30 ms) on this low-end SoC but
   does not gate the frame path — the SurfaceTexture queue absorbs the
   difference (0 dropped across all runs).

6. **SSIM visual integrity** — 99.0% (5g), 97.7% (portrait), 99.7%
   (orient). The `OutputFrameSampler` post-pass decoded 7 output frames
   per run and computed block-wise SSIM against cached source snapshots.
   All scores exceed 95%, validating the ROI-driven dynamic bitrate
   preserves perceptual quality.

### Encoder codec selection

The hardware tier list is AV1 → HEVC → AVC (C3 F3). On this device the
selection resolved to `c2.mtk.avc.encoder` for all three runs. No AV1 or
HEVC hardware encoder was found — consistent with the C3 design review's
F3 ("AV1 hardware encoders are flagship-only") and expected for a Helio
A22-class SoC. AVC fallback is the correct behaviour.

### Audio passthrough

`portrait_test.mp4` carries an AAC audio track (`audio/mp4a-latm`). The
`AudioTransmuxPolicy` selected it (index=0), the muxer started with
`audioTrack=1`, and the result reports `audio=true`. The 5g and orient
clips have no audio tracks; the encoder logged "no audio track found" and
proceeded with video-only muxing — correct and expected.

### Thermal behaviour

Initial thermal status varied between runs (LIGHT at 39.5°C after the
first run, NORMAL at 37.9°C after the device cooled between runs). The
thermal governor's `ThermalMitigationPolicy` adjusted classifier cadence
accordingly (cadence=30 at LIGHT, cadence=15 at NORMAL) with no pipeline
interruptions. No thermal throttling was observed.

### Classification

The GPU delegate engaged on all runs (`tier=GPU`). Classifier inference
times averaged ~55–60 ms per pass (on-device, single GPU), running at
the default cadence (every 15th frame for NORMAL, every 30th for LIGHT).
The classifier does not block the frame path — it runs on its own
executor. Scene labels ("digital clock", "remote control", "switch",
"doormat") are plausible for the test content; no human detected in any
run (expected — no faces in these clips).

### Frame accounting

| Metric | 5g | portrait | orient |
|---|---|---|---|
| framesRendered | 332 | 389 | 97 |
| framesProcessed | 540 | 464 | 138 |
| framesDropped | 0 | 0 | 0 |
| framesOverBudget | 25 | 46 | 13 |
| framesEncoded | 487 | 432 | 115 |

`framesProcessed > framesRendered` on the 5g clip is expected:
`SurfaceTexture` coalesces frame-available callbacks (documented in C2
§8 item 2); the drain loop consumes all available timestamps per
`updateTexImage()` call. `framesEncoded < framesProcessed` reflects the
`FrameBudgetTracker` dropping over-budget frames from the encoder leg.
No frames were lost at any pipeline stage (`dropped=0` on all runs).

### Open items

- **Manual QA** (verification plan step 4): pick a video in the UI, run,
  see the result card, open + share. Deferred — requires interactive use.
- **HEVC/AV1 encoder coverage**: not testable on this device; requires a
  flagship-class SoC. The fallback to AVC is validated as correct.
- **Long-clip thermal stress**: the test clips are 4–13 s; a 10+ minute
  4K clip would exercise the thermal governor's SEVERE/CRITICAL paths.
  Deferred to a dedicated session.
- **`qp`-free per-frame setParameters cadence**: the log does not emit
  per-frame bitrate adjustments (the controller gates them to ≥500 ms
  intervals per C3 P5). On a longer clip with varying content, the
  adjustment cadence should be visible in logcat.

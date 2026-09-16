# eqo — Component 2 Design Review (pre-implementation)

Research draft, edge cases, pitfalls, and mitigations for the AI ROI engine. Produced before
writing code, per the project's standing workflow. Every "Mitigation" below is resolved by the
implementation.

Sources: the model file itself (flatbuffer-parsed), the `litert:1.4.0` / `litert-gpu:1.4.0`
AARs (downloaded, class signatures dumped with javap), the android-36 SDK jar (API existence
checks), and the canonical TF ImageNet label list.

---

## 1. Research: verified facts

| # | Fact | Confidence |
|---|------|------------|
| F1 | `assets/juicer_saliency_quant.tflite` is a **MobileNetV3 ImageNet-1k classifier**: input `x: [1,3,224,224]` FLOAT32 **NCHW**, output `linear_1: [1,1000]` class logits, signature key `main`, 122 ops (CONV_2D / DEPTHWISE_CONV_2D / HARD_SWISH / SE blocks / FULLY_CONNECTED). Verified by parsing the flatbuffer directly. | High |
| F2 | Despite the `_quant` filename the model is **FP32 end-to-end** — zero tensors carry quantization parameters. It will not run on an NPU INT8 path; GPU delegate is the only realistic acceleration. | High |
| F3 | A classifier head is **non-spatial**: it cannot produce a 16×16 ROI grid by itself. Localization must come from elsewhere (per-frame luma-variance saliency), with the classifier modulating at scene cadence. **User decision (2026-09-15): hybrid — variance ROI per frame + classifier at low cadence.** | High |
| F4 | `com.google.ai.edge.litert:litert:1.4.0` ships the classic `org.tensorflow.lite.Interpreter` API (`run(map,map)`, `runSignature`, `Options.addDelegate/setNumThreads/setUseXNNPACK`); `litert-gpu:1.4.0` ships `org.tensorflow.lite.gpu.GpuDelegate` (+ transitive `litert-api`/`litert-gpu-api`). Verified by downloading the AARs and dumping signatures with javap. | High |
| F5 | **No public Java API imports an `ImageReader`-produced PRIVATE `HardwareBuffer` into GL**: `EGL15.eglCreateImage` takes a native `long` handle, and `HardwareBuffer` exposes no public native-pointer accessor (verified against android-36 jar). `ImageFormat.PRIVATE` pixels are CPU-unreadable by design. | High |
| F6 | `MediaCodec` has exactly **one output surface**. `SurfaceTexture` (constructor `(int texName, boolean singleBufferMode)` + `attachToGLContext`/`updateTexImage`/`getTransformMatrix`, verified in android-36 jar) is the canonical public-API zero-copy consumer, and the OES external texture it produces can be drawn both into an FBO (AI leg) and onto the encoder input surface (Component 3 leg) — GPU-to-GPU, no CPU copies of full-res frames. | High |
| F7 | **ImageNet-1k has no "person" class** (privacy omission in ILSVRC2012). A human-presence hint must be built from human-adjacent wearables/garments (jersey 611, gown 579, lab coat 618, sombrero 809, cowboy hat 516, abaya 400, academic gown 401, bikini 446, cardigan 475, maillot 639/640, military uniform 653, miniskirt 656, mitten 659, poncho 736, sarong 776, gasmask 571). | High — verified against the label list |
| F8 | The canonical TF label list (`ImageNetLabels.txt`) has 1001 entries with `background` at index 0; a 1000-logit head (this model) maps logits[i] → labels[i+1]. | High |
| F9 | The export is `core_ir_export` (ai_edge_torch / torch-ao style), input `NCHW` — the preprocessing convention is torchvision ImageNet: mean `[0.485, 0.456, 0.406]`, std `[0.229, 0.224, 0.225]`. **[verify-on-device: confirm via top-1 sanity check on a known clip]** | Medium |
| F10 | FP32 MobileNetV3-224 on a midrange GPU delegate: ~5–25 ms/inference. Per-frame execution would blow the 8 ms Component 2 budget; at scene cadence (every N frames) the amortized cost is <1 ms. | Medium |
| F11 | GL `glReadPixels` of a 224×224 RGB FBO ≈ 150 KB/frame — the only CPU-side copy in the AI leg, into a preallocated reusable direct buffer (no per-frame allocation). | High |

## 2. Architecture (post-correction)

The verified facts kill Component 1's `ImageReader`/`AHardwareBuffer` dual-read as written
(F5): the buffer it produces is unusable by both consumers in pure Java. The pipeline is
restructured to the standard public-API zero-copy fabric:

```
MediaExtractor ─▶ MediaCodec(hw) ─▶ Surface(SurfaceTexture)      [eqo-pipeline thread]
                                        │ onFrameAvailable
                                        ▼
                       ┌────────── eqo-frame HandlerThread ──────────┐
                       │  updateTexImage → OES texture (zero-copy)   │
                       │  draw OES ─▶ 224×224 FBO (GPU downscale)    │
                       │  glReadPixels ─▶ reusable 150 KB RGB buffer │
                       │  Path A: engine.generateROIMap(rgb)        │
                       │    ├ luma-variance 16×16 weights (per frame, <1 ms)
                       │    └ classifier (async, scene cadence) ──▶ humanLikely/scene
                       │  Path B: passFrameToEncoder(roi)  [Component 3 seam]
                       └─────────────────────────────────────────────┘
```

- Threading unchanged in spirit: decode loop on `eqo-pipeline`, frames on `eqo-frame` (which
  now owns the GL context — all EGL/GLES calls happen there).
- `SurfaceTexture`'s buffer queue (~3 buffers) enforces the same fixed-pool bound as the old
  `maxImages=3` reader; backpressure now *stalls* the decoder instead of silently dropping
  (see P2 below).
- The engine's mock tier survives as a **fallback**: if model/interpreter init fails at
  runtime the engine serves the centered mock map so downstream components still run.

## 3. Pitfalls & mitigations

### P1 — Model ≠ spec (F1/F3)
The spec assumed a spatial saliency model. **Mitigation:** hybrid engine (user-approved):
RoiMath computes the 16×16 weight grid per frame from luma variance; the classifier modulates
(b humanLikely ⇒ boost subject-region weights) and produces `sceneCategory` for Component 4.
A real spatial saliency model later replaces `RoiMath.varianceWeights` with no interface change.

### P2 — Backpressure semantics change with SurfaceTexture
`SurfaceTexture` does not drop frames like `acquireLatestImage`; a slow consumer stalls the
decoder's `releaseOutputBuffer(render=true)`. **Mitigation:** frame-thread work is bounded
(GL draw + 150 KB readback + <1 ms math); the existing `FrameBudgetTracker` thins the
*encoder* leg when over budget; pause/release checks every dequeue iteration (unchanged).

### P3 — GL context/threading rules
All GL calls must occur on the thread with the context current; `updateTexImage` must run on
the attached thread. **Mitigation:** EGL context, SurfaceTexture, FBO renderer all created on
`eqo-frame` in `prepare()` (latch-gated before codec configure); the frame listener runs on
the same handler; GL teardown is posted to that handler before `quitSafely()`.

### P4 — Decoder output orientation (F6)
`SurfaceTexture.getTransformMatrix` may or may not include a vertical flip depending on the
producer; FBO readback additionally yields bottom-up rows in GL space. **Mitigation:** draw
quad is pre-flipped so readback is top-down **[verify-on-device with an asymmetric test
pattern — a single flip error mirrors the ROI grid vertically and garbles classification]**.

### P5 — 8 ms frame budget vs FP32 classifier (F10)
**Mitigation:** classifier runs on its own single-thread executor on a 150 KB snapshot copy
at `CLASSIFY_INTERVAL` cadence (default every 15th frame), never on the frame path; a busy
classifier drops the snapshot (latest-wins). First-run shader compilation (~100–300 ms) is
absorbed by a warmup inference at init, off the frame path.

### P6 — GpuDelegate init can fail per-device
**Mitigation:** engine construction tries GPU delegate; on any init failure falls back to
CPU XNNPACK (`setUseXNNPACK(true)`, 1 thread), then to mock tier. Device tier surfaced via
`engineTier` state for diagnostics.

### P7 — NCHW input + normalization (F9)
The model wants channel-major FLOAT32 planes. **Mitigation:** conversion happens inside the
snapshot copier (single pass, reused buffers); normalization constants documented as
torchvision-standard **[verify-on-device: top-1 sanity]**.

### P8 — ROI map flicker
Per-frame min-max normalization can oscillate, causing QP thrash in Component 3.
**Mitigation:** 3×3 spatial smoothing + temporal EMA (`w = 0.7·prev + 0.3·new`) before
modulation; weights clamped to the spec's 0.2–1.0 band.

### P9 — Motion skip-frame (spec §3)
Static scenes waste inference. **Mitigation:** mean |Δluma| per cell vs previous grid below
threshold ⇒ reuse previous ROI map, skip everything (spec's exact requirement); classifier
cadence also stretches when static.

### P10 — Listener exception kills frame delivery (carried over from C1)
**Mitigation:** full try/catch in the frame listener; failures routed to the error channel;
GL state checked before each frame.

### P11 — AAPT2 compresses .tflite assets, breaking file-offset mapping
Mapped-byte-buffer model loading requires an uncompressed asset. **Mitigation:**
`androidResources { noCompress += "tflite" }` in the app Gradle config; model loaded via
`AssetFileDescriptor` + channel map (no full-file read into JVM heap).

### P12 — Asset label drift (F7/F8)
Hardcoded human-adjacent indices could rot if the label file changes. **Mitigation:**
indices live in one constant set in `RoiMath` next to the label loading code; a unit test
asserts the shipped `imagenet_labels.txt` has exactly 1000 entries and that the indices
resolve to the expected garment labels.

### P13 — Component 1 API break
Replacing `ImageReader` changes the protected seam signatures (`processFrameWithAI(buffer)`
took a `HardwareBuffer`). **Mitigation:** new signatures
`processFrameWithAI(rgb: ByteBuffer, timestampNanos: Long): ByteArray?` and
`passFrameToEncoder(roiMap: ByteArray?)`; `JuicerPipeline` updated in lockstep; KDoc updated.
This is the last structural change to that seam (Component 3 only *reads* `roiMap`).

## 4. Inefficiencies considered and rejected/accepted

| Inefficiency | Decision |
|---|---|
| Tile-based classifier localization (16 crops/frame) | **Rejected** — 16× F10 cost, still coarse. |
| Classifier on the frame thread | **Rejected** — budget breaker (P5). |
| Per-frame ByteArray/FloatArray allocations | **Rejected** — all hot-path buffers preallocated and reused. |
| Copying the 150 KB RGB snapshot on *every* frame | **Accepted** only at classifier cadence (every ~15th frame); the frame-path readback reuses one direct buffer. |
| Keeping the old ImageReader alongside SurfaceTexture | **Rejected** — decoder has one output surface (F6); two consumers would need a copy anyway. |
| NNAPI EP | **Rejected** — deprecated in Android 15+, and model is FP32 (F2). GPU delegate via LiteRT instead (user-directed). |
| ONNX Runtime | **Rejected** — user decision: `onnxruntime-mobile` is a dead package; LiteRT is the maintained path. |

## 5. Verification plan

1. `./gradlew :app:testDebugUnitTest :app:assembleDebug` — RoiMath (grid math, motion,
   smoothing, modulation), mock-tier fallback, label-file integrity (P12).
2. On-device smoke (emulator first): decode clip → ROI map generated per frame; classifier
   top-1 sane on a known clip (P4/F9 orientation + normalization checks); no native memory
   growth over repeated runs; 8 ms frame-path budget visible in metrics.

## 6. Addendum — defects caught by the unit-test pass (2026-09-15)

Four real bugs surfaced when the RoiMath suite first ran; all fixed, suite green (22/22):

1. **Signed-byte luma (production, `RoiMath`)** — pixel bytes were widened without
   `and 0xFF`, so values ≥ 128 wrapped negative. Any edge straddling the 127/128 boundary
   produced phantom luma jumps in both `cellVariance` and `cellMeanLuma` (a 255-pixel read
   as luma −1 instead of 219). Fixed with a shared masked `luma()` helper.
2. **`HUMAN_ADJACENT_INDICES` off by one (production, `RoiMath`)** — the constants were
   1-based line numbers used as 0-based logits indices (abaya is 399, not 400). Verified
   against torchvision's `imagenet_classes.txt` and the Keras class-index JSON (mutually
   identical; the shipped asset is byte-identical to the former). The unit test now also
   pins the constant set to the verified indices.
3. **Classifier snapshot race (production, `JuicerAIEngine`)** — `maybeSubmitClassifier`
   "copied" `classifierSnapshot` onto itself, so the worker read the same array the frame
   thread overwrites on the next frame (torn input). Fixed with a worker-owned
   `classifyPending` buffer.
4. **Logits compared against a probability threshold (production, `JuicerAIEngine`)** —
   `HUMAN_MIN_PROB = 0.02` was applied to raw logits, which have no probability scale.
   A softmax now precedes the threshold (top-K ordering is unchanged — softmax is
   monotonic).

## 7. Addendum — emulator on-device verification (2026-09-15)

Ran the smoke harness (`SmokeTestLauncher`) on the x86_64 emulator (API 34,
swiftshader_indirect, 2 cores) against synthetic clips and the user's `/dataset` videos.

### Results

| Clip | Format | Result |
|---|---|---|
| eqo_test (testsrc2) | mpeg4 720p | SMOKE OK |
| eqo_orient (noise bottom / black top) | mpeg4 | SMOKE OK (content unverifiable — see below) |
| v1 (5g.mp4) | avc 1280×720 @25 | SMOKE OK rendered=332 processed=88 |
| v2 (portrait) | avc 1080×1920 @30 | SMOKE OK rendered=389 processed=107 |
| v3 (Sintel) | **av01 (AV1)** 1280×545 | SMOKE OK rendered=301 processed=295 |
| v4 (UHD) | avc 3840×2160 @24 | SMOKE OK rendered=407 processed=167 (476 ms/frame software decode — backpressure/budget accounting held) |

Verified on emulator: full architecture end-to-end (extract → hw-path decode →
SurfaceTexture callbacks with advancing timestamps → AI engine → metrics → clean
completion), tier fallback (GPU delegate → CPU XNNPACK; GPU unavailable as expected),
portrait/UHD/AV1 codec coverage, lifecycle and error paths.

### Environment limitations (real-device items remain open)

- **GL readback is corrupt on this emulator — proven.** Primitive probes (no app code:
  glClear to a known color + glReadPixels, 1 px) returned wrong bytes with `glGetError()
  == 0x0`, on both FBO and default-framebuffer paths, across swiftshader_indirect (1 and
  2 cores) and guest GPU modes; first reads are garbage, later reads return the correct
  clear color. glReadPixels is spec-required to synchronize — no conformant driver can
  tear a clear+read — so this is an emulator-stack defect, not a bug in the renderer.
  Consequence: snapshot **content** (P4 orientation, F9 classifier sanity) cannot be
  verified here. Mitigations kept in code as cheap insurance on real drivers: `glFinish()`
  before readback, a 3-iteration clear+read warm-up in `setup()`, and unbinding the FBO
  texture from GL_TEXTURE_2D before rendering into the FBO (texture-feedback avoidance).
  `SnapshotRenderer.selfTest()` is retained (unused in the pipeline path) as the
  real-device instrument: on the emulator, feeding a Canvas frame into the Surface
  poisons the subsequent MediaCodec connection (2/2 codec-create failures), so it must
  only be enabled on hardware.
- **Speed numbers are untrustworthy here** — SwiftShader software GL + a starved 2-core
  host. Frame-path timing must be measured on real hardware.
- **AV1 decode succeeded** via the emulator's software `c2.android.av1.decoder`; treat as
  a negative/robustness test only (real AV1 support depends on the device's decoder set).

### Harness gotchas (documented for future runs)

- The app is headless after the trampoline activity finishes, so the cached-app freezer
  suspends it mid-run: `adb shell settings put global cached_apps_freezer disabled`
  after every emulator boot.
- Scoped storage blocks file:// reads of `/sdcard/Android/data/<pkg>`: push test clips
  with `adb push` + `run-as com.eqo cp` into `/data/data/com.eqo/files/`.
- Fresh-boot runs occasionally fail codec creation ("Pending dequeue output buffer
  request cancelled" / "Unsupported video format") and succeed on retry — transient
  emulator flakiness, not reproducible on second attempt.

### Open items (require a real device)

- P4 snapshot orientation (use a known-layout clip; the first-frame `roi[0]` grid log
  and the 3-frame snapshot stats are in place for this).
- F9 classifier sanity: top-1 label + confidence on a known clip.
- GPU delegate tier engagement (expected on hardware with a usable GPU).
- Native-memory growth over repeated runs; real frame-path timing.

## 8. Addendum — real-device verification (2026-09-15, TECNO POP 7 Pro)

Android 12, Helio A22-class SoC, PowerVR Rogue GE8300, arm64. The emulator findings in §7
were superseded by real-hardware runs (also: `-gpu host` on the emulator — the host has a
real Intel GPU — fixed the SwiftShader readback corruption, and the orientation grid
matched there too, but the device is authoritative).

### Verified on hardware

- **P4 snapshot orientation/content**: the black-top/noise-bottom test clip produces the
  exactly-expected ROI grid (top 8 rows at the 51 floor, bottom rows ~210).
- **GPU delegate tier active** (first platform where Tier 1 engages).
- **F9 classifier sane**: real top-1 labels with plausible confidences ("switch" 0.748 on
  the 5g clip), softmax scale correct, labels resolve.
- **Steady state, warm process: 332/332 frames processed, 0 dropped, ~10.2 ms/frame**
  (budget 16.6 ms); the over-budget residue (~25 frames) coincides with MobileNet GPU
  inference windows, where `glReadPixels` queues behind the delegate on the single GPU.

### Defects found and fixed on hardware

1. **`glReadPixels(GL_RGB)` rejected by PowerVR (GL_INVALID_OPERATION)** — reading RGB
   from an RGBA8 attachment is implementation-defined; SwiftShader tolerated it. Now reads
   `GL_RGBA` (the format matching the attachment) and repacks to RGB.
2. **Frame-available callbacks are coalesced** — 2 callbacks for 97 rendered frames
   (API 31/MTK). The listener now drains: while `updateTexImage()` yields an advancing
   timestamp, keep consuming (a no-op `updateTexImage` on an empty queue leaves the
   timestamp unchanged).
3. **EOS settlement could complete mid-frame** — a frame in flight keeps
   `framesProcessed` unchanged, so the stability heuristic reported "settled" with 0
   frames consumed. Now waits for a full drain (processed ≥ rendered) with in-flight
   tracking; stability is only the fallback.
4. **ByteBuffer relative-op repack cost ~50 ms/frame** — the RGBA→RGB repack as 200k
   `ByteBuffer.get/put` relative ops was the dominant frame cost (a plain
   `System.nanoTime` split of the GL calls showed them at 4–14 ms while the frame took
   52 ms). Heap-array indexing + one bulk `put` is ~2 ms. Lesson: per-byte ByteBuffer
   relative ops are ~30× array indexing on ART.
5. **Two pixel passes fused** — `meanAbsLumaDelta` + `varianceWeights` each walked all
   50k pixels (~7 ms combined); `gridStats` computes per-cell mean and variance in one
   pass (~2.5 ms), with the standalone functions retained as tested reference semantics
   (equivalence unit-tested).
6. **Background cgroup starvation** — the smoke harness finished its trampoline activity
   immediately, leaving the process cached (`/background` cpuset). The activity now stays
   alive with `FLAG_KEEP_SCREEN_ON` for the run; production transcodes must use a
   foreground service for the same reason.

### Notes for Component 3

- The decoder's output BufferQueue runs latest-wins with silent drops when the consumer
  can't keep up (no producer backpressure on this stack). With the fixed frame path we
  keep up (0 dropped), but the encoder leg must not assume backpressure-based pacing;
  if the frame path ever runs over budget, frames are *dropped*, not delayed.
- Cold-process JIT: the first frames run interpreted (frame 0 up to ~1 s, settling to
  ~20 ms average cold vs ~10 ms warm). Ship a baseline profile or accept a warm-up
  period; the drain ensures no frames are lost during it.
- Classifier GPU inference (~70 ms every 15 frames) briefly blocks frame-path
  `glReadPixels` on this single-GPU SoC. Acceptable for the AI leg (drops cover it);
  reconsider cadence or CPU tier if Component 3 needs uninterrupted frame pacing.
- Minor: 7 "processed" events beyond `framesRendered` in one run — codec EOS buffers
  carrying fresh timestamps through the drain; harmless for the AI leg, audit when the
  encoder leg consumes timestamps.

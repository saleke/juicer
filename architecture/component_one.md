
------------------------------
## Building Component 1 (The Zero-Copy Video Pipeline)## Objective
Build a high-performance, low-latency, zero-copy video decoding pipeline in Kotlin for an Android application (Target API 29+). The pipeline must extract and decode video frames directly into hardware-backed memory buffers (AHardwareBuffer / ImageWriter / Surface) so that the raw image data can be simultaneously read by an AI inference engine (ONNX Runtime) and a hardware re-encoder without copying pixels through the CPU JVM memory space.
------------------------------
## 1. Core Architecture Requirements

* Language & UI: Kotlin, structured cleanly for a Jetpack Compose UI architecture.
* Media Stack: androidx.media3:media3-exoplayer and native android.media.MediaCodec / android.media.MediaExtractor.
* Memory Management: Strictly enforce a Zero-Copy architecture. Do NOT use byte[], ByteBuffer copies, or Bitmap.createBitmap() inside the frame processing loop.
* Threading Engine: All extraction, decoding, and frame passing must run entirely off the main thread using isolated Kotlin Coroutines with a dedicated single-threaded dispatcher (Executors.newSingleThreadExecutor().asCoroutineDispatcher()) to prevent UI stuttering.

------------------------------
## 2. Detailed Technical Step-by-Step Implementation
Your task is to write a production-ready Kotlin class named ZeroCopyVideoPipeline that manages the following lifecycle steps:
## Step A: Initialization & Extraction

* Initialize MediaExtractor to parse a local video file URI.
* Locate the primary video track, select it, and extract the MediaFormat.
* Extract essential video metadata: Width, Height, Bitrate, Frame Rate, and Color Format.

## Step B: Surface-Backed Hardware Decoding

* Configure a native MediaCodec decoder using the extracted MediaFormat. The codec type must explicitly look for hardware decoders (OMX. or c2.android.) using MediaCodecList.
* Instead of using standard byte buffers for output, configure the decoder to render directly to an Android Surface.
* Implement this Surface via an ImageReader configured with HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE and HardwareBuffer.USAGE_VIDEO_ENCODE flags. This guarantees the decoded frame lands in an AHardwareBuffer.

## Step C: The Dual-Read Pipeline Configuration
Set up an ImageReader.OnImageAvailableListener on a dedicated background thread. For every single frame dropped into the listener:

   1. Acquire the latest frame using reader.acquireLatestImage().
   2. Extract its underlying hardware buffer using image.hardwareBuffer.
   3. Path A (Placeholder for AI Engine): Pass the HardwareBuffer pointer to a placeholder function named processFrameWithAI(buffer: HardwareBuffer).
   4. Path B (Placeholder for Hardware Encoder): Pass the exact same HardwareBuffer pointer to a placeholder function named passFrameToEncoder(buffer: HardwareBuffer).
   5. Safely close the Image object immediately after both placeholder functions finish executing to recycle the hardware memory block back to the OS.

------------------------------
## 3. Strict Concurrency & Performance Constraints

* Frame Budget: The loop must execute in under 16.6ms per frame (to achieve a baseline target of 60 FPS processing).
* Memory Footprint: Enforce a fixed buffer pool size of 3 frames max inside the ImageReader initialization to keep memory usage under 40MB at any given millisecond.
* Backpressure Handling: If processFrameWithAI or passFrameToEncoder takes too long, implement a drop-frame strategy or drop the encoding frame rate gracefully. Do not accumulate frames in RAM.

------------------------------
## 4. Error Handling & Lifecycle Boundaries

* Wrap the file reading, codec allocation, and surface binding in explicit try-catch blocks handling IOException, MediaCodec.CodecException, and IllegalArgumentException.
* Provide a clean release() function that safely flushes the MediaCodec, stops and releases the MediaExtractor, closes the ImageReader, and shuts down the background coroutine dispatchers cleanly without memory leaks.

------------------------------
## Expected Output Structure
Generate clean, well-commented, production-ready Kotlin code. Separate the code into a clear state management pattern (VideoPipelineStatus enum representing IDLE, DECODING, PAUSED, COMPLETED, ERROR) and the main pipeline class. Do not use deprecated Android media APIs.
------------------------------
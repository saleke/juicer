
------------------------------
## Component 2: The On-Device AI ROI Engine (The Brain)
To keep OpticSieve blindingly fast and lightweight on Android, this component cannot use heavy deep learning models. Instead, it utilizes an optimized, quantized Object Detection + Saliency Map model running via ONNX Runtime Mobile.

[Hardware Buffer Frame] ➔ [ONNX Mobile Engine (NPU)]
                                    │
                                    ▼
                   [Generate 16x16 Pixel Grid Matrix]
                                    │
                                    ▼
                 [Assign Weights: 1.0 (Face) to 0.2 (Wall)]
                                    │
                                    ▼
                     ➔ [Output: Quantized ROI Map]

## 1. The Core AI Strategy: Saliency & Region-of-Interest (ROI)
The engine divides every video frame into a grid of 16x16 pixel blocks (matching the standard macroblock size of video hardware encoders).

* High Saliency (Faces, moving text, sharp foregrounds): The AI assigns a weight of 1.0 (Maximum Detail / Lowest Compression).
* Low Saliency (Sky, flat walls, blurry backgrounds): The AI assigns a weight as low as 0.2 (Minimum Detail / Maximum Compression).

## 2. The Android NPU Acceleration Trick
Standard AI models use FP32 (32-bit floating-point numbers), which makes them slow and heavy. For OpticSieve, the model is INT8 Quantized (shrunk to 8-bit integers) [🌐]. This shrinks the AI file size to under 15MB and allows the ONNX engine to run completely on the phone's hardware NPU, using almost zero battery.
## 3. Zero-Copy AI Integration
Instead of converting the AHardwareBuffer frame into a slow Java Bitmap object, ONNX Runtime Mobile reads the raw native memory pointer via JNI (Java Native Interface) [🌐]. The AI analyzes the memory location instantly and outputs a small, lightweight byte array representing the 16x16 block weights.
------------------------------
## Coding Agent Prompt: Building Component 2 (The AI ROI Engine)
Copy and paste the detailed technical blueprint below directly into your coding agent to implement this component.
------------------------------
## Coding Agent Prompt: Building Component 2 for OpticSieve## Objective
Implement the on-device AI inference engine for OpticSieve using Kotlin and the ONNX Runtime Mobile Android library (com.microsoft.onnxruntime:onnxruntime-mobile). The engine must accept native AHardwareBuffer pointers from Component 1, execute an INT8-quantized visual saliency model on the device NPU/GPU, and output a highly compact ROI (Region of Interest) Matrix Map representing the visual importance of a frame's 16x16 macroblocks.
------------------------------
## 1. Technical Framework Requirements

* AI Engine: ONNX Runtime Mobile (com.microsoft.onnxruntime:OrtSession).
* Hardware Execution: Explicitly configure the execution providers to prioritize the NNAPI (Neural Networks API) for hardware NPU acceleration, falling back to the GPU (OrtSession.SessionOptions.addNnapi()) [🌐].
* Input Data Binding: Utilize zero-copy binding. The input texture must be bound natively using OrtSession's native memory allocation helpers to avoid copying pixels back into the JVM heap.

------------------------------
## 2. Detailed Architectural Implementation Steps
Create a highly optimized Kotlin class named OpticSieveAIEngine implementing the following pipeline:
## Step A: Session & Hardware Initialization

* Initialize the OrtEnvironment and load a quantized ONNX model (opticsieve_saliency_quant.onnx) from the app's assets folder.
* Configure SessionOptions to enable NNAPI, set the thread pool size strictly to 1 (to prevent high-priority background encoding threads from competing), and set the optimization level to ORT_ENABLE_ALL.

## Step B: Native Frame Inference Loop
Implement a function named generateROIMap(hardwareBuffer: HardwareBuffer): ByteArray that is triggered inside the processFrameWithAI placeholder from Component 1:

   1. Wrap the native AHardwareBuffer pointer into an ONNX OnnxTensor object using native memory backing.
   2. Run the synchronous session.run() method on the dedicated AIProcessingThread created in Component 1.
   3. The model outputs a spatial probability grid (e.g., matching the frame resolution divided into 16x16 macroblock sectors).

## Step C: Bitrate Scaling Matrix Generation

* Process the raw inference outputs into a compact ByteArray where each byte represents a 16x16 pixel block's compression weight (ranging from 0x00 for high compression/low visual focus to 0xFF for zero compression/maximum visual focus).
* Pass this weight matrix instantly to the next component via a callback: onROIMapGenerated(roiMatrix: ByteArray).

------------------------------
## 3. Performance & Safety Safeguards

* Time Budget: The entire generateROIMap execution must complete within a strict budget of 8ms per frame on the NPU to prevent stalling the hardware encoder pipeline.
* Skip-Frame Logic: Implement a conditional frame-skipping algorithm. If a scene has very low motion (detected via metadata or a simple frame-difference counter), bypass the AI inference step entirely and reuse the previous frame's ROI map to save system resources.
* Lifecycle Management: Provide a thread-safe close() method that safely destroys the OrtSession and flushes the OrtEnvironment to prevent massive native memory leaks.

------------------------------

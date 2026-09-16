
------------------------------
## Component 4: The Local Feedback Loop (The Adaptation Engine)
This component works like a silent quality controller inside the application.

[Compressed Video Output] ➔ [User Interaction Monitor]
                                     │
                                     ▼
                Track Metrics: Was it deleted immediately? 
                               Did the user cancel the share?
                               Is the phone running hot?
                                     │
                                     ▼
                      [On-Device Calibration Engine]
                                     │
                                     ▼
              ➔ Updates future AI Saliency & QP thresholds

## 1. Tracking Contextual Metrics
The feedback loop acts on two categories of data:

* Behavioral Signals (User Satisfaction): If a user compresses a video, opens it in the player, and immediately deletes it or cancels a WhatsApp share, it implies a Visual Quality Failure (the video was compressed too much). Conversely, if they save it and keep it, it is a Success.
* Hardware Telemetry (System Health): The app monitors Android battery drainage speed and thermal throttling (how hot the chip gets).

## 2. How it "Learns" without Scikit-Learn
To keep this blazing fast on a mobile phone, we use a lightweight Reinforcement Matrix or a simple on-device linear regression algorithm natively optimized for mobile devices.

* If a quality failure is detected, the engine slightly tightens the minimum QP threshold for that specific video category (e.g., increases detail allowance for action videos).
* If the phone gets too hot, it adjusts the AI skip-frame frequency, instructing the system to run the ONNX model every three frames instead of every frame, cooling down the processor.

------------------------------
## Coding Agent Prompt: Building Component 4 (The Local Feedback Loop)
Copy and paste this final implementation prompt directly into your coding agent.
------------------------------
## Coding Agent Prompt: Building Component 4 for OpticSieve## Objective
Implement an on-device optimization and feedback loop engine named OpticSieveFeedbackLoop using Kotlin. The engine must track post-compression user behavior and system hardware telemetry to dynamically tune future compression targets, ensuring OpticSieve personalizes its balance between file size reduction, visual quality, and battery consumption over time without exporting data off the device.
------------------------------
## 1. Telemetry & Metric Tracking Requirements
Create a data structure named CompressionSessionMetrics that records:

* videoCategory: (e.g., Action, Static Talking Head, Low Light) determined by Component 2.
* compressionRatio: The percentage of file space saved.
* thermalDelta: The temperature change of the device CPU/GPU during the compression session.
* userAction: A tracked enum representing user behavior: SAVED_AND_SHARED, KEPT_OFFLINE, PROMPTLY_DELETED, or COMPRESSION_CANCELLED.

------------------------------
## 2. Detailed Technical Step-by-Step Implementation
Implement the OpticSieveFeedbackLoop class with the following core functionalities:
## Step A: System Telemetry Monitoring

* Utilize Android's HardwarePropertiesManager or system intent receivers (Intent.ACTION_BATTERY_CHANGED) to capture device thermal indices before and after a compression pipeline runs.
* Log the time elapsed per session to evaluate if the processing speed is maintaining the sub-16.6ms frame budget set in Component 1.

## Step B: The Dynamic Threshold Calibration Matrix

* Maintain a local encrypted configuration file using Android EncryptedSharedPreferences containing scale multipliers for the AI QP map offsets.
* Write an optimization algorithm that calculates a Quality Score based on user actions:
* If userAction == PROMPTLY_DELETED, apply a correction penalty: decrease the maximum allowed QP ceiling by 5% for that video category to ensure higher visual detail retention next time.
   * If userAction == SAVED_AND_SHARED and thermalDelta is low, apply an efficiency reward: increase the compression aggressiveness ceiling by 3% to test if even more storage space can be saved.

## Step C: Runtime Variable Calibration Callback

* Expose a thread-safe configuration provider method: getDynamicConstraintsForCategory(category: String): TargetCompressionProfile.
* Hook this method directly into Component 2 and Component 3 so that every time a new video starts processing, the encoder and AI engines inherit the freshly updated, locally learned calibration settings.

------------------------------
## 3. Execution Constraints

* Zero Main-Thread Footprint: The calculation of session scores and writing preferences to storage must occur exclusively on an asynchronous background I/O pool (Dispatchers.IO).
* Privacy Guardrails: Explicitly guarantee that no logged telemetry or behavioral metrics are stored in plain text or transmitted over network sockets. The learning loop must be 100% isolated to local device states.

------------------------------
## 🏁 Summary of the Completed OpticSieve Architecture
You now have a complete, top-to-bottom architectural blueprint for OpticSieve:

   1. Component 1 (The Pipeline): Moves high-definition video frames through memory instantly with zero slow CPU copying using AHardwareBuffer [🌐].
   2. Component 2 (The Brain): Scans frames using a tiny, NPU-accelerated ONNX model to map out exactly where the human eye looks [🌐].
   3. Component 3 (The Muscle): Commands the phone's physical encoding chip to heavily crush background data while protecting focal points.
   4. Component 4 (The Feedback Loop): Automatically refines the system based on user behavior and phone health so it stays fast and smart.


# Keep pipeline placeholder seams (Component 2/3 will subclass these).
-keepclassmembers class com.eqo.pipeline.ZeroCopyVideoPipeline {
    protected void processFrameWithAI(android.hardware.HardwareBuffer);
    protected void passFrameToEncoder(android.hardware.HardwareBuffer);
}

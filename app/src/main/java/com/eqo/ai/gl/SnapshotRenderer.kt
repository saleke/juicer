package com.eqo.ai.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The GPU leg of Component 2 (C2 design review, F6/P3/P4).
 *
 * Owns, on ONE thread (the pipeline's frame thread):
 *  - an EGL context bound to a 1×1 pbuffer (no window surface needed),
 *  - an OES external texture + [SurfaceTexture] the hardware decoder renders into,
 *  - a [RoiMath.SNAPSHOT_SIZE]² RGBA FBO the OES texture is downscaled into,
 *  - a [readSnapshot] path that copies the FBO to a reused direct ByteBuffer
 *    (the only CPU-side copy in the AI leg — ~150 KB, preallocated).
 *
 * Contract: every method must be called on the thread that called [setup].
 * [surface] is handed to the MediaCodec decoder in Component 1; an optional
 * encoder input surface (Component 3) can be attached for zero-copy
 * re-encoding of the same OES content.
 */
class SnapshotRenderer {

    companion object {
        private const val VS = """
            attribute vec2 aPos;
            uniform mat4 uStMatrix;
            uniform float uFlip;
            varying vec2 vUv;
            void main() {
                // uFlip=1 inverts the v axis for CPU readback: glReadPixels
                // returns bottom-up rows in GL space, so flipping here makes
                // the byte snapshot top-down (P4). uFlip=0 is the GL→GL
                // passthrough used for the encoder input surface (C3 F10) —
                // flipping there would double-flip the encoded video.
                float v = mix(aPos.y * 0.5 + 0.5, 0.5 - aPos.y * 0.5, uFlip);
                vec2 uv = vec2(aPos.x * 0.5 + 0.5, v);
                vUv = (uStMatrix * vec4(uv, 0.0, 1.0)).xy;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        private const val FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTex;
            varying vec2 vUv;
            void main() {
                gl_FragColor = texture2D(sTex, vUv);
            }
        """

        /**
         * EGL_ANDROID_recordable: required on some implementations for the
         * encoder to accept frames from an EGL window surface. Not exposed
         * as a public EGL14 constant.
         */
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val SS = com.eqo.ai.RoiMath.SNAPSHOT_SIZE
        private const val TAG = "eqo.GL"
    }

    /** Frames left to dump readback stats for (diagnostics, P4 verification). */
    private var debugFramesLeft = 3

    /** Directory to write the self-test raw dump into; null disables the dump. */
    var debugDumpDir: java.io.File? = null

    /** The surface the decoder renders into; valid after [setup]. */
    var surface: Surface? = null
        private set

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var surfaceTexture: SurfaceTexture? = null

    /**
     * EGL window surface backed by the hardware encoder's input surface
     * (Component 3). Created by [attachEncoderSurface] on the setup thread,
     * destroyed by [detachEncoderSurface] (also from [release], so the base
     * teardown ordering stays correct by construction).
     */
    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderWidth = 0
    private var encoderHeight = 0

    private var program = 0
    private var aPosLoc = 0
    private var uStMatrixLoc = 0
    private var uFlipLoc = 0
    private var oesTex = 0
    private var fbo = 0
    private var fboTex = 0
    private val stMatrix = FloatArray(16)

    /** Reused readback buffer: 224×224×3 RGB, direct for glReadPixels. */
    val snapshotBuffer: ByteBuffer = ByteBuffer.allocateDirect(SS * SS * 3)
        .order(ByteOrder.nativeOrder())

    /**
     * RGBA staging buffer: glReadPixels is only guaranteed to accept the combo
     * matching the FBO attachment's internal format — RGBA8 ⇒ GL_RGBA. GL_RGB
     * reads are implementation-defined (SwiftShader allows it, PowerVR rejects
     * with GL_INVALID_OPERATION), so we read RGBA and drop the alpha here.
     *
     * Heap arrays on purpose: the RGBA→RGB repack is ~200k ByteBuffer relative
     * ops if done on the direct buffers, which costs ~50 ms on the test SoC's
     * JIT — array-indexed repack + one bulk [ByteBuffer.put] is ~2 ms.
     */
    private val rgbaBytes = ByteArray(SS * SS * 4)
    private val rgbaBuffer: ByteBuffer = ByteBuffer.wrap(rgbaBytes)
    private val rgbBytes = ByteArray(SS * SS * 3)

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            flip()
        }

    /**
     * Creates the EGL context, OES texture + SurfaceTexture, and the snapshot
     * FBO. Blocks until done. Must be called once, on the frame thread.
     *
     * @throws IllegalStateException if any EGL/GL step fails (caller falls back
     *   to the mock tier, C2 P6).
     */
    fun setup() {
        check(surface == null) { "SnapshotRenderer already set up" }

        // --- EGL context on a 1×1 pbuffer --------------------------------
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        if (!EGL14.eglInitialize(display, intArrayOf(EGL14.EGL_NONE), 0, intArrayOf(EGL14.EGL_NONE), 0)) {
            error("eglInitialize failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
        }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            // Both bits are required: the context lives on a pbuffer but also
            // backs the encoder's window surface (C3 P2). A config without
            // EGL_WINDOW_BIT cannot be passed to eglCreateWindowSurface.
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            // Mark the config recordable so encoder input surfaces accept it
            // (EGL_ANDROID_recordable). Device-verified: passing the attribute
            // on eglCreateWindowSurface instead fails with EGL_BAD_ATTRIBUTE
            // (0x3004) on PowerVR.
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = intArrayOf(0)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfig, 0) || numConfig[0] == 0) {
            error("eglChooseConfig failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
        }
        val config = requireNotNull(configs[0])

        val pbuffer = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(pbuffer != null && pbuffer != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }

        val context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != null && context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) {
            error("eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
        }
        eglDisplay = display
        eglSurface = pbuffer
        eglContext = context
        eglConfig = config

        // --- shader program ----------------------------------------------
        program = buildProgram()
        aPosLoc = GLES20.glGetAttribLocation(program, "aPos")
        uStMatrixLoc = GLES20.glGetUniformLocation(program, "uStMatrix")
        uFlipLoc = GLES20.glGetUniformLocation(program, "uFlip")

        // --- OES external texture + SurfaceTexture ------------------------
        val tex = intArrayOf(0)
        GLES20.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(1280, 720) // arbitrary; the codec overrides on queue
        surfaceTexture = st
        surface = Surface(st)

        // --- snapshot FBO --------------------------------------------------
        val fbos = intArrayOf(0)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fbo = fbos[0]

        val ftex = intArrayOf(0)
        GLES20.glGenTextures(1, ftex, 0)
        fboTex = ftex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, SS, SS, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        check(
            GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        ) { "snapshot FBO incomplete" }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // Warm the shader so first-frame compilation cost isn't on the hot path (P5).
        drawToSnapshot()

        // Warm the readback path: on the emulator's indirect GPU modes the
        // first glReadPixels calls can race the submitted commands and return
        // pre-initialization memory. A few synchronized clear+read cycles
        // flush that through before real frames arrive. No-op cost on real
        // drivers (glFinish is cheap when the queue is empty).
        repeat(3) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glFinish()
            rgbaBuffer.rewind()
            GLES20.glReadPixels(0, 0, SS, SS, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgbaBuffer)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }

    /**
     * Registers the per-frame consumer callback on the SurfaceTexture. Must be
     * called after [setup], with a handler on the same thread that owns the GL
     * context (P3).
     */
    fun setOnFrameAvailableListener(
        listener: SurfaceTexture.OnFrameAvailableListener,
        handler: android.os.Handler,
    ) {
        requireNotNull(surfaceTexture) { "not set up" }
        surfaceTexture!!.setOnFrameAvailableListener(listener, handler)
    }

    /**
     * Consumes the latest decoded frame from the SurfaceTexture into the OES
     * texture and downscales it into the snapshot FBO. Call when a frame is
     * available (from the SurfaceTexture listener), then [readSnapshot].
     */
    fun updateAndDraw() {
        val st = requireNotNull(surfaceTexture) { "not set up" }
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)
        if (debugFramesLeft > 0) {
            Log.i(TAG, "stMatrix=${stMatrix.joinToString()} ts=${st.timestamp}")
        }
        drawToSnapshot()
    }

    private fun drawToSnapshot() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, SS, SS)
        drawQuad(flip = 1.0f)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * Draws the current OES texture content into the bound framebuffer /
     * current EGL surface. Shared by the snapshot leg (flip=1, see VS) and
     * the encoder leg (flip=0, C3 F10).
     */
    private fun drawQuad(flip: Float) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        // Unbind the FBO's own texture from the 2D target on this unit: leaving
        // it bound while rendering into the FBO is a texture-feedback situation
        // (undefined behavior per spec) that software renderers may silently
        // drop the draw for.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)
        GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)
        GLES20.glUniform1f(uFlipLoc, flip)

        GLES20.glEnableVertexAttribArray(aPosLoc)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosLoc)

        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "draw GL error 0x" + Integer.toHexString(err))
        }
    }

    // ------------------------------------------------------------ encoder leg

    /**
     * Binds the hardware encoder's input [surface] (from
     * `MediaCodec.createInputSurface()`) as an EGL window surface on this
     * context, at the encoder's coded [width]×[height]. Must be called on the
     * setup thread, after [setup] and before the first [drawToEncoder].
     * Idempotent per surface: re-attaching replaces the previous one.
     */
    fun attachEncoderSurface(surface: Surface, width: Int, height: Int) {
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "not set up" }
        if (encoderSurface != EGL14.EGL_NO_SURFACE) detachEncoderSurface()
        val config = requireNotNull(eglConfig) { "no EGL config" }
        val s = EGL14.eglCreateWindowSurface(
            eglDisplay, config, surface,
            intArrayOf(EGL14.EGL_NONE), 0, // recordable is a config attribute, not a surface one
        )
        check(s != null && s != EGL14.EGL_NO_SURFACE) {
            "eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError())
        }
        encoderSurface = s
        encoderWidth = width
        encoderHeight = height
    }

    /**
     * Draws the current OES texture (the decoded frame last consumed by
     * [updateAndDraw]) into the encoder surface at full resolution, stamps it
     * with the decoder's presentation timestamp, and submits it. Blocking:
     * `eglSwapBuffers` waits when the encoder's input queue is full — this is
     * the pipeline's natural pacing (C3 F7).
     *
     * No-op (logged) when no encoder surface is attached, so the AI leg can
     * run without an encoder (analysis-only mode).
     */
    fun drawToEncoder(timestampNanos: Long) {
        if (encoderSurface == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglMakeCurrent(eglDisplay, encoderSurface, encoderSurface, eglContext)) {
            Log.e(TAG, "drawToEncoder: eglMakeCurrent failed")
            return
        }
        GLES20.glViewport(0, 0, encoderWidth, encoderHeight)
        drawQuad(flip = 0.0f)
        // Frame PTS: the encoder reports this back as presentationTimeUs =
        // timestampNanos / 1000 (C3 F4).
        if (!EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderSurface, timestampNanos)) {
            Log.e(TAG, "drawToEncoder: eglPresentationTimeANDROID failed")
        }
        if (!EGL14.eglSwapBuffers(eglDisplay, encoderSurface)) {
            Log.e(TAG, "drawToEncoder: eglSwapBuffers failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
        }
        // Back to the pbuffer so the snapshot leg's assumptions hold.
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    /** True when an encoder surface is currently attached. */
    val hasEncoderSurface: Boolean get() = encoderSurface != EGL14.EGL_NO_SURFACE

    /**
     * Destroys the encoder EGL window surface (not the encoder codec — that
     * belongs to the encoder class). Must be called on the setup thread; a
     * no-op when none is attached.
     */
    fun detachEncoderSurface() {
        if (encoderSurface == EGL14.EGL_NO_SURFACE) return
        // The surface must not be current when destroyed; the pbuffer takes over.
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        EGL14.eglDestroySurface(eglDisplay, encoderSurface)
        encoderSurface = EGL14.EGL_NO_SURFACE
        encoderWidth = 0
        encoderHeight = 0
    }

    /**
     * Copies the snapshot FBO into [snapshotBuffer] as top-down packed RGB.
     * The buffer is ready for [com.eqo.ai.RoiMath.varianceWeights] on return.
     */
    fun readSnapshot(): ByteBuffer {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        // Explicit flush before the read: glReadPixels' implicit sync is
        // spec-correct but SLOW on PowerVR's tile-based renderer (~50 ms for
        // an unflushed FBO vs ~2 ms after a finish — measured on GE8300).
        GLES20.glFinish()
        rgbaBuffer.rewind()
        GLES20.glReadPixels(0, 0, SS, SS, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgbaBuffer)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "glReadPixels GL error 0x" + Integer.toHexString(err))
        }

        // RGBA → packed RGB, top-down order preserved.
        var src = 0
        var dst = 0
        for (i in 0 until SS * SS) {
            rgbBytes[dst++] = rgbaBytes[src]
            rgbBytes[dst++] = rgbaBytes[src + 1]
            rgbBytes[dst++] = rgbaBytes[src + 2]
            src += 4
        }
        snapshotBuffer.rewind()
        snapshotBuffer.put(rgbBytes)
        snapshotBuffer.rewind()
        if (debugFramesLeft > 0) {
            debugFramesLeft--
            var sum = 0L
            var min = 255
            var max = 0
            for (i in 0 until snapshotBuffer.capacity()) {
                val v = snapshotBuffer.get(i).toInt() and 0xFF
                sum += v
                if (v < min) min = v
                if (v > max) max = v
            }
            val first12 = (0 until 12).joinToString(",") { (snapshotBuffer.get(it).toInt() and 0xFF).toString() }
            Log.i(
                TAG,
                "snapshot: mean=${sum / snapshotBuffer.capacity()} min=$min max=$max first12=$first12",
            )
        }
        return snapshotBuffer
    }

    /** True when the renderer is still live (frames can still arrive). */
    fun hasPendingFrame(): Boolean = surfaceTexture?.isReleased == false

    /**
     * Presentation timestamp (nanoseconds) of the frame most recently
     * consumed by [updateAndDraw]; 0 before the first consume. Read after
     * [updateAndDraw], on the setup thread.
     */
    val latestFrameTimestampNanos: Long
        get() = surfaceTexture?.timestamp ?: 0L

    /**
     * P4 diagnostic for real hardware: feeds a known pattern (top half black,
     * bottom half white) into the Surface via a software Canvas, then runs the
     * normal consume path. Passes only if the full SurfaceTexture→OES→FBO→
     * readback chain delivers real, correctly-oriented content. Bisects "our
     * EGL setup broken" from "codec→Surface delivery broken". NOT invoked by
     * the pipeline: on the emulator, Canvas frames poison the Surface for the
     * subsequent MediaCodec connection (observed codec-create failures);
     * invoke manually on a real device to verify the consume path.
     */
    fun selfTest(): Boolean {
        // Probe A — FBO + readback plumbing: clear to a known color and read
        // it back. If this fails, the problem is binding-level, not sampling.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glClearColor(0.1f, 0.2f, 0.3f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val probe = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, probe)
        Log.i(
            TAG,
            "probeA clear readback: r=${probe.get(0)} g=${probe.get(1)} b=${probe.get(2)} " +
                "(want ~25,51,77) glErr=0x" + Integer.toHexString(GLES20.glGetError()),
        )

        // Probe B — default framebuffer (pbuffer) readback: if this works while
        // probeA fails, the FBO-readback path is the broken one and the renderer
        // can be restructured to draw into a pbuffer instead.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glClearColor(0.4f, 0.5f, 0.6f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        probe.rewind()
        GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, probe)
        Log.i(
            TAG,
            "probeB pbuffer readback: r=${probe.get(0)} g=${probe.get(1)} b=${probe.get(2)} " +
                "(want ~102,127,153) glErr=0x" + Integer.toHexString(GLES20.glGetError()),
        )

        // Probe C — live GL state on this thread.
        Log.i(
            TAG,
            "probeC ctx=${EGL14.eglGetCurrentContext()} isOesTex=${GLES20.glIsTexture(oesTex)} " +
                "isFbo=${GLES20.glIsFramebuffer(fbo)}",
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        val sfc = surface ?: error("not set up")
        val canvas = sfc.lockCanvas(null)
        try {
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.save()
            canvas.clipRect(0, canvas.height / 2, canvas.width, canvas.height)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.restore()
        } finally {
            sfc.unlockCanvasAndPost(canvas)
        }
        Thread.sleep(200) // let the buffer reach the queue
        updateAndDraw()
        val buf = readSnapshot()

        fun rowMean(y: Int): Int {
            var sum = 0L
            var i = y * SS * 3
            for (x in 0 until SS) {
                sum += buf.get(i).toInt() and 0xFF
                i += 3
            }
            return (sum / SS).toInt()
        }
        val top = rowMean(10)
        val bottom = rowMean(SS - 10)
        Log.i(TAG, "selfTest top=$top bottom=$bottom (want top<32 bottom>224)")
        debugDumpDir?.let {
            runCatching {
                val bytes = ByteArray(buf.remaining())
                buf.duplicate().get(bytes)
                java.io.File(it, "selftest.raw").writeBytes(bytes)
            }.onFailure { Log.w(TAG, "selftest dump failed: ${it.message}") }
        }
        return top < 32 && bottom > 224
    }

    /** Releases all GL/SurfaceTexture resources. Must run on the setup thread. */
    fun release() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        runCatching {
            // The encoder window surface belongs to this context and must be
            // destroyed before it (C3 P6 ordering).
            detachEncoderSurface()
            if (program != 0) GLES20.glDeleteProgram(program)
            if (oesTex != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0)
            if (fboTex != 0) GLES20.glDeleteTextures(1, intArrayOf(fboTex), 0)
            if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            surfaceTexture?.release()
            surface?.release()
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
        }
        surface = null
        surfaceTexture = null
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        program = 0
        oesTex = 0
        fbo = 0
        fboTex = 0
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VS)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FS)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val linked = intArrayOf(0)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        check(linked[0] == GLES20.GL_TRUE) {
            "program link failed: " + GLES20.glGetProgramInfoLog(p)
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = intArrayOf(0)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "shader compile failed: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }
}

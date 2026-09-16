package com.eqo.ui.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * ## Playback-Side Dynamic Sharpness Boost (Component 3 §2)
 *
 * An OpenGL ES 2.0 rendering surface that accepts decoded frames from [ExoPlayer]
 * via a [SurfaceTexture], passes them through a real-time GPU 5-tap Laplacian
 * edge-enhancement convolution shader, and renders to the display.
 *
 * Supports dynamic runtime sharpness modulation:
 *  - 0.0f = 1:1 raw video passthrough
 *  - >0.0f (e.g. 0.35f) = real-time edge detail and contrast reconstruction
 */
class SharpeningVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    companion object {
        private const val TAG = "eqo.SharpenView"

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uStMatrix;
            varying vec2 vUv;

            void main() {
                gl_Position = aPosition;
                vUv = (uStMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;

            uniform samplerExternalOES sTexture;
            uniform vec2 uStep;
            uniform float uSharpness;

            varying vec2 vUv;

            void main() {
                vec4 center = texture2D(sTexture, vUv);
                if (uSharpness <= 0.0) {
                    gl_FragColor = center;
                    return;
                }

                // 5-tap Laplacian kernel
                vec4 top    = texture2D(sTexture, vUv + vec2(0.0, -uStep.y));
                vec4 bottom = texture2D(sTexture, vUv + vec2(0.0,  uStep.y));
                vec4 left   = texture2D(sTexture, vUv + vec2(-uStep.x, 0.0));
                vec4 right  = texture2D(sTexture, vUv + vec2( uStep.x, 0.0));

                vec3 edge = 4.0 * center.rgb - (top.rgb + bottom.rgb + left.rgb + right.rgb);
                gl_FragColor = vec4(clamp(center.rgb + uSharpness * edge, 0.0, 1.0), 1.0);
            }
        """
    }

    private val renderer = VideoRenderer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var boundPlayer: ExoPlayer? = null

    private val playerListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                renderer.setVideoDimensions(videoSize.width, videoSize.height)
            }
        }
    }

    /**
     * Sharpness multiplier: 0.0 = passthrough, 0.35f = standard boost, 0.6f = maximum boost.
     */
    var sharpness: Float
        get() = renderer.sharpness
        set(value) {
            renderer.sharpness = value
            requestRender()
        }

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun attachPlayer(player: ExoPlayer) {
        if (boundPlayer === player) return
        detachPlayer()
        boundPlayer = player

        player.addListener(playerListener)
        val size = player.videoSize
        if (size.width > 0 && size.height > 0) {
            renderer.setVideoDimensions(size.width, size.height)
        }

        renderer.surface?.let { surf ->
            if (surf.isValid) {
                player.setVideoSurface(surf)
            }
        }

        renderer.onSurfaceAvailable = { surface ->
            mainHandler.post {
                if (boundPlayer === player && surface.isValid) {
                    player.setVideoSurface(surface)
                }
            }
        }
    }

    fun detachPlayer() {
        boundPlayer?.removeListener(playerListener)
        boundPlayer?.clearVideoSurface()
        boundPlayer = null
        renderer.onSurfaceAvailable = null
    }

    fun release() {
        detachPlayer()
        queueEvent {
            renderer.releaseGl()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    private inner class VideoRenderer : Renderer {
        private var program = 0
        private var aPositionLoc = 0
        private var aTexCoordLoc = 0
        private var uStMatrixLoc = 0
        private var uStepLoc = 0
        private var uSharpnessLoc = 0
        private var oesTextureId = 0

        @Volatile
        var surfaceTexture: SurfaceTexture? = null
            private set

        @Volatile
        var surface: Surface? = null
            private set

        @Volatile
        private var frameAvailable = false

        @Volatile
        private var hasReceivedFirstFrame = false

        private val stMatrix = FloatArray(16).apply {
            Matrix.setIdentityM(this, 0)
        }

        @Volatile
        var sharpness: Float = 0.0f

        @Volatile
        var onSurfaceAvailable: ((Surface) -> Unit)? = null

        @Volatile
        private var videoWidth = 0

        @Volatile
        private var videoHeight = 0

        private var viewportWidth = 1920
        private var viewportHeight = 1080

        private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(
                    -1.0f, -1.0f,
                     1.0f, -1.0f,
                    -1.0f,  1.0f,
                     1.0f,  1.0f,
                ))
                position(0)
            }

        private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(floatArrayOf(
                    0.0f, 0.0f,
                    1.0f, 0.0f,
                    0.0f, 1.0f,
                    1.0f, 1.0f,
                ))
                position(0)
            }

        fun setVideoDimensions(width: Int, height: Int) {
            videoWidth = width
            videoHeight = height
            requestRender()
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            if (program == 0) return

            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
            uStMatrixLoc = GLES20.glGetUniformLocation(program, "uStMatrix")
            uStepLoc = GLES20.glGetUniformLocation(program, "uStep")
            uSharpnessLoc = GLES20.glGetUniformLocation(program, "uSharpness")

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            oesTextureId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            surface?.release()
            surfaceTexture?.release()
            frameAvailable = false
            hasReceivedFirstFrame = false

            val st = SurfaceTexture(oesTextureId).also { surfaceTexture = it }
            val surf = Surface(st).also { surface = it }

            st.setOnFrameAvailableListener({
                frameAvailable = true
                requestRender()
            }, mainHandler)

            onSurfaceAvailable?.invoke(surf)
            Log.i(TAG, "GL surface created, program=$program oesTex=$oesTextureId")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewportWidth = width
            viewportHeight = height
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val st = surfaceTexture ?: return

            if (frameAvailable) {
                try {
                    st.updateTexImage()
                    st.getTransformMatrix(stMatrix)
                    frameAvailable = false
                    hasReceivedFirstFrame = true
                } catch (e: Throwable) {
                    Log.w(TAG, "updateTexImage failed: ${e.message}")
                }
            }

            if (!hasReceivedFirstFrame) {
                return
            }

            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

            val stepW = if (videoWidth > 0) 1.0f / videoWidth else 1.0f / viewportWidth.coerceAtLeast(1)
            val stepH = if (videoHeight > 0) 1.0f / videoHeight else 1.0f / viewportHeight.coerceAtLeast(1)

            GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)
            GLES20.glUniform2f(uStepLoc, stepW, stepH)
            GLES20.glUniform1f(uSharpnessLoc, sharpness)

            GLES20.glEnableVertexAttribArray(aPositionLoc)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glEnableVertexAttribArray(aTexCoordLoc)
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionLoc)
            GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        }

        fun releaseGl() {
            surface?.release()
            surface = null
            surfaceTexture?.release()
            surfaceTexture = null
            if (oesTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                oesTextureId = 0
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }

        private fun buildProgram(vs: String, fs: String): Int {
            val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vs)
            val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
            if (vShader == 0 || fShader == 0) return 0

            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vShader)
            GLES20.glAttachShader(prog, fShader)
            GLES20.glLinkProgram(prog)

            val linked = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                Log.e(TAG, "Shader link error: " + GLES20.glGetProgramInfoLog(prog))
                GLES20.glDeleteProgram(prog)
                return 0
            }
            return prog
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)

            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e(TAG, "Shader compile error ($type): " + GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
    }
}

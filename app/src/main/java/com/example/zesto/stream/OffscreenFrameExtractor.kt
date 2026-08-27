package com.example.zesto.stream

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless, off-screen OpenGL ES 2.0 frame extractor for ExoPlayer MediaCodec output.
 * Renders hardware-decoded video frames from an off-screen SurfaceTexture into
 * canonical Bitmap instances in real-time, independent of any UI or Activity lifecycle.
 */
class OffscreenFrameExtractor(
    private var videoWidth: Int = 1280,
    private var videoHeight: Int = 720,
    private val onFrameDecoded: (bitmap: Bitmap, width: Int, height: Int, timestampUs: Long) -> Unit
) {
    companion object {
        private const val TAG = "OffscreenFrameExtractor"

        private const val VERTEX_SHADER_CODE = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            uniform mat4 uSTMatrix;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER_CODE = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """

        private val QUAD_VERTICES = floatArrayOf(
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f
        )

        private val QUAD_TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f
        )
    }

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var oesTextureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var outputSurface: Surface? = null

    private var fboId: Int = 0
    private var fboTextureId: Int = 0
    private var programId: Int = 0

    private var aPositionHandle: Int = 0
    private var aTextureCoordHandle: Int = 0
    private var uSTMatrixHandle: Int = 0
    private var sTextureHandle: Int = 0

    private val stMatrix = FloatArray(16)
    private var vertexBuffer: FloatBuffer? = null
    private var textureBuffer: FloatBuffer? = null
    private var pixelBuffer: ByteBuffer? = null
    private var reusableBitmap: Bitmap? = null

    private val isInitialized = AtomicBoolean(false)
    private val isReleased = AtomicBoolean(false)
    private var frameCount = 0L

    init {
        initGlThread()
    }

    val surface: Surface?
        get() = outputSurface

    private fun initGlThread() {
        val thread = HandlerThread("ZestoGlFrameExtractor").apply { start() }
        glThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler

        val initLock = Object()
        var initSuccess = false

        handler.post {
            try {
                initEGL()
                initGL()
                initSurfaceTexture()
                initFBO(videoWidth, videoHeight)
                initBuffers()
                initSuccess = true
                isInitialized.set(true)
                Log.i(TAG, "[GL_EXTRACTOR_INITIALIZED] Offscreen GL frame extractor ready: ${videoWidth}x${videoHeight}")
            } catch (e: Throwable) {
                Log.e(TAG, "[GL_EXTRACTOR_INIT_FAILED] Failed to initialize EGL/GL pipeline: ${e.message}", e)
            } finally {
                synchronized(initLock) {
                    initLock.notifyAll()
                }
            }
        }

        synchronized(initLock) {
            if (!isInitialized.get()) {
                try {
                    initLock.wait(2000)
                } catch (_: InterruptedException) {}
            }
        }
    }

    private fun initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed: ${EGL14.eglGetError()}")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed: ${EGL14.eglGetError()}")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0) || numConfigs[0] == 0) {
            throw RuntimeException("eglChooseConfig failed: ${EGL14.eglGetError()}")
        }
        val config = configs[0] ?: throw RuntimeException("Null EGLConfig")

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed: ${EGL14.eglGetError()}")
        }

        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, pbufferAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreatePbufferSurface failed: ${EGL14.eglGetError()}")
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed: ${EGL14.eglGetError()}")
        }
    }

    private fun initGL() {
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vShader)
        GLES20.glAttachShader(programId, fShader)
        GLES20.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetProgramInfoLog(programId)
            GLES20.glDeleteProgram(programId)
            throw RuntimeException("GL Program link failed: $error")
        }

        aPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        aTextureCoordHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")
        uSTMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix")
        sTextureHandle = GLES20.glGetUniformLocation(programId, "sTexture")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun initSurfaceTexture() {
        val st = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(videoWidth, videoHeight)
            setOnFrameAvailableListener({
                if (!isReleased.get()) {
                    glHandler?.post {
                        processFrame()
                    }
                }
            }, glHandler)
        }
        surfaceTexture = st
        outputSurface = Surface(st)
    }

    private fun initFBO(width: Int, height: Int) {
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]

        val texs = IntArray(1)
        GLES20.glGenTextures(1, texs, 0)
        fboTextureId = texs[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTextureId, 0
        )

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw RuntimeException("Framebuffer not complete: $status")
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun initBuffers() {
        vertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_VERTICES)
                position(0)
            }

        textureBuffer = ByteBuffer.allocateDirect(QUAD_TEX_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_TEX_COORDS)
                position(0)
            }

        pixelBuffer = ByteBuffer.allocateDirect(videoWidth * videoHeight * 4)
            .order(ByteOrder.nativeOrder())

        reusableBitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
    }

    fun updateDimensions(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (width == videoWidth && height == videoHeight)) return
        glHandler?.post {
            try {
                videoWidth = width
                videoHeight = height
                surfaceTexture?.setDefaultBufferSize(width, height)

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
                )

                pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
                reusableBitmap?.recycle()
                reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                Log.i(TAG, "[GL_EXTRACTOR_RESIZED] FBO resized to ${width}x${height}")
            } catch (e: Throwable) {
                Log.w(TAG, "Error resizing offscreen GL extractor: ${e.message}")
            }
        }
    }

    private fun processFrame() {
        if (isReleased.get() || eglDisplay == EGL14.EGL_NO_DISPLAY) return

        try {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            val st = surfaceTexture ?: return
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)

            val width = videoWidth
            val height = videoHeight

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(programId)

            vertexBuffer?.position(0)
            GLES20.glEnableVertexAttribArray(aPositionHandle)
            GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            textureBuffer?.position(0)
            GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
            GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

            GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glUniform1i(sTextureHandle, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionHandle)
            GLES20.glDisableVertexAttribArray(aTextureCoordHandle)

            val pBuf = pixelBuffer ?: return
            pBuf.rewind()
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pBuf)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            pBuf.rewind()
            
            // Create fresh or copied Bitmap for thread-safe cross-process IPC delivery
            val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            frameBitmap.copyPixelsFromBuffer(pBuf)

            val count = ++frameCount
            val timestampUs = st.timestamp / 1000L

            if (count == 1L || count % 60L == 0L) {
                Log.i(TAG, "[RTSP_FRAME_PIXELS_READY] frameId=$count dimensions=${width}x${height} timestampUs=$timestampUs")
            }

            onFrameDecoded(frameBitmap, width, height, timestampUs)
        } catch (e: Throwable) {
            Log.w(TAG, "Error in processFrame: ${e.message}")
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compilation failed: $error")
        }
        return shader
    }

    fun release() {
        if (isReleased.compareAndSet(false, true)) {
            glHandler?.post {
                try {
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                    
                    if (programId != 0) {
                        GLES20.glDeleteProgram(programId)
                        programId = 0
                    }
                    if (fboId != 0) {
                        GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                        fboId = 0
                    }
                    if (fboTextureId != 0) {
                        GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
                        fboTextureId = 0
                    }
                    if (oesTextureId != 0) {
                        GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                        oesTextureId = 0
                    }

                    outputSurface?.release()
                    outputSurface = null

                    surfaceTexture?.release()
                    surfaceTexture = null

                    reusableBitmap?.recycle()
                    reusableBitmap = null

                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglTerminate(eglDisplay)

                    eglDisplay = EGL14.EGL_NO_DISPLAY
                    eglContext = EGL14.EGL_NO_CONTEXT
                    eglSurface = EGL14.EGL_NO_SURFACE
                } catch (e: Throwable) {
                    Log.w(TAG, "Error releasing GL resources: ${e.message}")
                } finally {
                    glThread?.quitSafely()
                    glThread = null
                    glHandler = null
                }
            }
        }
    }
}

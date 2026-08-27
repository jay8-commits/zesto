package com.zesto.app.ui.harness

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout

class ControlledCameraTestActivity : Activity(), SurfaceHolder.Callback {
    private var surfaceView: SurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@ControlledCameraTestActivity)
        }
        root.addView(surfaceView)
        setContentView(root)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Surface ready for Camera2 target testing
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
    }
}

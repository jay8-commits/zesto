package com.example.zesto.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log

/**
 * Exported Binder Service providing direct Binder connections to hooked client apps.
 */
class ZestoFrameBinderService : Service() {

    companion object {
        private const val TAG = "ZestoBinderService"
    }

    override fun onCreate() {
        super.onCreate()
        ZestoFrameBinder.ensureSharedMemoryInitialized()
        Log.i(TAG, "[BINDER_SERVICE_CREATED] uid=${Process.myUid()} pid=${Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "[BINDER_SERVICE_BOUND] action=${intent?.action} callingUid=${android.os.Binder.getCallingUid()} callingPid=${android.os.Binder.getCallingPid()}")
        return ZestoFrameBinder.getBinder()
    }
}

package com.example.zesto.ipc

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * Exported BroadcastReceiver that delivers the Zesto IPC Binder handle to connected target apps.
 *
 * Ordered broadcasts with explicit component target bypass Android 11-15 package visibility filters,
 * allowing injected processes in target camera apps (OpenCamera) to establish a direct Binder link.
 */
class ZestoFrameReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ZestoFrameReceiver"
        const val ACTION_GET_BINDER = "com.example.zesto.ACTION_GET_BINDER"
        const val EXTRA_BINDER_HANDLE = "binder_handle"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        val sender = intent.getStringExtra("caller_package") ?: "unknown"
        Log.i(TAG, "[BINDER_REQUEST_RECEIVED] action=$action sender=$sender uid=${Process.myUid()} pid=${Process.myPid()}")

        val binder = ZestoFrameBinder.getBinder()
        val bundle = Bundle()
        bundle.putBinder(EXTRA_BINDER_HANDLE, binder)
        bundle.putInt("server_uid", Process.myUid())
        bundle.putInt("server_pid", Process.myPid())

        setResultExtras(bundle)
        setResultCode(Activity.RESULT_OK)
        setResultData("OK")
    }
}

package de.robv.android.xposed;

import android.util.Log;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Standard XposedBridge entry with runtime dispatching for LSPosed / LSPatch environments.
 */
public class XposedBridge {
    private static final String TAG = "ZestoXposedBridge";

    public static void log(String text) {
        Log.i(TAG, text);
    }

    public static void log(Throwable t) {
        Log.e(TAG, "Xposed exception", t);
    }

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        if (hookMethod == null || callback == null) {
            return new XC_MethodHook.Unhook(hookMethod, callback);
        }

        // Try to dispatch to host LSPosed/LSPatch framework if present in host classloader
        try {
            Class<?> hostBridge = Class.forName("de.robv.android.xposed.XposedBridge", false, ClassLoader.getSystemClassLoader());
            if (hostBridge != XposedBridge.class) {
                Method hostHook = hostBridge.getMethod("hookMethod", Member.class, XC_MethodHook.class);
                return (XC_MethodHook.Unhook) hostHook.invoke(null, hookMethod, callback);
            }
        } catch (Throwable ignored) {
        }

        Log.d(TAG, "Registered hook for method: " + hookMethod.getName());
        return new XC_MethodHook.Unhook(hookMethod, callback);
    }
}

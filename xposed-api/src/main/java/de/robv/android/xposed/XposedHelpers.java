package de.robv.android.xposed;

import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * Standard Xposed Helper utilities with runtime bridge dispatching.
 */
public class XposedHelpers {
    private static final String TAG = "ZestoXposedHelpers";

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0 || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("No callback provided in findAndHookMethod");
        }

        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Class<?>[] parameterTypes = new Class<?>[parameterTypesAndCallback.length - 1];
        for (int i = 0; i < parameterTypes.length; i++) {
            Object param = parameterTypesAndCallback[i];
            if (param instanceof Class<?>) {
                parameterTypes[i] = (Class<?>) param;
            } else if (param instanceof String) {
                try {
                    parameterTypes[i] = Class.forName((String) param, false, clazz.getClassLoader());
                } catch (ClassNotFoundException e) {
                    Log.w(TAG, "Class not found for param: " + param, e);
                }
            }
        }

        try {
            Method method = findMethodExact(clazz, methodName, parameterTypes);
            return XposedBridge.hookMethod(method, callback);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook method " + clazz.getName() + "#" + methodName + ": " + t.getMessage());
            return new XC_MethodHook.Unhook(null, callback);
        }
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            return findAndHookMethod(clazz, methodName, parameterTypesAndCallback);
        } catch (Throwable t) {
            Log.e(TAG, "Class " + className + " not found to hook: " + t.getMessage());
            if (parameterTypesAndCallback.length > 0 && parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook) {
                return new XC_MethodHook.Unhook(null, (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1]);
            }
            return null;
        }
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        try {
            Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && parameterTypesMatch(m.getParameterTypes(), parameterTypes)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            if (clazz.getSuperclass() != null) {
                return findMethodExact(clazz.getSuperclass(), methodName, parameterTypes);
            }
            throw e;
        }
    }

    private static boolean parameterTypesMatch(Class<?>[] declared, Class<?>[] requested) {
        if (declared.length != requested.length) return false;
        for (int i = 0; i < declared.length; i++) {
            if (requested[i] != null && !declared[i].isAssignableFrom(requested[i])) {
                return false;
            }
        }
        return true;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        try {
            Class<?>[] paramTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                paramTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
            }
            Method method = findMethodExact(obj.getClass(), methodName, paramTypes);
            return method.invoke(obj, args);
        } catch (Exception e) {
            Log.e(TAG, "Error calling method " + methodName + ": " + e.getMessage());
            return null;
        }
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (Exception e) {
            Log.w(TAG, "Error setting field " + fieldName + ": " + e.getMessage());
        }
    }
}

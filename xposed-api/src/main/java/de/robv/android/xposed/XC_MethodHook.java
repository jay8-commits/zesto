package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Standard Xposed Method Hook callback container.
 */
public abstract class XC_MethodHook {
    public int priority;

    public XC_MethodHook() {
        this.priority = 50;
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        public Member method;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }
    }

    public static class Unhook {
        private final Member hookMethod;
        private final XC_MethodHook callback;

        public Unhook(Member hookMethod, XC_MethodHook callback) {
            this.hookMethod = hookMethod;
            this.callback = callback;
        }

        public Member getHookMethod() {
            return hookMethod;
        }

        public XC_MethodHook getCallback() {
            return callback;
        }

        public void unhook() {
            // Unhook placeholder
        }
    }

    public void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    public void afterHookedMethod(MethodHookParam param) throws Throwable {}
}

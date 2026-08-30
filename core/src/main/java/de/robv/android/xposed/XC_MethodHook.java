package de.robv.android.xposed;

import java.lang.reflect.Member;

public class XC_MethodHook {
    public static final int PRIORITY_HIGHEST = 10000;
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;

    public final int priority;

    public XC_MethodHook() {
        this(PRIORITY_DEFAULT);
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public boolean returnEarly;
        private Object result;
        private Throwable throwable;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        public Object getResultValue() {
            return result;
        }

        public void setResultValue(Object result) {
            setResult(result);
        }

        public Throwable getThrowableValue() {
            return throwable;
        }

        public void setThrowableValue(Throwable throwable) {
            setThrowable(throwable);
        }

        public Object result() {
            return result;
        }

        public Throwable throwable() {
            return throwable;
        }

        public Object getResultCompat() {
            return result;
        }

        public void setResultFromOriginal(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = false;
        }

        public void setThrowableFromOriginal(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = false;
        }
    }

    public static class Unhook {
        public final Member hookedMethod;
        public final XC_MethodHook callback;
        private final Runnable unhookAction;

        public Unhook(Member hookedMethod, XC_MethodHook callback, Runnable unhookAction) {
            this.hookedMethod = hookedMethod;
            this.callback = callback;
            this.unhookAction = unhookAction;
        }

        public void unhook() {
            if (unhookAction != null) unhookAction.run();
        }
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public final void callBefore(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    public final void callAfter(MethodHookParam param) throws Throwable {
        afterHookedMethod(param);
    }
}

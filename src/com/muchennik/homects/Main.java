package com.muchennik.homects;

import android.os.Bundle;
import android.os.SystemClock;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Long press on the Home button (3-button navigation) opens Circle to Search instead of the
 * assistant (Gemini).
 *
 * SystemUI handles the long press by asking VoiceInteractionManagerService (system_server) to
 * show the session of the active assistant (Google) with invocation_type = 5
 * (INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS). We add the same keys MiCTS sends — Google's
 * voice interaction service then starts Circle to Search instead of Gemini.
 * Scope: System Framework ("android").
 */
public class Main implements IXposedHookLoadPackage {
    private static final String TAG = "HomeCTS: ";
    private static final int HOME_LONG_PRESS = 5;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!"android".equals(lp.packageName)) return;
        Class<?> stub;
        try {
            stub = lp.classLoader.loadClass(
                    "com.android.server.voiceinteraction.VoiceInteractionManagerService$VoiceInteractionManagerServiceStub");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "VIMS stub not found: " + t);
            return;
        }
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Bundle args = null;
                    int flagsIdx = -1;
                    for (int i = 0; i < param.args.length; i++) {
                        if (param.args[i] instanceof Bundle && args == null) args = (Bundle) param.args[i];
                        else if (param.args[i] instanceof Integer && flagsIdx < 0) flagsIdx = i;
                    }
                    if (args == null) return;
                    int type = args.getInt("invocation_type", -1);
                    XposedBridge.log(TAG + param.method.getName() + " invocation_type=" + type + " keys=" + args.keySet());
                    if (type != HOME_LONG_PRESS || args.containsKey("omni.entry_point")) return;
                    args.putInt("omni.entry_point", 1);
                    args.putLong("invocation_time_ms", SystemClock.elapsedRealtime());
                    args.putBoolean("micts_trigger", true);
                    // SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT | SHOW_SOURCE_ASSIST_GESTURE, as MiCTS uses
                    if (flagsIdx >= 0) param.args[flagsIdx] = ((Integer) param.args[flagsIdx]) | 7;
                    XposedBridge.log(TAG + "home long press -> Circle to Search");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + t);
                }
            }
        };
        int n = 0;
        for (String m : new String[] {"showSessionForActiveService", "showSessionFromSession"}) {
            java.util.Set<?> s = XposedBridge.hookAllMethods(stub, m, hook);
            n += s != null ? s.size() : 0;
        }
        XposedBridge.log(TAG + "hooked " + n + " methods");
    }
}

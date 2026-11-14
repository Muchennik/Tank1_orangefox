package com.muchennik.volskip;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;

import java.lang.reflect.Field;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Screen off + music playing:
 *   hold Volume Up   (>= 300 ms) -> next track
 *   hold Volume Down (>= 300 ms) -> previous track (players restart the current track
 *                                   if it has been playing for more than a few seconds)
 *   short press                  -> volume step, as usual.
 *
 * Hooks InputManagerService.interceptKeyBeforeQueueing in system_server (scope: "android").
 * While the screen is off and music is active the volume key is consumed; on release a short
 * press is turned back into a normal volume step, a long press has already skipped the track.
 */
public class Main implements IXposedHookLoadPackage {
    private static final String TAG = "VolSkip: ";
    private static final int FLAG_INTERACTIVE = 0x20000000;
    private static final long LONG_PRESS_MS = 300;

    private static Context ctx;
    private static Handler handler;
    private static PowerManager.WakeLock wl;
    private static int downKey = 0;
    private static boolean fired;
    private static Runnable pending;
    private static int volAtDown = -1;
    private static long lastLog;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!"android".equals(lp.packageName)) return;
        // Hook the very first Java entry point of key events (called from native InputDispatcher),
        // before WindowManager and any vendor PhoneWindowManager subclass: Blackview's policy
        // changed the volume before our PhoneWindowManager hook ran (volume + skip together).
        Class<?> target;
        String where;
        try {
            target = lp.classLoader.loadClass("com.android.server.input.InputManagerService");
            where = "InputManagerService";
        } catch (Throwable t) {
            target = lp.classLoader.loadClass("com.android.server.policy.PhoneWindowManager");
            where = "PhoneWindowManager";
        }
        final String hookedIn = where;
        XposedBridge.hookAllMethods(target, "interceptKeyBeforeQueueing", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length < 2 || !(param.args[0] instanceof KeyEvent)
                            || !(param.args[1] instanceof Integer)) return;
                    KeyEvent ev = (KeyEvent) param.args[0];
                    int code = ev.getKeyCode();
                    if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return;
                    if (!init(param.thisObject)) return;
                    boolean interactive = (((Integer) param.args[1]) & FLAG_INTERACTIVE) != 0;
                    int action = ev.getAction();
                    XposedBridge.log(TAG + "key " + code + " act=" + action + " rep=" + ev.getRepeatCount()
                            + " dev=" + ev.getDeviceId() + " flags=0x" + Integer.toHexString((Integer) param.args[1])
                            + " down=" + downKey);

                    if (action == KeyEvent.ACTION_DOWN) {
                        // repeats and the same key reported by a second input device while held
                        if (downKey == code) { param.setResult(0); return; }
                        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                        if (interactive || am == null || !am.isMusicActive()) { downKey = 0; return; }
                        downKey = code;
                        fired = false;
                        volAtDown = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        wl.acquire(3000);
                        final int k = code;
                        pending = new Runnable() {
                            @Override public void run() {
                                if (downKey != k) return;
                                fired = true;
                                restoreVolume();
                                media(k == KeyEvent.KEYCODE_VOLUME_UP
                                        ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                            }
                        };
                        handler.postDelayed(pending, LONG_PRESS_MS);
                        param.setResult(0);
                    } else if (action == KeyEvent.ACTION_UP && downKey == code) {
                        if (fired) restoreVolume();
                        handler.removeCallbacks(pending);
                        downKey = 0;
                        if (!fired) {
                            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                            if (am != null) am.adjustSuggestedStreamVolume(
                                    code == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                                    AudioManager.STREAM_MUSIC, 0);
                        }
                        if (wl.isHeld()) wl.release();
                        param.setResult(0);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + t);
                }
            }
        });
        XposedBridge.log(TAG + "hooked " + hookedIn + ".interceptKeyBeforeQueueing");
    }

    private static boolean init(Object pwm) {
        if (ctx != null) return true;
        try {
            Field f = findField(pwm.getClass(), "mContext");
            ctx = (Context) f.get(pwm);
            handler = new Handler(Looper.getMainLooper());
            wl = ((PowerManager) ctx.getSystemService(Context.POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VolSkip");
            wl.setReferenceCounted(false);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "init: " + t);
            ctx = null;
            return false;
        }
    }

    /** whoever else nudged the music volume while the key was held: put it back */
    private static void restoreVolume() {
        try {
            if (volAtDown < 0) return;
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int now = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (now != volAtDown) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, volAtDown, 0);
                XposedBridge.log(TAG + "volume restored " + now + " -> " + volAtDown);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "restore: " + t);
        }
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void media(int code) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            long t = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
            XposedBridge.log(TAG + (code == KeyEvent.KEYCODE_MEDIA_NEXT ? "next" : "previous"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "media: " + t);
        }
    }
}

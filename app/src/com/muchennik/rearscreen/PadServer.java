package com.muchennik.rearscreen;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Rear touch -> main screen. Runs as the shell user through app_process (shell may inject input):
 *   su 2000 -c "CLASSPATH=<apk> app_process /system/bin com.muchennik.rearscreen.PadServer"
 * Reads the helper's rear-touch lines on stdin ("D x y", "M x y", "U x y", rear 240x296) and
 * injects them as a finger on display 0. The rear panel is touched from behind, so X is mirrored;
 * the point is scaled to the whole main screen and follows its rotation.
 * Diagnostics go to stderr ("I ...", "E ...").
 */
public final class PadServer {
    private static final int RW = Frames.W, RH = Frames.H;
    /** rear pixels a finger may wander before a tap turns into a drag (the touch is jittery) */
    private static final int SLOP = 6;

    private static Object inputManager;
    private static Method injectMethod, setDisplayId;
    private static Object dmg;
    private static Method getDisplayInfo;

    private static long downTime;
    private static boolean down, dragging;
    private static int downX, downY;
    // main display geometry captured on finger down
    private static int lw, lh, rot;

    public static void main(String[] args) throws Exception {
        initInput();
        try {
            Class<?> g = Class.forName("android.hardware.display.DisplayManagerGlobal");
            dmg = g.getMethod("getInstance").invoke(null);
            getDisplayInfo = g.getMethod("getDisplayInfo", int.class);
        } catch (Exception e) {
            log("E display info: " + e);
        }
        geometry();
        log("I pad ready " + lw + "x" + lh + " rot " + rot);
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        String l;
        while ((l = r.readLine()) != null) {
            String[] s = l.trim().split(" ");
            if (s.length < 3) {
                if ("QUIT".equals(s[0])) break;
                continue;
            }
            try {
                int x = Integer.parseInt(s[1]), y = Integer.parseInt(s[2]);
                switch (s[0]) {
                    case "D": onDown(x, y); break;
                    case "M": onMove(x, y); break;
                    case "U": onUp(x, y); break;
                }
            } catch (NumberFormatException ignored) {}
        }
        if (down) onUp(downX, downY);
    }

    private static void onDown(int x, int y) {
        if (down) onUp(x, y); // missed release
        geometry();
        down = true;
        dragging = false;
        downX = x;
        downY = y;
        downTime = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, x, y);
    }

    private static void onMove(int x, int y) {
        if (!down) { onDown(x, y); return; }
        if (!dragging && Math.abs(x - downX) <= SLOP && Math.abs(y - downY) <= SLOP) return; // still a tap
        dragging = true;
        send(MotionEvent.ACTION_MOVE, x, y);
    }

    private static void onUp(int x, int y) {
        if (!down) return;
        if (!dragging) { x = downX; y = downY; } // a clean tap where it went down
        send(MotionEvent.ACTION_UP, x, y);
        down = false;
    }

    /** rear point -> main screen point in the current (rotated) logical coordinates */
    private static void send(int action, int x, int y) {
        float fx = 1f - clamp(x / (float) (RW - 1)); // mirrored: we touch the panel from behind
        float fy = clamp(y / (float) (RH - 1));
        boolean odd = (rot & 1) == 1;
        float nw = odd ? lh : lw, nh = odd ? lw : lh; // natural (portrait) size
        float nx = fx * (nw - 1), ny = fy * (nh - 1);
        float px, py;
        switch (rot) {
            case 1: px = ny; py = nw - 1 - nx; break;            // ROTATION_90
            case 2: px = nw - 1 - nx; py = nh - 1 - ny; break;   // ROTATION_180
            case 3: px = nh - 1 - ny; py = nx; break;            // ROTATION_270
            default: px = nx; py = ny;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(downTime, now, action, px, py, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            setDisplayId.invoke(e, 0);
            injectMethod.invoke(inputManager, e, 0); // INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (Exception ex) {
            log("E inject: " + ex);
        }
        e.recycle();
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static void geometry() {
        try {
            Object di = getDisplayInfo.invoke(dmg, 0);
            Class<?> c = di.getClass();
            lw = intField(c, di, "logicalWidth");
            lh = intField(c, di, "logicalHeight");
            rot = intField(c, di, "rotation");
        } catch (Exception e) {
            if (lw == 0) { lw = 1080; lh = 2400; rot = 0; }
        }
    }

    private static int intField(Class<?> c, Object o, String name) throws Exception {
        Field f = c.getField(name);
        return f.getInt(o);
    }

    private static void initInput() throws Exception {
        try {
            Class<?> g = Class.forName("android.hardware.input.InputManagerGlobal");
            inputManager = g.getMethod("getInstance").invoke(null);
            injectMethod = g.getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (Exception e) {
            Class<?> im = android.hardware.input.InputManager.class;
            inputManager = im.getDeclaredMethod("getInstance").invoke(null);
            injectMethod = im.getMethod("injectInputEvent", InputEvent.class, int.class);
        }
        setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
    }

    private static void log(String s) {
        System.err.println(s);
        System.err.flush();
    }
}

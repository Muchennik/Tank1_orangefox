package com.muchennik.rearscreen;

import android.annotation.SuppressLint;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Runs as the shell user through app_process (like scrcpy's --new-display), started by
 * RearService:  su 2000 -c "CLASSPATH=<apk> app_process /system/bin com.muchennik.rearscreen.AppServer
 *               <package> <local|fallback> <dpi>"
 *
 * Creates a trusted 240x296 virtual display with system decorations (so the keyboard can open
 * on it), launches the app there, writes every new frame as RGB565-BE to stdout and injects the
 * rear-touch commands it reads on stdin ("D x y", "M x y", "U x y", "KEY code", "QUIT").
 * Diagnostics go to stderr ("I ...", "E ...").
 */
public final class AppServer {
    private static final String SHELL = "com.android.shell";
    // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (several are hidden)
    private static final int PUBLIC = 1, OWN_CONTENT_ONLY = 1 << 3, SUPPORTS_TOUCH = 1 << 6,
            ROTATES_WITH_CONTENT = 1 << 7, DESTROY_CONTENT_ON_REMOVAL = 1 << 8,
            SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9, TRUSTED = 1 << 10, OWN_DISPLAY_GROUP = 1 << 11,
            ALWAYS_UNLOCKED = 1 << 12, TOUCH_FEEDBACK_DISABLED = 1 << 13, OWN_FOCUS = 1 << 14;

    private static Object inputManager;
    private static Method injectMethod, setDisplayId;
    private static int displayId;
    private static long downTime;

    public static void main(String[] args) throws Exception {
        String pkg = args.length > 0 ? args[0] : "com.android.chrome";
        boolean imeLocal = args.length < 2 || "local".equals(args[1]);
        int dpi = args.length > 2 ? Integer.parseInt(args[2]) : 140;

        Looper.prepareMainLooper();
        Context ctx = new FakeContext(systemContext());

        OutputStream out = new FileOutputStream(FileDescriptor.out);
        byte[] frame = new byte[Frames.SIZE];
        byte[] row = new byte[Frames.W * 4];
        HandlerThread ht = new HandlerThread("frames");
        ht.start();
        ImageReader reader = ImageReader.newInstance(Frames.W, Frames.H, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img == null) return;
            try {
                Image.Plane p = img.getPlanes()[0];
                ByteBuffer b = p.getBuffer();
                int stride = p.getRowStride();
                for (int y = 0; y < Frames.H; y++) {
                    b.position(y * stride);
                    b.get(row, 0, row.length);
                    for (int x = 0, j = (y * Frames.W) * 2; x < Frames.W; x++, j += 2) {
                        int r8 = row[4 * x] & 0xFF, g8 = row[4 * x + 1] & 0xFF, b8 = row[4 * x + 2] & 0xFF;
                        int s = ((r8 & 0xF8) << 8) | ((g8 & 0xFC) << 3) | (b8 >> 3);
                        frame[j] = (byte) (s >> 8);
                        frame[j + 1] = (byte) s;
                    }
                }
            } finally {
                img.close();
            }
            try {
                out.write(frame); // blocks while the rear panel is busy: we always send the latest
                out.flush();
            } catch (Exception e) {
                System.exit(0); // RearService went away
            }
        }, new Handler(ht.getLooper()));

        // no SHOULD_SHOW_SYSTEM_DECORATIONS: with it SystemUI adds a navigation bar and back gesture
        // (which close the app) and Android 16 forces desktop windows with a caption bar; the
        // keyboard then opens on the main screen (normal size), which is also easier to type on
        int flags = PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT
                | DESTROY_CONTENT_ON_REMOVAL | TRUSTED
                | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED;
        // no OWN_FOCUS: a touch on the rear display makes it the focused display, otherwise focus
        // jumps back to the main display and the keyboard closes right after opening
        Constructor<DisplayManager> c = DisplayManager.class.getDeclaredConstructor(Context.class);
        c.setAccessible(true);
        DisplayManager dm = c.newInstance(ctx);
        VirtualDisplay vd = dm.createVirtualDisplay("RearScreen", Frames.W, Frames.H, dpi, reader.getSurface(), flags);
        displayId = vd.getDisplay().getDisplayId();
        log("I display " + displayId);

        sh("wm set-display-ime-policy " + displayId + " fallback");
        initInput();
        launch(pkg);

        // relaunch the app if it gets closed (it would leave the rear screen black)
        Thread watch = new Thread(() -> {
            while (true) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                String tasks = shOut("am stack list");
                if (!tasks.contains("displayId=" + displayId)) {
                    log("I app closed, relaunching");
                    launch(pkg);
                }
            }
        }, "watch");
        watch.setDaemon(true);
        watch.start();

        Thread in = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in))) {
                String l;
                while ((l = r.readLine()) != null) {
                    String[] s = l.trim().split(" ");
                    switch (s[0]) {
                        case "D": touch(MotionEvent.ACTION_DOWN, s); break;
                        case "M": touch(MotionEvent.ACTION_MOVE, s); break;
                        case "U": touch(MotionEvent.ACTION_UP, s); break;
                        case "KEY": key(Integer.parseInt(s[1])); break;
                        case "LAUNCH": launch(s[1]); break;
                        case "QUIT": l = null; break;
                        default: break;
                    }
                    if (l == null) break;
                }
            } catch (Exception e) {
                log("E stdin: " + e);
            }
            vd.release();
            System.exit(0);
        }, "commands");
        in.start();
        Looper.loop();
    }

    private static void log(String s) {
        System.err.println(s);
        System.err.flush();
    }

    @SuppressLint("PrivateApi")
    private static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = at.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object thread = ctor.newInstance();
        java.lang.reflect.Field cur = at.getDeclaredField("sCurrentActivityThread");
        cur.setAccessible(true);
        cur.set(null, thread);
        Method m = at.getDeclaredMethod("getSystemContext");
        return (Context) m.invoke(thread);
    }

    /** Pretends to be the shell package so that system services accept our calls. */
    static final class FakeContext extends ContextWrapper {
        FakeContext(Context base) { super(base); }
        @Override public String getPackageName() { return SHELL; }
        @Override public String getOpPackageName() { return SHELL; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL).build();
        }
        @Override public Context getApplicationContext() { return this; }
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

    private static void inject(InputEvent e) {
        try {
            setDisplayId.invoke(e, displayId);
            injectMethod.invoke(inputManager, e, 0); // INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (Exception ex) {
            log("E inject: " + ex);
        }
    }

    private static void touch(int action, String[] s) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) downTime = now;
        MotionEvent e = MotionEvent.obtain(downTime, now, action, Float.parseFloat(s[1]), Float.parseFloat(s[2]), 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        inject(e);
        e.recycle();
    }

    private static void key(int code) {
        long now = SystemClock.uptimeMillis();
        for (int a : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            KeyEvent e = new KeyEvent(now, now, a, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    InputDevice.SOURCE_KEYBOARD);
            inject(e);
        }
    }

    /** Starts the package's launcher activity on the rear virtual display. */
    private static void launch(String pkg) {
        String comp = null;
        for (String l : shOut("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER " + pkg).split("\n"))
            if (l.contains("/")) comp = l.trim();
        if (comp == null) {
            log("E no launcher activity for " + pkg);
            return;
        }
        log("I launch " + comp + " on " + displayId);
        // Android 16 opens apps on a display with system decorations in desktop (freeform) mode:
        // force fullscreen (WINDOWING_MODE_FULLSCREEN = 1)
        sh("am start --display " + displayId + " --windowingMode 1 -f 0x10200000 -n " + comp);
    }

    private static void sh(String cmd) {
        log("I $ " + cmd + " -> " + shOut(cmd).trim());
    }

    private static String shOut(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[] {"sh", "-c", cmd + " 2>&1"});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "error " + e;
        }
    }
}

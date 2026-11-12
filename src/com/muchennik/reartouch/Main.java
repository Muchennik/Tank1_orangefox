package com.muchennik.reartouch;

import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * OSCAL TANK 1: the rear display touch ("cst0xx_input") is mapped by stock Android to the
 * main display, so touching the rear screen also taps whatever is on the main screen.
 *
 * Runs as root via app_process and registers an input gesture monitor (a spy window over
 * display 0). When a gesture starts on the rear touch device it calls pilferPointers():
 * the main-screen windows get ACTION_CANCEL and never see a click, while spy monitors
 * (where the stock rear-screen logic listens) keep receiving the events.
 * Root passes the MONITOR_INPUT permission check (uid 0 is always granted).
 */
public final class Main {
    private static final String NAME = "cst0xx";

    private static Object im;
    private static Method getInputDevice;
    private static final Map<Integer, Boolean> rear = new HashMap<>();

    public static void main(String[] args) throws Throwable {
        Looper.prepareMainLooper();
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "input");
        im = Class.forName("android.hardware.input.IInputManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, b);
        getInputDevice = im.getClass().getMethod("getInputDevice", int.class);

        Method monitor = null;
        for (Method m : im.getClass().getMethods())
            if (m.getName().equals("monitorGestureInput") && m.getParameterTypes().length == 3) monitor = m;
        if (monitor == null) throw new IllegalStateException("monitorGestureInput not found");
        final InputMonitor mon = (InputMonitor) monitor.invoke(im, new Binder(), "muchennik_rear_touch", 0);
        log("monitor registered on display 0");

        new InputEventReceiver(mon.getInputChannel(), Looper.myLooper()) {
            @Override
            public void onInputEvent(InputEvent ev) {
                try {
                    if (ev instanceof MotionEvent) {
                        MotionEvent m = (MotionEvent) ev;
                        if (m.getActionMasked() == MotionEvent.ACTION_DOWN && isRear(m.getDeviceId())) {
                            mon.pilferPointers();
                        }
                    }
                } catch (Throwable t) {
                    log("event: " + t);
                } finally {
                    finishInputEvent(ev, false);
                }
            }
        };
        Looper.loop();
    }

    private static boolean isRear(int id) {
        Boolean r = rear.get(id);
        if (r == null) {
            String name = "";
            try {
                InputDevice d = (InputDevice) getInputDevice.invoke(im, id);
                if (d != null) name = d.getName();
            } catch (Throwable t) {
                log("getInputDevice " + id + ": " + t);
            }
            r = name != null && name.contains(NAME);
            rear.put(id, r);
            log("device " + id + " '" + name + "' rear=" + r);
        }
        return r;
    }

    private static void log(String s) {
        System.out.println(new java.util.Date() + " " + s);
        System.out.flush();
    }
}

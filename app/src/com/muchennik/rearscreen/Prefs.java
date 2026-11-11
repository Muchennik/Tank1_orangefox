package com.muchennik.rearscreen;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** All settings of the app. */
final class Prefs {
    static final int FIT_FILL = 0, FIT_FIT = 1, FIT_STRETCH = 2;
    /** when the picture goes out */
    static final int OFF_TIMER = 0, OFF_SCREEN = 1, OFF_DOUBLE_TAP = 2, OFF_NEVER = 3;

    final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("rear", Context.MODE_PRIVATE);
    }

    List<String> media() {
        List<String> l = new ArrayList<>();
        String s = sp.getString("media", "");
        for (String u : s.split("\n")) if (!u.isEmpty()) l.add(u);
        return l;
    }

    void setMedia(List<String> l) {
        sp.edit().putString("media", String.join("\n", l)).apply();
    }

    int fit() { return sp.getInt("fit", FIT_FILL); }
    int offMode() { return sp.getInt("off", OFF_SCREEN); }
    int timerSec() { return sp.getInt("timer", 30); }
    int slideSec() { return sp.getInt("slide", 10); }
    int brightness() { return sp.getInt("bl", 12); }
    /** how a sleeping (black) rear screen wakes up */
    static final int WAKE_NONE = 0, WAKE_TAP = 1, WAKE_DOUBLE_TAP = 2;
    int wakeMode() { return sp.getInt("wake", sp.getBoolean("wake_dt", true) ? WAKE_DOUBLE_TAP : WAKE_NONE); }
    boolean canWake() { return wakeMode() != WAKE_NONE; }
    boolean running() { return sp.getBoolean("running", false); }

    /** what the rear screen shows */
    static final int MODE_MEDIA = 0, MODE_APP = 1, MODE_VIS = 2, MODE_STATS = 3;
    int mode() { return sp.getInt("mode", MODE_MEDIA); }
    String appPackage() { return sp.getString("app_pkg", "com.android.chrome"); }
    String appLabel() { return sp.getString("app_label", "Chrome"); }
    boolean imeOnRear() { return sp.getBoolean("ime_rear", true); }
    int dpi() { return sp.getInt("dpi", 140); }
    void putApp(String pkg, String label) { sp.edit().putString("app_pkg", pkg).putString("app_label", label).apply(); }

    /** apps offered in the rear-screen grid, in order */
    List<String> apps() {
        List<String> l = new ArrayList<>();
        String s = sp.getString("apps", null);
        if (s == null) { l.add(appPackage()); return l; }
        for (String u : s.split("\n")) if (!u.isEmpty()) l.add(u);
        return l;
    }
    void setApps(List<String> l) { sp.edit().putString("apps", String.join("\n", l)).apply(); }
    /** own keyboard on the rear screen (else the normal one opens on the main screen) */
    boolean rearKeyboard() { return sp.getBoolean("kbd_rear", true); }

    int visSource() { return sp.getInt("vis_src", VisSource.SRC_OUT); }
    int visStyle() { return sp.getInt("vis_style", VisSource.STYLE_BARS); }
    int visSensitivity() { return sp.getInt("vis_sens", 10); }

    void put(String k, int v) { sp.edit().putInt(k, v).apply(); }
    void put(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }
}
